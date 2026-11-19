#!/usr/bin/env bash
# One bounded optimized instrumentation attempt. Preserve its original exit code and all evidence.
set -euo pipefail
qualification_root="${RUNNER_TEMP:-/tmp}/artflow-release-qualification"
mkdir -p "$qualification_root"
collect() {
  timeout 15 adb logcat -b all -d > "$qualification_root/first-attempt-logcat.txt" || true
  timeout 15 adb shell getprop > "$qualification_root/device-properties.txt" || true
  timeout 15 adb pull /sdcard/Android/data/com.artflow.studio/files/test-evidence \
    "$qualification_root/app-external-evidence" || true
}
if [[ -e "$qualification_root/attempt-token.txt" ]]; then
  echo 'Refusing a second qualification attempt in the same output directory' >&2
  exit 2
fi
python - <<'PY'
from pathlib import Path

results = Path("app/build/outputs/androidTest-results/connected/benchmark")
evidence = Path("app/build/outputs/connected_android_test_additional_output/benchmarkAndroidTest")
if any(results.rglob("TEST-*.xml")) or (evidence.exists() and any(evidence.rglob("*"))):
    raise SystemExit("Refusing stale benchmark results or evidence; use a clean qualification checkout")
PY
attempt_token="$(python -c 'import uuid; print(uuid.uuid4())')"
# Claim the attempt exclusively, including when two invocations start concurrently. Refused
# invocations must not collect new diagnostics over the preserved first-attempt evidence.
(set -o noclobber; printf '%s\n' "$attempt_token" > "$qualification_root/attempt-token.txt")
trap collect EXIT
classes="$(python .github/scripts/release_qualification.py inventory --output "$qualification_root/test-inventory.json")"
api="$(timeout 15 adb shell getprop ro.build.version.sdk | tr -d '\r')"
if [[ "$api" != 36 ]]; then
  echo "Qualification requires API 36, observed $api" >&2
  exit 2
fi

# Keep the shell alive to capture status from timeout/Gradle even when tee succeeds.
set +e
timeout --signal=TERM --kill-after=20s 25m ./gradlew connectedBenchmarkAndroidTest \
  -PartflowTestBuildType=benchmark \
  "-Pandroid.testInstrumentationRunnerArguments.class=$classes" \
  -Pandroid.testInstrumentationRunnerArguments.expectedBuildType=benchmark \
  -Pandroid.testInstrumentationRunnerArguments.expectedPageSize=4096 \
  "-Pandroid.testInstrumentationRunnerArguments.artflowApkEvidenceToken=$attempt_token" \
  --stacktrace 2>&1 | tee "$qualification_root/first-attempt-instrumentation.log"
pipeline_status=("${PIPESTATUS[@]}")
instrumentation_status=${pipeline_status[0]}
log_status=${pipeline_status[1]}
set -e
printf '%s\n' "$instrumentation_status" > "$qualification_root/instrumentation-exit-code.txt"
printf '%s\n' "$log_status" > "$qualification_root/instrumentation-log-exit-code.txt"
if ((instrumentation_status == 0 && log_status != 0)); then
  exit "$log_status"
fi
exit "$instrumentation_status"
