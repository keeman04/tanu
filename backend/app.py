import json
import os
import re
import shutil
import subprocess
import tempfile
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import httpx
from fastapi import FastAPI, File, Form, Header, HTTPException, UploadFile
from pydantic import BaseModel, Field

OPENAI_API_KEY = os.getenv("OPENAI_API_KEY", "").strip()
MAI_GATEWAY_TOKEN = os.getenv("MAI_GATEWAY_TOKEN", "").strip()
STT_MODEL = os.getenv("MAI_STT_MODEL", "gpt-transcribe")
DIARIZE_MODEL = os.getenv("MAI_DIARIZE_MODEL", "gpt-4o-transcribe-diarize")
LIVE_STT_MODEL = os.getenv("MAI_LIVE_STT_MODEL", "gpt-live-transcribe")
TRANSLATE_MODEL = os.getenv("MAI_TRANSLATE_MODEL", "gpt-5.6-terra")
MOM_MODEL = os.getenv("MAI_MOM_MODEL", "gpt-5.6-sol")
OPENAI_BASE = os.getenv("OPENAI_BASE_URL", "https://api.openai.com/v1").rstrip("/")
SEGMENT_SECONDS = int(os.getenv("MAI_SEGMENT_SECONDS", "480"))
MAX_STT_WORKERS = max(1, min(int(os.getenv("MAI_STT_WORKERS", "4")), 6))
MAX_DIARIZE_WORKERS = max(1, min(int(os.getenv("MAI_DIARIZE_WORKERS", "3")), 4))
MAX_TRANSLATE_WORKERS = max(1, min(int(os.getenv("MAI_TRANSLATE_WORKERS", "4")), 6))

DOMAIN_KEYWORDS = [
    "MAI",
    "VGP",
    "VGP Marine Kingdom",
    "VGP Universal Kingdom",
    "VGP Waghoba",
    "VGP Playy Kingdom",
    "Rednote",
    "WhatsApp",
    "MOM",
]

app = FastAPI(title="MAI Private Processing Gateway", version="1.3.0")


class Action(BaseModel):
    text: str
    owner: str | None = None
    due: str | None = None


class MeetingResult(BaseModel):
    meeting_id: str
    transcript: str
    summary: str
    decisions: list[str] = Field(default_factory=list)
    actions: list[Action] = Field(default_factory=list)
    language: str | None = None
    speakers: list[str] = Field(default_factory=list)


class RealtimeSecretRequest(BaseModel):
    participants: list[str] = Field(default_factory=list)


class RealtimeSecretResponse(BaseModel):
    value: str
    expires_at: int | None = None
    websocket_url: str


def require_auth(authorization: str | None) -> None:
    if not MAI_GATEWAY_TOKEN:
        raise HTTPException(status_code=503, detail="MAI_GATEWAY_TOKEN is not configured")
    if authorization != f"Bearer {MAI_GATEWAY_TOKEN}":
        raise HTTPException(status_code=401, detail="Invalid MAI gateway token")


def openai_headers() -> dict[str, str]:
    if not OPENAI_API_KEY:
        raise HTTPException(status_code=503, detail="OPENAI_API_KEY is not configured")
    return {"Authorization": f"Bearer {OPENAI_API_KEY}"}


def parse_participants(participants_json: str) -> list[str]:
    try:
        participants = json.loads(participants_json or "[]")
    except json.JSONDecodeError:
        return []
    names: list[str] = []
    seen: set[str] = set()
    for person in participants:
        if not isinstance(person, dict):
            continue
        name = str(person.get("name", "")).strip()
        key = name.casefold()
        if name and key not in seen:
            seen.add(key)
            names.append(name)
    return names


def transcription_keywords(participant_names: list[str]) -> list[str]:
    result: list[str] = []
    seen: set[str] = set()
    for value in [*participant_names, *DOMAIN_KEYWORDS]:
        clean = value.replace("\n", " ").replace("\r", " ").replace("<", "").replace(">", "").strip()
        key = clean.casefold()
        if clean and key not in seen:
            seen.add(key)
            result.append(clean)
    return result[:40]


def language_hints() -> list[str]:
    return ["ta", "en"]


