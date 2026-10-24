#!/usr/bin/env bash
# Controlled same-image collector diagnostic; never retry instrumentation.
set -uo pipefail
root="${RUNNER_TEMP:-/tmp}/artflow-device"
control="$root/cc-control"
mkdir -p "$control"
monitor_pid=""
app_log_pid=""
app_log_phase=""
stop_app_log() {
  if [[ -n "$app_log_pid" ]]; then
    kill "$app_log_pid" 2>/dev/null || true
    wait "$app_log_pid" 2>/dev/null || true
    app_log_pid=""
    timeout 15 adb logcat -b all -d -v threadtime > "$control/$app_log_phase-ring-logcat.txt" 2>&1 || true
    cat "$control/$app_log_phase-ring-logcat.txt" >> "$control/$app_log_phase-logcat.txt"
  fi
}
start_app_log() {
  app_log_phase="$1"
  adb logcat -b all -v threadtime > "$control/$app_log_phase-logcat.txt" 2>&1 &
  app_log_pid=$!
}
capture_current_configuration() {
  local phase="$1" key value
  timeout 15 adb shell cat /proc/sys/kernel/random/boot_id > "$control/$phase-boot-id.txt" || return $?
  : > "$control/$phase-properties.txt"
  for key in ro.build.fingerprint ro.build.version.sdk ro.build.version.security_patch ro.build.id ro.build.version.incremental ro.product.cpu.abilist ro.kernel.qemu ro.debuggable sys.boot_completed dalvik.vm.usejit dalvik.vm.usejitprofiles dalvik.vm.gctype ro.dalvik.vm.enable_uffd_gc persist.device_config.runtime_native_boot.enable_uffd_gc_2 persist.device_config.runtime_native_boot.force_disable_uffd_gc; do
    value="$(timeout 15 adb shell getprop "$key" | tr -d '\r')" || return $?
    printf '%s=%s\n' "$key" "$value" >> "$control/$phase-properties.txt"
  done
}
collect() {
  if [[ -n "$monitor_pid" ]]; then
    kill "$monitor_pid" 2>/dev/null || true
    wait "$monitor_pid" 2>/dev/null || true
  fi
  stop_app_log
  timeout 15 adb logcat -b all -d > "$root/device-logcat.txt" || true
  timeout 15 adb pull /sdcard/Android/data/com.artflow.studio/files/test-evidence "$root/test-evidence" || true
}
trap collect EXIT
# Older Android images have no getconf executable. Measure via Os.sysconf inside
# the instrumentation process instead; RuntimeEnvironmentTest asserts this value.
case "${EXPECTED_PAGE_SIZE:-}" in
  16384) ;;
  *) echo 'The collector control requires EXPECTED_PAGE_SIZE=16384' >&2; exit 2 ;;
esac
if [[ "${ART_JIT_MODE:-}" != disabled ]]; then
  echo 'The same-image collector control requires ART_JIT_MODE=disabled' >&2
  exit 2
fi
bash "$(dirname "${BASH_SOURCE[0]}")/configure-android15-cc-control.sh" || exit $?
# The collector control has exactly one instrumentation attempt. A process crash fails it.
# Observe the instrumentation process without launching the app or changing its initial state.
capture_instrumentation_process() {
  local deadline=$((SECONDS + 300)) pid
  while ((SECONDS < deadline)); do
    pid="$(timeout 10 adb shell pidof com.artflow.studio 2>/dev/null | tr -d '\r' || true)"
    if [[ "$pid" =~ ^[0-9]+$ ]]; then
      printf '%s\n' "$pid" > "$control/instrumentation-app-pid.txt"
      timeout 10 adb shell cat "/proc/$pid/cmdline" > "$control/instrumentation-app-cmdline.txt" || return 1
      timeout 10 adb shell cat "/proc/$pid/maps" > "$control/instrumentation-app-maps.txt" || return 1
      return 0
    fi
    sleep 1
  done
  return 1
}
start_app_log instrumentation
capture_instrumentation_process &
monitor_pid=$!
printf 'instrumentationInvocations=1\n' > "$control/attempt-count.txt"
instrumentation_status=0
./gradlew connectedDebugAndroidTest \
  "-Pandroid.testInstrumentationRunnerArguments.expectedPageSize=${EXPECTED_PAGE_SIZE}" \
  --stacktrace || instrumentation_status=$?
stop_app_log
if kill -0 "$monitor_pid" 2>/dev/null; then kill "$monitor_pid" 2>/dev/null || true; fi
wait "$monitor_pid" 2>/dev/null || true
monitor_pid=""
collector_status=0
capture_current_configuration instrumentation || collector_status=$?
python .github/scripts/verify_android15_cc_control.py --root "$control" --phase instrumentation || collector_status=$?
if ((instrumentation_status != 0)); then exit "$instrumentation_status"; fi
if ((collector_status != 0)); then exit "$collector_status"; fi

# Capture evidence before the release-equivalent install replaces instrumentation state.
# RuntimeEnvironmentTest also logs measured values into UTP's preserved per-test logcat.
timeout 15 adb logcat -b all -d > "$root/instrumentation-logcat.txt" || true
timeout 15 adb pull /sdcard/Android/data/com.artflow.studio/files/test-evidence "$root/instrumentation-evidence" || true

# Benchmark inherits release R8/resource shrinking but uses the disposable debug key.
# This is a release-equivalent launch smoke test, NOT a publisher-signed release.
./gradlew assembleBenchmark --stacktrace || exit $?
sha256sum app/build/outputs/apk/benchmark/app-benchmark.apk > "$control/minified-apk-sha256.txt" || exit $?
adb install -r app/build/outputs/apk/benchmark/app-benchmark.apk || exit $?
adb shell am force-stop com.artflow.studio || exit $?
start_app_log minified
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
pid="$(timeout 10 adb shell pidof com.artflow.studio | tr -d '\r')" || exit $?
if [[ ! "$pid" =~ ^[0-9]+$ ]]; then exit 1; fi
printf '%s\n' "$pid" > "$control/minified-app-pid.txt"
timeout 10 adb shell cat "/proc/$pid/cmdline" > "$control/minified-app-cmdline.txt" || exit $?
timeout 10 adb shell cat "/proc/$pid/maps" > "$control/minified-app-maps.txt" || exit $?
stop_app_log
capture_current_configuration minified || exit $?
python .github/scripts/verify_android15_cc_control.py --root "$control" --phase minified || exit $?
echo 'Minified release-equivalent launch: PASS' >> "$root/minified-launch.txt"
