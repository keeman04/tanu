import base64
import time
import uuid
from pathlib import Path

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from fastapi.testclient import TestClient

import jobs
import secure_app


def _session(client: TestClient, monkeypatch, tmp_path: Path) -> str:
    monkeypatch.setenv("MAI_AUTH_DB", str(tmp_path / "auth.sqlite3"))
    monkeypatch.setenv("MAI_ADMIN_CODE", "MAI-JOBS-ADMIN")
    monkeypatch.delenv("MAI_ENROLLMENT_CODES", raising=False)
    monkeypatch.delenv("MAI_ENROLLMENT_CODE", raising=False)

    private_key = ec.generate_private_key(ec.SECP256R1())
    public_der = private_key.public_key().public_bytes(
        serialization.Encoding.DER,
        serialization.PublicFormat.SubjectPublicKeyInfo,
    )
    device_id = str(uuid.uuid4())
    activation = client.post(
        "/v1/auth/activation-code",
        headers={"X-MAI-Admin": "MAI-JOBS-ADMIN"},
    )
    assert activation.status_code == 200
    enrolled = client.post(
        "/v1/auth/enroll",
        json={
            "device_id": device_id,
            "public_key": base64.b64encode(public_der).decode("ascii"),
            "activation_code": activation.json()["activation_code"],
            "label": "Job test",
        },
    )
    assert enrolled.status_code == 200
    now = int(time.time())
    nonce = uuid.uuid4().hex
    canonical = f"MAI1\n{device_id}\n{now}\n{nonce}".encode("utf-8")
    signature = private_key.sign(canonical, ec.ECDSA(hashes.SHA256()))
    session = client.post(
        "/v1/auth/session",
        json={
            "device_id": device_id,
            "timestamp": now,
            "nonce": nonce,
            "signature": base64.b64encode(signature).decode("ascii"),
        },
    )
    assert session.status_code == 200
    return session.json()["access_token"]


def test_resumable_upload_survives_worker_boundaries(tmp_path, monkeypatch):
    jobs.JOB_DB = tmp_path / "jobs.sqlite3"
    jobs.JOB_ROOT = tmp_path / "meetings"
    jobs.init_job_system()
    monkeypatch.setattr(jobs, "_schedule", lambda job_id: None)
    client = TestClient(secure_app.app)
    token = _session(client, monkeypatch, tmp_path)
    headers = {"Authorization": f"Bearer {token}"}

    payload = b"A" * 900 + b"B" * 900
    created = client.post(
        "/v1/meetings/jobs/init",
        headers=headers,
        json={
            "meeting_id": str(uuid.uuid4()),
            "title": "Multilingual job",
            "started_at": "1787500000000",
            "participants": [{"name": "Ravi", "phone": ""}],
            "language_mode": "auto",
            "audio_size": len(payload),
        },
    )
    assert created.status_code == 200
    job_id = created.json()["job_id"]
    assert created.json()["uploaded_bytes"] == 0

    first = client.put(
        f"/v1/meetings/jobs/{job_id}/audio?offset=0",
        headers={**headers, "Content-Type": "application/octet-stream"},
        content=payload[:900],
    )
    assert first.status_code == 200
    assert first.json()["uploaded_bytes"] == 900

    wrong_offset = client.put(
        f"/v1/meetings/jobs/{job_id}/audio?offset=0",
        headers={**headers, "Content-Type": "application/octet-stream"},
        content=b"bad",
    )
    assert wrong_offset.status_code == 409

    resumed = client.put(
        f"/v1/meetings/jobs/{job_id}/audio?offset=900",
        headers={**headers, "Content-Type": "application/octet-stream"},
        content=payload[900:],
    )
    assert resumed.status_code == 200
    assert resumed.json()["uploaded_bytes"] == len(payload)

    started = client.post(f"/v1/meetings/jobs/{job_id}/start", headers=headers)
    assert started.status_code == 200
    assert started.json()["status"] == "queued"
    state = client.get(f"/v1/meetings/jobs/{job_id}", headers=headers)
    assert state.status_code == 200
    assert state.json()["expected_bytes"] == len(payload)


def test_multilingual_auto_mode_is_unbiased():
    assert jobs._languages("auto") == []
    assert jobs._languages("ta_en") == ["ta", "en"]
    assert jobs._languages("hi_en") == ["hi", "en"]
    assert jobs._languages("te_en") == ["te", "en"]
    assert jobs._languages("ml_en") == ["ml", "en"]
    assert jobs._languages("kn_en") == ["kn", "en"]


def test_overlap_stitcher_removes_repeated_boundary_words():
    merged = jobs._dedupe_overlap([
        "Ravi confirmed the Google campaign budget will be eighty thousand rupees",
        "the Google campaign budget will be eighty thousand rupees and Meta will receive forty thousand",
    ])
    assert merged.lower().count("google campaign budget") == 1
    assert "Meta will receive forty thousand" in merged


def test_mom_schema_is_strict():
    schema = jobs._mom_schema()
    assert schema["additionalProperties"] is False
    assert set(schema["required"]) == {"summary", "decisions", "actions", "language"}
    action = schema["properties"]["actions"]["items"]
    assert action["additionalProperties"] is False
    assert set(action["required"]) == {"text", "owner", "due"}
