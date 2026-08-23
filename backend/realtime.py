from typing import Any

import httpx
from fastapi import APIRouter, Header, HTTPException
from pydantic import BaseModel, Field

import app as core
from device_auth import require_auth
from jobs import LANGUAGE_PRESETS

router = APIRouter()


class RealtimeInitRequest(BaseModel):
    participants: list[str] = Field(default_factory=list)
    language_mode: str = Field(default="auto", max_length=30)


class RealtimeInitResponse(BaseModel):
    value: str
    expires_at: int | None = None
    websocket_url: str
    languages: list[str] = Field(default_factory=list)


@router.post("/v1/realtime/client-secret-v2", response_model=RealtimeInitResponse)
def realtime_client_secret_v2(
    request: RealtimeInitRequest,
    authorization: str | None = Header(default=None),
) -> RealtimeInitResponse:
    require_auth(authorization)
    languages = LANGUAGE_PRESETS.get(request.language_mode, [])
    transcription: dict[str, Any] = {
        "model": core.LIVE_STT_MODEL,
        "keywords": core.transcription_keywords(request.participants),
        "delay": "high",
    }
    if languages:
        transcription["languages"] = languages

    payload = {
        "expires_after": {"anchor": "created_at", "seconds": 600},
        "session": {
            "type": "transcription",
            "audio": {
                "input": {
                    "format": {"type": "audio/pcm", "rate": 24000},
                    "noise_reduction": {"type": "far_field"},
                    "transcription": transcription,
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
            f"{core.OPENAI_BASE}/realtime/client_secrets",
            headers={**core.openai_headers(), "Content-Type": "application/json"},
            json=payload,
        )
    if response.status_code >= 400:
        raise HTTPException(status_code=502, detail=f"Realtime token service failed ({response.status_code})")
    data = response.json()
    value = str(data.get("value", "")).strip()
    if not value:
        raise HTTPException(status_code=502, detail="Realtime token service returned no client secret")
    return RealtimeInitResponse(
        value=value,
        expires_at=data.get("expires_at"),
        websocket_url=f"wss://api.openai.com/v1/realtime?model={core.LIVE_STT_MODEL}",
        languages=languages,
    )