def run_ffmpeg(input_path: Path, output_dir: Path) -> list[Path]:
    if shutil.which("ffmpeg") is None:
        raise HTTPException(status_code=503, detail="ffmpeg is required on the MAI backend")
    pattern = output_dir / "segment-%04d.mp3"
    command = [
        "ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
        "-i", str(input_path),
        "-vn", "-ac", "1", "-ar", "16000", "-b:a", "32k",
        "-f", "segment", "-segment_time", str(SEGMENT_SECONDS),
        "-reset_timestamps", "1", str(pattern),
    ]
    completed = subprocess.run(command, capture_output=True, text=True, timeout=60 * 60)
    if completed.returncode != 0:
        raise HTTPException(status_code=422, detail=f"Audio conversion failed: {completed.stderr[-500:]}")
    segments = sorted(output_dir.glob("segment-*.mp3"))
    if not segments:
        raise HTTPException(status_code=422, detail="No audio segments were produced")
    return segments


def transcribe_segment(path: Path, index: int, participant_names: list[str]) -> tuple[int, str]:
    # The accurate content pass uses native multi-language + keyword hints instead of an
    # English-only prompt, which avoids biasing Tamil/Tanglish speech toward English words.
    multipart: list[tuple[str, tuple[Any, ...]]] = [("model", (None, STT_MODEL))]
    for language in language_hints():
        multipart.append(("languages[]", (None, language)))
    for keyword in transcription_keywords(participant_names):
        multipart.append(("keywords[]", (None, keyword)))

    with path.open("rb") as handle:
        multipart.append(("file", (path.name, handle, "audio/mpeg")))
        with httpx.Client(timeout=httpx.Timeout(15 * 60, connect=30.0)) as client:
            response = client.post(
                f"{OPENAI_BASE}/audio/transcriptions",
                headers=openai_headers(),
                files=multipart,
            )
    if response.status_code >= 400:
        raise RuntimeError(f"STT failed ({response.status_code}): {response.text[:500]}")
    payload = response.json()
    return index, str(payload.get("text", "")).strip()


def transcribe_segments(segments: list[Path], participant_names: list[str]) -> list[str]:
    results: dict[int, str] = {}
    with ThreadPoolExecutor(max_workers=min(MAX_STT_WORKERS, len(segments))) as executor:
        futures = [
            executor.submit(transcribe_segment, path, index, participant_names)
            for index, path in enumerate(segments)
        ]
        for future in as_completed(futures):
            index, text = future.result()
            results[index] = text
    return [results.get(i, "") for i in range(len(segments))]


def safe_speaker_label(raw: Any, part_index: int) -> str:
    clean = re.sub(r"[^A-Za-z0-9_-]", "", str(raw or "").strip())[:24]
    if not clean:
        clean = "?"
    # Labels from independently chunked files cannot safely be assumed to identify the same
    # person across chunk boundaries, so part number is intentionally included.
    return f"P{part_index + 1:02d}-{clean}"


def diarize_segment(path: Path, index: int) -> tuple[int, str, list[str]]:
    multipart: list[tuple[str, tuple[Any, ...]]] = [
        ("model", (None, DIARIZE_MODEL)),
        ("response_format", (None, "diarized_json")),
        ("chunking_strategy", (None, "auto")),
    ]
    with path.open("rb") as handle:
        multipart.append(("file", (path.name, handle, "audio/mpeg")))
        with httpx.Client(timeout=httpx.Timeout(15 * 60, connect=30.0)) as client:
            response = client.post(
                f"{OPENAI_BASE}/audio/transcriptions",
                headers=openai_headers(),
                files=multipart,
            )
    if response.status_code >= 400:
        raise RuntimeError(f"Diarization failed ({response.status_code}): {response.text[:500]}")

    payload = response.json()
    lines: list[str] = []
    labels: list[str] = []
    for segment in payload.get("segments", []) or []:
        if not isinstance(segment, dict):
            continue
        text = str(segment.get("text", "")).strip()
        if not text:
            continue
        label = safe_speaker_label(segment.get("speaker"), index)
        if label not in labels:
            labels.append(label)
        lines.append(f"[{label}] {text}")
    return index, "\n".join(lines), labels


def diarize_segments(segments: list[Path]) -> tuple[list[str], list[str]]:
    # Diarization enriches the transcript but must never block the authoritative content
    # pass. If a diarization request fails, that part falls back to an unlabeled transcript.
    results: dict[int, str] = {}
    labels: list[str] = []
    with ThreadPoolExecutor(max_workers=min(MAX_DIARIZE_WORKERS, len(segments))) as executor:
        futures = {
            executor.submit(diarize_segment, path, index): index
            for index, path in enumerate(segments)
        }
        for future in as_completed(futures):
            index = futures[future]
            try:
                _, text, segment_labels = future.result()
                results[index] = text
                for label in segment_labels:
                    if label not in labels:
                        labels.append(label)
            except Exception:
                results[index] = ""
    return [results.get(i, "") for i in range(len(segments))], labels


