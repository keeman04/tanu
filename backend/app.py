import json
import os
from typing import Annotated

import httpx
from fastapi import FastAPI, File, Form, Header, HTTPException, UploadFile
from pydantic import BaseModel, Field

OPENAI_API_KEY = os.getenv("OPENAI_API_KEY", "")
TANU_API_TOKEN = os.getenv("TANU_API_TOKEN", "")
TRANSCRIBE_MODEL = os.getenv("TANU_TRANSCRIBE_MODEL", "gpt-4o-mini-transcribe")
MOM_MODEL = os.getenv("TANU_MOM_MODEL", "gpt-5.6-luna")
OPENAI_BASE = "https://api.openai.com/v1"

app = FastAPI(title="TANU Core API", version="0.1.0")


class MomRequest(BaseModel):
    title: str = "Meeting"
    transcript: str = Field(min_length=1, max_length=200_000)


class ActionItem(BaseModel):
    task: str
    owner: str = ""
    dueDate: str = ""


class MomResponse(BaseModel):
    summary: str
    decisions: list[str]
    actions: list[ActionItem]
    followUps: list[str]


def require_auth(authorization: str | None) -> None:
    if not TANU_API_TOKEN:
        return
    if authorization != f"Bearer {TANU_API_TOKEN}":
        raise HTTPException(status_code=401, detail="Invalid TANU API token")


def openai_headers() -> dict[str, str]:
    if not OPENAI_API_KEY:
        raise HTTPException(status_code=503, detail="OPENAI_API_KEY is not configured")
    return {"Authorization": f"Bearer {OPENAI_API_KEY}"}


@app.get("/health")
async def health() -> dict[str, str]:
    return {"status": "ok"}


@app.post("/v1/transcriptions/chunks")
async def transcribe_chunk(
    audio: Annotated[UploadFile, File()],
    meeting_id: Annotated[str, Form()],
    chunk_index: Annotated[int, Form()],
    authorization: Annotated[str | None, Header()] = None,
) -> dict[str, object]:
    require_auth(authorization)
    audio_bytes = await audio.read()
    if not audio_bytes:
        raise HTTPException(status_code=400, detail="Empty audio chunk")
    if len(audio_bytes) > 8 * 1024 * 1024:
        raise HTTPException(status_code=413, detail="Audio chunk is too large")

    files = {"file": (audio.filename or "chunk.wav", audio_bytes, audio.content_type or "audio/wav")}
    data = {
        "model": TRANSCRIBE_MODEL,
        "prompt": (
            "TANU meeting transcript. Preserve natural English, Tamil, and Tanglish/code-switched speech. "
            "Do not translate Tamil into English unless the speaker actually used English. "
            "Keep names, companies, dates, numbers, commitments, and action items accurate."
        ),
        "response_format": "json",
    }

    timeout = httpx.Timeout(40.0, connect=10.0)
    async with httpx.AsyncClient(timeout=timeout) as client:
        response = await client.post(
            f"{OPENAI_BASE}/audio/transcriptions",
            headers=openai_headers(),
            data=data,
            files=files,
        )
    if response.status_code >= 300:
        raise HTTPException(status_code=502, detail=f"Transcription provider failed: {response.text[:500]}")
    payload = response.json()
    text = str(payload.get("text", "")).strip()
    return {"meeting_id": meeting_id, "chunk_index": chunk_index, "text": text}


@app.post("/v1/mom", response_model=MomResponse)
async def generate_mom(
    request: MomRequest,
    authorization: Annotated[str | None, Header()] = None,
) -> MomResponse:
    require_auth(authorization)
    schema = {
        "type": "object",
        "properties": {
            "summary": {"type": "string"},
            "decisions": {"type": "array", "items": {"type": "string"}},
            "actions": {
                "type": "array",
                "items": {
                    "type": "object",
                    "properties": {
                        "task": {"type": "string"},
                        "owner": {"type": "string"},
                        "dueDate": {"type": "string"},
                    },
                    "required": ["task", "owner", "dueDate"],
                    "additionalProperties": False,
                },
            },
            "followUps": {"type": "array", "items": {"type": "string"}},
        },
        "required": ["summary", "decisions", "actions", "followUps"],
        "additionalProperties": False,
    }
    prompt = f"""
You are TANU, a meeting minutes assistant.
Meeting title: {request.title}

Create concise, factual minutes from the transcript. The meeting may contain English, Tamil, Tanglish, or code-switching. Understand the meaning across languages, but produce the final MOM in clear English unless the transcript explicitly requires another language.

Rules:
- Never invent decisions, owners, deadlines, names, or commitments.
- Put only explicit decisions in decisions.
- Put concrete commitments/tasks in actions.
- If an owner or due date was not stated, use an empty string.
- Keep unresolved next steps in followUps.
- Make the summary useful but concise.

TRANSCRIPT:
{request.transcript}
""".strip()
    body = {
        "model": MOM_MODEL,
        "input": prompt,
        "store": False,
        "text": {
            "format": {
                "type": "json_schema",
                "name": "tanu_mom",
                "strict": True,
                "schema": schema,
            }
        },
    }
    headers = {**openai_headers(), "Content-Type": "application/json"}
    timeout = httpx.Timeout(55.0, connect=10.0)
    async with httpx.AsyncClient(timeout=timeout) as client:
        response = await client.post(f"{OPENAI_BASE}/responses", headers=headers, json=body)
    if response.status_code >= 300:
        raise HTTPException(status_code=502, detail=f"MOM provider failed: {response.text[:500]}")

    envelope = response.json()
    output_text = ""
    for item in envelope.get("output", []):
        for content in item.get("content", []) or []:
            if content.get("type") == "output_text":
                output_text = content.get("text", "")
                break
        if output_text:
            break
    if not output_text:
        raise HTTPException(status_code=502, detail="MOM provider returned no output_text")

    try:
        return MomResponse.model_validate(json.loads(output_text))
    except Exception as exc:
        raise HTTPException(status_code=502, detail=f"Invalid structured MOM: {exc}") from exc
