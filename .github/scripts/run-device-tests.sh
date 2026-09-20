#!/usr/bin/env bash
# Preserve failures while capturing diagnostics before the emulator action stops the device.
set -uo pipefail
root="${RUNNER_TEMP:-/tmp}/artflow-device"
mkdir -p "$root"
collect() {
  timeout 15 adb logcat -b all -d > "$root/device-logcat.txt" || true
  timeout 15 adb pull /sdcard/Android/data/com.artflow.studio/files/test-evidence "$root/test-evidence" || true
}
trap collect EXIT
# Older Android images have no getconf executable. Measure via Os.sysconf inside
# the instrumentation process instead; RuntimeEnvironmentTest asserts this value.
case "${EXPECTED_PAGE_SIZE:-}" in
  4096|16384) ;;
  *) echo 'EXPECTED_PAGE_SIZE must explicitly be 4096 or 16384' >&2; exit 2 ;;
esac
# Record default runtime settings, or explicitly configure and verify the debug-emulator mitigation.
bash "$(dirname "${BASH_SOURCE[0]}")/configure-emulator-runtime.sh" || exit $?
./gradlew connectedDebugAndroidTest \
  "-Pandroid.testInstrumentationRunnerArguments.expectedPageSize=${EXPECTED_PAGE_SIZE}" \
  --stacktrace || exit $?

# Capture evidence before the release-equivalent install replaces instrumentation state.
# RuntimeEnvironmentTest also logs measured values into UTP's preserved per-test logcat.
timeout 15 adb logcat -b all -d > "$root/instrumentation-logcat.txt" || true
timeout 15 adb pull /sdcard/Android/data/com.artflow.studio/files/test-evidence "$root/instrumentation-evidence" || true

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
