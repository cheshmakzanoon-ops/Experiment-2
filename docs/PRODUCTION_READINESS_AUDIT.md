# Production Readiness Audit & Architectural Report: ArtFlow Studio (Experiment-2)

**Document Version:** 2.0.0
**Audit Date:** November 12, 2026
**Baseline `main` SHA:** `d340bc6f777e9444a07df97760aa4ae290c7c7c1`
**Hardening Branch:** `fix/production-readiness-certification`
**Repository:** `https://github.com/cheshmakzanoon-ops/Experiment-2`

---

## 1. Executive Summary & Release Decision

ArtFlow Studio is a feature-rich offline digital art editor with genuine engineering assets (Hilt/Room persistence, authoritative recovery guards, atomic project writes, immutable raster generations, and comprehensive emulator matrix tooling). However, the `d340bc6` `main` baseline was **NO-GO for unconditional public production release** due to CI failure on Android 15 / 16 KB, non-atomic font overwrites, missing backup/package dependencies, misleading graphics/documentation claims, unchecked integer arithmetic in binary codecs, unpinned workflow action tags, and unfulfilled publisher/physical-device requirements.

On branch `fix/production-readiness-certification`, all repository-fixable P0, P1, and P2 engineering findings have been remediated and verified locally. **Final public production launch remains gated on external publisher deliverables (`keystore.properties` private upload signing, Play Console declarations & public privacy URL, and physical Android 8/15/16 + stylus + 16 KB hardware verification).**

| Qualification Area | Baseline (`d340bc6`) | Hardened State | Verification Basis |
| :--- | :--- | :--- | :--- |
| **CI Tombstone Tooling & Runtime** | Failed on `ubuntu-22.04` Python 3.10 nanosecond timestamps | **Fixed** | `collect_native_tombstones.py` updated + `setup-python` 3.11 in `device-tests`; exact-SHA multi-run CI check required after push |
| **Data Safety & Recovery** | Authoritative recovery & Room/filesystem guards present | **Verified** | `AuthoritativeRecoveryTest`, `PersistenceRegressionTest`, `StorageFileTreeTest` |
| **Font Import & Portability** | Direct overwrite deleted prior font on validation failure; `file:` absolute paths | **Fixed** | Atomic temp staging (`ATOMIC_MOVE`), non-destructive suffix rename, `font:<filename>` IDs, UI warning (`FontLibraryTest`) |
| **Backup & `.artflow` Packages** | `fonts/` & `grains/` excluded from backup rules; RAM-buffered ZIP; missing grain/font deps | **Fixed** | `backup_rules.xml` & `data_extraction_rules.xml` updated; streaming temp/`ZipFile` packages with `dependencies.json` font metadata only + custom `.grain` bundle |
| **Graphics Architecture Honesty** | False OpenGL ES 3.x / NDK claims; `SOFTWARE` mapped to `GLES3` | **Reconciled** | `RenderBackendType.GLES2` (GLES 2.0 `OpenGLCanvasRenderer` + CPU `VirtualTileCanvas`), `SoftwareRenderBackend`, experimental `VulkanRenderBackend`, unwired `jni` note |
| **Color Management Honesty** | Accurate analytical model in code; earlier docs overstated | **Reconciled** | Analytical ICC-referenced UCR/GCR/TAC model (`IccColorEngine`); zero external LittleCMS bindings claimed |
| **Binary Codec Safety** | Multiple `Int` arithmetic overflows, forged trailer allocations, infinite loops | **Hardened** | Bounds/overflow/cycle guards across 9 codecs + `CodecHardeningTest` |
| **Static Analysis (`detekt`)** | 82 suppressed entries in `config/detekt/baseline.xml` | **Reduced (`82 -> 67`)** | `ktlintCheck` PASS; `detekt` PASS with 0 new baseline items (`67` entries) |
| **Workflow & Supply Chain** | Mutable tag pins (`@v4`/`@v5`/`@v3`/`@v2`), no secret/license/SBOM CI gates | **Hardened** | All 14 workflows SHA-pinned; `scan_secrets.py`, `check_licenses_and_sbom.py` (42 components, CycloneDX 1.5), wired into `android-ci.yml` |
| **Publisher Signing & Physical Devices** | No private keystore, no Play Console submission, no physical device test logs | **OPEN (External)** | `-PrequireReleaseSigning=true` fails closed as required; physical hardware & Play Console remain publisher-owned |

