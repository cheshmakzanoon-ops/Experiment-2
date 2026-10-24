#!/usr/bin/env bash
# Disposable, separate diagnostic only. Never used by the regular seven-lane matrix.
# AOSP ART 8222aa2d: force_disable_uffd_gc -> full reboot -> odrefresh -> verified CC.
set -euo pipefail
script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root="${RUNNER_TEMP:-/tmp}/artflow-device/cc-control"
mkdir -p "$root"
log_pid=''
phase=''
run_adb() { timeout 20 adb "$@"; }
property() { run_adb shell getprop "$1" | tr -d '\r'; }
stop_log() {
  if [[ -n "$log_pid" ]]; then
    kill "$log_pid" 2>/dev/null || true
    wait "$log_pid" 2>/dev/null || true
    log_pid=''
    run_adb logcat -b all -d -v threadtime > "$root/$phase/ring-logcat.txt" 2>&1 || true
    cat "$root/$phase/ring-logcat.txt" >> "$root/$phase/logcat.txt"
  fi
}
finish() {
  local status=$?
  trap - EXIT
  stop_log
  printf 'exitCode=%s\nlastPhase=%s\n' "$status" "$phase" > "$root/setup-status.txt"
  if ((status != 0)); then
    run_adb logcat -b all -d -v threadtime > "$root/failure-logcat.txt" 2>&1 || true
    run_adb shell getprop > "$root/failure-properties.txt" 2>&1 || true
  fi
  exit "$status"
}
trap finish EXIT
fail() { echo "$*" >&2; exit 1; }
[[ "${ART_CC_CONTROL_DISPOSABLE:-}" == 1 ]] || fail 'Explicit disposable collector-control opt-in is required'
[[ "${ART_JIT_MODE:-}" == disabled ]] || fail 'Collector control must retain disabled JIT'
boot_timeout="${ART_CC_BOOT_TIMEOUT_SECONDS:-600}"
[[ "$boot_timeout" =~ ^[1-9][0-9]{0,2}$ ]] && ((boot_timeout <= 900)) || fail 'Invalid collector boot timeout (1..900 seconds)'
[[ "$(property ro.kernel.qemu)" == 1 && "$(property ro.debuggable)" == 1 && "$(property ro.build.version.sdk)" == 35 ]] || fail 'Requires a debuggable API35 emulator'
[[ ",$(property ro.product.cpu.abilist)," == *,x86_64,* ]] || fail 'Requires x86_64'
[[ "$(run_adb shell getconf PAGE_SIZE | tr -d '\r')" == 16384 ]] || fail 'Requires actual 16KB pages'
[[ "$(property ro.build.fingerprint)" == 'google/sdk_gphone16k_x86_64/emu64xa16k:15/AE3A.240806.043/12960925:userdebug/dev-keys' ]] || fail 'Unexpected system-image fingerprint'
[[ "$(run_adb shell uname -r | tr -d '\r')" == '6.6.50-android15-8-g8adecb593e9b-ab12525588' ]] || fail 'Unexpected kernel'
root_adb() {
  run_adb root || true # adbd can close its transport while successfully restarting.
  timeout 60 adb wait-for-device
  [[ "$(run_adb shell id -u | tr -d '\r')" == 0 ]] || fail 'adb root was not granted'
}
start_log() {
  phase="$1"
  mkdir -p "$root/$phase"
  adb logcat -b all -v threadtime > "$root/$phase/logcat.txt" 2>&1 &
  log_pid=$!
}
snapshot() {
  local key label process_name pids pid
  : > "$root/$phase/properties.txt"
  for key in ro.build.fingerprint ro.build.version.sdk ro.product.cpu.abilist ro.kernel.qemu ro.debuggable ro.build.version.security_patch ro.build.id ro.build.version.incremental sys.boot_completed dalvik.vm.usejit dalvik.vm.usejitprofiles dalvik.vm.gctype ro.dalvik.vm.enable_uffd_gc persist.device_config.runtime_native_boot.enable_uffd_gc_2 persist.device_config.runtime_native_boot.force_disable_uffd_gc; do
    printf '%s=%s\n' "$key" "$(property "$key")" >> "$root/$phase/properties.txt"
  done
  run_adb shell cat /proc/sys/kernel/random/boot_id > "$root/$phase/boot-id.txt"
  run_adb shell uname -r > "$root/$phase/kernel.txt"
  run_adb shell uname -v > "$root/$phase/kernel-version.txt"
  run_adb shell getconf PAGE_SIZE > "$root/$phase/page-size.txt"
  run_adb shell id -u > "$root/$phase/uid.txt"
  run_adb shell sha256sum /apex/com.android.art/lib64/libart.so > "$root/$phase/libart-sha256.txt"
  run_adb shell device_config get runtime_native_boot force_disable_uffd_gc > "$root/$phase/device-config-force-disable.txt"
  if ! run_adb shell cat /data/misc/apexdata/com.android.art/dalvik-cache/cache-info.xml > "$root/$phase/cache-info.xml" 2> "$root/$phase/cache-info-error.txt"; then
    : > "$root/$phase/cache-info.xml"
  fi
  for label in zygote system-server; do
    process_name=zygote64
    [[ "$label" != system-server ]] || process_name=system_server
    pids="$(run_adb shell pidof "$process_name" | tr -d '\r')"
    pid="${pids%% *}"
    [[ "$pid" =~ ^[1-9][0-9]*$ ]] || fail "Missing $process_name PID"
    printf '%s\n' "$pid" > "$root/$phase/$label-pid.txt"
    run_adb shell cat "/proc/$pid/cmdline" > "$root/$phase/$label-cmdline.txt"
    run_adb shell cat "/proc/$pid/maps" > "$root/$phase/$label-maps.txt"
  done
  stop_log
  python3 "$script_dir/verify_android15_cc_control.py" --root "$root" --phase "$phase"
}
root_adb
start_log before
bash "$script_dir/configure-emulator-runtime.sh" > "$root/before/jit-configuration-call.txt" 2>&1
cp "${RUNNER_TEMP:-/tmp}/artflow-device/runtime-configuration.txt" "$root/before/runtime-configuration.txt"
snapshot
old_boot="$(tr -d '\r\n' < "$root/before/boot-id.txt")"
# This documented testing switch prevents a remote DeviceConfig sync changing the experiment.
run_adb shell device_config set_sync_disabled_for_tests persistent > "$root/device-config-sync.txt"
run_adb shell device_config put runtime_native_boot force_disable_uffd_gc true
[[ "$(run_adb shell device_config get runtime_native_boot force_disable_uffd_gc | tr -d '\r')" == true ]] || fail 'DeviceConfig write was not retained'
[[ "$(property persist.device_config.runtime_native_boot.force_disable_uffd_gc)" == true ]] || fail 'DeviceConfig property was not propagated'
deadline=$((SECONDS + boot_timeout))
run_adb reboot > "$root/reboot-command.txt" 2>&1 || true
# An offline transition can finish before a late wait-for-disconnect is attached.
# Require the actual new boot ID instead of relying on observing that transition.
while true; do
  timeout 10 adb wait-for-device || true
  current_boot="$(run_adb shell cat /proc/sys/kernel/random/boot_id 2>/dev/null | tr -d '\r\n')" || current_boot=''
  [[ -z "$current_boot" || "$current_boot" == "$old_boot" ]] || break
  ((SECONDS < deadline)) || fail 'A new kernel boot was not observed within the deadline'
  sleep 1
done
root_adb
start_log rebooted
while true; do
  current_boot="$(run_adb shell cat /proc/sys/kernel/random/boot_id 2>/dev/null | tr -d '\r\n')" || current_boot=''
  completed="$(property sys.boot_completed 2>/dev/null)" || completed=''
  if [[ -n "$current_boot" && "$current_boot" != "$old_boot" && "$completed" == 1 ]] && run_adb shell cmd package list packages android > "$root/rebooted/package-ready.txt" 2>&1; then
    break
  fi
  ((SECONDS < deadline)) || fail 'Full reboot/odrefresh did not complete within the deadline'
  sleep 2
done
snapshot
start_log configured
bash "$script_dir/configure-emulator-runtime.sh" > "$root/configured/jit-configuration-call.txt" 2>&1
cp "${RUNNER_TEMP:-/tmp}/artflow-device/runtime-configuration.txt" "$root/configured/runtime-configuration.txt"
snapshot
printf 'configurationStatus=PASS\n' > "$root/setup-complete.txt"
