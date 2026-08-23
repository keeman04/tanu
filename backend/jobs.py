import asyncio
import difflib
import json
import os
import re
import shutil
import sqlite3
import subprocess
import threading
import time
import uuid
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path
from typing import Any

import httpx
from fastapi import APIRouter, Header, HTTPException, Query, Request
from pydantic import BaseModel, Field

import app as core
from device_auth import require_auth

router = APIRouter()

JOB_ROOT = Path(os.getenv("MAI_JOB_ROOT", "/data/meetings"))
JOB_DB = Path(os.getenv("MAI_JOB_DB", "/data/mai-jobs.sqlite3"))
UPLOAD_CHUNK_BYTES = max(1, min(int(os.getenv("MAI_UPLOAD_CHUNK_BYTES", str(5 * 1024 * 1024))), 8 * 1024 * 1024))
MAX_AUDIO_BYTES = max(20 * 1024 * 1024, min(int(os.getenv("MAI_MAX_AUDIO_BYTES", str(200 * 1024 * 1024))), 1024 * 1024 * 1024))
SEGMENT_SECONDS = max(120, min(int(os.getenv("MAI_SEGMENT_SECONDS", "420")), 900))
SEGMENT_OVERLAP_SECONDS = max(0, min(int(os.getenv("MAI_SEGMENT_OVERLAP_SECONDS", "5")), 15))
MAX_JOB_WORKERS = max(1, min(int(os.getenv("MAI_JOB_WORKERS", "2")), 4))
MAX_STT_WORKERS = max(1, min(int(os.getenv("MAI_STT_WORKERS", "3")), 6))
MAX_DIARIZE_WORKERS = max(1, min(int(os.getenv("MAI_DIARIZE_WORKERS", "2")), 4))
MAX_TRANSLATE_WORKERS = max(1, min(int(os.getenv("MAI_TRANSLATE_WORKERS", "3")), 6))

_job_executor = ThreadPoolExecutor(max_workers=MAX_JOB_WORKERS, thread_name_prefix="mai-job")
_scheduled: set[str] = set()
_schedule_lock = threading.Lock()

LANGUAGE_PRESETS: dict[str, list[str]] = {
    "auto": [],
    "ta_en": ["ta", "en"],
    "hi_en": ["hi", "en"],
    "te_en": ["te", "en"],
    "ml_en": ["ml", "en"],
    "kn_en": ["kn", "en"],
    "bn_en": ["bn", "en"],
    "mr_en": ["mr", "en"],
    "gu_en": ["gu", "en"],
    "pa_en": ["pa", "en"],
    "ur_en": ["ur", "en"],
}


class JobInitRequest(BaseModel):
    meeting_id: str = Field(min_length=8, max_length=100)
    title: str = Field(default="Meeting", max_length=250)
    started_at: str = Field(default="", max_length=40)
    participants: list[dict[str, str]] = Field(default_factory=list)
    language_mode: str = Field(default="auto", max_length=30)
    audio_size: int = Field(gt=512)


class JobState(BaseModel):
    job_id: str
    status: str
    progress: int
    uploaded_bytes: int
    expected_bytes: int
    result: dict[str, Any] | None = None
    error: str | None = None


def _connect() -> sqlite3.Connection:
    JOB_DB.parent.mkdir(parents=True, exist_ok=True)
    connection = sqlite3.connect(str(JOB_DB), timeout=30.0)
    connection.row_factory = sqlite3.Row
    connection.execute("PRAGMA journal_mode=WAL")
    connection.execute("PRAGMA synchronous=NORMAL")
    connection.executescript(
        """
        CREATE TABLE IF NOT EXISTS jobs(
            job_id TEXT PRIMARY KEY,
            meeting_id TEXT NOT NULL,
            device_id TEXT NOT NULL,
            title TEXT NOT NULL,
            started_at TEXT NOT NULL,
            participants TEXT NOT NULL,
            language_mode TEXT NOT NULL DEFAULT 'auto',
            audio_path TEXT NOT NULL,
            expected_bytes INTEGER NOT NULL,
            uploaded_bytes INTEGER NOT NULL DEFAULT 0,
            status TEXT NOT NULL DEFAULT 'uploading',
            progress INTEGER NOT NULL DEFAULT 0,
            result_json TEXT,
            error TEXT,
            attempts INTEGER NOT NULL DEFAULT 0,
            created_at INTEGER NOT NULL,
            updated_at INTEGER NOT NULL,
            UNIQUE(device_id, meeting_id)
        );
        CREATE TABLE IF NOT EXISTS job_chunks(
            job_id TEXT NOT NULL,
            kind TEXT NOT NULL,
            chunk_index INTEGER NOT NULL,
            text TEXT NOT NULL,
            updated_at INTEGER NOT NULL,
            PRIMARY KEY(job_id, kind, chunk_index)
        );
        CREATE INDEX IF NOT EXISTS idx_jobs_status ON jobs(status);
        """
    )
    return connection


