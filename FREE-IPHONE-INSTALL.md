# TANU on iPhone for $0

This branch is prepared for direct installation on your own iPhone using Xcode and a free Apple Account (Personal Team). No paid Apple Developer membership, distribution certificate, Ad Hoc profile, TestFlight, or App Store submission is required for this personal-device test.

## You need

- A Mac capable of running a current Xcode version.
- Xcode installed from the Mac App Store.
- A free Apple Account with two-factor authentication.
- Your iPhone running iOS 17 or later.
- A USB cable for the easiest first install.

## Download TANU

Download the `ios-free-personal` branch ZIP from GitHub and unzip it on the Mac, or clone the repository and switch to that branch.

## Prepare the project

Open Terminal, change into the unzipped TANU folder, then run:

```bash
bash setup-free-personal.sh
```

The script checks Xcode, installs XcodeGen through Homebrew when available, generates `TANU.xcodeproj`, and opens the project in Xcode.

If Homebrew is not installed, install it first from https://brew.sh and run the setup command again.

## Add your free Apple Account to Xcode

1. In Xcode, choose **Xcode > Settings > Accounts**.
2. Press **+** and sign in with your Apple Account.
3. A team ending in **(Personal Team)** should appear.

Do not send anyone your Apple password or two-factor authentication code.

## Connect the iPhone

1. Connect the iPhone to the Mac and unlock it.
2. Tap **Trust This Computer** if the iPhone asks.
3. In Xcode, choose the connected iPhone from the run destination menu at the top.
4. If Xcode/iOS asks for Developer Mode, enable it on the iPhone and restart the phone when requested.

## Select Personal Team signing

1. In the Xcode Project navigator, click the blue **TANU** project.
2. Select **TARGETS > TANU**.
3. Open **Signing & Capabilities**.
4. Keep **Automatically manage signing** enabled.
5. Set **Team** to your **(Personal Team)**.
6. The free-build bundle identifier is `com.keeman04.tanu.personal`.
7. Wait for Xcode to show that signing/provisioning succeeded.

## Install TANU

With your iPhone still selected as the run destination, press the **Run** triangle in Xcode.

Xcode will compile, sign, install, and launch TANU on that iPhone.

On first launch, allow:

- Microphone access
- Speech Recognition access

Without these permissions TANU cannot record/transcribe meetings.

## First functional test

Do a short test before relying on a long meeting:

1. Add one participant.
2. Start a meeting.
3. Speak clearly for about 60–90 seconds.
4. Lock the iPhone screen for part of the test, then unlock it.
5. Stop the meeting.
6. Confirm transcript text appears.
7. Confirm a MOM is generated.
8. Try Share and select WhatsApp if WhatsApp is installed.

The app records microphone audio in short chunks. It does not directly capture protected internal audio from cellular, FaceTime, or WhatsApp calls.

## OpenAI is optional

TANU has a deterministic local MOM fallback. If you want cloud-enhanced MOM, open TANU Settings and enter your own OpenAI API key. TANU stores it in iOS Keychain. Do not hard-code or commit the key to GitHub.

## Free Personal Team limitation

Apple free provisioning is temporary. If the app stops opening after the Personal Team provisioning period expires, reconnect the iPhone to the Mac and press **Run** in Xcode again to rebuild/reinstall it.

## If Xcode shows a signing error

First confirm:

- You are signed into Xcode with the correct Apple Account.
- **Automatically manage signing** is enabled.
- **Team** is the Personal Team.
- The iPhone is unlocked, trusted, and selected as the destination.
- Developer Mode is enabled if requested.

If the bundle identifier is reported as unavailable, change it in Signing & Capabilities to another unique value such as `com.keeman04.tanu.personal2`, then build again.

This free-install branch is also submitted directly against `main` so the repository's macOS/Xcode CI can validate the complete source build independently of Personal Team signing.
