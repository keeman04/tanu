#!/usr/bin/env python3
"""Real MAI V1.4 backend certification using an actual meeting audio file.

Example:
  python e2e_certify.py \
    --base-url https://mai.example.com \
    --admin-code "$MAI_ADMIN_CODE" \
    --audio tamil-tanglish-test.aac \
    --language-mode auto \
    --participant Ravi --participant Karthick \
    --expect Waghoba --expect 80000 --expect Google --expect Meta

This uses the same production path as Android:
one-time activation -> signed device session -> resumable 5 MB upload -> persistent server
job -> per-chunk transcription/diarization/translation -> strict structured MOM -> result poll.
"""

import argparse
import base64
import json
import re
import time
import uuid
from pathlib import Path

import httpx
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec

LANGUAGE_MODES = {
    "auto",
    "ta_en",
    "hi_en",
    "te_en",
    "ml_en",
    "kn_en",
    "bn_en",
    "mr_en",
    "gu_en",
    "pa_en",
    "ur_en",
}
UPLOAD_CHUNK = 5 * 1024 * 1024


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", required=True)
    parser.add_argument("--admin-code", required=True)
    parser.add_argument("--audio", required=True)
    parser.add_argument("--language-mode", choices=sorted(LANGUAGE_MODES), default="auto")
    parser.add_argument("--participant", action="append", default=[])
    parser.add_argument("--expect", action="append", default=[])
    parser.add_argument("--timeout-minutes", type=int, default=60)
    return parser.parse_args()


def require_ok(response: httpx.Response, label: str) -> dict:
    if response.status_code >= 400:
        raise SystemExit(f"{label} failed ({response.status_code}): {response.text[:1000]}")
    try:
        return response.json()
    except Exception as exc:
        raise SystemExit(f"{label} returned invalid JSON: {response.text[:1000]}") from exc


