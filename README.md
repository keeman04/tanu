# MAI — Meeting Assistant Intelligence

MAI is a personal Android meeting assistant for recording long in-person meetings, creating concise minutes, tracking actions, searching past meetings and sharing a MOM PDF.

## MAI 1.1 launch candidate

### Personal meeting flow
- Microphone permission is required only for recording.
- Contacts permission is requested only when the user chooses **Contacts**.
- Participants are mandatory before Start.
- Add participants from phone contacts or manually with **name + WhatsApp number**.
- System / Light / Dark themes.

### Long-meeting recording
- Android 10+ (`minSdk 29`).
- Foreground microphone service with partial wake lock.
- 16 kHz mono speech audio.
- Audio is written locally before any transcription/AI work.
- Silence-aware chunking around 15 seconds, hard cut at 20 seconds.
- Opus/Ogg at ~24 kbps where supported; AAC/M4A fallback.
- Every chunk has sequence/timestamps/SHA-256 and persistent state.
- WorkManager handles network-constrained upload and exponential retry.
- Up to three concurrent chunk uploads.
- Recovery worker repairs saved PCM/chunks after a stale process or connectivity failure.
- Recording never waits for cloud processing.

### Meeting intelligence
- Offline English live preview remains available as a fallback.
- Optional private MAI backend provides final **English + Tamil + Tanglish/code-switched transcription**.
- Rolling meeting memory about every 10 minutes prevents hours of audio from being reprocessed at Stop.
- Final MOM is short and structured:
  - Summary
  - Decisions
  - Actions / owner / due date
  - Follow-ups
- Cloud MOM prompt semantically deduplicates repeated decisions/tasks and merges repeated ownership/date information.
- Local heuristic MOM remains available if the server is unavailable.

### Meeting memory
- Meetings screen with search across title, people, transcript, MOM, decisions and actions.
- **Ask MAI** with cloud answer when configured and local evidence-based fallback otherwise.
- Global Actions screen, due/overdue highlighting and daily reminder worker.
- MOM/transcript/decisions/actions remain until the meeting is deleted.
- Audio retention: **Forever** by default, or 7 / 30 / 90 days.

### Sharing
- One-page concise MOM PDF.
- WhatsApp-targeted Android share flow plus generic Share.
- Personal WhatsApp still requires the user to choose/confirm the destination chat; unattended recipient delivery is intentionally not implemented with unofficial APIs.

## Privacy and keys

The app never asks for or embeds an OpenAI API key. The optional MAI backend stores the OpenAI credential server-side. Android stores only the MAI server URL/access token needed to reach the user's own backend.

See [`backend/README.md`](backend/README.md) for private-server deployment.

## Build and test

GitHub Actions builds the Android APK, runs JVM tests, validates the backend contract, and repeatedly cold-starts MAI on the oldest and newest supported Android test targets.

```bash
gradle --no-daemon testDebugUnitTest
gradle --no-daemon assembleDebug
python -m unittest -v backend.test_app
```

## Release boundary

The repository contains the launch-candidate application and private backend. A public Play Store release additionally requires the owner's production signing key, final package/store ownership decision, privacy-policy URL, Play Console Data Safety declarations, screenshots/listing content, and a deployed HTTPS MAI backend if multilingual cloud intelligence is enabled.