---

## 2. Priority Zero: CI Determinism & External Release Gates

### 2.1 Tombstone Timestamp Parsing & Python 3.11 Pin
- Run `43189321650` Attempt 2 on `d340bc6` hit two separate issues in `Device tests (API 35 independent 16 KB repeat)`:
  1. `MarkCompact::ScanObject<true>` SIGSEGV / `system_server` crash under `google_apis_ps16k`.
  2. `collect_native_tombstones.py` raised `ValueError` in `datetime.fromisoformat()` when parsing `.9` fractional nanoseconds under `ubuntu-22.04`'s default Python `3.10`.
- **Remediation**: Updated `.github/scripts/collect_native_tombstones.py` (`parse_tombstone_timestamp`) with Python 3.10+ nanosecond truncation, added regression coverage in `.github/scripts/test_collect_native_tombstones.py`, and pinned `actions/setup-python` (`3.11`) inside `device-tests` in `.github/workflows/android-ci.yml`.
- **Certification Criteria**: After pushing the frozen certification SHA, `Android CI` must achieve at least **3 consecutive green `API 35` / `16 KB` runs** and **2 consecutive all-green 8-job runs** on that exact commit SHA with all **270** `@Test` identities accounted for, zero unexpected skips, `16384` runtime page size verified, and minified launch verified.

### 2.2 External Publisher & Hardware Gates (Open)
- `app/build.gradle.kts` (`requireReleaseSigning`) intentionally blocks release signing when git-ignored `keystore.properties` is absent.
- Physical Android 8 / 15 / 16 phones, tablet + stylus latency testing, 16 KB hardware verification, in-place upgrade from a prior signed build, Play Console Data Safety/listing declarations, and a hosted public privacy-policy URL remain **OPEN** external requirements documented in `docs/RELEASE_READINESS.md` and `docs/release/RELEASE_SIGNOFF.md`.

---

## 3. Priority One: Font Safety, Backup Coverage & Portable Packaging

### 3.1 Atomic Font Validation & Collision Protection (`FontLibrary.kt`)
- Previously, `FontLibrary.import` wrote untrusted font bytes directly to `filesDir/fonts/<filename>` and called `target.delete()` on validation failure, deleting any pre-existing valid font of the same name.
- `FontLibrary` now validates font streams in an isolated temporary file via `Typeface.createFromFile`, rejects symlinks (`StorageFileTree`), generates a non-destructive unique name (`Name (2).ttf`) on collision, and publishes atomically (`StandardCopyOption.ATOMIC_MOVE`).
- Imported fonts are addressed using portable `font:<filename>` IDs rather than device-specific `file:/...` paths while retaining fallback resolution for legacy projects. Missing imported fonts surface a non-destructive warning in the canvas UI.

### 3.2 Backup & Portable Streaming `.artflow` Packages
- `backup_rules.xml` and `data_extraction_rules.xml` now include `filesDir/fonts/` and `filesDir/grains/` alongside Room DBs and `filesDir/projects/`. `docs/privacy-policy.md` and `app/src/main/res/values/privacy.xml` disclose the ~25 MB Android cloud backup quota versus full device-to-device transfer.
- `ArtflowPackage` now supports temporary-file + `ZipFile` streaming (`writeToFile`, `openFile`), eliminating full-archive heap buffering. Exported packages include referenced custom `.grain` assets and `dependencies.json` recording font metadata (`id`, `label`) **without copying or redistributing third-party font files**. On import, `ProjectStorage` and `ProjectRepositoryImpl` restore missing custom grains and report missing font IDs back to the gallery/canvas UI.

---

## 4. Priority One: Graphics Architecture Reconciliation

