import base64
import time
import uuid

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from fastapi.testclient import TestClient

import device_auth
import secure_app


def _new_device():
    private_key = ec.generate_private_key(ec.SECP256R1())
    public_der = private_key.public_key().public_bytes(
        serialization.Encoding.DER,
        serialization.PublicFormat.SubjectPublicKeyInfo,
    )
    return private_key, base64.b64encode(public_der).decode("ascii")


def _signed_session(private_key, device_id: str, timestamp: int, nonce: str) -> dict:
    canonical = f"MAI1\n{device_id}\n{timestamp}\n{nonce}".encode("utf-8")
    signature = private_key.sign(canonical, ec.ECDSA(hashes.SHA256()))
    return {
        "device_id": device_id,
        "timestamp": timestamp,
        "nonce": nonce,
        "signature": base64.b64encode(signature).decode("ascii"),
    }


def test_device_enrollment_session_and_replay_protection(tmp_path, monkeypatch):
    monkeypatch.setenv("MAI_AUTH_DB", str(tmp_path / "auth.sqlite3"))
    monkeypatch.setenv("MAI_ENROLLMENT_CODES", "MAI-TEST-CODE-001,MAI-TEST-CODE-002")
    monkeypatch.setenv("MAI_ADMIN_CODE", "MAI-ADMIN-TEST")
    client = TestClient(secure_app.app)

    status = client.get("/v1/auth/status")
    assert status.status_code == 200
    assert status.json()["device_auth"] is True
    assert status.json()["enrollment_configured"] is True

    private_key, public_key = _new_device()
    device_id = str(uuid.uuid4())
    enroll = client.post(
        "/v1/auth/enroll",
        json={
            "device_id": device_id,
            "public_key": public_key,
            "activation_code": "MAI-TEST-CODE-001",
            "label": "Pixel test",
        },
    )
    assert enroll.status_code == 200
    assert enroll.json()["enrolled"] is True

    now = int(time.time())
    nonce = uuid.uuid4().hex
    payload = _signed_session(private_key, device_id, now, nonce)
    session = client.post("/v1/auth/session", json=payload)
    assert session.status_code == 200
    token = session.json()["access_token"]
    assert token
    assert session.json()["expires_at"] > now
    assert device_auth.require_auth(f"Bearer {token}") == device_id

    replay = client.post("/v1/auth/session", json=payload)
    assert replay.status_code == 401

    revoked = client.post(
        "/v1/auth/revoke",
        json={"device_id": device_id},
        headers={"X-MAI-Admin": "MAI-ADMIN-TEST"},
    )
    assert revoked.status_code == 200

    denied = client.post(
        "/v1/auth/session",
        json=_signed_session(private_key, device_id, int(time.time()), uuid.uuid4().hex),
    )
    assert denied.status_code == 403


def test_activation_code_is_single_use_across_devices(tmp_path, monkeypatch):
    monkeypatch.setenv("MAI_AUTH_DB", str(tmp_path / "auth.sqlite3"))
    monkeypatch.setenv("MAI_ENROLLMENT_CODES", "ONE-TIME-MAI-CODE")
    client = TestClient(secure_app.app)

    _, public_one = _new_device()
    first = client.post(
        "/v1/auth/enroll",
        json={
            "device_id": str(uuid.uuid4()),
            "public_key": public_one,
            "activation_code": "ONE-TIME-MAI-CODE",
            "label": "Phone one",
        },
    )
    assert first.status_code == 200

    _, public_two = _new_device()
    second = client.post(
        "/v1/auth/enroll",
        json={
            "device_id": str(uuid.uuid4()),
            "public_key": public_two,
            "activation_code": "ONE-TIME-MAI-CODE",
            "label": "Phone two",
        },
    )
    assert second.status_code == 401
