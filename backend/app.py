import asyncio
import hashlib
import json
import os
import sqlite3
import time
from contextlib import asynccontextmanager
from pathlib import Path
from typing import Any

import httpx
from fastapi import FastAPI, Header, HTTPException, Request, Response
from pydantic import BaseModel, Field

OPENAI_API_KEY = os.getenv("OPENAI_API_KEY", "")
TANU_API_TOKEN = os.getenv("TANU_API_TOKEN", "")
TRANSCRIBE_MODEL = os.getenv("TANU_TRANSCRIBE_MODEL", "gpt-4o-mini-transcribe")
MOM_MODEL = os.getenv("TANU_MOM_MODEL", "gpt-5.6-luna")
OPENAI_BASE = os.getenv("OPENAI_BASE_URL", "https://api.openai.com/v1").rstrip("/")
DATA_DIR = Path(os.getenv("TANU_DATA_DIR", "./data")).resolve()
DB_PATH = DATA_DIR / "tanu.db"
WORKER_COUNT = max(1, min(6, int(os.getenv("TANU_STT_WORKERS", "3"))))
ROLLING_WINDOW_MS = int(os.getenv("TANU_ROLLING_WINDOW_MS", str(10 * 60 * 1000)))
DISABLE_WORKERS = os.getenv("TANU_DISABLE_WORKERS", "0") == "1"
MAX_CHUNK_BYTES = 2 * 1024 * 1024

DATA_DIR.mkdir(parents=True, exist_ok=True)


def now_ms() -> int:
    return int(time.time() * 1000)


def db_connect() -> sqlite3.Connection:
    conn = sqlite3.connect(DB_PATH, timeout=30, isolation_level=None)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA journal_mode=WAL")
    conn.execute("PRAGMA foreign_keys=ON")
    return conn


def init_db() -> None:
    with db_connect() as db:
        db.executescript(
            """
            CREATE TABLE IF NOT EXISTS meetings (
                id TEXT PRIMARY KEY,
                title TEXT NOT NULL,
                started_at_ms INTEGER NOT NULL,
                created_at_ms INTEGER NOT NULL,
                state TEXT NOT NULL DEFAULT 'recording',
                final_mom_json TEXT,
                final_error TEXT
            );

            CREATE TABLE IF NOT EXISTS chunks (
                meeting_id TEXT NOT NULL,
                sequence INTEGER NOT NULL,
                start_ms INTEGER NOT NULL,
                end_ms INTEGER NOT NULL,
                codec TEXT NOT NULL,
                mime_type TEXT NOT NULL,
                sha256 TEXT NOT NULL,
                path TEXT NOT NULL,
                size_bytes INTEGER NOT NULL,
                state TEXT NOT NULL,
                transcript TEXT,
                retry_count INTEGER NOT NULL DEFAULT 0,
                next_retry_at_ms INTEGER NOT NULL DEFAULT 0,
                last_error TEXT,
                created_at_ms INTEGER NOT NULL,
                updated_at_ms INTEGER NOT NULL,
                PRIMARY KEY (meeting_id, sequence),
                FOREIGN KEY (meeting_id) REFERENCES meetings(id) ON DELETE CASCADE
            );

            CREATE INDEX IF NOT EXISTS idx_chunks_state_retry ON chunks(state, next_retry_at_ms);
            CREATE INDEX IF NOT EXISTS idx_chunks_meeting ON chunks(meeting_id, sequence);

            CREATE TABLE IF NOT EXISTS rolling_summaries (
                meeting_id TEXT NOT NULL,
                window_start_ms INTEGER NOT NULL,
                window_end_ms INTEGER NOT NULL,
                data_json TEXT NOT NULL,
                created_at_ms INTEGER NOT NULL,
                PRIMARY KEY (meeting_id, window_start_ms),
                FOREIGN KEY (meeting_id) REFERENCES meetings(id) ON DELETE CASCADE
            );
            """
        )
        db.execute("UPDATE chunks SET state='uploaded' WHERE state='transcribing'")


def require_auth(authorization: str | None) -> None:
    if TANU_API_TOKEN and authorization != f"Bearer {TANU_API_TOKEN}":
        raise HTTPException(status_code=401, detail="Invalid TANU API token")


def openai_headers() -> dict[str, str]:
    if not OPENAI_API_KEY:
        raise RuntimeError("OPENAI_API_KEY is not configured")
    return {"Authorization": f"Bearer {OPENAI_API_KEY}"}


class MeetingCreate(BaseModel):
    id: str = Field(min_length=8, max_length=128)
    title: str = Field(default="Meeting", min_length=1, max_length=300)
    started_at_ms: int


