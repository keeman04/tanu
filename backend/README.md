# TANU Core API

This is the Phase 1 backend boundary for the Android app. The mobile application never stores the OpenAI production key.

## Run locally

```bash
cd backend
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
export OPENAI_API_KEY='...'
export TANU_API_TOKEN='choose-a-random-dev-token'
uvicorn app:app --host 0.0.0.0 --port 8000
```

Android emulator default API URL: `http://10.0.2.2:8000`.

Pass Android Gradle properties when building:

```bash
./gradlew :app:assembleDebug \
  -PTANU_API_BASE_URL=http://10.0.2.2:8000 \
  -PTANU_API_TOKEN=choose-a-random-dev-token
```

For a physical Android phone, point `TANU_API_BASE_URL` to an HTTPS backend reachable by that phone. Do not expose the development server directly to the public internet.

## Endpoints

- `GET /health`
- `POST /v1/transcriptions/chunks`
- `POST /v1/mom`

The transcription endpoint is designed for short audio chunks and prompts for English/Tamil/Tanglish preservation. The MOM endpoint returns a strict structured shape: summary, decisions, actions with owner/due date, and follow-ups.
