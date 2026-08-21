# TANU Android Phase 1

Android 10+ (API 29+) long-meeting meeting assistant.

## What is implemented

- Foreground microphone recording initiated by the user.
- Continuous 16 kHz mono AudioRecord capture.
- Silence-aware 15–20 second chunk boundaries without stopping the microphone.
- Native Opus/Ogg ~24 kbps using MediaCodec + MediaMuxer; AAC/M4A fallback if the device has no Opus encoder.
- Local-first audio safety: every active chunk is written to a PCM temp file before compression.
- Room database tracks meetings, audio chunks, transcript segments, rolling summaries and final MOM.
- WorkManager handles network-aware resumable chunk uploads with exponential backoff.
- SHA-256/idempotent chunk upload metadata.
- Server async transcription; upload ACK is not blocked by STT.
- Transcript sync every ~10 seconds while recording.
- Rolling AI meeting memory approximately every 10 minutes.
- Final MOM built from rolling memory plus remaining transcript tail.
- Retry/recovery after connection loss or app process restart.
- Android share sheet for WhatsApp/email/etc.

## Audio size

Opus target is 24 kbps, approximately 10.8 MB/hour of speech audio before container overhead. A 4-hour meeting is roughly 43 MB.

## Configure

In `~/.gradle/gradle.properties` or CI properties:

```
TANU_API_BASE_URL=https://your-api.example.com
TANU_API_TOKEN=your-shared-development-token
```

Debug builds can use `http://10.0.2.2:8000` for a local emulator backend. Release builds keep cleartext traffic disabled.

## Phase 1 boundary

This app records the Android device microphone. It does not promise direct access to protected WhatsApp, cellular or other app call audio.
