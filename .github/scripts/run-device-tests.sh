#!/usr/bin/env bash
# Preserve failures while capturing diagnostics before the emulator action stops the device.
set -uo pipefail
root="${RUNNER_TEMP:-/tmp}/artflow-device"
mkdir -p "$root"
collect() {
  timeout 15 adb logcat -d > "$root/device-logcat.txt" || true
  timeout 15 adb pull /sdcard/Android/data/com.artflow.studio/files/test-evidence "$root/test-evidence" || true
}
trap collect EXIT
page_size=$(adb shell getconf PAGE_SIZE | tr -d "\r")
echo "Runtime page size: $page_size" > "$root/page-size.txt"
test "$page_size" = "${EXPECTED_PAGE_SIZE:-$page_size}" || exit 1
./gradlew connectedDebugAndroidTest --stacktrace || exit $?

# Benchmark inherits release R8/resource shrinking but uses the disposable debug key.
# This is a release-equivalent launch smoke test, NOT a publisher-signed release.
./gradlew assembleBenchmark --stacktrace || exit $?
adb install -r app/build/outputs/apk/benchmark/app-benchmark.apk || exit $?
adb shell am force-stop com.artflow.studio || exit $?
adb shell am start -W -n com.artflow.studio/.presentation.ui.MainActivity > "$root/minified-launch.txt" || exit $?
grep -F 'Status: ok' "$root/minified-launch.txt" || exit 1
# Check sustained process survival rather than trusting am's launch acknowledgement.
for attempt in $(seq 1 10); do
  sleep 1
  adb shell pidof com.artflow.studio | tr -d '\r' | grep -E '^[0-9 ]+$' >/dev/null || exit 1
done
adb shell uiautomator dump /sdcard/artflow-window.xml || exit $?
adb pull /sdcard/artflow-window.xml "$root/minified-window.xml" || exit $?
grep -F 'package="com.artflow.studio"' "$root/minified-window.xml" || exit 1
adb exec-out screencap -p > "$root/minified-launch.png" || exit $?
echo 'Minified release-equivalent launch: PASS' >> "$root/minified-launch.txt"
