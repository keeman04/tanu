#!/usr/bin/env python3
"""Real MAI backend certification using an actual meeting audio file.

Example:
  python e2e_certify.py \
    --base-url https://mai.example.com \
    --admin-code "$MAI_ADMIN_CODE" \
    --audio tamil-tanglish-test.aac \
    --participant Ravi --participant Karthick \
    --expect Waghoba --expect 80000 --expect Google --expect Meta

The script creates a temporary per-device identity, obtains a one-time activation code,
proves possession of its P-256 private key, uploads the real audio through the same secured
endpoint used by Android, validates the result, then revokes the temporary device.
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


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", required=True)
    parser.add_argument("--admin-code", required=True)
    parser.add_argument("--audio", required=True)
    parser.add_argument("--participant", action="append", default=[])
    parser.add_argument("--expect", action="append", default=[])
    parser.add_argument("--timeout-minutes", type=int, default=45)
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

    timeout = httpx.Timeout(args.timeout_minutes * 60.0, connect=30.0)
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
        code = activation["activation_code"]

        require_ok(
            client.post(
                base + "/v1/auth/enroll",
                json={
                    "device_id": device_id,
                    "public_key": base64.b64encode(public_der).decode("ascii"),
                    "activation_code": code,
                    "label": "MAI E2E Certification",
                },
            ),
            "device enrollment",
        )

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
        token = session["access_token"]

        people = [{"name": name, "phone": ""} for name in participants]
        with audio.open("rb") as handle:
            result = require_ok(
                client.post(
                    base + "/v1/meetings/process",
                    headers={"Authorization": f"Bearer {token}"},
                    data={
                        "meeting_id": "cert-" + uuid.uuid4().hex,
                        "title": "Tamil Tanglish Production Certification",
                        "started_at": str(int(time.time() * 1000)),
                        "participants": json.dumps(people),
                    },
                    files={"audio": (audio.name, handle, "audio/aac")},
                ),
                "real meeting processing",
            )

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

        print("PASS: real MAI audio completed secure device auth -> full transcription -> English MOM")
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
