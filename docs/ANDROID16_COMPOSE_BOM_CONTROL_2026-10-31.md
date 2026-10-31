# Android 16 Compose dependency A/B — 31 October 2026

## Observation and independent variable

Two first-attempt required CI failures occurred in different Android 16 emulator configurations on unchanged application/test code; they should not be merged with the separate Android 15/16 KB native ART problem in [issue #38](https://github.com/cheshmakzanoon-ops/Experiment-2/issues/38).

- [CI #327, API36 Pixel C](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/runs/41146206986/job/124375419842): `BrushAttributeUiTest#studioRetainsTheChosenGroupAcrossTabsAndPublishesOnlyOnUse` failed with a Compose runtime concurrent `recordModificationsOf`/`applyChanges` error. Other 269 cases passed.
- [CI #328, API36/16 KB](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/runs/41150385563/job/124389181940): `ArtworkWorkflowTest#createPaintSaveReopenRecreateAndDiscardPreserveTheSavedPixels` failed with `performMeasureAndLayout called during measure layout`, crashing the app after six other test passes; 263 cases were not run. The runtime page check and minified launch were not reached.

Current source uses the Compose BOM `2024.09.03`. The [official Android Compose BOM guide](https://developer.android.com/develop/ui/compose/bom) now documents `2026.09.00` as a stable-version BOM. The newest BOM needs a newer SDK/AGP than the project currently pins; the follow-up tests the older 2025.05.01 candidate against the pinned toolchain first. This remains a *version hypothesis*, **not** evidence that newer libraries fix either crash. The two errors may be unrelated.

## Controlled GitHub Actions comparison

[API36 Compose runtime A/B workflow](../.github/workflows/android36-compose-bom-control.yml) compares four fresh GitHub-hosted Ubuntu 24.04 VMs, with their app/test sources otherwise identical:

| Device | Original BOM | Candidate BOM |
| --- | --- | --- |
| Android 16 API36, 4 KB Google APIs, Pixel C tablet | `2024.09.03` | `2025.05.01` (configurable on manual dispatch) |
| Android 16 API36, 16 KB Google APIs `google_apis_ps16k`, standard profile | `2024.09.03` | `2025.05.01` (configurable on manual dispatch) |

Only the **disposable Actions checkout** is changed for the candidate: the `androidx.compose:compose-bom` dependency is replaced before building both the application and instrumentation APK. The committed application source and mandatory `.github/workflows/android-ci.yml` are untouched. The exact source SHA, selected BOM, Gradle build output, modified Gradle-file checksum, guest identity and built APK SHA-256 hashes are preserved.

Each emulator boot performs **eight successive focused instrumentation invocations**, running:

1. `RuntimeEnvironmentTest#processPageSizeMatchesRequestedTestEnvironment` (actual process page-size assertion).
2. `BrushAttributeUiTest#studioRetainsTheChosenGroupAcrossTabsAndPublishesOnlyOnUse`.
3. `ArtworkWorkflowTest#createPaintSaveReopenRecreateAndDiscardPreserveTheSavedPixels`.

Every invocation gets fresh UTP XML, APK provenance, test evidence and full logcat. The verifier rejects missing, unexpected, duplicate, skipped or failing identities, and the loop stops on its **first** failure. No failed invocation is retried or recategorized. Repetitions share a single boot per arm; they are not eight independent fresh-VM samples.

This deliberately **does not** substitute for the full 270-case acceptance gate, minified launch, or published-release qualification. If a candidate is promising, it must be implemented deliberately in production source and verified by the *unchanged full matrix* on fresh first attempts. A single all-green diagnostic arm does not prove a fix.

## Required CI cross-check on the same source

[Full Android CI #330](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/runs/41158107867), also on `02e9341`, finished **7/8 jobs passing**. All three API36 jobs—standard 4 KB, 16 KB and Pixel C tablet—passed **270/270** required tests, measured their actual 4096/16384-byte guest page sizes and passed minified launches; each includes the two relevant UI tests. The API26, API35/4 KB and primary API35/16 KB lanes also passed their full inventories (API26 permits two OS-limited skips). The independent API35/16 KB repeat failed on `EditorRenderingTest#redrawsCompleteWhileNewInvalidationsKeepArriving` after 75 passes, one failed instrumentation process and 194 missing tests. Its cause is still unconfirmed in [#38](https://github.com/cheshmakzanoon-ops/Experiment-2/issues/38), distinct from the intermittent API36 Compose signatures in [#39](https://github.com/cheshmakzanoon-ops/Experiment-2/issues/39).

## First A/B run: latest BOM cannot be compared on this toolchain

[Run 41158107909](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/runs/41158107909) on commit `02e9341` tested the original `2024.09.03` source and disposable-checkout candidate `2026.09.00` against the same two API36 emulators.

- Both `2026.09.00` jobs **failed before emulator startup** at `:app:checkDebugAarMetadata`. Gradle reported **22 AAR metadata violations**: Compose UI/runtime version `1.12.1` libraries require `compileSdk 37+` and Android Gradle Plugin `9.1.0+`, whereas production source uses `compileSdk 36` and AGP `8.10.1`. This is a toolchain compatibility result, not a test of the two Compose UI crash signatures.
- The original BOM's API36/16 KB job passed **all eight complete focused invocations**, each with the three exact test identities (including actual process page-size assertion), zero failures and no recovery. This is within **one emulator boot**, not eight independent fresh VM samples.
- The original BOM's Pixel C tablet job likewise passed **all eight complete focused invocations**, each with the same three exact identities, no failures and no framework recovery. Each arm ran on one fresh hosted emulator boot, so eight invocations are not eight independent boots.
### Preserved run-1 evidence archives

GitHub reports the following **uploaded ZIP SHA-256 digests**; these are artifact-archive hashes, not individual APK hashes. Candidate artifacts only contain preflight/build provenance because emulator startup never occurred.

| Arm | Artifact | GitHub ZIP SHA-256 |
| --- | --- | --- |
| Original BOM, 16 KB | [13026840068](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/runs/41158107909/artifacts/13026840068) | `4af36e80116724b92df9af683a4ce69372dd423aca6db981d3d2ae0f3853e54d` |
| Original BOM, tablet | [13027030167](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/runs/41158107909/artifacts/13027030167) | `a0af346d4cec46de228e97a0831accb8409e81ff2611bea7b4e001ab09a2b439` |
| `2026.09.00` candidate, 16 KB | [13026152753](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/runs/41158107909/artifacts/13026152753) | `b19551e51e991042529a6e6b4f21d3cab1edecbc00859b43c0660ddad064767b` |
| `2026.09.00` candidate, tablet | [13025433965](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/runs/41158107909/artifacts/13025433965) | `31f8a9895535e9228fe39edddd467b72a2663404d050326cc8836e7ae2303f37` |

The original-BOM artifacts contain all eight result inventories, test evidence, logcats and APK provenance; the archives are scheduled to expire after 14 days.

**Decision:** keep production Gradle/SDK/Compose versions unchanged. The next diagnostic should compare `2024.09.03` with an explicitly toolchain-compatible intermediate, initially `2025.05.01` (Compose core `1.8.2` series), while retaining the same two emulators, exact test filter, per-invocation evidence and strict verification. This tests library modernization without simultaneously changing AGP or the Android SDK. A passing focused test alone cannot close [issue #39](https://github.com/cheshmakzanoon-ops/Experiment-2/issues/39), and it cannot substitute for the required 270-case matrix.

The workflow can be manually dispatched with a candidate BOM input after the update. Only the disposable Actions checkout is modified for the candidate build; there is no local Android emulator.
