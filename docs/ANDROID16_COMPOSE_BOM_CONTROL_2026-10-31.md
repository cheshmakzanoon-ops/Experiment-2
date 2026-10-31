# Android 16 Compose dependency A/B — 31 October 2026

## Observation and independent variable

Two first-attempt required CI failures occurred in different Android 16 emulator configurations on unchanged application/test code; they should not be merged with the separate Android 15/16 KB native ART problem in [issue #38](https://github.com/cheshmakzanoon-ops/Experiment-2/issues/38).

- [CI #327, API36 Pixel C](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/runs/41146206986/job/124375419842): `BrushAttributeUiTest#studioRetainsTheChosenGroupAcrossTabsAndPublishesOnlyOnUse` failed with a Compose runtime concurrent `recordModificationsOf`/`applyChanges` error. Other 269 cases passed.
- [CI #328, API36/16 KB](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/runs/41150385563/job/124389181940): `ArtworkWorkflowTest#createPaintSaveReopenRecreateAndDiscardPreserveTheSavedPixels` failed with `performMeasureAndLayout called during measure layout`, crashing the app after six other test passes; 263 cases were not run. The runtime page check and minified launch were not reached.

Current source uses the Compose BOM `2024.09.03`. The [official Android Compose BOM guide](https://developer.android.com/develop/ui/compose/bom) now documents `2026.09.00` as a stable-version BOM. This creates a *version hypothesis*, **not** evidence that the newer libraries fix either crash. The two errors may be unrelated.

## Controlled GitHub Actions comparison

[API36 Compose runtime A/B workflow](../.github/workflows/android36-compose-bom-control.yml) compares four fresh GitHub-hosted Ubuntu 24.04 VMs, with their app/test sources otherwise identical:

| Device | Original BOM | Candidate BOM |
| --- | --- | --- |
| Android 16 API36, 4 KB Google APIs, Pixel C tablet | `2024.09.03` | `2026.09.00` |
| Android 16 API36, 16 KB Google APIs `google_apis_ps16k`, standard profile | `2024.09.03` | `2026.09.00` |

Only the **disposable Actions checkout** is changed for the candidate: the `androidx.compose:compose-bom` dependency is replaced before building both the application and instrumentation APK. The committed application source and mandatory `.github/workflows/android-ci.yml` are untouched. The exact source SHA, selected BOM, Gradle build output, modified Gradle-file checksum, guest identity and built APK SHA-256 hashes are preserved.

Each emulator boot performs **eight successive focused instrumentation invocations**, running:

1. `RuntimeEnvironmentTest#processPageSizeMatchesRequestedTestEnvironment` (actual process page-size assertion).
2. `BrushAttributeUiTest#studioRetainsTheChosenGroupAcrossTabsAndPublishesOnlyOnUse`.
3. `ArtworkWorkflowTest#createPaintSaveReopenRecreateAndDiscardPreserveTheSavedPixels`.

Every invocation gets fresh UTP XML, APK provenance, test evidence and full logcat. The verifier rejects missing, unexpected, duplicate, skipped or failing identities, and the loop stops on its **first** failure. No failed invocation is retried or recategorized. Repetitions share a single boot per arm; they are not eight independent fresh-VM samples.

This deliberately **does not** substitute for the full 270-case acceptance gate, minified launch, or published-release qualification. If a candidate is promising, it must be implemented deliberately in production source and verified by the *unchanged full matrix* on fresh first attempts. A single all-green diagnostic arm does not prove a fix.

## Status

Workflow implementation prepared; result and exact artifact digests pending its first GitHub Actions run. [Issue #39](https://github.com/cheshmakzanoon-ops/Experiment-2/issues/39) owns the Compose investigation. No Android emulator is required locally.