def extract_response_text(payload: dict[str, Any]) -> str:
    if isinstance(payload.get("output_text"), str):
        return payload["output_text"]
    chunks: list[str] = []
    for item in payload.get("output", []):
        if not isinstance(item, dict):
            continue
        for content in item.get("content", []):
            if isinstance(content, dict) and content.get("type") == "output_text":
                chunks.append(str(content.get("text", "")))
    return "\n".join(chunks).strip()


def response_text(model: str, prompt: str, max_output_tokens: int) -> str:
    payload = {
        "model": model,
        "input": prompt,
        "max_output_tokens": max_output_tokens,
        "store": False,
    }
    with httpx.Client(timeout=httpx.Timeout(15 * 60, connect=30.0)) as client:
        response = client.post(
            f"{OPENAI_BASE}/responses",
            headers={**openai_headers(), "Content-Type": "application/json"},
            json=payload,
        )
    if response.status_code >= 400:
        raise RuntimeError(f"Text processing failed ({response.status_code}): {response.text[:500]}")
    text = extract_response_text(response.json()).strip()
    if not text:
        raise RuntimeError("Text processing returned no output")
    return text


def translate_segment(
    authoritative_text: str,
    index: int,
    participant_names: list[str],
    diarized_text: str = "",
) -> tuple[int, str]:
    if not authoritative_text.strip():
        return index, ""
    participant_context = ", ".join(participant_names) if participant_names else "not supplied"
    guide = diarized_text.strip() or "No reliable speaker guide was available for this part."
    prompt = f"""Create a faithful English meeting transcript for PART {index + 1:02d}.

You receive TWO inputs:
1. AUTHORITATIVE CONTENT TRANSCRIPT: source of truth for every word, fact, name, number and commitment.
2. DIARIZATION GUIDE: used ONLY to identify speaker-turn boundaries and generic speaker labels.

Accuracy rules:
- This is translation/transcript alignment, NOT summarization. Keep every meaningful statement from the authoritative content.
- Source may be Tamil, English, or Tamil-English code-switching (Tanglish).
- Preserve proper names exactly. Known participant names: {participant_context}.
- Preserve numbers, money, dates, times, percentages, product names and commitments exactly.
- If the diarization guide contains labels like [P01-A], preserve those labels exactly on the corresponding English turns.
- Generic labels such as P01-A are NOT participant identities. Never replace a generic label with a participant name merely by guessing.
- If the two inputs disagree about wording, the AUTHORITATIVE CONTENT TRANSCRIPT wins.
- If the diarization guide is missing or cannot be aligned safely, output the faithful English transcript without inventing speaker labels.
- Do not add facts, owners, dates or explanations that are not present.
- If a phrase is genuinely unclear, write [unclear] instead of guessing.
- Output only the English transcript text.

AUTHORITATIVE CONTENT TRANSCRIPT:
{authoritative_text}

DIARIZATION GUIDE:
{guide}
"""
    return index, response_text(TRANSLATE_MODEL, prompt, max_output_tokens=14000)


def translate_segments(
    transcripts: list[str],
    participant_names: list[str],
    diarized_transcripts: list[str] | None = None,
) -> list[str]:
    diarized_transcripts = diarized_transcripts or [""] * len(transcripts)
    results: dict[int, str] = {}
    with ThreadPoolExecutor(max_workers=min(MAX_TRANSLATE_WORKERS, max(1, len(transcripts)))) as executor:
        futures = [
            executor.submit(
                translate_segment,
                text,
                index,
                participant_names,
                diarized_transcripts[index] if index < len(diarized_transcripts) else "",
            )
            for index, text in enumerate(transcripts)
        ]
        for future in as_completed(futures):
            index, text = future.result()
            results[index] = text
    return [results.get(i, "") for i in range(len(transcripts))]


def strip_json_fence(text: str) -> str:
    value = text.strip()
    if value.startswith("```"):
        value = value.split("\n", 1)[1] if "\n" in value else value
        if value.endswith("```"):
            value = value[:-3]
    return value.strip()


def normalize_text_key(value: str) -> str:
    return re.sub(r"\s+", " ", re.sub(r"[^a-z0-9 ]", " ", value.casefold())).strip()


def canonical_owner(value: Any, participant_names: list[str]) -> str | None:
    raw = str(value or "").strip()
    if not raw:
        return None
    by_key = {normalize_text_key(name): name for name in participant_names}
    resolved: list[str] = []
    for part in re.split(r"\s*/\s*|\s*,\s*|\s+and\s+", raw, flags=re.IGNORECASE):
        key = normalize_text_key(part)
        match = by_key.get(key)
        if match and match not in resolved:
            resolved.append(match)
    return " / ".join(resolved) or None