def _job_dir(job_id: str) -> Path:
    root = JOB_ROOT / job_id
    root.mkdir(parents=True, exist_ok=True)
    return root


def _row_state(row: sqlite3.Row) -> JobState:
    raw_result = row["result_json"]
    result = None
    if raw_result:
        try:
            result = json.loads(raw_result)
        except json.JSONDecodeError:
            result = None
    return JobState(
        job_id=str(row["job_id"]),
        status=str(row["status"]),
        progress=int(row["progress"]),
        uploaded_bytes=int(row["uploaded_bytes"]),
        expected_bytes=int(row["expected_bytes"]),
        result=result,
        error=str(row["error"]) if row["error"] else None,
    )


def _get_job(job_id: str, device_id: str) -> sqlite3.Row:
    with _connect() as connection:
        row = connection.execute(
            "SELECT * FROM jobs WHERE job_id=? AND device_id=?",
            (job_id, device_id),
        ).fetchone()
    if row is None:
        raise HTTPException(status_code=404, detail="MAI processing job not found")
    return row


def _update_job(job_id: str, *, status: str | None = None, progress: int | None = None, error: str | None = None, result: dict[str, Any] | None = None) -> None:
    values: list[Any] = []
    parts: list[str] = []
    if status is not None:
        parts.append("status=?")
        values.append(status)
    if progress is not None:
        parts.append("progress=?")
        values.append(max(0, min(progress, 100)))
    if error is not None:
        parts.append("error=?")
        values.append(error[:1200])
    if result is not None:
        parts.append("result_json=?")
        values.append(json.dumps(result, ensure_ascii=False))
    parts.append("updated_at=?")
    values.append(int(time.time()))
    values.append(job_id)
    with _connect() as connection:
        connection.execute(f"UPDATE jobs SET {', '.join(parts)} WHERE job_id=?", values)


def _chunk_get(job_id: str, kind: str, index: int) -> str | None:
    with _connect() as connection:
        row = connection.execute(
            "SELECT text FROM job_chunks WHERE job_id=? AND kind=? AND chunk_index=?",
            (job_id, kind, index),
        ).fetchone()
    return str(row["text"]) if row is not None else None


def _chunk_put(job_id: str, kind: str, index: int, text: str) -> None:
    with _connect() as connection:
        connection.execute(
            "INSERT OR REPLACE INTO job_chunks(job_id,kind,chunk_index,text,updated_at) VALUES(?,?,?,?,?)",
            (job_id, kind, index, text, int(time.time())),
        )


def _participant_names(raw: str) -> list[str]:
    try:
        people = json.loads(raw)
    except json.JSONDecodeError:
        return []
    names: list[str] = []
    seen: set[str] = set()
    for person in people if isinstance(people, list) else []:
        if not isinstance(person, dict):
            continue
        name = str(person.get("name", "")).strip()
        key = name.casefold()
        if name and key not in seen:
            names.append(name)
            seen.add(key)
    return names


def _languages(mode: str) -> list[str]:
    return LANGUAGE_PRESETS.get(mode, [])


def _retry_after(response: httpx.Response, attempt: int) -> float:
    raw = response.headers.get("retry-after", "").strip()
    try:
        return max(0.5, min(float(raw), 60.0))
    except ValueError:
        return min(2 ** attempt, 30.0)


