# TANU iOS

TANU is a native SwiftUI personal meeting assistant for iPhone. This repository is the clean iOS implementation of the TANU workflow.

## What this version does

- Records user-started in-person meetings with `AVAudioRecorder`.
- Keeps recording in the background while the screen is locked using the iOS audio background mode.
- Rotates the recording into ~55-second AAC chunks so multi-hour meetings do not depend on one huge audio file.
- Transcribes chunks serially with Apple's Speech framework while the meeting is still running.
- Requests on-device speech recognition when the current iPhone supports it.
- Saves transcript text after each completed chunk.
- Generates a deterministic local MOM whenever a usable transcript exists.
- Optionally improves the final MOM with the OpenAI Responses API, falling back to the local MOM if the API is unavailable.
- Requires participant name + WhatsApp/mobile number; email and company are optional.
- Uses Apple's contact picker for user-selected contact import.
- Shares the MOM through the standard iOS share sheet, including WhatsApp when installed.
- Stores OpenAI credentials in Keychain and meeting files with iOS file protection.

## Important recording limitation

TANU records the iPhone microphone. It does not promise direct capture of cellular call audio, FaceTime audio, WhatsApp call audio, or protected system audio from another app. Phone calls and other audio-session interruptions can interrupt a recording.

## Why this architecture addresses the Android failure

The app does not wait until the end of a long recording to begin the entire conversion. It records bounded chunks, queues each chunk for transcription, persists transcript segments as they finish, and creates the MOM from the accumulated transcript when you press Stop. If a chunk cannot be transcribed, the source audio is retained instead of being discarded.

## Build

The repository uses XcodeGen so the Xcode project is generated from `project.yml`.

```bash
brew install xcodegen
xcodegen generate
open TANU.xcodeproj
```

The deployment target is iOS 17.0. Select your Apple Developer Team in Xcode to install the app on a physical iPhone.

## CI

GitHub Actions runs on macOS and performs:

- secret/security source checks
- Xcode project generation
- participant/MOM unit tests
- simulator build and tests
- unsigned generic iPhone compilation
- CI artifact packaging

The CI gate is bootstrapped on `main`, so pull requests can be compiled and tested before merge.

The CI `.app` artifact is a simulator build. A physical-iPhone `.ipa` requires Apple signing credentials and a provisioning profile; those credentials are intentionally not stored in this public repository.

## API-key security

The default iOS build does not contain a preinstalled OpenAI key. The owner can enter a personal key in Settings and TANU stores it in iOS Keychain. Before any public distribution, move OpenAI calls behind a backend so no reusable API key ships inside the mobile application.

See `SECURITY.md` for the security model.
