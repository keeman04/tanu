import base64
import hashlib
import hmac
import os
import re
import secrets
import sqlite3
import time
from pathlib import Path

from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from fastapi import APIRouter, Header, HTTPException
from pydantic import BaseModel, Field

router = APIRouter()

DEVICE_ID_RE = re.compile(r"^[A-Za-z0-9._-]{8,80}$")
NONCE_RE = re.compile(r"^[A-Za-z0-9_-]{16,128}$")
CLOCK_SKEW_SECONDS = 180
NONCE_RETENTION_SECONDS = 600


class EnrollRequest(BaseModel):
    device_id: str = Field(min_length=8, max_length=80)
    public_key: str = Field(min_length=40, max_length=4096)
    activation_code: str = Field(min_length=6, max_length=200)
    label: str = Field(default="Android", max_length=80)


class EnrollResponse(BaseModel):
    device_id: str
    enrolled: bool


class SessionRequest(BaseModel):
    device_id: str = Field(min_length=8, max_length=80)
    timestamp: int
    nonce: str = Field(min_length=16, max_length=128)
    signature: str = Field(min_length=20, max_length=4096)


class SessionResponse(BaseModel):
    access_token: str
    expires_at: int


class RevokeRequest(BaseModel):
    device_id: str = Field(min_length=8, max_length=80)


def _db_path() -> Path:
    raw = os.getenv("MAI_AUTH_DB", "/data/mai-auth.sqlite3").strip() or "/data/mai-auth.sqlite3"
    path = Path(raw)
    path.parent.mkdir(parents=True, exist_ok=True)
    return path


def _connect() -> sqlite3.Connection:
    connection = sqlite3.connect(str(_db_path()), timeout=10.0)
    connection.row_factory = sqlite3.Row
    connection.execute("PRAGMA journal_mode=WAL")
    connection.execute("PRAGMA foreign_keys=ON")
    connection.executescript(
        """
        CREATE TABLE IF NOT EXISTS devices(
            device_id TEXT PRIMARY KEY,
            public_key TEXT NOT NULL,
            label TEXT NOT NULL DEFAULT '',
            created_at INTEGER NOT NULL,
            revoked INTEGER NOT NULL DEFAULT 0
        );
        CREATE TABLE IF NOT EXISTS used_activation_codes(
            code_hash TEXT PRIMARY KEY,
            used_at INTEGER NOT NULL,
            device_id TEXT NOT NULL
        );
        CREATE TABLE IF NOT EXISTS used_nonces(
            nonce TEXT PRIMARY KEY,
            seen_at INTEGER NOT NULL,
            device_id TEXT NOT NULL
        );
        CREATE TABLE IF NOT EXISTS sessions(
            token_hash TEXT PRIMARY KEY,
            device_id TEXT NOT NULL,
            expires_at INTEGER NOT NULL,
            created_at INTEGER NOT NULL,
            FOREIGN KEY(device_id) REFERENCES devices(device_id)
        );
        CREATE INDEX IF NOT EXISTS idx_sessions_expiry ON sessions(expires_at);
        """
    )
    return connection


def _configured_activation_codes() -> list[str]:
    raw = os.getenv("MAI_ENROLLMENT_CODES", "").strip()
    if not raw:
        raw = os.getenv("MAI_ENROLLMENT_CODE", "").strip()
    return [value.strip() for value in raw.split(",") if value.strip()]


def _activation_code_matches(candidate: str) -> bool:
    return any(hmac.compare_digest(candidate, configured) for configured in _configured_activation_codes())


def _code_hash(code: str) -> str:
    return hashlib.sha256(code.encode("utf-8")).hexdigest()


def _token_hash(token: str) -> str:
    return hashlib.sha256(token.encode("utf-8")).hexdigest()


def _load_public_key(encoded: str) -> ec.EllipticCurvePublicKey:
    try:
        der = base64.b64decode(encoded, validate=True)
        key = serialization.load_der_public_key(der)
    except Exception as exc:
        raise HTTPException(status_code=400, detail="Invalid MAI device public key") from exc
    if not isinstance(key, ec.EllipticCurvePublicKey) or not isinstance(key.curve, ec.SECP256R1):
        raise HTTPException(status_code=400, detail="MAI device key must use P-256")
    return key


def _session_ttl() -> int:
    try:
        value = int(os.getenv("MAI_SESSION_TTL_SECONDS", "900"))
    except ValueError:
        value = 900
    return max(120, min(value, 3600))


def _canonical(device_id: str, timestamp: int, nonce: str) -> bytes:
    return f"MAI1\n{device_id}\n{timestamp}\n{nonce}".encode("utf-8")


def _prune(connection: sqlite3.Connection, now: int) -> None:
    connection.execute("DELETE FROM sessions WHERE expires_at<=?", (now,))
    connection.execute("DELETE FROM used_nonces WHERE seen_at<?", (now - NONCE_RETENTION_SECONDS,))


@router.get("/v1/auth/status")
def auth_status() -> dict[str, object]:
    return {
        "device_auth": True,
        "enrollment_configured": bool(_configured_activation_codes()),
        "session_ttl_seconds": _session_ttl(),
    }


