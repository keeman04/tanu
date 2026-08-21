# TANU Android — Core Pipeline Phase 1

This branch introduces the first clean Android implementation of TANU instead of patching the iOS pipeline.

## What works in this phase

- Foreground microphone recording for user-started in-person meetings.
- 16 kHz mono PCM audio stored as recoverable WAV chunks.
- 20-second chunks so transcription starts during the meeting.
- Up to three chunk transcriptions in parallel instead of a serial queue.
- Transcript segments persisted by chunk index so parallel completion does not scramble meeting order.
- 90-second hard finalization deadline; unfinished audio remains on device for Retry Processing.
- Structured cloud MOM through the TANU backend.
- Local emergency MOM fallback if the cloud MOM request fails.
- Android system share sheet for WhatsApp/email/other installed apps.

## Deliberately NOT in Phase 1

- Automatic WhatsApp/cellular call capture.
- Floating overlay controls.
- Speaker diarization.
- CRM/calendar integrations.
- Automatic sending to participants.

Those features come after the core 5/15/30/60-minute meeting pipeline is proven reliable.

## Build

Open the `android/` directory in Android Studio. The project targets Android API 35 and requires Java 17.

For emulator + local backend:

```bash
./gradlew :app:assembleDebug \
  -PTANU_API_BASE_URL=http://10.0.2.2:8000 \
  -PTANU_API_TOKEN=choose-a-random-dev-token
```

The debug manifest allows cleartext traffic only for local development. Release builds should use HTTPS.

## Acceptance test

1. Run the backend.
2. Install the debug APK on a real Android phone when testing microphone behavior.
3. Grant microphone and notification permissions.
4. Record 5 minutes containing English, Tamil and Tanglish.
5. Confirm transcript text appears during the meeting.
6. Press Stop and confirm the app finishes remaining chunks, then creates a MOM.
7. Kill network access during a test and confirm saved audio remains available for Retry Processing.
8. Share the resulting MOM via the Android share sheet.

Do not delete source audio until the transcript and MOM are verified during the MVP test period.
