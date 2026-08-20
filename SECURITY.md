# TANU iOS Security

## Security baseline

TANU treats meeting audio, transcripts, contact details, and API credentials as sensitive local data.

- OpenAI credentials are stored in iOS Keychain using `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`.
- API credentials are never written to `UserDefaults`, JSON meeting files, logs, GitHub source, or the default CI app binary.
- Meeting data is stored under Application Support with iOS file protection and excluded from device backups.
- Active recording chunks use `completeUnlessOpen` protection so an already-open recording can continue when the phone locks.
- Stored JSON uses `completeUntilFirstUserAuthentication` so background-safe data remains encrypted at rest.
- TANU uses `CNContactPickerViewController` so the user chooses the contact instead of granting broad address-book access for this workflow.
- Network calls use HTTPS only and an ephemeral `URLSession` with cookies/cache disabled.
- The CI workflow scans source files for OpenAI-style API keys and rejects obvious hard-coded secrets.
- Audio chunks are deleted after a successful MOM. If transcription produces no usable text, TANU keeps the chunks for recovery instead of silently deleting evidence of the failed conversion.

## OpenAI key policy

OpenAI recommends that API keys not be deployed in mobile apps. TANU therefore does not preinstall an API key in the default iOS build. For the current personal prototype, a key may be entered by the device owner and is then stored in Keychain. Before distribution to other users, OpenAI requests should be moved behind a TANU backend so the API key never ships to the client.

## Recording boundary

TANU records microphone input only. It is designed for in-person meetings or audio that is physically audible to the iPhone microphone. It does not claim to capture protected system audio, cellular call audio, or another app's private VoIP audio.

## Reporting

Do not post API keys, private transcripts, participant phone numbers, or signing certificates in GitHub issues. Revoke any credential that is accidentally committed or shared.
