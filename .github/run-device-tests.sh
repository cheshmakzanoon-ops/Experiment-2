#!/usr/bin/env bash
# Collect diagnostics before the emulator action shuts down its device.
set -uo pipefail
./gradlew connectedDebugAndroidTest --stacktrace
status=$?
timeout 15 adb logcat -d > "$RUNNER_TEMP/device-logcat.txt" || true
timeout 15 adb pull /sdcard/Android/data/com.artflow.studio/files/test-evidence "$RUNNER_TEMP/test-evidence" || true
exit "$status"