def _request_with_retry(method: str, url: str, *, attempts: int = 5, timeout_seconds: float = 900.0, **kwargs: Any) -> httpx.Response:
    last: Exception | None = None
    for attempt in range(attempts):
        try:
            with httpx.Client(timeout=httpx.Timeout(timeout_seconds, connect=30.0)) as client:
                response = client.request(method, url, **kwargs)
            if response.status_code not in {408, 409, 425, 429} and response.status_code < 500:
                return response
            if attempt == attempts - 1:
                return response
            time.sleep(_retry_after(response, attempt))
        except (httpx.TimeoutException, httpx.NetworkError, httpx.RemoteProtocolError) as exc:
            last = exc
            if attempt == attempts - 1:
                raise
            time.sleep(min(2 ** attempt, 30.0))
    if last:
        raise last
    raise RuntimeError("MAI request retry loop ended unexpectedly")


def _duration_seconds(path: Path) -> float:
    if shutil.which("ffprobe") is None:
        raise RuntimeError("ffprobe is required on the MAI backend")
    completed = subprocess.run(
        ["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "default=noprint_wrappers=1:nokey=1", str(path)],
        capture_output=True,
        text=True,
        timeout=120,
    )
    if completed.returncode != 0:
        raise RuntimeError("Could not inspect meeting audio duration")
    try:
        return max(0.0, float(completed.stdout.strip()))
    except ValueError as exc:
        raise RuntimeError("Meeting audio duration was invalid") from exc


def _segment_audio(source: Path, output_dir: Path) -> list[Path]:
    if shutil.which("ffmpeg") is None:
        raise RuntimeError("ffmpeg is required on the MAI backend")
    duration = _duration_seconds(source)
    if duration <= 0:
        raise RuntimeError("Meeting audio contains no usable duration")
    output_dir.mkdir(parents=True, exist_ok=True)
    starts: list[float] = []
    position = 0.0
    while position < duration:
        starts.append(position)
        position += SEGMENT_SECONDS
    segments: list[Path] = []
    for index, start in enumerate(starts):
        target = output_dir / f"segment-{index:04d}.mp3"
        length = min(float(SEGMENT_SECONDS + SEGMENT_OVERLAP_SECONDS), duration - start)
        command = [
            "ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
            "-ss", f"{start:.3f}", "-i", str(source), "-t", f"{length:.3f}",
            "-vn", "-ac", "1", "-ar", "16000", "-b:a", "32k", str(target),
        ]
        completed = subprocess.run(command, capture_output=True, text=True, timeout=20 * 60)
        if completed.returncode != 0 or not target.is_file() or target.stat().st_size <= 512:
            raise RuntimeError(f"Audio segment {index + 1} conversion failed: {completed.stderr[-300:]}")
        segments.append(target)
    return segments


def _transcribe_one(path: Path, index: int, participant_names: list[str], language_mode: str) -> tuple[int, str]:
    cached = None
    # cache lookup is done by caller so file handles can be retried cleanly
    del cached
    languages = _languages(language_mode)
    for attempt in range(5):
        multipart: list[tuple[str, tuple[Any, ...]]] = [("model", (None, core.STT_MODEL))]
        for language in languages:
            multipart.append(("languages[]", (None, language)))
        for keyword in core.transcription_keywords(participant_names):
            multipart.append(("keywords[]", (None, keyword)))
        with path.open("rb") as handle:
            multipart.append(("file", (path.name, handle, "audio/mpeg")))
            response = _request_with_retry(
                "POST",
                f"{core.OPENAI_BASE}/audio/transcriptions",
                headers=core.openai_headers(),
                files=multipart,
                attempts=1,
                timeout_seconds=15 * 60,
            )
        if response.status_code < 400:
            return index, str(response.json().get("text", "")).strip()
        if response.status_code in {408, 409, 425, 429} or response.status_code >= 500:
            if attempt < 4:
                time.sleep(_retry_after(response, attempt))
                continue
        raise RuntimeError(f"STT chunk {index + 1} failed ({response.status_code}): {response.text[:500]}")
    raise RuntimeError(f"STT chunk {index + 1} failed after retrying")


def _diarize_one(path: Path, index: int) -> tuple[int, str, list[str]]:
    for attempt in range(4):
        multipart: list[tuple[str, tuple[Any, ...]]] = [
            ("model", (None, core.DIARIZE_MODEL)),
            ("response_format", (None, "diarized_json")),
            ("chunking_strategy", (None, "auto")),
        ]
        with path.open("rb") as handle:
            multipart.append(("file", (path.name, handle, "audio/mpeg")))
            response = _request_with_retry(
                "POST",
                f"{core.OPENAI_BASE}/audio/transcriptions",
                headers=core.openai_headers(),
                files=multipart,
                attempts=1,
                timeout_seconds=15 * 60,
            )
        if response.status_code < 400:
            payload = response.json()
            lines: list[str] = []
            labels: list[str] = []
            for segment in payload.get("segments", []) or []:
                if not isinstance(segment, dict):
                    continue
                text = str(segment.get("text", "")).strip()
                if not text:
                    continue
                label = core.safe_speaker_label(segment.get("speaker"), index)
                if label not in labels:
                    labels.append(label)
                lines.append(f"[{label}] {text}")
            return index, "\n".join(lines), labels
        if (response.status_code in {408, 409, 425, 429} or response.status_code >= 500) and attempt < 3:
            time.sleep(_retry_after(response, attempt))
            continue
        raise RuntimeError(f"Diarization chunk {index + 1} failed ({response.status_code})")
    raise RuntimeError(f"Diarization chunk {index + 1} failed after retrying")


def _response_text_retry(model: str, prompt: str, max_output_tokens: int) -> str:
    payload = {
        "model": model,
        "input": prompt,
        "max_output_tokens": max_output_tokens,
        "store": False,
        "reasoning": {"effort": "low"},
    }
    response = _request_with_retry(
        "POST",
        f"{core.OPENAI_BASE}/responses",
        headers={**core.openai_headers(), "Content-Type": "application/json"},
        json=payload,
        attempts=5,
        timeout_seconds=15 * 60,
    )
    if response.status_code >= 400:
        raise RuntimeError(f"Text processing failed ({response.status_code}): {response.text[:500]}")
    text = core.extract_response_text(response.json()).strip()
    if not text:
        raise RuntimeError("Text processing returned no output")
    return text


def _translate_one(authoritative: str, diarized: str, index: int, participant_names: list[str]) -> tuple[int, str]:
    if not authoritative.strip():
        return index, ""
    names = ", ".join(participant_names) if participant_names else "not supplied"
    guide = diarized.strip() or "No reliable speaker guide was available."
    prompt = f"""Create a faithful English transcript for meeting part {index + 1}.
The authoritative transcript is the source of truth. The diarization guide may only supply generic speaker-turn boundaries.
The source may contain any language or multiple code-switched languages. Translate all meaningful speech into English while preserving names, numbers, money, dates, times, percentages, product names and commitments exactly.
Known participant names: {names}.
Never guess a participant identity from a generic speaker label. If wording is genuinely unclear, use [unclear]. Do not summarize or add facts.

AUTHORITATIVE TRANSCRIPT:
{authoritative}

DIARIZATION GUIDE:
{guide}
"""
    return index, _response_text_retry(core.TRANSLATE_MODEL, prompt, 14000)


def _dedupe_overlap(parts: list[str]) -> str:
    if not parts:
        return ""
    merged = parts[0].strip()
    for current in parts[1:]:
        current = current.strip()
        if not current:
            continue
        previous_words = merged.split()
        current_words = current.split()
        left = previous_words[-80:]
        right = current_words[:80]
        left_norm = [re.sub(r"[^a-z0-9]+", "", word.casefold()) for word in left]
        right_norm = [re.sub(r"[^a-z0-9]+", "", word.casefold()) for word in right]
        matcher = difflib.SequenceMatcher(a=left_norm, b=right_norm, autojunk=False)
        match = matcher.find_longest_match(0, len(left_norm), 0, len(right_norm))
        drop = 0
        if match.size >= 4 and match.a + match.size >= len(left_norm) - 2 and match.b <= 2:
            drop = match.b + match.size
        tail = " ".join(current_words[drop:]).strip()
        if tail:
            merged = merged.rstrip() + "\n" + tail
    return merged.strip()


def _mom_schema() -> dict[str, Any]:
    return {
        "type": "object",
        "properties": {
            "summary": {"type": "string"},
            "decisions": {"type": "array", "items": {"type": "string"}},
            "actions": {
                "type": "array",
                "items": {
                    "type": "object",
                    "properties": {
                        "text": {"type": "string"},
                        "owner": {"type": ["string", "null"]},
                        "due": {"type": ["string", "null"]},
                    },
                    "required": ["text", "owner", "due"],
                    "additionalProperties": False,
                },
            },
            "language": {"type": ["string", "null"]},
        },
        "required": ["summary", "decisions", "actions", "language"],
        "additionalProperties": False,
    }


def _build_mom_structured(meeting_id: str, title: str, started_at: str, participant_names: list[str], transcript: str, speaker_labels: list[str]) -> dict[str, Any]:
    names = ", ".join(participant_names) if participant_names else "Unknown"
    date = core.meeting_date(started_at)
    prompt = f"""Create final Minutes of Meeting from the verified English transcript. The transcript is the only source of truth.
Do not invent or infer missing facts. Summary must cover the important discussion in at most 6 sentences. Decisions must contain only explicit decisions/agreements. Actions must contain only explicit commitments, requests or assigned work.
Owner may only be an exact selected participant name from: {names}. If unclear, owner must be null. Generic speaker labels are never owners. Due date must be YYYY-MM-DD only when explicitly supported by the transcript and meeting date {date}; otherwise null. Preserve all amounts, dates, numbers, brand names and commitments exactly.
Meeting title: {title}
Meeting date: {date}
Participants: {names}

VERIFIED ENGLISH TRANSCRIPT:
{transcript}
"""
    payload = {
        "model": core.MOM_MODEL,
        "input": prompt,
        "max_output_tokens": 8000,
        "store": False,
        "reasoning": {"effort": "medium"},
        "text": {
            "format": {
                "type": "json_schema",
                "name": "mai_minutes_of_meeting",
                "strict": True,
                "schema": _mom_schema(),
            }
        },
    }
    response = _request_with_retry(
        "POST",
        f"{core.OPENAI_BASE}/responses",
        headers={**core.openai_headers(), "Content-Type": "application/json"},
        json=payload,
        attempts=5,
        timeout_seconds=15 * 60,
    )
    if response.status_code >= 400:
        raise RuntimeError(f"MOM generation failed ({response.status_code}): {response.text[:500]}")
    text = core.extract_response_text(response.json()).strip()
    raw = json.loads(text)
    result = core.normalize_result(raw, meeting_id, transcript, participant_names, speaker_labels)
    return result.model_dump()


def _process_job(job_id: str) -> None:
    try:
        with _connect() as connection:
            row = connection.execute("SELECT * FROM jobs WHERE job_id=?", (job_id,)).fetchone()
            if row is None:
                return
            connection.execute(
                "UPDATE jobs SET status='transcribing', progress=15, error=NULL, attempts=attempts+1, updated_at=? WHERE job_id=?",
                (int(time.time()), job_id),
            )
        source = Path(str(row["audio_path"]))
        if not source.is_file() or source.stat().st_size != int(row["expected_bytes"]):
            raise RuntimeError("Uploaded meeting audio is incomplete")
        participant_names = _participant_names(str(row["participants"]))
        language_mode = str(row["language_mode"] or "auto")
        work = _job_dir(job_id) / "work"
        if work.exists():
            shutil.rmtree(work)
        segments = _segment_audio(source, work / "segments")

        transcripts: list[str] = [""] * len(segments)
        missing: list[tuple[int, Path]] = []
        for index, path in enumerate(segments):
            cached = _chunk_get(job_id, "stt", index)
            if cached is None:
                missing.append((index, path))
            else:
                transcripts[index] = cached
        if missing:
            with ThreadPoolExecutor(max_workers=min(MAX_STT_WORKERS, len(missing))) as executor:
                futures = {executor.submit(_transcribe_one, path, index, participant_names, language_mode): index for index, path in missing}
                for future in as_completed(futures):
                    index, text = future.result()
                    transcripts[index] = text
                    _chunk_put(job_id, "stt", index, text)
        if not any(text.strip() for text in transcripts):
            raise RuntimeError("No speech was transcribed")

        _update_job(job_id, status="diarizing", progress=45)
        diarized: list[str] = [""] * len(segments)
        speaker_labels: list[str] = []
        missing_diarize: list[tuple[int, Path]] = []
        for index, path in enumerate(segments):
            cached = _chunk_get(job_id, "diarize", index)
            if cached is None:
                missing_diarize.append((index, path))
            else:
                diarized[index] = cached
                for label in re.findall(r"\[([^\]]+)\]", cached):
                    if label not in speaker_labels:
                        speaker_labels.append(label)
        if missing_diarize:
            with ThreadPoolExecutor(max_workers=min(MAX_DIARIZE_WORKERS, len(missing_diarize))) as executor:
                futures = {executor.submit(_diarize_one, path, index): index for index, path in missing_diarize}
                for future in as_completed(futures):
                    index = futures[future]
                    try:
                        _, text, labels = future.result()
                    except Exception:
                        text, labels = "", []
                    diarized[index] = text
                    _chunk_put(job_id, "diarize", index, text)
                    for label in labels:
                        if label not in speaker_labels:
                            speaker_labels.append(label)

        _update_job(job_id, status="translating", progress=65)
        translated: list[str] = [""] * len(segments)
        missing_translate: list[int] = []
        for index in range(len(segments)):
            cached = _chunk_get(job_id, "translate", index)
            if cached is None:
                missing_translate.append(index)
            else:
                translated[index] = cached
        if missing_translate:
            with ThreadPoolExecutor(max_workers=min(MAX_TRANSLATE_WORKERS, len(missing_translate))) as executor:
                futures = {
                    executor.submit(_translate_one, transcripts[index], diarized[index], index, participant_names): index
                    for index in missing_translate
                }
                for future in as_completed(futures):
                    index, text = future.result()
                    translated[index] = text
                    _chunk_put(job_id, "translate", index, text)
        english_transcript = _dedupe_overlap([text for text in translated if text.strip()])
        if not english_transcript:
            raise RuntimeError("No English transcript was produced")

        _update_job(job_id, status="mom", progress=88)
        result = _build_mom_structured(
            meeting_id=str(row["meeting_id"]),
            title=str(row["title"]),
            started_at=str(row["started_at"]),
            participant_names=participant_names,
            transcript=english_transcript,
            speaker_labels=speaker_labels,
        )
        _update_job(job_id, status="ready", progress=100, error="", result=result)
        shutil.rmtree(work, ignore_errors=True)
    except Exception as exc:
        _update_job(job_id, status="failed", progress=0, error=str(exc)[:1200])
    finally:
        with _schedule_lock:
            _scheduled.discard(job_id)


def _schedule(job_id: str) -> None:
    with _schedule_lock:
        if job_id in _scheduled:
            return
        _scheduled.add(job_id)
    _job_executor.submit(_process_job, job_id)


def init_job_system() -> None:
    JOB_ROOT.mkdir(parents=True, exist_ok=True)
    with _connect() as connection:
        rows = connection.execute(
            "SELECT job_id FROM jobs WHERE status IN ('queued','transcribing','diarizing','translating','mom') AND uploaded_bytes=expected_bytes"
        ).fetchall()
        connection.execute(
            "UPDATE jobs SET status='queued', progress=10, updated_at=? WHERE status IN ('transcribing','diarizing','translating','mom')",
            (int(time.time()),),
        )
    for row in rows:
        _schedule(str(row["job_id"]))


@router.post("/v1/meetings/jobs/init", response_model=JobState)
def init_job(request: JobInitRequest, authorization: str | None = Header(default=None)) -> JobState:
    device_id = require_auth(authorization)
    if request.audio_size > MAX_AUDIO_BYTES:
        raise HTTPException(status_code=413, detail="Meeting audio exceeds the MAI server upload limit")
    language_mode = request.language_mode if request.language_mode in LANGUAGE_PRESETS else "auto"
    now = int(time.time())
    participants = json.dumps(request.participants, ensure_ascii=False)
    with _connect() as connection:
        existing = connection.execute(
            "SELECT * FROM jobs WHERE device_id=? AND meeting_id=?",
            (device_id, request.meeting_id),
        ).fetchone()
        if existing is not None:
            return _row_state(existing)
        job_id = uuid.uuid4().hex
        audio_path = _job_dir(job_id) / "meeting.aac"
        connection.execute(
            """INSERT INTO jobs(job_id,meeting_id,device_id,title,started_at,participants,language_mode,audio_path,expected_bytes,uploaded_bytes,status,progress,created_at,updated_at)
               VALUES(?,?,?,?,?,?,?,?,?,0,'uploading',0,?,?)""",
            (job_id, request.meeting_id, device_id, request.title.strip() or "Meeting", request.started_at, participants, language_mode, str(audio_path), request.audio_size, now, now),
        )
        row = connection.execute("SELECT * FROM jobs WHERE job_id=?", (job_id,)).fetchone()
    return _row_state(row)


@router.put("/v1/meetings/jobs/{job_id}/audio", response_model=JobState)
async def upload_job_audio(
    job_id: str,
    request: Request,
    offset: int = Query(ge=0),
    authorization: str | None = Header(default=None),
) -> JobState:
    device_id = require_auth(authorization)
    row = _get_job(job_id, device_id)
    if str(row["status"]) not in {"uploading", "failed"}:
        return _row_state(row)
    expected = int(row["expected_bytes"])
    current = int(row["uploaded_bytes"])
    if offset != current:
        raise HTTPException(status_code=409, detail=f"Resume upload at byte {current}")
    body = await request.body()
    if not body or len(body) > UPLOAD_CHUNK_BYTES:
        raise HTTPException(status_code=413, detail=f"Upload chunks must be 1-{UPLOAD_CHUNK_BYTES} bytes")
    if current + len(body) > expected:
        raise HTTPException(status_code=400, detail="Upload exceeds declared meeting audio size")
    path = Path(str(row["audio_path"]))
    path.parent.mkdir(parents=True, exist_ok=True)
    mode = "r+b" if path.exists() else "wb"
    with path.open(mode) as output:
        output.seek(current)
        output.write(body)
        output.flush()
        os.fsync(output.fileno())
    new_size = current + len(body)
    progress = min(10, int(new_size * 10 / max(expected, 1)))
    with _connect() as connection:
        connection.execute(
            "UPDATE jobs SET uploaded_bytes=?, status='uploading', progress=?, error=NULL, updated_at=? WHERE job_id=?",
            (new_size, progress, int(time.time()), job_id),
        )
        updated = connection.execute("SELECT * FROM jobs WHERE job_id=?", (job_id,)).fetchone()
    return _row_state(updated)


@router.post("/v1/meetings/jobs/{job_id}/start", response_model=JobState)
def start_job(job_id: str, authorization: str | None = Header(default=None)) -> JobState:
    device_id = require_auth(authorization)
    row = _get_job(job_id, device_id)
    if int(row["uploaded_bytes"]) != int(row["expected_bytes"]):
        raise HTTPException(status_code=409, detail=f"Meeting audio upload is incomplete at byte {row['uploaded_bytes']}")
    with _connect() as connection:
        connection.execute(
            "UPDATE jobs SET status='queued', progress=10, error=NULL, updated_at=? WHERE job_id=? AND status!='ready'",
            (int(time.time()), job_id),
        )
        updated = connection.execute("SELECT * FROM jobs WHERE job_id=?", (job_id,)).fetchone()
    if str(updated["status"]) != "ready":
        _schedule(job_id)
    return _row_state(updated)


@router.get("/v1/meetings/jobs/{job_id}", response_model=JobState)
def get_job(job_id: str, authorization: str | None = Header(default=None)) -> JobState:
    device_id = require_auth(authorization)
    return _row_state(_get_job(job_id, device_id))


@router.post("/v1/meetings/jobs/{job_id}/retry", response_model=JobState)
def retry_job(job_id: str, authorization: str | None = Header(default=None)) -> JobState:
    device_id = require_auth(authorization)
    row = _get_job(job_id, device_id)
    if int(row["uploaded_bytes"]) != int(row["expected_bytes"]):
        return _row_state(row)
    if str(row["status"]) == "ready":
        return _row_state(row)
    with _connect() as connection:
        connection.execute(
            "UPDATE jobs SET status='queued', progress=10, error=NULL, updated_at=? WHERE job_id=?",
            (int(time.time()), job_id),
        )
        updated = connection.execute("SELECT * FROM jobs WHERE job_id=?", (job_id,)).fetchone()
    _schedule(job_id)
    return _row_state(updated)