class ActionItem(BaseModel):
    task: str
    owner: str = ""
    dueDate: str = ""


class MomResponse(BaseModel):
    summary: str
    decisions: list[str]
    actions: list[ActionItem]
    followUps: list[str]


ROLLING_SCHEMA: dict[str, Any] = {
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
        "openQuestions": {"type": "array", "items": {"type": "string"}},
        "followUps": {"type": "array", "items": {"type": "string"}},
    },
    "required": ["summary", "decisions", "actions", "openQuestions", "followUps"],
    "additionalProperties": False,
}

MOM_SCHEMA: dict[str, Any] = {
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


def extract_output_text(envelope: dict[str, Any]) -> str:
    for item in envelope.get("output", []):
        for content in item.get("content", []) or []:
            if content.get("type") == "output_text" and content.get("text"):
                return str(content["text"])
    return ""


async def responses_json(prompt: str, schema_name: str, schema: dict[str, Any]) -> dict[str, Any]:
    body = {
        "model": MOM_MODEL,
        "input": prompt,
        "store": False,
        "text": {
            "format": {
                "type": "json_schema",
                "name": schema_name,
                "strict": True,
                "schema": schema,
            }
        },
    }
    timeout = httpx.Timeout(55.0, connect=10.0)
    async with httpx.AsyncClient(timeout=timeout) as client:
        response = await client.post(
            f"{OPENAI_BASE}/responses",
            headers={**openai_headers(), "Content-Type": "application/json"},
            json=body,
        )
    if response.status_code >= 300:
        raise RuntimeError(f"Responses API failed {response.status_code}: {response.text[:500]}")
    output = extract_output_text(response.json())
    if not output:
        raise RuntimeError("Responses API returned no output_text")
    parsed = json.loads(output)
    return parsed


async def transcribe_file(path: Path, mime_type: str) -> str:
    prompt = (
        "TANU meeting transcript. Preserve natural English, Tamil, and Tanglish/code-switched speech. "
        "Do not translate Tamil into English unless the speaker did so. Preserve names, numbers, dates, "
        "companies, commitments and action language accurately. Return only the transcript text."
    )
    timeout = httpx.Timeout(45.0, connect=10.0)
    async with httpx.AsyncClient(timeout=timeout) as client:
        with path.open("rb") as fh:
            files = {"file": (path.name, fh, mime_type)}
            data = {"model": TRANSCRIBE_MODEL, "prompt": prompt, "response_format": "json"}
            response = await client.post(
                f"{OPENAI_BASE}/audio/transcriptions",
                headers=openai_headers(),
                data=data,
                files=files,
            )
    if response.status_code >= 300:
        raise RuntimeError(f"Transcription failed {response.status_code}: {response.text[:500]}")
    return str(response.json().get("text", "")).strip()


def meeting_dir(meeting_id: str) -> Path:
    safe = "".join(ch for ch in meeting_id if ch.isalnum() or ch in "-_" )[:128]
    if safe != meeting_id:
        raise HTTPException(status_code=400, detail="Invalid meeting id")
    path = DATA_DIR / "meetings" / safe / "chunks"
    path.mkdir(parents=True, exist_ok=True)
    return path


def mime_for(codec: str) -> tuple[str, str]:
    normalized = codec.lower()
    if normalized == "opus":
        return "audio/ogg", ".ogg"
    if normalized == "aac":
        return "audio/mp4", ".m4a"
    raise HTTPException(status_code=415, detail="Unsupported audio codec")


def claim_next_chunk() -> sqlite3.Row | None:
    db = db_connect()
    try:
        db.execute("BEGIN IMMEDIATE")
        row = db.execute(
            """
            SELECT * FROM chunks
            WHERE state='uploaded' AND next_retry_at_ms <= ?
            ORDER BY created_at_ms, meeting_id, sequence
            LIMIT 1
            """,
            (now_ms(),),
        ).fetchone()
        if row is None:
            db.execute("COMMIT")
            return None
        db.execute(
            "UPDATE chunks SET state='transcribing', updated_at_ms=? WHERE meeting_id=? AND sequence=?",
            (now_ms(), row["meeting_id"], row["sequence"]),
        )
        db.execute("COMMIT")
        return row
    except Exception:
        db.execute("ROLLBACK")
        raise
    finally:
        db.close()


def set_chunk_success(meeting_id: str, sequence: int, text: str) -> None:
    with db_connect() as db:
        db.execute(
            """
            UPDATE chunks
            SET state='transcribed', transcript=?, last_error=NULL, updated_at_ms=?
            WHERE meeting_id=? AND sequence=?
            """,
            (text, now_ms(), meeting_id, sequence),
        )


def set_chunk_failure(row: sqlite3.Row, error: str) -> None:
    retry = int(row["retry_count"]) + 1
    delay_seconds = min(300, 2 ** min(retry, 8))
    next_retry = now_ms() + delay_seconds * 1000
    state = "uploaded" if retry < 8 else "failed"
    with db_connect() as db:
        db.execute(
            """
            UPDATE chunks
            SET state=?, retry_count=?, next_retry_at_ms=?, last_error=?, updated_at_ms=?
            WHERE meeting_id=? AND sequence=?
            """,
            (state, retry, next_retry, error[:1000], now_ms(), row["meeting_id"], row["sequence"]),
        )


async def maybe_generate_rolling(meeting_id: str) -> None:
    while True:
        with db_connect() as db:
            last = db.execute(
                "SELECT MAX(window_end_ms) AS end_ms FROM rolling_summaries WHERE meeting_id=?",
                (meeting_id,),
            ).fetchone()["end_ms"]
            window_start = int(last or 0)
            max_end_row = db.execute(
                "SELECT MAX(end_ms) AS end_ms FROM chunks WHERE meeting_id=? AND state='transcribed'",
                (meeting_id,),
            ).fetchone()
            max_end = int(max_end_row["end_ms"] or 0)
            if max_end - window_start < ROLLING_WINDOW_MS:
                return
            window_end = window_start + ROLLING_WINDOW_MS
            rows = db.execute(
                """
                SELECT sequence, start_ms, end_ms, transcript FROM chunks
                WHERE meeting_id=? AND state='transcribed' AND end_ms>? AND start_ms<?
                ORDER BY sequence
                """,
                (meeting_id, window_start, window_end),
            ).fetchall()
        transcript = "\n".join(str(r["transcript"] or "") for r in rows if str(r["transcript"] or "").strip())
        if transcript:
            prompt = f"""
You are TANU's rolling meeting-memory engine. This is one approximately ten-minute block of a longer meeting.
The speech can be English, Tamil, Tanglish, or code-switched.
Extract only facts stated in this block. Never invent owners, deadlines, decisions, names or commitments.
Keep the summary concise. Put unresolved questions in openQuestions.

TRANSCRIPT BLOCK:
{transcript}
""".strip()
            data = await responses_json(prompt, "tanu_rolling_memory", ROLLING_SCHEMA)
        else:
            data = {"summary": "", "decisions": [], "actions": [], "openQuestions": [], "followUps": []}
        with db_connect() as db:
            db.execute(
                """
                INSERT OR REPLACE INTO rolling_summaries
                (meeting_id, window_start_ms, window_end_ms, data_json, created_at_ms)
                VALUES (?, ?, ?, ?, ?)
                """,
                (meeting_id, window_start, window_end, json.dumps(data), now_ms()),
            )


async def build_final_mom(meeting_id: str) -> None:
    with db_connect() as db:
        meeting = db.execute("SELECT * FROM meetings WHERE id=?", (meeting_id,)).fetchone()
        if not meeting or meeting["final_mom_json"]:
            return
        pending = db.execute(
            "SELECT COUNT(*) AS n FROM chunks WHERE meeting_id=? AND state NOT IN ('transcribed','failed')",
            (meeting_id,),
        ).fetchone()["n"]
        if pending:
            return
        summaries = db.execute(
            "SELECT * FROM rolling_summaries WHERE meeting_id=? ORDER BY window_start_ms",
            (meeting_id,),
        ).fetchall()
        last_end = int(summaries[-1]["window_end_ms"]) if summaries else 0
        tail_rows = db.execute(
            """
            SELECT transcript FROM chunks
            WHERE meeting_id=? AND state='transcribed' AND end_ms>?
            ORDER BY sequence
            """,
            (meeting_id, last_end),
        ).fetchall()

    memory_text = "\n".join(
        f"BLOCK {i + 1}: {row['data_json']}" for i, row in enumerate(summaries)
    )
    tail = "\n".join(str(row["transcript"] or "") for row in tail_rows if str(row["transcript"] or "").strip())
    if not summaries:
        with db_connect() as db:
            all_rows = db.execute(
                "SELECT transcript FROM chunks WHERE meeting_id=? AND state='transcribed' ORDER BY sequence",
                (meeting_id,),
            ).fetchall()
        tail = "\n".join(str(row["transcript"] or "") for row in all_rows if str(row["transcript"] or "").strip())

    prompt = f"""
You are TANU, a meeting-minutes assistant.
Meeting title: {meeting['title']}
The meeting may contain English, Tamil, Tanglish, or code-switching.
Create concise final minutes in clear English unless the meeting explicitly requires another language.
Never invent a decision, owner, deadline, name or commitment.
Deduplicate repeated actions/decisions across rolling blocks.
If owner or due date was not explicitly stated, use an empty string.

ROLLING MEETING MEMORY:
{memory_text or '(none; use transcript below)'}

TRANSCRIPT AFTER LAST ROLLING BLOCK:
{tail or '(none)'}
""".strip()
    try:
        data = await responses_json(prompt, "tanu_final_mom", MOM_SCHEMA)
        MomResponse.model_validate(data)
        with db_connect() as db:
            db.execute(
                "UPDATE meetings SET final_mom_json=?, final_error=NULL, state='ready' WHERE id=?",
                (json.dumps(data), meeting_id),
            )
    except Exception as exc:
        with db_connect() as db:
            db.execute("UPDATE meetings SET final_error=? WHERE id=?", (str(exc)[:1000], meeting_id))


async def transcription_worker(worker_id: int) -> None:
    del worker_id
    while True:
        row = claim_next_chunk()
        if row is None:
            await asyncio.sleep(0.8)
            continue
        try:
            text = await transcribe_file(Path(row["path"]), str(row["mime_type"]))
            set_chunk_success(str(row["meeting_id"]), int(row["sequence"]), text)
            await maybe_generate_rolling(str(row["meeting_id"]))
        except asyncio.CancelledError:
            raise
        except Exception as exc:
            set_chunk_failure(row, str(exc))


async def finalizer_worker() -> None:
    while True:
        with db_connect() as db:
            ids = [
                str(r["id"])
                for r in db.execute(
                    "SELECT id FROM meetings WHERE state='finalizing' AND final_mom_json IS NULL"
                ).fetchall()
            ]
        for meeting_id in ids:
            try:
                await maybe_generate_rolling(meeting_id)
                await build_final_mom(meeting_id)
            except Exception:
                pass
        await asyncio.sleep(1.0)


@asynccontextmanager
async def lifespan(app: FastAPI):
    del app
    init_db()
    tasks: list[asyncio.Task[Any]] = []
    if not DISABLE_WORKERS:
        tasks.extend(asyncio.create_task(transcription_worker(i)) for i in range(WORKER_COUNT))
        tasks.append(asyncio.create_task(finalizer_worker()))
    try:
        yield
    finally:
        for task in tasks:
            task.cancel()
        if tasks:
            await asyncio.gather(*tasks, return_exceptions=True)


app = FastAPI(title="TANU Core API", version="0.2.0", lifespan=lifespan)


@app.get("/health")
async def health() -> dict[str, Any]:
    return {
        "status": "ok",
        "stt_workers": 0 if DISABLE_WORKERS else WORKER_COUNT,
        "rolling_window_ms": ROLLING_WINDOW_MS,
    }


@app.post("/v1/meetings")
async def create_meeting(
    body: MeetingCreate,
    authorization: str | None = Header(default=None),
) -> dict[str, Any]:
    require_auth(authorization)
    with db_connect() as db:
        existing = db.execute("SELECT * FROM meetings WHERE id=?", (body.id,)).fetchone()
        if existing:
            return {"id": body.id, "accepted": True, "existing": True}
        db.execute(
            """
            INSERT INTO meetings(id, title, started_at_ms, created_at_ms, state)
            VALUES (?, ?, ?, ?, 'recording')
            """,
            (body.id, body.title, body.started_at_ms, now_ms()),
        )
    return {"id": body.id, "accepted": True, "existing": False}


@app.put("/v1/meetings/{meeting_id}/chunks/{sequence}")
async def upload_chunk(
    meeting_id: str,
    sequence: int,
    request: Request,
    authorization: str | None = Header(default=None),
    x_tanu_sha256: str = Header(alias="X-TANU-SHA256"),
    x_tanu_start_ms: int = Header(alias="X-TANU-START-MS"),
    x_tanu_end_ms: int = Header(alias="X-TANU-END-MS"),
    x_tanu_codec: str = Header(alias="X-TANU-CODEC"),
) -> dict[str, Any]:
    require_auth(authorization)
    if sequence < 0 or x_tanu_end_ms <= x_tanu_start_ms:
        raise HTTPException(status_code=400, detail="Invalid chunk metadata")
    with db_connect() as db:
        meeting = db.execute("SELECT id FROM meetings WHERE id=?", (meeting_id,)).fetchone()
        if not meeting:
            raise HTTPException(status_code=404, detail="Meeting does not exist")
        existing = db.execute(
            "SELECT sha256, state FROM chunks WHERE meeting_id=? AND sequence=?",
            (meeting_id, sequence),
        ).fetchone()
        if existing:
            if existing["sha256"] != x_tanu_sha256:
                raise HTTPException(status_code=409, detail="Chunk sequence already exists with a different checksum")
            return {"accepted": True, "sequence": sequence, "duplicate": True, "state": existing["state"]}

    mime_type, extension = mime_for(x_tanu_codec)
    raw = await request.body()
    if not raw:
        raise HTTPException(status_code=400, detail="Empty audio chunk")
    if len(raw) > MAX_CHUNK_BYTES:
        raise HTTPException(status_code=413, detail="Audio chunk too large")
    actual_sha = hashlib.sha256(raw).hexdigest()
    if actual_sha.lower() != x_tanu_sha256.lower():
        raise HTTPException(status_code=422, detail="SHA-256 checksum mismatch")

    path = meeting_dir(meeting_id) / f"{sequence:06d}{extension}"
    temp = path.with_suffix(path.suffix + ".part")
    temp.write_bytes(raw)
    temp.replace(path)
    stamp = now_ms()
    with db_connect() as db:
        db.execute(
            """
            INSERT INTO chunks(
                meeting_id, sequence, start_ms, end_ms, codec, mime_type, sha256, path,
                size_bytes, state, created_at_ms, updated_at_ms
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'uploaded', ?, ?)
            """,
            (
                meeting_id, sequence, x_tanu_start_ms, x_tanu_end_ms, x_tanu_codec.lower(), mime_type,
                actual_sha, str(path), len(raw), stamp, stamp,
            ),
        )
    return {"accepted": True, "sequence": sequence, "duplicate": False, "state": "uploaded"}


@app.get("/v1/meetings/{meeting_id}/updates")
async def meeting_updates(
    meeting_id: str,
    authorization: str | None = Header(default=None),
) -> dict[str, Any]:
    require_auth(authorization)
    with db_connect() as db:
        meeting = db.execute("SELECT * FROM meetings WHERE id=?", (meeting_id,)).fetchone()
        if not meeting:
            raise HTTPException(status_code=404, detail="Meeting does not exist")
        chunks = db.execute(
            "SELECT sequence,start_ms,end_ms,state,transcript,last_error FROM chunks WHERE meeting_id=? ORDER BY sequence",
            (meeting_id,),
        ).fetchall()
        rolling = db.execute(
            "SELECT * FROM rolling_summaries WHERE meeting_id=? ORDER BY window_start_ms",
            (meeting_id,),
        ).fetchall()
    transcribed = sum(1 for c in chunks if c["state"] == "transcribed")
    pending = sum(1 for c in chunks if c["state"] not in ("transcribed", "failed"))
    return {
        "state": meeting["state"],
        "total_chunks": len(chunks),
        "pending_chunks": pending,
        "transcribed_chunks": transcribed,
        "chunks": [
            {
                "sequence": c["sequence"],
                "start_ms": c["start_ms"],
                "end_ms": c["end_ms"],
                "state": c["state"],
                "text": c["transcript"] or "",
                "last_error": c["last_error"],
            }
            for c in chunks
        ],
        "rolling_summaries": [
            {
                "window_start_ms": r["window_start_ms"],
                "window_end_ms": r["window_end_ms"],
                "data": json.loads(r["data_json"]),
            }
            for r in rolling
        ],
    }


@app.post("/v1/meetings/{meeting_id}/finalize", status_code=202)
async def finalize_meeting(
    meeting_id: str,
    authorization: str | None = Header(default=None),
) -> dict[str, Any]:
    require_auth(authorization)
    with db_connect() as db:
        meeting = db.execute("SELECT id FROM meetings WHERE id=?", (meeting_id,)).fetchone()
        if not meeting:
            raise HTTPException(status_code=404, detail="Meeting does not exist")
        db.execute("UPDATE meetings SET state='finalizing' WHERE id=?", (meeting_id,))
    return {"accepted": True, "state": "finalizing"}


@app.get("/v1/meetings/{meeting_id}/mom")
async def get_mom(
    meeting_id: str,
    authorization: str | None = Header(default=None),
) -> Response:
    require_auth(authorization)
    with db_connect() as db:
        meeting = db.execute("SELECT * FROM meetings WHERE id=?", (meeting_id,)).fetchone()
    if not meeting:
        raise HTTPException(status_code=404, detail="Meeting does not exist")
    if not meeting["final_mom_json"]:
        return Response(
            content=json.dumps({"state": meeting["state"], "error": meeting["final_error"]}),
            status_code=202,
            media_type="application/json",
        )
    return Response(content=meeting["final_mom_json"], media_type="application/json")
