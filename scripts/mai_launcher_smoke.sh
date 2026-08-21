#!/usr/bin/env bash
set -euo pipefail

APK="app/build/outputs/apk/debug/app-debug.apk"
PACKAGE="com.mai.app"
ACTIVITY="com.mai.app/.MainActivity"
DIAG="cold-start-diagnostics"
mkdir -p "$DIAG"

assert_launch() {
  local label="$1"
  echo "=== MAI launch test: $label ==="
  adb shell am force-stop "$PACKAGE" || true
  adb logcat -c || true
  adb shell am start -W -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n "$ACTIVITY" > "$DIAG/${label}-start.txt"
  sleep 8

  adb logcat -d -v threadtime > "$DIAG/${label}-logcat.txt" || true
  adb shell dumpsys activity activities > "$DIAG/${label}-activities.txt" || true

  local pid
  pid="$(adb shell pidof "$PACKAGE" 2>/dev/null | tr -d '\r' || true)"
  if [[ -z "$pid" ]]; then
    echo "MAI process is not alive after launch: $label" >&2
    return 1
  fi

  if grep -q "Process: $PACKAGE" "$DIAG/${label}-logcat.txt" && grep -q "FATAL EXCEPTION" "$DIAG/${label}-logcat.txt"; then
    echo "MAI fatal exception detected: $label" >&2
    return 1
  fi

  if grep -q "Force finishing activity $ACTIVITY" "$DIAG/${label}-logcat.txt"; then
    echo "Android force-finished MAI: $label" >&2
    return 1
  fi

  if ! grep -q "$PACKAGE/.MainActivity" "$DIAG/${label}-activities.txt"; then
    echo "MAI MainActivity is not present after launch: $label" >&2
    return 1
  fi

  echo "PASS: $label (pid $pid)"
}

gradle --no-daemon assembleDebug
adb install -r "$APK"

# 1. Fresh install with microphone denied: custom permission screen must stay alive.
assert_launch "fresh-no-permissions"

# 2. Grant recording permissions and make sure Home can cold-start independently of Vosk.
adb shell pm grant "$PACKAGE" android.permission.RECORD_AUDIO
adb shell pm grant "$PACKAGE" android.permission.POST_NOTIFICATIONS || true
assert_launch "microphone-granted"

# 3-5. Repeat real launcher cold starts to catch intermittent resource/native startup failures.
assert_launch "repeat-1"
assert_launch "repeat-2"
assert_launch "repeat-3"

# 6. Contacts is optional; revoke it and verify the app still cold-starts to Home.
adb shell pm revoke "$PACKAGE" android.permission.READ_CONTACTS || true
assert_launch "contacts-optional"

# 7. Run the real service pipeline: create meeting -> start microphone FGS -> stop ->
# persist encoded chunk + checksum + local MOM. This uses the emulator microphone (silence is OK).
adb shell pm grant "$PACKAGE" android.permission.RECORD_AUDIO
adb shell pm grant "$PACKAGE" android.permission.POST_NOTIFICATIONS || true
adb logcat -c || true
gradle --no-daemon connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.mai.app.RecordingPipelineSmokeTest
adb logcat -d -v threadtime > "$DIAG/recording-pipeline-logcat.txt" || true

if adb logcat -d | grep -q "Process: $PACKAGE" && adb logcat -d | grep -q "FATAL EXCEPTION"; then
  echo "MAI fatal exception detected during recording pipeline smoke" >&2
  exit 1
fi

echo "All MAI launcher and recording pipeline smoke tests passed."
touch mai-launch-smoke-passed
