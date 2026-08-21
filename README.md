# MAI — Meeting Assistant Intelligence

Native Android V1 for in-person meetings.

## V1
- Mandatory microphone + contacts permissions on first launch
- Add attendee from phone contacts or manual name + WhatsApp number
- Screen-lock-safe foreground recording service
- Live microphone-volume waveform
- Offline English speech recognition using Vosk
- Local MOM: summary, decisions, actions, owner, simple due-date normalization
- Meeting history + global actions
- Audio retention: 1 / 7 / 15 / 30 days; default 7
- MOM/transcript/actions remain after audio expires
- PDF MOM sharing through Android share sheet
- System / Light / Dark theme

## Reliability rule
Audio capture is independent from transcription and MOM generation. If speech recognition fails, recording continues.

## Build
GitHub Actions builds an installable debug APK on every PR/push.

## Language note
This first fully-local build ships an English Vosk model. Tamil/Tanglish requires a suitable on-device model or the planned backend multilingual STT path.
