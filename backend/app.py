import json
import os
import shutil
import subprocess
import tempfile
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path
from typing import Any

import httpx
from fastapi import FastAPI, File, Form, Header, HTTPException, UploadFile
from pydantic import BaseModel, Field

OPENAI_API_KEY = os.getenv("OPENAI_API_KEY", "").strip()
MAI_GATEWAY_TOKEN = os.getenv("MAI_GATEWAY_TOKEN", "").strip()
STT_MODEL = os.getenv("MAI_STT_MODEL", "gpt-4o-transcribe")
MOM_MODEL = os.getenv("MAI_MOM_MODEL", "gpt-5.6-luna")
OPENAI_BASE = os.getenv("OPENAI_BASE_URL", "https://api.openai.com/v1").rstrip("/")
SEGMENT_SECONDS = int(os.getenv("MAI_SEGMENT_SECONDS", "900"))
MAX_STT_WORKERS = max(1, min(int(os.getenv("MAI_STT_WORKERS", "3")), 6))

app = FastAPI(title="MAI Private Processing Gateway", version="1.1.0")


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


def require_auth(authorization: str | None) -> None:
    if not MAI_GATEWAY_TOKEN:
        return
    if authorization != f"Bearer {MAI_GATEWAY_TOKEN}":
        raise HTTPException(status_code=401, detail="Invalid MAI gateway token")


def openai_headers() -> dict[str, str]:
    if not OPENAI_API_KEY:
        raise HTTPException(status_code=503, detail="OPENAI_API_KEY is not configured")
    return {"Authorization": f"Bearer {OPENAI_API_KEY}"}


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


def transcribe_segment(path: Path, index: int) -> tuple[int, str]:
    with path.open("rb") as handle:
        files = {"file": (path.name, handle, "audio/mpeg")}
        data = {
            "model": STT_MODEL,
            "response_format": "json",
            "prompt": "Meeting audio may contain English, Tamil, and Tanglish. Transcribe faithfully in the language actually spoken. Preserve names, numbers, dates, prices, and action wording.",
        }
        with httpx.Client(timeout=httpx.Timeout(15 * 60, connect=30.0)) as client:
            response = client.post(f"{OPENAI_BASE}/audio/transcriptions", headers=openai_headers(), data=data, files=files)
    if response.status_code >= 400:
        raise RuntimeError(f"STT failed ({response.status_code}): {response.text[:500]}")
    payload = response.json()
    return index, str(payload.get("text", "")).strip()


def transcribe_segments(segments: list[Path]) -> str:
    results: dict[int, str] = {}
    with ThreadPoolExecutor(max_workers=min(MAX_STT_WORKERS, len(segments))) as executor:
        futures = [executor.submit(transcribe_segment, path, index) for index, path in enumerate(segments)]
        for future in as_completed(futures):
            index, text = future.result()
            results[index] = text
    return "\n".join(results.get(i, "") for i in range(len(segments))).strip()


def extract_response_text(payload: dict[str, Any]) -> str:
    if isinstance(payload.get("output_text"), str):
        return payload["output_text"]
    chunks: list[str] = []
    for item in payload.get("output", []):
        for content in item.get("content", []) if isinstance(item, dict) else []:
            if isinstance(content, dict) and content.get("type") == "output_text":
                chunks.append(str(content.get("text", "")))
    return "\n".join(chunks).strip()


def strip_json_fence(text: str) -> str:
    value = text.strip()
    if value.startswith("```"):
        value = value.split("\n", 1)[1] if "\n" in value else value
        if value.endswith("```"):
            value = value[:-3]
    return value.strip()


def normalize_result(raw: dict[str, Any], meeting_id: str, transcript: str) -> MeetingResult:
    decisions: list[str] = []
    seen_decisions: set[str] = set()
    for item in raw.get("decisions", []) or []:
        text = str(item).strip()
        key = " ".join(text.lower().split())
        if text and key not in seen_decisions:
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
        key = " ".join(text.lower().split())
        owner = str(value.get("owner", "")).strip() or None
        due = str(value.get("due", "")).strip() or None
        if key in seen_actions:
            previous = actions[seen_actions[key]]
            owners = [x.strip() for x in (previous.owner or "").split("/") if x.strip()]
            if owner and owner not in owners:
                owners.append(owner)
            actions[seen_actions[key]] = Action(text=previous.text, owner=" / ".join(owners) or None, due=previous.due or due)
        else:
            seen_actions[key] = len(actions)
            actions.append(Action(text=text, owner=owner, due=due))

    return MeetingResult(
        meeting_id=meeting_id,
        transcript=transcript,
        summary=str(raw.get("summary", "")).strip() or "Meeting recorded.",
        decisions=decisions[:10],
        actions=actions[:15],
        language=str(raw.get("language", "")).strip() or None,
    )


def build_mom(meeting_id: str, title: str, started_at: str, participants_json: str, transcript: str) -> MeetingResult:
    try:
        participants = json.loads(participants_json or "[]")
    except json.JSONDecodeError:
        participants = []
    names = [str(p.get("name", "")).strip() for p in participants if isinstance(p, dict) and p.get("name")]
    prompt = f"""You are MAI, a meeting-intelligence system. Create a short, practical MOM from the transcript below.

Rules:
- Output ONLY valid JSON. No markdown.
- The MOM itself must be in English, even when the meeting is Tamil or Tanglish.
- Keep meaning, proper names, amounts, dates, commitments, and decisions accurate.
- Do not invent facts, owners, or due dates.
- Semantically duplicate decisions/actions must appear only once.
- If the same task is assigned or repeated under multiple names, make ONE canonical action and merge correct owners using ' / '.
- Summary: maximum 4 concise sentences.
- Decisions: maximum 10 concise strings.
- Actions: maximum 15 objects with text, owner, due. Use null when unknown.
- Infer owner only when clearly supported by the transcript or participant context.
- Detect the dominant language/mix as a short string such as English, Tamil, or Tamil + English (Tanglish).

Meeting title: {title}
Meeting start: {started_at}
Participants: {', '.join(names) if names else 'Unknown'}

Return shape:
{{"summary":"...","decisions":["..."],"actions":[{{"text":"...","owner":null,"due":null}}],"language":"..."}}

Transcript:
{transcript}
"""
    payload = {
        "model": MOM_MODEL,
        "input": prompt,
        "max_output_tokens": 3000,
    }
    with httpx.Client(timeout=httpx.Timeout(10 * 60, connect=30.0)) as client:
        response = client.post(
            f"{OPENAI_BASE}/responses",
            headers={**openai_headers(), "Content-Type": "application/json"},
            json=payload,
        )
    if response.status_code >= 400:
        raise RuntimeError(f"MOM generation failed ({response.status_code}): {response.text[:500]}")
    text = strip_json_fence(extract_response_text(response.json()))
    raw = json.loads(text)
    return normalize_result(raw, meeting_id, transcript)


@app.get("/health")
def health() -> dict[str, Any]:
    return {
        "ok": True,
        "openai_configured": bool(OPENAI_API_KEY),
        "ffmpeg": bool(shutil.which("ffmpeg")),
        "stt_model": STT_MODEL,
        "mom_model": MOM_MODEL,
    }


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
            transcript = transcribe_segments(segments)
            if not transcript:
                raise HTTPException(status_code=422, detail="No speech was transcribed")
            return build_mom(meeting_id, title, started_at, participants, transcript)
        except HTTPException:
            raise
        except Exception as exc:
            raise HTTPException(status_code=502, detail=str(exc)[:800]) from exc
