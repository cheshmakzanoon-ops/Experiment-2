#!/usr/bin/env bash
# Configure only an explicitly requested, disposable debug emulator before starting test processes.
set -euo pipefail
mode="${ART_JIT_MODE:-default}"
collector_mode="${ART_COLLECTOR_MODE:-default}"
root="${RUNNER_TEMP:-/tmp}/artflow-device"
mkdir -p "$root"
record="$root/runtime-configuration.txt"
{
  printf 'requestedJitMode=%s\n' "$mode"
  printf 'requestedCollectorMode=%s\n' "$collector_mode"
} > "$record"
case "$mode" in
  default|disabled) ;;
  *) echo 'ART_JIT_MODE must be default or disabled' >&2; exit 2 ;;
esac
case "$collector_mode" in
  default|cc) ;;
  *) echo 'ART_COLLECTOR_MODE must be default or cc' >&2; exit 2 ;;
esac
boot_timeout="${ART_BOOT_TIMEOUT_SECONDS:-180}"
if [[ ! "$boot_timeout" =~ ^[1-9][0-9]{0,2}$ ]] || ((boot_timeout > 600)); then
  echo 'ART_BOOT_TIMEOUT_SECONDS must be an integer from 1 to 600' >&2
  exit 2
fi
run_adb() { timeout 20 adb "$@"; }
property() { run_adb shell getprop "$1" | tr -d '\r'; }
boot_id() { run_adb shell cat /proc/sys/kernel/random/boot_id | tr -d '\r\n'; }
record_properties() {
  local key value
  for key in ro.build.fingerprint ro.build.version.sdk ro.product.cpu.abilist ro.kernel.qemu ro.debuggable dalvik.vm.usejit dalvik.vm.usejitprofiles persist.device_config.runtime_native_boot.force_disable_uffd_gc; do
    value="$(property "$key")" || return $?
    printf '%s=%s\n' "$key" "$value" >> "$record"
  done
}
require_disposable_emulator() {
  # Never restart Android services or reboot a physical device or a production build.
  if [[ "$(property ro.kernel.qemu)" != 1 || "$(property ro.debuggable)" != 1 ]]; then
    echo 'Runtime configuration requires a disposable, debuggable Android emulator' >&2
    exit 1
  fi
}
require_adb_root() {
  # "adb root" commonly restarts adbd and may return a transient non-zero/closed
  # transport status even though the restart succeeds. The authoritative check is
  # the post-reconnect uid, so tolerate only this command's transient disconnect.
  run_adb root || true
  timeout 60 adb wait-for-device
  if [[ "$(run_adb shell id -u | tr -d '\r')" != 0 ]]; then
    echo 'The emulator did not grant adb root; runtime configuration was not applied' >&2
    exit 1
  fi
}
cache_force_disable_true() {
  local xml
  xml="$(run_adb shell cat /data/misc/apexdata/com.android.art/dalvik-cache/cache-info.xml 2>/dev/null)" || return 1
  python3 -c '
import sys, xml.etree.ElementTree as ET
ns = "http://schemas.android.com/art/apex-cache-info/v1_0"
raw = sys.stdin.read()
if not raw.strip() or "<!DOCTYPE" in raw.upper():
    sys.exit(1)
try:
    root = ET.fromstring(raw)
except ET.ParseError:
    sys.exit(1)
prefix = "{" + ns + "}" if root.tag.startswith("{") else ""
if root.tag != prefix + "cacheInfo":
    sys.exit(1)
groups = root.findall(prefix + "systemProperties")
if len(groups) != 1:
    sys.exit(1)
for item in groups[0]:
    if (
        item.attrib.get("k") == "persist.device_config.runtime_native_boot.force_disable_uffd_gc"
        and item.attrib.get("v") == "true"
    ):
        sys.exit(0)
sys.exit(1)
' <<< "$xml"
}
record_properties
if [[ "$collector_mode" == cc ]]; then
  require_disposable_emulator
  require_adb_root
  if [[ "$(property persist.device_config.runtime_native_boot.force_disable_uffd_gc)" != true ]] || ! cache_force_disable_true; then
    before_boot="$(boot_id)"
    if [[ -z "$before_boot" ]]; then
      echo 'Could not read boot id before collector configuration' >&2
      exit 1
    fi
    # AOSP runtime_native_boot.force_disable_uffd_gc -> full reboot -> odrefresh -> CC.
    run_adb shell device_config set_sync_disabled_for_tests persistent
    run_adb shell device_config put runtime_native_boot force_disable_uffd_gc true
    prop_deadline=$((SECONDS + 60))
    while true; do
      dc_val="$(run_adb shell device_config get runtime_native_boot force_disable_uffd_gc 2>/dev/null | tr -d '\r')" || dc_val=''
      prop_val="$(property persist.device_config.runtime_native_boot.force_disable_uffd_gc 2>/dev/null)" || prop_val=''
      if [[ "$dc_val" == true && "$prop_val" == true ]]; then
        break
      fi
      if ((SECONDS >= prop_deadline)); then
        echo 'force_disable_uffd_gc did not propagate to persistent property within deadline' >&2
        exit 1
      fi
      sleep 1
    done
    run_adb reboot || true
    cc_deadline=$((SECONDS + boot_timeout))
    while true; do
      timeout 15 adb wait-for-device >/dev/null 2>&1 || true
      current_boot="$(boot_id 2>/dev/null || true)"
      if [[ -n "$current_boot" && "$current_boot" != "$before_boot" && "$(property sys.boot_completed 2>/dev/null || true)" == 1 ]] \
        && run_adb shell cmd package list packages android >/dev/null 2>&1; then
        break
      fi
      if ((SECONDS >= cc_deadline)); then
        echo 'Android did not finish collector reboot within the runtime-configuration deadline' >&2
        exit 1
      fi
      sleep 2
    done
    require_adb_root
  fi
  if [[ "$(property persist.device_config.runtime_native_boot.force_disable_uffd_gc)" != true ]] || ! cache_force_disable_true; then
    echo 'ART collector CC boot-cache verification failed' >&2
    exit 1
  fi
  run_adb logcat -b all -d -v threadtime > "$root/cc-boot-logcat.txt" 2>&1 || true
fi
if [[ "$mode" == disabled ]]; then
  require_disposable_emulator
  require_adb_root
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
if [[ "$collector_mode" == cc ]]; then
  printf 'cacheInfoForceDisable=true\n' >> "$record"
fi
printf 'configurationStatus=PASS\n' >> "$record"
cat "$record"