Tracing the shipping rendering stack established the actual runtime behavior:
- **Canvas Presentation**: `ArtFlowCanvasView` configures `setEGLContextClientVersion(2)` and delegates surface presentation to `OpenGLCanvasRenderer` using `GLES20` shaders and textures.
- **Layer Compositing**: `VirtualTileCanvas` allocates $128 \times 128$ / $256 \times 256$ tiles within `8,192 x 8,192` ($\le 40$ MP) bounds and composites layers on CPU via `Compositor`.
- **Factory & Backends**: `RenderBackendType.GLES3` was replaced by `RenderBackendType.GLES2` (`GlesRenderBackend`), `RenderBackendFactory.createBackend(RenderBackendType.SOFTWARE)` now returns `SoftwareRenderBackend`, and `VulkanRenderBackend` accurately documents its experimental CPU-composited preview status.
- **Unwired C++20 Prototype**: `app/src/main/jni` contains C++20 sources not wired into `app/build.gradle.kts`. Artifact inspection confirms only `libandroidx.graphics.path.so` is packaged and 16 KB ELF aligned.

---

## 5. Priority One: Static Analysis & Baseline Reduction

- Zero new issues added to `config/detekt/baseline.xml`.
- Resolved and removed **15** baseline/stale issues across `CanvasScreen.kt`, `EditorSheets.kt`, `OpenGLCanvasRenderer.kt`, `LiquifyTool.kt`, `PixelBrushes.kt`, `PsdCodec.kt`, and `ArtworkExporter.kt`, shrinking `config/detekt/baseline.xml` from **82** entries to **67** entries while `./gradlew ktlintCheck detekt` passes with 0 reported issues.

---

## 6. Priority Two: Codec Hardening & Supply-Chain Security

- **Codec Hardening**: Guarded `ApngEncoder`, `Lz4`, `Lzo1x`, `AbrReader`, `BinaryPlist`/`KeyedArchive`, `GltfReader`, `ModelPackage`, `UsdReader`, and `PsdCodec` against integer overflows, negative lengths/counts, forged bplist trailers, directory-only ZIP entries, and cyclic LZ4/USD loops; verified with deterministic unit tests in `CodecHardeningTest.kt`.
- **Supply-Chain & CI Pinning**:
  - All `uses:` references across all **14** workflows under `.github/workflows/` are pinned to 40-char commit SHAs and checked in CI via `.github/scripts/verify_workflow_pins.py`.
  - `.github/scripts/scan_secrets.py` scans tracked files for credentials/keystores and CI keystore files.
  - `.github/scripts/check_licenses_and_sbom.py` audits 42 resolved Gradle dependency components for GPLv3 compatibility and emits a CycloneDX 1.5 JSON SBOM uploaded as a build artifact.

---

## 7. Local Verification Summary

| Suite / Check | Result | Measured Details |
| --- | :---: | :--- |
| JVM Unit Tests (`./gradlew testDebugUnitTest`) | **PASS** | `1,706` tests across `284` suites (`2` corpora-gated tests skipped locally, execute in CI; `0` failures, `0` errors) |
| Formatting (`./gradlew ktlintCheck`) | **PASS** | `0` violations across `main`, `test`, `androidTest`, and Gradle scripts |
| Static Analysis (`./gradlew detekt`) | **PASS** | `0` new issues; baseline reduced `82 -> 67` `<ID>` entries |
| Android Lint (`./gradlew lintDebug lintRelease`) | **PASS** | `0 Fatal`, `0 Error` |
| Instrumentation Compile (`./gradlew compileDebugAndroidTestKotlin`) | **PASS** | All `270` `@Test` identities compiled cleanly |
| Python & Security Scripts | **PASS** | `164` `.github/scripts` tests + `76` `tools` tests; `verify_workflow_pins.py` (14 files); `scan_secrets.py`; `check_licenses_and_sbom.py` (42 components) |
| Release Signing Negative Guard | **PASS** | `./gradlew help -PrequireReleaseSigning=true` fails closed without private keystore |
