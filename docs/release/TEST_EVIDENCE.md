# Test & Build Verification Evidence — ArtFlow Studio (Experiment-2)

## Baseline `main` Reference (`d340bc6f777e9444a07df97760aa4ae290c7c7c1`)

- GitHub Actions Run [`43189321650`](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/runs/43189321650) on `d340bc6`: **7/8 jobs passed**; `Device tests (API 35 independent 16 KB repeat)` failed Attempt 1 (`system_server` died / `StudioControlsTest.pageStripSelectsAddsAndProtectsTheLastPage`) and Attempt 2 (`118 passed, 1 failed` in `ReferenceEditorUiTest.referenceAndFocusExitRemainReachableWithEitherTouchTargetSize`, `MarkCompact::ScanObject<true>` SIGSEGV in SystemUI & ArtFlow, plus Python 3.10 nanosecond timestamp `ValueError` in `collect_native_tombstones.py`).
- Historical GitHub Actions Run [`43176819261`](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/runs/43176819261) on `541a6c1`: **8/8 jobs passed** (belongs to preceding commit `541a6c1`, not `d340bc6`).

## Current Branch Local Verification (`fix/production-readiness-certification`)

| Check / Suite | Command | Result | Verified Metrics / Notes |
| --- | --- | :---: | --- |
| Python `.github/scripts` suite | `python3 -m unittest discover -s .github/scripts -p 'test_*.py'` | **PASS** | `166` tests ran in `24.040s`, `0` failures |
| Python `tools` suite | `python3 -m unittest discover -s tools -p 'test_*.py'` | **PASS** | `76` tests ran in `2.885s`, `0` failures |
| Workflow SHA-pin audit | `python3 .github/scripts/verify_workflow_pins.py .github/workflows` | **PASS** | All third-party `uses:` across `14` `.github/workflows/*.yml` files pinned to 40-char commit SHAs |
| Secret & keystore hygiene scan | `python3 .github/scripts/scan_secrets.py .` | **PASS** | Zero secrets or tracked keystore artifacts detected |
| Gradle license check & CycloneDX SBOM | `python3 .github/scripts/check_licenses_and_sbom.py . --revision "$(git rev-parse HEAD)" --sbom-out /tmp/artflow-sbom.cdx.json` | **PASS** | `42` Gradle components verified with zero GPL/AGPL-conflict or unknown licenses |
| JVM unit tests | `./gradlew testDebugUnitTest --offline --stacktrace` | **PASS** | `284` suites, `1,706` tests, `2` skipped (pre-existing external-corpora assumptions: `RealProcreateFilesTest`, `ReferenceBrushCorpusTest`), `0` failures, `0` errors |
| Kotlin formatting (`ktlint`) | `./gradlew ktlintCheck --offline` | **PASS** | `0` violations across `main`, `test`, `androidTest`, and Kotlin scripts |
| Static analysis (`detekt`) | `./gradlew detekt --offline` | **PASS** | `0` new issues; `config/detekt/baseline.xml` shrunk from `82` -> `67` `<ID>` entries |
| Android Lint (Debug & Release) | `./gradlew lintDebug lintRelease --offline` | **PASS** | `0 Fatal`, `0 Error` |
| Instrumentation compile & inventory | `./gradlew compileDebugAndroidTestKotlin --offline` | **PASS** | `270` `@Test` identities declared and compiled cleanly |
| Release signing negative guard | `! ./gradlew help -PrequireReleaseSigning=true --offline` | **PASS** | Fails closed at `app/build.gradle.kts:49` without private `keystore.properties` |

## Exact-SHA Multi-Run CI Certification Log

*(Populated in PR & final release report once `fix/production-readiness-certification` is pushed and exact-SHA `Android CI` runs complete.)*
