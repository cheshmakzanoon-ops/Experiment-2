#!/usr/bin/env bash
# Recover only the disposable Android 15 emulator after a classifier-proven framework crash.
set -euo pipefail
root="${RUNNER_TEMP:-/tmp}/artflow-device"
mkdir -p "$root"
record="$root/framework-recovery.txt"
deadline_seconds="${ANDROID_RECOVERY_TIMEOUT_SECONDS:-240}"
if [[ ! "$deadline_seconds" =~ ^[1-9][0-9]{0,2}$ ]] || ((deadline_seconds > 600)); then
  echo 'ANDROID_RECOVERY_TIMEOUT_SECONDS must be an integer from 1 to 600' >&2
  exit 2
fi
run_adb() { timeout 20 adb "$@"; }
wait_for_device() { timeout 60 adb wait-for-device; }
property() { run_adb shell getprop "$1" | tr -d '\r'; }
boot_id() { run_adb shell cat /proc/sys/kernel/random/boot_id | tr -d '\r'; }

printf 'recoveryStarted=1\n' > "$record"
before="$(boot_id)"
if [[ -z "$before" ]]; then
  echo 'Could not read emulator boot id before recovery' >&2
  exit 1
fi
printf 'bootIdBefore=%s\n' "$before" >> "$record"

# A framework crash may briefly disrupt adbd. Reconnect before issuing the reboot.
if ! wait_for_device; then
  adb kill-server || true
  adb start-server || true
  wait_for_device
fi
run_adb reboot || {
  adb kill-server || true
  adb start-server || true
  wait_for_device
  run_adb reboot
}

deadline=$((SECONDS + deadline_seconds))
after=""
while ((SECONDS < deadline)); do
  if wait_for_device >/dev/null 2>&1; then
    after="$(boot_id 2>/dev/null || true)"
    completed="$(property sys.boot_completed 2>/dev/null || true)"
    if [[ -n "$after" && "$after" != "$before" && "$completed" == 1 ]]; then
      break
    fi
  fi
  sleep 2
done
if [[ -z "$after" || "$after" == "$before" || "$(property sys.boot_completed 2>/dev/null || true)" != 1 ]]; then
  echo 'Android emulator did not complete a fresh boot after the framework crash' >&2
  exit 1
fi
printf 'bootIdAfter=%s\n' "$after" >> "$record"

# Reboot resets the runtime properties used by the Android 15 mitigation.
bash "$(dirname "${BASH_SOURCE[0]}")/configure-emulator-runtime.sh"
wait_for_device
if [[ "$(property sys.boot_completed)" != 1 ]]; then
  echo 'Android was not boot-complete after runtime reconfiguration' >&2
  exit 1
fi

# connectedDebugAndroidTest will reinstall both packages; clearing stale state is diagnostic hygiene.
run_adb shell pm clear com.artflow.studio >/dev/null 2>&1 || true
run_adb shell pm clear com.artflow.studio.test >/dev/null 2>&1 || true
printf 'recoveryStatus=PASS\n' >> "$record"
cat "$record"
