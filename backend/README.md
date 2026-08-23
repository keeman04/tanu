# MAI Private Processing Gateway — V1.1.1 Accuracy Pipeline

This service is required for MAI's final high-accuracy Tamil / English / Tanglish transcript and final English MOM. Android recording remains local and crash-safe even if the server or internet is unavailable.

## Accuracy pipeline

1. Android records the original meeting audio locally.
2. After Stop, the final AAC audio is uploaded over HTTPS to this gateway.
3. The gateway splits long audio into 8-minute, mono 16 kHz / 32 kbps segments.
4. `gpt-transcribe` transcribes each segment with both Tamil (`ta`) and English (`en`) language hints plus selected participant names and MAI/VGP vocabulary as keyword hints. It is explicitly instructed to transcribe, not translate or summarize.
5. `gpt-5.6-terra` creates a faithful English transcript from each source segment. It is instructed to preserve names, numbers, amounts, dates, commitments, and to use `[unclear]` instead of guessing.
6. `gpt-5.6-sol` generates the final MOM only from the verified English transcript.
7. Programmatic validation rejects invented action owners that do not match selected participants and de-duplicates decisions/actions.

The rough offline Vosk transcript in the Android recording screen is only a live preview. It must not be treated as the final Tamil/Tanglish transcript.

## Privacy model

- `OPENAI_API_KEY` exists only on this server, never in the Android APK.
- Uploaded meeting audio is written only to an OS temporary directory while the request runs.
- The temporary source and derived segments are deleted automatically when processing finishes or fails.
- The gateway has no meeting database and does not persist uploaded audio.
- Put the service behind HTTPS.
- `MAI_GATEWAY_TOKEN` is a revocable client credential, not an OpenAI key. For wider production rollout, replace the static gateway token with employee/device authentication and short-lived tokens.

## Docker deployment

```bash
docker build -t mai-backend ./backend
docker run --restart unless-stopped -p 8080:8080 \
  -e OPENAI_API_KEY='SET_THIS_AS_A_SERVER_SECRET' \
  -e MAI_GATEWAY_TOKEN='SET_A_RANDOM_REVOCABLE_TOKEN' \
  mai-backend
```

Check the service:

```bash
curl https://YOUR-MAI-BACKEND/health
```

Expected health output includes:

- `stt_model: gpt-transcribe`
- `translate_model: gpt-5.6-terra`
- `mom_model: gpt-5.6-sol`
- `languages: ["ta", "en"]`

## Connect Android

Set these GitHub Actions repository secrets before producing the installable APK:

- `MAI_BACKEND_URL=https://YOUR-MAI-BACKEND`
- `MAI_GATEWAY_TOKEN=<same revocable token used by the server>`

The Android CI passes those values as Gradle properties. The OpenAI API key is never passed to Gradle and never enters the APK.

## Accuracy expectations

No speech-recognition system can guarantee 100% accuracy. Accuracy depends strongly on microphone placement, overlapping speakers, background music/noise, pronunciation, and names/domain vocabulary. MAI therefore treats the original audio as the source of truth, uses selected participant names as transcription hints, and leaves uncertain owners/dates blank rather than inventing them.

Speaker diarization / automatic mapping of anonymous voices to selected participant names is a separate capability and is not yet certified in V1.1.1. When a speaker says only “I’ll do it” and their voice has not been mapped, MAI should prefer an empty owner over a wrong owner.
