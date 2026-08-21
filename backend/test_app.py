import hashlib
import os
import tempfile

os.environ["TANU_DISABLE_WORKERS"] = "1"
os.environ["TANU_DATA_DIR"] = tempfile.mkdtemp(prefix="tanu-tests-")

from fastapi.testclient import TestClient
from app import app


def test_health_and_idempotent_upload():
    with TestClient(app) as client:
        health = client.get("/health")
        assert health.status_code == 200
        assert health.json()["status"] == "ok"

        meeting = {
            "id": "meeting-test-001",
            "title": "Long Meeting Test",
            "started_at_ms": 123456,
        }
        created = client.post("/v1/meetings", json=meeting)
        assert created.status_code == 200
        assert created.json()["accepted"] is True

        audio = b"fake-ogg-opus-payload-for-contract-test"
        sha = hashlib.sha256(audio).hexdigest()
        headers = {
            "X-TANU-SHA256": sha,
            "X-TANU-START-MS": "0",
            "X-TANU-END-MS": "15000",
            "X-TANU-CODEC": "opus",
            "Content-Type": "audio/ogg",
        }
        first = client.put("/v1/meetings/meeting-test-001/chunks/0", content=audio, headers=headers)
        assert first.status_code == 200
        assert first.json()["duplicate"] is False

        second = client.put("/v1/meetings/meeting-test-001/chunks/0", content=audio, headers=headers)
        assert second.status_code == 200
        assert second.json()["duplicate"] is True

        update = client.get("/v1/meetings/meeting-test-001/updates")
        assert update.status_code == 200
        assert update.json()["total_chunks"] == 1
        assert update.json()["pending_chunks"] == 1

        final = client.post("/v1/meetings/meeting-test-001/finalize")
        assert final.status_code == 202
        pending_mom = client.get("/v1/meetings/meeting-test-001/mom")
        assert pending_mom.status_code == 202


def test_checksum_mismatch_is_rejected():
    with TestClient(app) as client:
        client.post("/v1/meetings", json={"id": "meeting-test-002", "title": "Checksum", "started_at_ms": 1})
        response = client.put(
            "/v1/meetings/meeting-test-002/chunks/0",
            content=b"audio",
            headers={
                "X-TANU-SHA256": "00" * 32,
                "X-TANU-START-MS": "0",
                "X-TANU-END-MS": "15000",
                "X-TANU-CODEC": "opus",
                "Content-Type": "audio/ogg",
            },
        )
        assert response.status_code == 422