@router.post("/v1/auth/enroll", response_model=EnrollResponse)
def enroll_device(request: EnrollRequest) -> EnrollResponse:
    if not DEVICE_ID_RE.fullmatch(request.device_id):
        raise HTTPException(status_code=400, detail="Invalid MAI device id")
    if not _configured_activation_codes():
        raise HTTPException(status_code=503, detail="MAI device enrollment is not configured")
    if not _activation_code_matches(request.activation_code):
        raise HTTPException(status_code=401, detail="Invalid or expired MAI activation code")

    _load_public_key(request.public_key)
    now = int(time.time())
    code_hash = _code_hash(request.activation_code)

    with _connect() as connection:
        existing = connection.execute(
            "SELECT public_key, revoked FROM devices WHERE device_id=?",
            (request.device_id,),
        ).fetchone()
        if existing is not None:
            if not hmac.compare_digest(str(existing["public_key"]), request.public_key):
                raise HTTPException(status_code=409, detail="This MAI device id is already bound to another key")
            if int(existing["revoked"]) != 0:
                raise HTTPException(status_code=403, detail="This MAI device has been revoked")
            connection.execute(
                "UPDATE devices SET label=? WHERE device_id=?",
                (request.label.strip(), request.device_id),
            )
            return EnrollResponse(device_id=request.device_id, enrolled=True)

        used = connection.execute(
            "SELECT device_id FROM used_activation_codes WHERE code_hash=?",
            (code_hash,),
        ).fetchone()
        if used is not None:
            raise HTTPException(status_code=401, detail="This MAI activation code has already been used")

        connection.execute(
            "INSERT INTO devices(device_id, public_key, label, created_at, revoked) VALUES(?,?,?,?,0)",
            (request.device_id, request.public_key, request.label.strip(), now),
        )
        connection.execute(
            "INSERT INTO used_activation_codes(code_hash, used_at, device_id) VALUES(?,?,?)",
            (code_hash, now, request.device_id),
        )
    return EnrollResponse(device_id=request.device_id, enrolled=True)


@router.post("/v1/auth/session", response_model=SessionResponse)
def create_session(request: SessionRequest) -> SessionResponse:
    if not DEVICE_ID_RE.fullmatch(request.device_id) or not NONCE_RE.fullmatch(request.nonce):
        raise HTTPException(status_code=400, detail="Invalid MAI authentication request")
    now = int(time.time())
    if abs(now - request.timestamp) > CLOCK_SKEW_SECONDS:
        raise HTTPException(status_code=401, detail="MAI authentication timestamp is outside the allowed window")

    try:
        signature = base64.b64decode(request.signature, validate=True)
    except Exception as exc:
        raise HTTPException(status_code=400, detail="Invalid MAI device signature") from exc

    with _connect() as connection:
        _prune(connection, now)
        device = connection.execute(
            "SELECT public_key, revoked FROM devices WHERE device_id=?",
            (request.device_id,),
        ).fetchone()
        if device is None:
            raise HTTPException(status_code=401, detail="This MAI device has not been enrolled")
        if int(device["revoked"]) != 0:
            raise HTTPException(status_code=403, detail="This MAI device has been revoked")

        replay = connection.execute(
            "SELECT 1 FROM used_nonces WHERE nonce=?",
            (request.nonce,),
        ).fetchone()
        if replay is not None:
            raise HTTPException(status_code=401, detail="MAI authentication request was already used")

        public_key = _load_public_key(str(device["public_key"]))
        try:
            public_key.verify(
                signature,
                _canonical(request.device_id, request.timestamp, request.nonce),
                ec.ECDSA(hashes.SHA256()),
            )
        except InvalidSignature as exc:
            raise HTTPException(status_code=401, detail="Invalid MAI device signature") from exc

        connection.execute(
            "INSERT INTO used_nonces(nonce, seen_at, device_id) VALUES(?,?,?)",
            (request.nonce, now, request.device_id),
        )
        token = secrets.token_urlsafe(32)
        expiry = now + _session_ttl()
        connection.execute(
            "INSERT INTO sessions(token_hash, device_id, expires_at, created_at) VALUES(?,?,?,?)",
            (_token_hash(token), request.device_id, expiry, now),
        )
    return SessionResponse(access_token=token, expires_at=expiry)


def require_auth(authorization: str | None) -> str:
    if not authorization or not authorization.startswith("Bearer "):
        raise HTTPException(status_code=401, detail="MAI device session is required")
    token = authorization[7:].strip()
    if not token:
        raise HTTPException(status_code=401, detail="MAI device session is required")

    now = int(time.time())
    with _connect() as connection:
        _prune(connection, now)
        session = connection.execute(
            """
            SELECT s.device_id, s.expires_at, d.revoked
            FROM sessions s JOIN devices d ON d.device_id=s.device_id
            WHERE s.token_hash=?
            """,
            (_token_hash(token),),
        ).fetchone()
        if session is None or int(session["expires_at"]) <= now:
            raise HTTPException(status_code=401, detail="MAI device session has expired")
        if int(session["revoked"]) != 0:
            raise HTTPException(status_code=403, detail="This MAI device has been revoked")
        return str(session["device_id"])


@router.post("/v1/auth/revoke")
def revoke_device(
    request: RevokeRequest,
    x_mai_admin: str | None = Header(default=None, alias="X-MAI-Admin"),
) -> dict[str, object]:
    admin_code = os.getenv("MAI_ADMIN_CODE", "").strip()
    if not admin_code:
        raise HTTPException(status_code=503, detail="MAI admin revocation is not configured")
    if not x_mai_admin or not hmac.compare_digest(x_mai_admin, admin_code):
        raise HTTPException(status_code=401, detail="Invalid MAI admin credential")

    with _connect() as connection:
        changed = connection.execute(
            "UPDATE devices SET revoked=1 WHERE device_id=?",
            (request.device_id,),
        ).rowcount
        connection.execute("DELETE FROM sessions WHERE device_id=?", (request.device_id,))
    if changed == 0:
        raise HTTPException(status_code=404, detail="MAI device not found")
    return {"device_id": request.device_id, "revoked": True}
