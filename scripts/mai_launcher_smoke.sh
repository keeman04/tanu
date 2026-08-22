#!/usr/bin/env bash
set -euo pipefail

APK="app/build/outputs/apk/debug/app-debug.apk"
PACKAGE="com.mai.app"
ACTIVITY="com.mai.app/.MainActivity"
DIAG="cold-start-diagnostics"
REPORT="app/build/reports/androidTests/connected/debug/index.html"
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

run_instrumentation() {
  rm -rf app/build/reports/androidTests/connected/debug

  set +e
  gradle --no-daemon connectedDebugAndroidTest
  local first_rc=$?
  set -e

  if [[ "$first_rc" -eq 0 ]]; then
    return 0
  fi

  # Hosted Android emulators can occasionally crash the instrumentation process
  # before AndroidJUnitRunner discovers any tests. Retry exactly once only for
  # that zero-test bootstrap failure. Real test/assertion failures are never retried.
  if [[ -f "$REPORT" ]] \
    && grep -q "Instrumentation run failed due to Process crashed" "$REPORT" \
    && grep -q '<tbody/>' "$REPORT"; then
    echo "::warning::Instrumentation process crashed before discovering any tests; retrying once after device reset."
    adb wait-for-device
    adb shell am force-stop "$PACKAGE" || true
    adb logcat -c || true
    sleep 3
    gradle --no-daemon connectedDebugAndroidTest --rerun-tasks
    return
  fi

  return "$first_rc"
}

gradle --no-daemon assembleDebug
adb install -r "$APK"

# 1. Fresh install with permissions denied: custom permission screen must stay alive.
assert_launch "fresh-no-permissions"

# 2. Grant normal MAI permissions and make sure Home can cold-start independently of network AI.
adb shell pm grant "$PACKAGE" android.permission.RECORD_AUDIO
adb shell pm grant "$PACKAGE" android.permission.READ_CONTACTS
SDK="$(adb shell getprop ro.build.version.sdk | tr -d '\r')"
if [[ "$SDK" -ge 33 ]]; then
  adb shell pm grant "$PACKAGE" android.permission.POST_NOTIFICATIONS || true
fi
assert_launch "permissions-granted"

# 3-5. Repeat real launcher cold starts to catch intermittent resource/native startup failures.
assert_launch "repeat-1"
assert_launch "repeat-2"
assert_launch "repeat-3"

# 6. Revoke contacts and verify the permission path still survives a cold start.
adb shell pm revoke "$PACKAGE" android.permission.READ_CONTACTS || true
assert_launch "contacts-revoked"

# Android runtime integration tests cover launch survival plus meeting data lifecycle:
# create -> finish -> search -> complete action -> delete, and checkpoint recovery.
run_instrumentation

echo "All MAI launcher and Android integration tests passed."
touch mai-launch-smoke-passed
