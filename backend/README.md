# TANU Core Backend Phase 1

A small single-node backend for the Android 10+ Phase 1 pipeline.

## Responsibilities

1. Idempotently create meetings.
2. Accept small Opus/Ogg or AAC/M4A chunks and ACK immediately after durable local storage.
3. Keep durable chunk/transcript state in SQLite (WAL mode).
4. Run up to 3 asynchronous transcription workers by default.
5. Retry failed STT with exponential backoff.
6. Build rolling structured meeting memory every ~10 minutes.
7. On finalize, wait for queued chunks and create the final structured MOM from rolling memory plus the transcript tail.

For a production deployment, replace local disk with object storage and the in-process durable queue with a managed queue/worker service; the mobile API contract can stay the same.

## Run

```bash
python -m venv .venv
. .venv/bin/activate
pip install -r requirements.txt
export OPENAI_API_KEY=...
export TANU_API_TOKEN=...
uvicorn app:app --host 0.0.0.0 --port 8000
```

Optional environment variables:

- `TANU_STT_WORKERS` (default 3)
- `TANU_TRANSCRIBE_MODEL` (default `gpt-4o-mini-transcribe`)
- `TANU_MOM_MODEL` (default `gpt-5.6-luna`)
- `TANU_DATA_DIR` (default `./data`)
- `TANU_ROLLING_WINDOW_MS` (default 600000)
