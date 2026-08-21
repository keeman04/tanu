# MAI Private Processing Gateway

This service is optional. The Android app always records and creates a local fallback MOM first. When a gateway URL is configured, WorkManager sends the completed local audio to this service for higher-quality multilingual transcription (English / Tamil / Tanglish) and a stronger concise MOM.

## Privacy model

- `OPENAI_API_KEY` exists only on this server, never in the Android app.
- Uploaded meeting audio is written only to an OS temporary directory while the request runs.
- The temporary source and all derived 15-minute MP3 segments are deleted automatically when processing finishes or fails.
- The service does not include a database or persistent audio store.
- Put the service behind HTTPS. Set `MAI_GATEWAY_TOKEN` and rotate it if a client build is lost or distributed outside the intended users.

## Run with Docker

```bash
docker build -t mai-backend ./backend
docker run --rm -p 8080:8080 \
  -e OPENAI_API_KEY='...' \
  -e MAI_GATEWAY_TOKEN='replace-with-a-random-revocable-token' \
  mai-backend
```

Check:

```bash
curl http://localhost:8080/health
```

## Models and long meetings

Defaults:

- Speech-to-text: `gpt-4o-transcribe`
- MOM: `gpt-5.6-luna`
- Source audio is converted by ffmpeg to mono 16 kHz 32 kbps MP3.
- Long meetings are split into 15-minute segments and up to three transcription requests run concurrently.

Override with environment variables:

- `MAI_STT_MODEL`
- `MAI_MOM_MODEL`
- `MAI_SEGMENT_SECONDS`
- `MAI_STT_WORKERS`
- `OPENAI_BASE_URL`

## Connect an Android build

Build properties are deliberately empty by default, so a public/debug build never contains an OpenAI API key and simply uses MAI's local fallback.

Configure your private Android build with:

```properties
MAI_BACKEND_URL=https://mai-api.example.com
MAI_GATEWAY_TOKEN=replace-with-the-same-revocable-gateway-token
```

These are Gradle properties, not the OpenAI secret. The gateway token is a client credential and can be extracted from a distributed APK, so keep it revocable and never reuse it as an infrastructure or OpenAI credential.

After every completed recording, MAI queues a network-constrained WorkManager job. The local MOM remains immediately usable; when enhancement succeeds, the stored transcript, decisions and actions are upgraded automatically.