def normalize_result(
    raw: dict[str, Any],
    meeting_id: str,
    transcript_english: str,
    participant_names: list[str] | None = None,
    speaker_labels: list[str] | None = None,
) -> MeetingResult:
    participant_names = participant_names or []
    speaker_labels = speaker_labels or []
    decisions: list[str] = []
    seen_decisions: set[str] = set()
    for item in raw.get("decisions", []) or []:
        text = str(item).strip()
        key = normalize_text_key(text)
        if text and key and key not in seen_decisions:
            seen_decisions.add(key)
            decisions.append(text)

    actions: list[Action] = []
    seen_actions: dict[str, int] = {}
    for value in raw.get("actions", []) or []:
        if not isinstance(value, dict):
            continue
        text = str(value.get("text", "")).strip()
        if not text:
            continue
        key = normalize_text_key(text)
        owner = canonical_owner(value.get("owner"), participant_names)
        due = str(value.get("due", "")).strip() or None
        if key in seen_actions:
            previous = actions[seen_actions[key]]
            owners = [x.strip() for x in (previous.owner or "").split("/") if x.strip()]
            if owner:
                for candidate in [x.strip() for x in owner.split("/") if x.strip()]:
                    if candidate not in owners:
                        owners.append(candidate)
            actions[seen_actions[key]] = Action(
                text=previous.text,
                owner=" / ".join(owners) or None,
                due=previous.due or due,
            )
        else:
            seen_actions[key] = len(actions)
            actions.append(Action(text=text, owner=owner, due=due))

    return MeetingResult(
        meeting_id=meeting_id,
        transcript=transcript_english,
        summary=str(raw.get("summary", "")).strip() or "Meeting recorded.",
        decisions=decisions[:12],
        actions=actions[:20],
        language=str(raw.get("language", "")).strip() or None,
        speakers=speaker_labels,
    )


def meeting_date(started_at: str) -> str:
    try:
        value = float(started_at)
        if value > 10_000_000_000:
            value /= 1000.0
        return datetime.fromtimestamp(value, tz=timezone.utc).date().isoformat()
    except (TypeError, ValueError, OSError, OverflowError):
        return "unknown"


def build_mom(
    meeting_id: str,
    title: str,
    started_at: str,
    participant_names: list[str],
    transcript_english: str,
    speaker_labels: list[str] | None = None,
) -> MeetingResult:
    names = ", ".join(participant_names) if participant_names else "Unknown"
    date = meeting_date(started_at)
    prompt = f"""You are MAI, a high-accuracy meeting-intelligence system. Create the final Minutes of Meeting from the VERIFIED ENGLISH TRANSCRIPT below.

The transcript is the only source of truth.

Rules:
- Output ONLY valid JSON. No markdown.
- Do not invent, assume or complete missing information.
- Summary: concise but cover the important discussion, maximum 6 sentences.
- Decisions: include only explicit decisions/agreements. Maximum 12.
- Actions: include only explicit commitments, requests or assigned work. Maximum 20.
- Action text must state exactly what needs to be done, not a vague summary.
- Owner may ONLY be one or more exact names from this participant list: {names}. If the owner is not clearly stated, use null.
- Generic diarization labels such as P01-A are not participant names and can NEVER be used as action owners.
- A speaker label alone does not prove that speaker's real-world identity. Never infer a participant name from a generic label.
- For due dates: use YYYY-MM-DD only when a date or relative deadline is explicitly supported. Meeting date is {date}. If unclear, use null.
- Never infer an owner merely because that person discussed the topic.
- Never convert a suggestion into a decision or action.
- Remove semantic duplicates while preserving distinct tasks.
- Preserve amounts, dates, numbers, brand names and commitments exactly.
- language should describe the original spoken mix, e.g. English, Tamil, Tamil + English (Tanglish).

Meeting title: {title}
Meeting date: {date}
Participants: {names}

Return shape:
{{"summary":"...","decisions":["..."],"actions":[{{"text":"...","owner":null,"due":null}}],"language":"..."}}

VERIFIED ENGLISH TRANSCRIPT:
{transcript_english}
"""
    text = strip_json_fence(response_text(MOM_MODEL, prompt, max_output_tokens=8000))
    raw = json.loads(text)
    return normalize_result(raw, meeting_id, transcript_english, participant_names, speaker_labels)


