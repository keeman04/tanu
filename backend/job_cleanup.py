import os
import shutil
import sqlite3
import threading
import time
from pathlib import Path

JOB_DB = Path(os.getenv("MAI_JOB_DB", "/data/mai-jobs.sqlite3"))
FAILED_CACHE_SECONDS = max(3600, min(int(os.getenv("MAI_FAILED_AUDIO_CACHE_SECONDS", "86400")), 7 * 86400))
STALE_UPLOAD_SECONDS = max(86400, min(int(os.getenv("MAI_STALE_UPLOAD_SECONDS", str(7 * 86400))), 30 * 86400))
CLEANUP_INTERVAL_SECONDS = max(60, min(int(os.getenv("MAI_JOB_CLEANUP_INTERVAL_SECONDS", "300")), 3600))


def _connect() -> sqlite3.Connection:
    connection = sqlite3.connect(str(JOB_DB), timeout=30.0)
    connection.row_factory = sqlite3.Row
    return connection


def _delete_audio_cache(path_text: str) -> None:
    path = Path(path_text)
    run_dir = path.parent
    try:
        path.unlink(missing_ok=True)
    except OSError:
        pass
    shutil.rmtree(run_dir / "work", ignore_errors=True)


def cleanup_once(now: int | None = None) -> dict[str, int]:
    """Remove backend audio caches without deleting the final result metadata.

    Ready jobs lose their server-side audio immediately because Android retains the source
    according to the user's local retention setting. Failed jobs keep audio for a bounded
    retry window; when that cache expires, uploaded_bytes is reset to zero so Android can
    resumably upload the local source again instead of processing a missing file.
    """
    if not JOB_DB.exists():
        return {"ready_audio_deleted": 0, "retry_caches_reset": 0}
    now = now or int(time.time())
    ready_deleted = 0
    retry_reset = 0
    with _connect() as connection:
        ready = connection.execute(
            "SELECT job_id,audio_path FROM jobs WHERE status='ready' AND uploaded_bytes>0"
        ).fetchall()
        for row in ready:
            _delete_audio_cache(str(row["audio_path"]))
            connection.execute(
                "UPDATE jobs SET uploaded_bytes=0, updated_at=? WHERE job_id=?",
                (now, str(row["job_id"])),
            )
            ready_deleted += 1

        stale = connection.execute(
            """
            SELECT job_id,audio_path,status,updated_at
            FROM jobs
            WHERE status IN ('failed','uploading') AND uploaded_bytes>0
            """
        ).fetchall()
        for row in stale:
            age = now - int(row["updated_at"])
            threshold = FAILED_CACHE_SECONDS if str(row["status"]) == "failed" else STALE_UPLOAD_SECONDS
            if age < threshold:
                continue
            _delete_audio_cache(str(row["audio_path"]))
            connection.execute(
                """
                UPDATE jobs
                SET uploaded_bytes=0, status='uploading', progress=0,
                    error='Server audio cache expired; resume upload from byte 0.', updated_at=?
                WHERE job_id=?
                """,
                (now, str(row["job_id"])),
            )
            retry_reset += 1
    return {"ready_audio_deleted": ready_deleted, "retry_caches_reset": retry_reset}


def start_cleanup_loop() -> None:
    def run() -> None:
        while True:
            try:
                cleanup_once()
            except Exception:
                # Cleanup must never take the processing API down. The next cycle retries.
                pass
            time.sleep(CLEANUP_INTERVAL_SECONDS)

    thread = threading.Thread(target=run, name="mai-job-cleanup", daemon=True)
    thread.start()
