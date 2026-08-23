import json
import time
from pathlib import Path

import job_cleanup
import jobs


def _insert_job(job_id: str, audio: Path, status: str, uploaded: int, expected: int, updated_at: int) -> None:
    with jobs._connect() as connection:
        connection.execute(
            """
            INSERT INTO jobs(
                job_id,meeting_id,device_id,title,started_at,participants,language_mode,
                audio_path,expected_bytes,uploaded_bytes,status,progress,created_at,updated_at
            ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """,
            (
                job_id,
                "meeting-" + job_id,
                "device-12345678",
                "Cleanup test",
                "1787500000000",
                json.dumps([]),
                "auto",
                str(audio),
                expected,
                uploaded,
                status,
                100 if status == "ready" else 0,
                updated_at,
                updated_at,
            ),
        )


def test_ready_job_deletes_server_audio_but_keeps_completed_upload_metadata(tmp_path):
    jobs.JOB_DB = tmp_path / "jobs.sqlite3"
    jobs.JOB_ROOT = tmp_path / "meetings"
    job_cleanup.JOB_DB = jobs.JOB_DB
    jobs.init_job_system()

    audio = jobs.JOB_ROOT / "ready-job" / "meeting.aac"
    audio.parent.mkdir(parents=True)
    audio.write_bytes(b"A" * 2048)
    _insert_job("ready-job", audio, "ready", 2048, 2048, int(time.time()))

    result = job_cleanup.cleanup_once()
    assert result["ready_audio_deleted"] == 1
    assert not audio.exists()

    with jobs._connect() as connection:
        row = connection.execute("SELECT status,uploaded_bytes,expected_bytes FROM jobs WHERE job_id='ready-job'").fetchone()
    assert row["status"] == "ready"
    assert row["uploaded_bytes"] == 2048
    assert row["expected_bytes"] == 2048


def test_expired_failed_cache_resets_to_resumable_upload(tmp_path):
    jobs.JOB_DB = tmp_path / "jobs.sqlite3"
    jobs.JOB_ROOT = tmp_path / "meetings"
    job_cleanup.JOB_DB = jobs.JOB_DB
    jobs.init_job_system()

    audio = jobs.JOB_ROOT / "failed-job" / "meeting.aac"
    audio.parent.mkdir(parents=True)
    audio.write_bytes(b"B" * 4096)
    old = int(time.time()) - job_cleanup.FAILED_CACHE_SECONDS - 10
    _insert_job("failed-job", audio, "failed", 4096, 4096, old)

    result = job_cleanup.cleanup_once(now=int(time.time()))
    assert result["retry_caches_reset"] == 1
    assert not audio.exists()

    with jobs._connect() as connection:
        row = connection.execute("SELECT status,uploaded_bytes,expected_bytes,error FROM jobs WHERE job_id='failed-job'").fetchone()
    assert row["status"] == "uploading"
    assert row["uploaded_bytes"] == 0
    assert row["expected_bytes"] == 4096
    assert "resume upload" in row["error"].lower()