@app.get("/health")
def health() -> dict[str, Any]:
    return {
        "ok": True,
        "openai_configured": bool(OPENAI_API_KEY),
        "gateway_auth_configured": bool(MAI_GATEWAY_TOKEN),
        "ffmpeg": bool(shutil.which("ffmpeg")),
        "stt_model": STT_MODEL,
        "diarize_model": DIARIZE_MODEL,
        "live_stt_model": LIVE_STT_MODEL,
        "translate_model": TRANSLATE_MODEL,
        "mom_model": MOM_MODEL,
        "languages": language_hints(),
    }


@app.post("/v1/realtime/client-secret", response_model=RealtimeSecretResponse)
def realtime_client_secret(
    request: RealtimeSecretRequest,
    authorization: str | None = Header(default=None),
) -> RealtimeSecretResponse:
    require_auth(authorization)
    keywords = transcription_keywords(request.participants)
    payload = {
        "expires_after": {"anchor": "created_at", "seconds": 600},
        "session": {
            "type": "transcription",
            "audio": {
                "input": {
                    "format": {"type": "audio/pcm", "rate": 24000},
                    "noise_reduction": {"type": "far_field"},
                    "transcription": {
                        "model": LIVE_STT_MODEL,
                        "languages": language_hints(),
                        "keywords": keywords,
                        "delay": "high",
                    },
                    "turn_detection": {
                        "type": "server_vad",
                        "threshold": 0.45,
                        "prefix_padding_ms": 350,
                        "silence_duration_ms": 650,
                    },
                }
            },
        },
    }
    with httpx.Client(timeout=httpx.Timeout(30.0, connect=15.0)) as client:
        response = client.post(
            f"{OPENAI_BASE}/realtime/client_secrets",
            headers={**openai_headers(), "Content-Type": "application/json"},
            json=payload,
        )
    if response.status_code >= 400:
        raise HTTPException(status_code=502, detail=f"Realtime token service failed ({response.status_code})")
    data = response.json()
    value = str(data.get("value", "")).strip()
    if not value:
        raise HTTPException(status_code=502, detail="Realtime token service returned no client secret")
    return RealtimeSecretResponse(
        value=value,
        expires_at=data.get("expires_at"),
        websocket_url=f"wss://api.openai.com/v1/realtime?model={LIVE_STT_MODEL}",
    )


@app.post("/v1/meetings/process", response_model=MeetingResult)
def process_meeting(
    meeting_id: str = Form(...),
    title: str = Form("Meeting"),
    started_at: str = Form(""),
    participants: str = Form("[]"),
    audio: UploadFile = File(...),
    authorization: str | None = Header(default=None),
) -> MeetingResult:
    require_auth(authorization)
    if not audio.filename:
        raise HTTPException(status_code=400, detail="Audio file is required")

    participant_names = parse_participants(participants)

    with tempfile.TemporaryDirectory(prefix="mai-") as temp:
        root = Path(temp)
        source = root / "meeting.aac"
        with source.open("wb") as output:
            while True:
                chunk = audio.file.read(1024 * 1024)
                if not chunk:
                    break
                output.write(chunk)
        if source.stat().st_size == 0:
            raise HTTPException(status_code=400, detail="Audio file is empty")

        segments_dir = root / "segments"
        segments_dir.mkdir()
        segments = run_ffmpeg(source, segments_dir)
        try:
            # Accuracy pass and speaker-boundary pass are deliberately separate. The first
            # owns the words; diarization can enrich speaker turns but cannot overwrite facts.
            with ThreadPoolExecutor(max_workers=2) as executor:
                content_future = executor.submit(transcribe_segments, segments, participant_names)
                diarize_future = executor.submit(diarize_segments, segments)
                original_segments = content_future.result()
                diarized_segments, speaker_labels = diarize_future.result()

            original_transcript = "\n".join(x for x in original_segments if x).strip()
            if not original_transcript:
                raise HTTPException(status_code=422, detail="No speech was transcribed")

            english_segments = translate_segments(original_segments, participant_names, diarized_segments)
            english_transcript = "\n".join(x for x in english_segments if x).strip()
            if not english_transcript:
                raise HTTPException(status_code=422, detail="No English transcript was produced")

            return build_mom(
                meeting_id=meeting_id,
                title=title,
                started_at=started_at,
                participant_names=participant_names,
                transcript_english=english_transcript,
                speaker_labels=speaker_labels,
            )
        except HTTPException:
            raise
        except Exception as exc:
            raise HTTPException(status_code=502, detail=str(exc)[:800]) from exc
