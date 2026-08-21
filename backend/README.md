# MAI Private Intelligence Backend

This service is the private server-side component for MAI 1.1. It receives small speech chunks from the Android app, transcribes English/Tamil/Tanglish, maintains rolling meeting memory, creates the final concise MOM, and powers Ask MAI.

## Security model

- The Android APK never contains the OpenAI API key.
- `OPENAI_API_KEY` is configured only on this server.
- Protect the MAI API with a strong random `MAI_API_TOKEN`.
- Production Android clients should use an HTTPS endpoint.
- Audio is first saved on the Android device. Upload/retry is asynchronous.

## Environment

```bash
export OPENAI_API_KEY="..."
export MAI_API_TOKEN="use-a-long-random-token"
export MAI_DATA_DIR="/srv/mai/data"
export MAI_STT_WORKERS="3"
# Optional overrides:
# export MAI_TRANSCRIBE_MODEL="gpt-transcribe"
# export MAI_MOM_MODEL="gpt-5.6-luna"
```

Install and run:

```bash
python -m venv .venv
. .venv/bin/activate
pip install -r backend/requirements.txt
uvicorn backend.app:app --host 127.0.0.1 --port 8000
```

Put a TLS reverse proxy (for example Nginx/Caddy or your existing HTTPS gateway) in front of Uvicorn for production.

## Android configuration

You can configure the server from **MAI → Settings → MAI Cloud Intelligence** with:

- Server URL, e.g. `https://mai.example.com`
- MAI server access token

Do **not** paste the OpenAI API key into the app.

For a managed build you can also provide Gradle properties:

```properties
MAI_API_BASE_URL=https://mai.example.com
MAI_API_TOKEN=your-server-access-token
```

## Health check

`GET /health` returns server status and whether AI credentials are configured. A production deployment should show `"status":"ok"` and `"ai_configured":true`.

## Data flow

1. Android records 16 kHz mono audio continuously.
2. Roughly every 15 seconds (20 seconds maximum), a chunk is safely closed.
3. The app prefers Opus/Ogg and falls back to AAC/M4A.
4. WorkManager uploads each checksum-protected chunk independently.
5. Server STT workers transcribe chunks and retry transient failures.
6. Rolling meeting memory is generated about every 10 minutes.
7. On Stop, the app sends the expected chunk count. The server waits for all available chunks before finalizing.
8. The final MOM semantically deduplicates decisions/actions and returns summary, decisions, actions and follow-ups.

## Tests

```bash
python -m unittest -v backend.test_app
```

The contract tests disable AI workers and do not call OpenAI.
