# Formal Release Sign-Off & Go/No-Go Record — ArtFlow Studio (`Experiment-2`)

- **Audit Baseline (`main`)**: `d340bc6f777e9444a07df97760aa4ae290c7c7c1`
- **Hardening Branch**: `fix/production-readiness-certification`
- **Current Decision for Unconditional Public Production Rollout**: **NO-GO** (until Publisher & Physical-Device gates below are completed by the release owner).
- **Engineering Readiness Decision**: **READY FOR FINAL EXACT-SHA CI CERTIFICATION** upon pushing `fix/production-readiness-certification`.

---

## 1. Engineering P0 / P1 / P2 Gate Disposition

| Scope | Status | Summary |
| --- | :---: | --- |
| P0: Tombstone timestamp fix & Python 3.11 runtime | **DONE** | Fixed `collect_native_tombstones.py` + `test_collect_native_tombstones.py` + Python `3.11` setup in `device-tests`. |
| P0: Multi-run exact-SHA `Android CI` qualification | **PENDING PUSH** | Requires `>= 3` consecutive green API 35 / 16 KB runs and `>= 2` consecutive all-green 8-job runs on final pushed commit SHA. |
| P0: Unsigned AAB verification & fail-closed signing guard | **DONE** | Verified `-PrequireReleaseSigning=true` fails closed; `verify_android_artifacts.py` confirms only `libandroidx.graphics.path.so` packaged with 16 KB ELF alignment. |
| P1: FontLibrary atomic staging & portable IDs | **DONE** | Isolated temp validation, non-destructive suffix collision handling, `font:<filename>` IDs, legacy `file:` fallback, UI warning on missing font. |
| P1: Backup rules & portable streaming `.artflow` packages | **DONE** | `fonts/` & `grains/` added to XML backup/extraction rules + quota disclosure; streaming `ZipFile` packages with `dependencies.json` font metadata only + custom `.grain` bundle/restore. |
| P1: Graphics backend reconciliation | **DONE** | `RenderBackendType.GLES2` (`OpenGLCanvasRenderer` GLES 2.0 presentation + CPU `VirtualTileCanvas`), `SoftwareRenderBackend`, experimental `VulkanRenderBackend`, unwired `jni/` prototype note. |
| P1: Detekt baseline reduction & honest documentation | **DONE** | `config/detekt/baseline.xml` reduced `82 -> 67` with 0 new suppressions; `README.md`, `PRODUCTION_READINESS_AUDIT.md`, `RELEASE_READINESS.md`, and `docs/release/*.md` reconciled. |
| P2: Binary codec overflow/bounds/fuzz hardening | **DONE** | Hardened 9 codecs (`ApngEncoder`, `Lz4`, `Lzo1x`, `AbrReader`, `KeyedArchive`/`BinaryPlist`, `GltfReader`, `ModelPackage`, `UsdReader`, `PsdCodec`) + `CodecHardeningTest.kt`. |
| P2: Workflow SHA pinning & supply-chain security | **DONE** | All 14 `.github/workflows/*.yml` pinned to 40-char commit SHAs; added `verify_workflow_pins.py`, `scan_secrets.py`, `check_licenses_and_sbom.py` (42 components, CycloneDX 1.5) & tests in `android-ci.yml`. |

---

## 2. Open External Blockers (Publisher & Physical Hardware Owner Required)

The repository cannot fabricate, waive, or execute the following external requirements:

1. **Private Upload Keystore & Signed Production AAB**: Supply git-ignored `keystore.properties` and private upload keystore, build `./gradlew bundleRelease -PrequireReleaseSigning=true`, verify signature (`apksigner` / `jarsigner`), confirm `applicationId = "com.artflow.studio"` (`versionCode` increment if updating an existing store listing), and record the SHA-256 digest of the signed AAB.
2. **Physical Device Campaign**: Execute the signed, minified release/benchmark APK on representative hardware:
   - Android 8 (API 26) low-memory handset
   - Android 15 (API 35) & Android 16 (API 36) phones
   - Large-screen tablet + pressure/tilt active stylus (latency, palm rejection, hover, orientation changes)
   - Physical ARM64 **16 KB page-size** hardware (checking native library load, storage exhaustion, denied/revoked SAF URIs, process death/relaunch, and upgrade over an already-installed prior version with existing artworks/custom fonts/custom grains).
3. **Google Play Console Declarations & Store Compliance**:
   - Publish a live public privacy policy URL matching [`docs/privacy-policy.md`](../privacy-policy.md) and in-app Settings text.
   - Complete Data Safety form, Content Rating (IARC), Target Audience, Ads declaration, App Access instructions, publisher contact/identity verification, and review Pre-Launch Report after internal/closed track upload before staged production rollout.
