#!/usr/bin/env bash
# A/B Compose runtime diagnostic on API36. This is not release/device acceptance.
# Each invocation has its own result inventory; a failure stops without recovery.
set -uo pipefail

root="${RUNNER_TEMP:-/tmp}/artflow-compose-control"
mkdir -p "$root"
trap 'timeout 15 adb logcat -b all -d > "$root/final-logcat.txt" || true' EXIT

if [[ "${EXPECTED_PAGE_SIZE:-}" != 4096 && "${EXPECTED_PAGE_SIZE:-}" != 16384 ]]; then
  echo "Explicit EXPECTED_PAGE_SIZE=4096 or 16384 is required" >&2
  exit 2
fi
if [[ ! "${COMPOSE_ITERATIONS:-}" =~ ^[1-9][0-9]*$ ]]; then
  echo "Positive COMPOSE_ITERATIONS is required" >&2
  exit 2
fi

case_filter="com.artflow.studio.RuntimeEnvironmentTest#processPageSizeMatchesRequestedTestEnvironment,com.artflow.studio.BrushAttributeUiTest#studioRetainsTheChosenGroupAcrossTabsAndPublishesOnlyOnUse,com.artflow.studio.ArtworkWorkflowTest#createPaintSaveReopenRecreateAndDiscardPreserveTheSavedPixels"
{
  printf 'revision=%s\n' "$(git rev-parse HEAD)"
  printf 'bom=%s\n' "${COMPOSE_BOM:?COMPOSE_BOM required}"
  printf 'expected_page_size=%s\n' "$EXPECTED_PAGE_SIZE"
  printf 'guest_api=%s\n' "$(adb shell getprop ro.build.version.sdk | tr -d '\r')"
  printf 'guest_abi=%s\n' "$(adb shell getprop ro.product.cpu.abi | tr -d '\r')"
  printf 'guest_fingerprint=%s\n' "$(adb shell getprop ro.build.fingerprint | tr -d '\r')"
  printf 'guest_cores=%s\n' "$(adb shell nproc | tr -d '\r')"
  sha256sum app/build.gradle.kts app/build/outputs/apk/debug/app-debug.apk app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
} > "$root/provenance.txt" || exit 1
test "$(adb shell getprop ro.build.version.sdk | tr -d '\r')" = 36 || exit 1
bash .github/scripts/configure-emulator-runtime.sh > "$root/runtime-configuration.txt" 2>&1 || exit $?
printf 'iteration\tgradle_exit\tinventory_exit\n' > "$root/attempts.tsv"

for (( i = 1; i <= COMPOSE_ITERATIONS; i++ )); do
  name="$(printf 'iteration-%02d' "$i")"
  attempt="$root/$name"
  mkdir -p "$attempt"
  token="$(python3 -c 'import uuid; print(uuid.uuid4())')"
  adb logcat -c || true
  status=0
  ./gradlew connectedDebugAndroidTest \
    "-Pandroid.testInstrumentationRunnerArguments.class=$case_filter" \
    "-Pandroid.testInstrumentationRunnerArguments.expectedPageSize=$EXPECTED_PAGE_SIZE" \
    "-Pandroid.testInstrumentationRunnerArguments.artflowApkEvidenceToken=$token" \
    --stacktrace --console=plain > "$attempt/gradle.txt" 2>&1 || status=$?

  results="app/build/outputs/androidTest-results/connected/debug"
  if [[ -d "$results" ]]; then mv "$results" "$attempt/results"; fi
  evidence="app/build/outputs/connected_android_test_additional_output"
  if [[ -d "$evidence" ]]; then mv "$evidence" "$attempt/test-evidence"; fi
  timeout 15 adb logcat -b all -d > "$attempt/logcat.txt" || true
  python3 .github/scripts/collect_installed_apks.py \
    --output "$attempt/apk-provenance" --instrumentation-status "$status" \
    --attempt-token "$token" > "$attempt/apk-collection.txt" 2>&1 || true

  inventory=0
  python3 .github/scripts/verify_compose_repetition.py \
    --results "$attempt/results" --output "$attempt/verification.json" \
    > "$attempt/verification.txt" 2>&1 || inventory=$?
  printf '%s\t%s\t%s\n' "$i" "$status" "$inventory" >> "$root/attempts.tsv"

  if (( status != 0 || inventory != 0 )); then
    timeout --kill-after=2s 58s python3 .github/scripts/collect_native_tombstones.py \
      --output "$attempt/native-tombstones" \
      --apk-provenance "$attempt/apk-provenance/manifest.json" \
      --instrumentation-status "$status" --attempt-token "$token" \
      > "$attempt/tombstone-collection.txt" 2>&1 || true
    echo "First failing focused invocation: $name, Gradle=$status, inventory=$inventory" >&2
    tail -80 "$attempt/gradle.txt" || true
    cat "$attempt/verification.txt" || true
    exit 1
  fi
  echo "PASS: $name includes exactly the three declared test identities and measured page-size check"
done

echo "PASS: all $COMPOSE_ITERATIONS focused invocations completed without retry or framework recovery"
