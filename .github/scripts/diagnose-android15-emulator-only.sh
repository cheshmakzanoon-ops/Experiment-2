#!/usr/bin/env bash
# Disposable GitHub-hosted AVD control: no ArtFlow APK or test APK is installed.
# The purpose is to detect Android 15 / 16 KB runtime crashes *without* ArtFlow.
# A clean control is inconclusive; neither result substitutes for the seven-lane app gate.
set -uo pipefail

root="${RUNNER_TEMP:-/tmp}/artflow-android15-control"
mkdir -p "$root"
status=0

collect() {
  local exit_status=$?
  trap - EXIT
  echo "scriptExitStatus=$exit_status" >> "$root/metadata.txt"
  timeout 30 adb logcat -b all -d > "$root/final-logcat.txt" 2>"$root/logcat-error.txt" || true
  timeout 20 adb shell getprop > "$root/final-properties.txt" 2>/dev/null || true
  timeout 20 adb shell dumpsys SurfaceFlinger > "$root/surfaceflinger.txt" 2>/dev/null || true
  timeout 20 adb shell dumpsys activity processes > "$root/activity-processes.txt" 2>/dev/null || true
  # Tombstones are diagnostic data. A failure to collect them cannot make a crash disappear.
  timeout 20 adb root >> "$root/collection.txt" 2>&1 || true
  timeout 30 adb wait-for-device >> "$root/collection.txt" 2>&1 || true
  timeout 50 adb pull /data/tombstones "$root/tombstones" >> "$root/collection.txt" 2>&1 || true

  python3 - "$root" <<'PY'
import json
from pathlib import Path
import re
import sys

root = Path(sys.argv[1])
log = (root / "final-logcat.txt").read_text(errors="replace") if (root / "final-logcat.txt").exists() else ""
patterns = {
    "native_signals": r"\bFatal signal \d+ \(SIG[A-Z]+\)",
    "system_process_fatals": r"FATAL EXCEPTION IN SYSTEM PROCESS:",
    "java_fatals": r"\bFATAL EXCEPTION:",
    "art_frames": r"#00 pc .*\/apex\/com\.android\.art\/lib64\/libart\.so",
}
counts = {name: len(re.findall(pattern, log)) for name, pattern in patterns.items()}
events = [
    line[:850] for line in log.splitlines()
    if re.search(r"Fatal signal|FATAL EXCEPTION|DeadSystemException|#00 pc .*libart\.so", line)
]
(root / "summary.json").write_text(json.dumps({
    "experiment": "Android 15 16KB system-only Settings activity churn",
    "artflow_apk_installed": False,
    "mode": (root / "mode.txt").read_text().strip() if (root / "mode.txt").exists() else "unknown",
    "counts": counts,
    "events": events[:220],
    "truncated_events": max(0, len(events) - 220),
}, indent=2) + "\n")
print("Emulator-only fatal counts:", counts)
for event in events[:35]:
    print(event)
PY
  exit "$exit_status"
}
trap collect EXIT

echo "mode=${ART_JIT_MODE:-default}" > "$root/metadata.txt"
echo "${ART_JIT_MODE:-default}" > "$root/mode.txt"
echo "revision=$(git rev-parse HEAD)" >> "$root/metadata.txt"
echo "startedUtc=$(date -u +%Y-%m-%dT%H:%M:%SZ)" >> "$root/metadata.txt"
timeout 15 adb shell pm path com.artflow.studio > "$root/artflow-package-check.txt" 2>&1 || true
if grep -q '^package:' "$root/artflow-package-check.txt"; then
  echo "Invalid control: ArtFlow APK is installed" | tee -a "$root/metadata.txt"
  exit 1
fi
echo "artflowInstalled=false" >> "$root/metadata.txt"

bash .github/scripts/configure-emulator-runtime.sh > "$root/runtime-configuration.txt" 2>&1 || {
  echo "Android runtime configuration failed" >> "$root/metadata.txt"
  exit 1
}
timeout 20 adb shell getprop ro.build.fingerprint >> "$root/metadata.txt" || exit 1
timeout 20 adb shell getprop ro.kernel.qemu >> "$root/metadata.txt" || exit 1
timeout 20 adb shell getprop dalvik.vm.usejit >> "$root/metadata.txt" || exit 1
timeout 20 adb shell cat /proc/sys/kernel/random/boot_id > "$root/initial-boot-id.txt" || exit 1
timeout 20 adb logcat -b all -d > "$root/boot-logcat.txt" || true

# Repeatedly create and dismiss a STOCK system UI, while app/test APKs remain absent.
# The primary evidence is the native/system runtime log, not a pass/fail assertion.
for iteration in $(seq 1 150); do
  timeout 25 adb shell am start -W -a android.settings.SETTINGS >> "$root/settings-launches.txt" 2>&1 || echo "launchFailed=$iteration" >> "$root/metadata.txt"
  timeout 15 adb shell input keyevent 4 >> "$root/settings-launches.txt" 2>&1 || echo "backFailed=$iteration" >> "$root/metadata.txt"
  if (( iteration % 10 == 0 )); then
    echo "iteration=$iteration utc=$(date -u +%Y-%m-%dT%H:%M:%SZ)" >> "$root/progress.txt"
    timeout 15 adb shell cat /proc/sys/kernel/random/boot_id >> "$root/progress.txt" 2>&1 || echo "bootIdUnavailable=$iteration" >> "$root/progress.txt"
    timeout 15 adb shell pidof system_server >> "$root/progress.txt" 2>&1 || echo "systemServerUnavailable=$iteration" >> "$root/progress.txt"
  fi
  sleep 1
done

timeout 15 adb shell pm path com.artflow.studio > "$root/final-artflow-package-check.txt" 2>&1 || true
if grep -q '^package:' "$root/final-artflow-package-check.txt"; then
  echo "Invalid control: ArtFlow appeared during the experiment" >> "$root/metadata.txt"
  exit 1
fi
echo "finishedUtc=$(date -u +%Y-%m-%dT%H:%M:%SZ)" >> "$root/metadata.txt"
echo "controlStatus=completed-without-ArtFlow" >> "$root/metadata.txt"
