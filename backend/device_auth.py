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


class ActivationCodeResponse(BaseModel):
    activation_code: str
    expires_at: int


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
        CREATE TABLE IF NOT EXISTS issued_activation_codes(
            code_hash TEXT PRIMARY KEY,
            created_at INTEGER NOT NULL,
            expires_at INTEGER NOT NULL,
            used_at INTEGER,
            device_id TEXT
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
        CREATE INDEX IF NOT EXISTS idx_activation_expiry ON issued_activation_codes(expires_at);
        """
    )
    return connection


def _configured_activation_codes() -> list[str]:
    raw = os.getenv("MAI_ENROLLMENT_CODES", "").strip()
    if not raw:
        raw = os.getenv("MAI_ENROLLMENT_CODE", "").strip()
    return [value.strip() for value in raw.split(",") if value.strip()]


def _activation_code_matches_bootstrap(candidate: str) -> bool:
    return any(hmac.compare_digest(candidate, configured) for configured in _configured_activation_codes())


def _admin_code() -> str:
    return os.getenv("MAI_ADMIN_CODE", "").strip()


def _require_admin(candidate: str | None) -> None:
    configured = _admin_code()
    if not configured:
        raise HTTPException(status_code=503, detail="MAI admin authentication is not configured")
    if not candidate or not hmac.compare_digest(candidate, configured):
        raise HTTPException(status_code=401, detail="Invalid MAI admin credential")


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


def _activation_ttl() -> int:
    try:
        value = int(os.getenv("MAI_ACTIVATION_TTL_SECONDS", "86400"))
    except ValueError:
        value = 86400
    return max(300, min(value, 7 * 86400))


def _canonical(device_id: str, timestamp: int, nonce: str) -> bytes:
    return f"MAI1\n{device_id}\n{timestamp}\n{nonce}".encode("utf-8")


def _prune(connection: sqlite3.Connection, now: int) -> None:
    connection.execute("DELETE FROM sessions WHERE expires_at<=?", (now,))
    connection.execute("DELETE FROM used_nonces WHERE seen_at<?", (now - NONCE_RETENTION_SECONDS,))
    connection.execute("DELETE FROM issued_activation_codes WHERE expires_at<? AND used_at IS NULL", (now - 86400,))


def _consume_activation_code(connection: sqlite3.Connection, code: str, device_id: str, now: int) -> bool:
    code_hash = _code_hash(code)

    issued = connection.execute(
        "SELECT used_at, expires_at FROM issued_activation_codes WHERE code_hash=?",
        (code_hash,),
    ).fetchone()
    if issued is not None:
        if issued["used_at"] is not None or int(issued["expires_at"]) <= now:
            return False
        connection.execute(
            "UPDATE issued_activation_codes SET used_at=?, device_id=? WHERE code_hash=? AND used_at IS NULL",
            (now, device_id, code_hash),
        )
        return True

    if not _activation_code_matches_bootstrap(code):
        return False
    already_used = connection.execute(
        "SELECT 1 FROM used_activation_codes WHERE code_hash=?",
        (code_hash,),
    ).fetchone()
    if already_used is not None:
        return False
    connection.execute(
        "INSERT INTO used_activation_codes(code_hash, used_at, device_id) VALUES(?,?,?)",
        (code_hash, now, device_id),
    )
    return True


@router.get("/v1/auth/status")
def auth_status() -> dict[str, object]:
    return {
        "device_auth": True,
        "enrollment_configured": bool(_admin_code() or _configured_activation_codes()),
        "admin_code_generation": bool(_admin_code()),
        "session_ttl_seconds": _session_ttl(),
        "activation_ttl_seconds": _activation_ttl(),
    }


@router.post("/v1/auth/activation-code", response_model=ActivationCodeResponse)
def issue_activation_code(
    x_mai_admin: str | None = Header(default=None, alias="X-MAI-Admin"),
) -> ActivationCodeResponse:
    _require_admin(x_mai_admin)
    now = int(time.time())
    expires = now + _activation_ttl()
    code = "MAI-" + secrets.token_hex(6).upper()
    with _connect() as connection:
        _prune(connection, now)
        connection.execute(
            "INSERT INTO issued_activation_codes(code_hash, created_at, expires_at) VALUES(?,?,?)",
            (_code_hash(code), now, expires),
        )
    return ActivationCodeResponse(activation_code=code, expires_at=expires)


@router.post("/v1/auth/enroll", response_model=EnrollResponse)
def enroll_device(request: EnrollRequest) -> EnrollResponse:
    if not DEVICE_ID_RE.fullmatch(request.device_id):
        raise HTTPException(status_code=400, detail="Invalid MAI device id")
    if not (_admin_code() or _configured_activation_codes()):
        raise HTTPException(status_code=503, detail="MAI device enrollment is not configured")

    _load_public_key(request.public_key)
    now = int(time.time())

    with _connect() as connection:
        connection.execute("BEGIN IMMEDIATE")
        _prune(connection, now)
        existing = connection.execute(
            "SELECT public_key, revoked FROM devices WHERE device_id=?",
            (request.device_id,),
        ).fetchone()
        if existing is not None:
            if not hmac.compare_digest(str(existing["public_key"]), request.public_key):
                raise HTTPException(status_code=409, detail="This MAI device id is already bound to another key")
            if int(existing["revoked"]) != 0:
                raise HTTPException(status_code=403, detail="This MAI device has been revoked")

        if not _consume_activation_code(connection, request.activation_code, request.device_id, now):
            raise HTTPException(status_code=401, detail="Invalid, expired, or already used MAI activation code")

        if existing is None:
            connection.execute(
                "INSERT INTO devices(device_id, public_key, label, created_at, revoked) VALUES(?,?,?,?,0)",
                (request.device_id, request.public_key, request.label.strip(), now),
            )
        else:
            connection.execute(
                "UPDATE devices SET label=? WHERE device_id=?",
                (request.label.strip(), request.device_id),
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
        connection.execute("BEGIN IMMEDIATE")
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

        try:
            connection.execute(
                "INSERT INTO used_nonces(nonce, seen_at, device_id) VALUES(?,?,?)",
                (request.nonce, now, request.device_id),
            )
        except sqlite3.IntegrityError as exc:
            raise HTTPException(status_code=401, detail="MAI authentication request was already used") from exc

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
    _require_admin(x_mai_admin)

    with _connect() as connection:
        changed = connection.execute(
            "UPDATE devices SET revoked=1 WHERE device_id=?",
            (request.device_id,),
        ).rowcount
        connection.execute("DELETE FROM sessions WHERE device_id=?", (request.device_id,))
    if changed == 0:
        raise HTTPException(status_code=404, detail="MAI device not found")
    return {"device_id": request.device_id, "revoked": True}