def main() -> None:
    args = parse_args()
    base = args.base_url.rstrip("/")
    audio = Path(args.audio)
    if not audio.is_file() or audio.stat().st_size <= 512:
        raise SystemExit("Audio fixture is missing or too small")

    participants = args.participant or ["MAI Certification"]
    device_id = str(uuid.uuid4())
    private_key = ec.generate_private_key(ec.SECP256R1())
    public_der = private_key.public_key().public_bytes(
        serialization.Encoding.DER,
        serialization.PublicFormat.SubjectPublicKeyInfo,
    )
    timeout = httpx.Timeout(180.0, connect=30.0)
    headers_admin = {"X-MAI-Admin": args.admin_code}

    with httpx.Client(timeout=timeout) as client:
        health = require_ok(client.get(base + "/health"), "health")
        if not health.get("ok") or not health.get("openai_configured") or not health.get("ffmpeg"):
            raise SystemExit(f"Backend is not production-ready: {health}")

        auth_status = require_ok(client.get(base + "/v1/auth/status"), "auth status")
        if not auth_status.get("device_auth") or not auth_status.get("enrollment_configured"):
            raise SystemExit(f"Device authentication is not ready: {auth_status}")

        activation = require_ok(
            client.post(base + "/v1/auth/activation-code", headers=headers_admin),
            "activation code",
        )
        require_ok(
            client.post(
                base + "/v1/auth/enroll",
                json={
                    "device_id": device_id,
                    "public_key": base64.b64encode(public_der).decode("ascii"),
                    "activation_code": activation["activation_code"],
                    "label": "MAI V1.4 E2E Certification",
                },
            ),
            "device enrollment",
        )

        def create_session() -> str:
            now = int(time.time())
            nonce = uuid.uuid4().hex
            canonical = f"MAI1\n{device_id}\n{now}\n{nonce}".encode("utf-8")
            signature = private_key.sign(canonical, ec.ECDSA(hashes.SHA256()))
            session = require_ok(
                client.post(
                    base + "/v1/auth/session",
                    json={
                        "device_id": device_id,
                        "timestamp": now,
                        "nonce": nonce,
                        "signature": base64.b64encode(signature).decode("ascii"),
                    },
                ),
                "device session",
            )
            return str(session["access_token"])

        token = create_session()

        def authed(method: str, path: str, **kwargs) -> httpx.Response:
            nonlocal token
            headers = dict(kwargs.pop("headers", {}))
            headers["Authorization"] = f"Bearer {token}"
            response = client.request(method, base + path, headers=headers, **kwargs)
            if response.status_code in {401, 403}:
                response.close()
                token = create_session()
                headers["Authorization"] = f"Bearer {token}"
                response = client.request(method, base + path, headers=headers, **kwargs)
            return response

        meeting_id = "cert-" + uuid.uuid4().hex
        people = [{"name": name, "phone": ""} for name in participants]
        created = require_ok(
            authed(
                "POST",
                "/v1/meetings/jobs/init",
                json={
                    "meeting_id": meeting_id,
                    "title": "MAI Multilingual Production Certification",
                    "started_at": str(int(time.time() * 1000)),
                    "participants": people,
                    "language_mode": args.language_mode,
                    "audio_size": audio.stat().st_size,
                },
            ),
            "job initialization",
        )
        job_id = str(created["job_id"])
        uploaded = int(created.get("uploaded_bytes", 0))

        with audio.open("rb") as handle:
            while uploaded < audio.stat().st_size:
                handle.seek(uploaded)
                chunk = handle.read(UPLOAD_CHUNK)
                if not chunk:
                    raise SystemExit("FAIL: local audio ended before declared upload size")
                response = authed(
                    "PUT",
                    f"/v1/meetings/jobs/{job_id}/audio?offset={uploaded}",
                    headers={"Content-Type": "application/octet-stream"},
                    content=chunk,
                )
                if response.status_code == 409:
                    state = require_ok(
                        authed("GET", f"/v1/meetings/jobs/{job_id}"),
                        "upload resume state",
                    )
                    uploaded = int(state.get("uploaded_bytes", uploaded))
                    continue
                state = require_ok(response, "resumable audio upload")
                uploaded = int(state.get("uploaded_bytes", uploaded + len(chunk)))
                print(f"UPLOAD {uploaded}/{audio.stat().st_size} bytes")

        state = require_ok(
            authed("POST", f"/v1/meetings/jobs/{job_id}/start", content=b""),
            "job start",
        )
        deadline = time.time() + args.timeout_minutes * 60
        last_status = None
        while time.time() < deadline:
            status = str(state.get("status", ""))
            progress = int(state.get("progress", 0))
            if status != last_status:
                print(f"JOB {status} {progress}%")
                last_status = status
            if status == "ready":
                break
            if status == "failed":
                raise SystemExit(f"FAIL: server job failed: {state.get('error', 'unknown error')}")
            time.sleep(5)
            state = require_ok(
                authed("GET", f"/v1/meetings/jobs/{job_id}"),
                "job status",
            )
        else:
            raise SystemExit(f"FAIL: server job exceeded {args.timeout_minutes} minute certification timeout")

        result = state.get("result") or {}
        transcript = str(result.get("transcript", "")).strip()
        summary = str(result.get("summary", "")).strip()
        if not transcript or not summary:
            raise SystemExit("FAIL: final transcript or MOM summary is empty")

        valid_names = {name.casefold() for name in participants}
        for action in result.get("actions", []) or []:
            owner = str(action.get("owner") or "").strip()
            if owner:
                for part in [piece.strip() for piece in owner.split("/") if piece.strip()]:
                    if part.casefold() not in valid_names:
                        raise SystemExit(f"FAIL: action owner was invented or not selected: {part}")
            due = str(action.get("due") or "").strip()
            if due and not re.fullmatch(r"\d{4}-\d{2}-\d{2}", due):
                raise SystemExit(f"FAIL: invalid due-date format: {due}")

        searchable = "\n".join(
            [
                transcript,
                summary,
                *[str(x) for x in result.get("decisions", []) or []],
                *[str(x.get("text", "")) for x in result.get("actions", []) or [] if isinstance(x, dict)],
            ]
        ).casefold()
        missing = [value for value in args.expect if value.casefold() not in searchable]
        if missing:
            raise SystemExit("FAIL: expected facts missing from final result: " + ", ".join(missing))

        print(
            "PASS: real MAI audio completed secure device auth -> resumable upload -> "
            "persistent multilingual job -> verified English transcript -> structured MOM"
        )
        print(json.dumps(result, indent=2, ensure_ascii=False))

        revoke = client.post(
            base + "/v1/auth/revoke",
            headers=headers_admin,
            json={"device_id": device_id},
        )
        if revoke.status_code >= 400:
            print(f"WARNING: temporary certification device could not be revoked: {revoke.status_code}")


if __name__ == "__main__":
    main()
