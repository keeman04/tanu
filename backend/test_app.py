import hashlib
import os
import tempfile
import unittest

os.environ["MAI_DISABLE_WORKERS"] = "1"
os.environ["MAI_DATA_DIR"] = tempfile.mkdtemp(prefix="mai-tests-")
os.environ["MAI_API_TOKEN"] = "test-token"
os.environ.pop("OPENAI_API_KEY", None)

from fastapi.testclient import TestClient
from backend.app import app


AUTH = {"Authorization": "Bearer test-token"}


class MaiApiContractTests(unittest.TestCase):
    def test_health_reports_server_without_ai_key(self):
        with TestClient(app) as client:
            response = client.get("/health")
            self.assertEqual(response.status_code, 200)
            self.assertEqual(response.json()["status"], "ok")
            self.assertFalse(response.json()["ai_configured"])
            self.assertEqual(response.json()["stt_workers"], 0)

    def test_v1_requires_server_token(self):
        with TestClient(app) as client:
            response = client.post(
                "/v1/meetings",
                json={"id": "meeting-auth-001", "title": "Auth", "started_at_ms": 1, "participants": []},
            )
            self.assertEqual(response.status_code, 401)

    def test_create_upload_duplicate_and_finalize_contract(self):
        with TestClient(app) as client:
            meeting = {
                "id": "meeting-contract-001",
                "title": "Tamil English Planning",
                "started_at_ms": 123456,
                "participants": [
                    {"name": "Ravi", "phone": "+919999999999"},
                    {"name": "Karthick", "phone": "+918888888888"},
                ],
            }
            created = client.post("/v1/meetings", json=meeting, headers=AUTH)
            self.assertEqual(created.status_code, 200)
            self.assertTrue(created.json()["accepted"])

            audio = b"fake-ogg-opus-payload-for-mai-contract-test"
            sha = hashlib.sha256(audio).hexdigest()
            headers = {
                **AUTH,
                "X-MAI-SHA256": sha,
                "X-MAI-START-MS": "0",
                "X-MAI-END-MS": "15000",
                "X-MAI-CODEC": "opus",
                "Content-Type": "audio/ogg",
            }
            first = client.put("/v1/meetings/meeting-contract-001/chunks/0", content=audio, headers=headers)
            self.assertEqual(first.status_code, 200)
            self.assertFalse(first.json()["duplicate"])

            duplicate = client.put("/v1/meetings/meeting-contract-001/chunks/0", content=audio, headers=headers)
            self.assertEqual(duplicate.status_code, 200)
            self.assertTrue(duplicate.json()["duplicate"])

            final = client.post(
                "/v1/meetings/meeting-contract-001/finalize",
                json={"expected_chunks": 2},
                headers=AUTH,
            )
            self.assertEqual(final.status_code, 202)

            update = client.get("/v1/meetings/meeting-contract-001/updates", headers=AUTH)
            self.assertEqual(update.status_code, 200)
            payload = update.json()
            self.assertEqual(payload["total_chunks"], 1)
            self.assertEqual(payload["expected_chunks"], 2)
            self.assertEqual(payload["pending_chunks"], 2)

            pending_mom = client.get("/v1/meetings/meeting-contract-001/mom", headers=AUTH)
            self.assertEqual(pending_mom.status_code, 202)

    def test_checksum_mismatch_is_rejected(self):
        with TestClient(app) as client:
            client.post(
                "/v1/meetings",
                json={"id": "meeting-checksum-001", "title": "Checksum", "started_at_ms": 1, "participants": []},
                headers=AUTH,
            )
            response = client.put(
                "/v1/meetings/meeting-checksum-001/chunks/0",
                content=b"audio",
                headers={
                    **AUTH,
                    "X-MAI-SHA256": "00" * 32,
                    "X-MAI-START-MS": "0",
                    "X-MAI-END-MS": "15000",
                    "X-MAI-CODEC": "opus",
                    "Content-Type": "audio/ogg",
                },
            )
            self.assertEqual(response.status_code, 422)

    def test_unsupported_codec_is_rejected(self):
        with TestClient(app) as client:
            client.post(
                "/v1/meetings",
                json={"id": "meeting-codec-001", "title": "Codec", "started_at_ms": 1, "participants": []},
                headers=AUTH,
            )
            audio = b"some-audio"
            response = client.put(
                "/v1/meetings/meeting-codec-001/chunks/0",
                content=audio,
                headers={
                    **AUTH,
                    "X-MAI-SHA256": hashlib.sha256(audio).hexdigest(),
                    "X-MAI-START-MS": "0",
                    "X-MAI-END-MS": "15000",
                    "X-MAI-CODEC": "wav",
                },
            )
            self.assertEqual(response.status_code, 415)

    def test_ask_requires_ai_configuration(self):
        with TestClient(app) as client:
            response = client.post("/v1/ask", json={"question": "What decisions did we make?"}, headers=AUTH)
            self.assertEqual(response.status_code, 503)


if __name__ == "__main__":
    unittest.main()
