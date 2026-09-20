#!/usr/bin/env bash
# Configure only an explicitly requested, disposable debug emulator before starting test processes.
set -euo pipefail
mode="${ART_JIT_MODE:-default}"
root="${RUNNER_TEMP:-/tmp}/artflow-device"
mkdir -p "$root"
record="$root/runtime-configuration.txt"
printf 'requestedJitMode=%s\n' "$mode" > "$record"
case "$mode" in
  default|disabled) ;;
  *) echo 'ART_JIT_MODE must be default or disabled' >&2; exit 2 ;;
esac
boot_timeout="${ART_BOOT_TIMEOUT_SECONDS:-180}"
if [[ ! "$boot_timeout" =~ ^[1-9][0-9]{0,2}$ ]] || ((boot_timeout > 600)); then
  echo 'ART_BOOT_TIMEOUT_SECONDS must be an integer from 1 to 600' >&2
  exit 2
fi
run_adb() { timeout 20 adb "$@"; }
property() { run_adb shell getprop "$1" | tr -d '\r'; }
record_properties() {
  local key value
  for key in ro.build.fingerprint ro.build.version.sdk ro.product.cpu.abilist ro.kernel.qemu ro.debuggable dalvik.vm.usejit dalvik.vm.usejitprofiles; do
    value="$(property "$key")" || return $?
    printf '%s=%s\n' "$key" "$value" >> "$record"
  done
}
record_properties
if [[ "$mode" == disabled ]]; then
  # Never restart Android services on a physical device or a production build.
  if [[ "$(property ro.kernel.qemu)" != 1 || "$(property ro.debuggable)" != 1 ]]; then
    echo 'JIT configuration requires a disposable, debuggable Android emulator' >&2
    exit 1
  fi
  # "adb root" commonly restarts adbd and may return a transient non-zero/closed
  # transport status even though the restart succeeds. The authoritative check is
  # the post-reconnect uid, so tolerate only this command's transient disconnect.
  run_adb root || true
  timeout 60 adb wait-for-device
  if [[ "$(run_adb shell id -u | tr -d '\r')" != 0 ]]; then
    echo 'The emulator did not grant adb root; runtime configuration was not applied' >&2
    exit 1
  fi
  # AOSP documents stop/setprop/start: -prop alone does not prove what ART read at startup.
  # https://source.android.com/docs/core/runtime/jit-compiler#turn-off-jit
  run_adb shell stop
  restart_needed=true
  restore_services() {
    if [[ "$restart_needed" == true ]]; then run_adb shell start || true; fi
  }
  trap restore_services EXIT
  run_adb shell setprop dalvik.vm.usejit false
  run_adb shell setprop dalvik.vm.usejitprofiles false
  run_adb shell setprop sys.boot_completed 0
  run_adb shell start
  restart_needed=false
  deadline=$((SECONDS + boot_timeout))
  until [[ "$(property sys.boot_completed)" == 1 ]]; do
    if ((SECONDS >= deadline)); then
      echo 'Android did not finish restarting within the runtime-configuration deadline' >&2
      exit 1
    fi
    sleep 1
  done
  if [[ "$(property dalvik.vm.usejit)" != false || "$(property dalvik.vm.usejitprofiles)" != false ]]; then
    echo 'ART runtime property verification failed' >&2
    exit 1
  fi
fi
printf '\nverifiedConfiguration:\n' >> "$record"
record_properties
printf 'configurationStatus=PASS\n' >> "$record"
cat "$record"
