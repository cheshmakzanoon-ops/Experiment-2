# ArtFlow — Digital Art Studio for Android

[![Android CI](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/workflows/android-ci.yml/badge.svg?branch=main)](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/workflows/android-ci.yml)
[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)

An offline painting and illustration studio built with Kotlin, Jetpack Compose, an **OpenGL ES 2.0** presentation surface ([`OpenGLCanvasRenderer`](app/src/main/java/com/artflow/studio/data/renderer/opengl/OpenGLCanvasRenderer.kt)), and **CPU virtual tile compositing** ([`VirtualTileCanvas`](app/src/main/java/com/artflow/studio/data/renderer/gpu/VirtualTileCanvas.kt)). ArtFlow provides pressure-sensitive brushes with wet-mix dynamics, 3D PBR surface painting, non-destructive vector typography, recovery state protection, analytical ICC-referenced CMYK separation, and `.procreate` / `.psd` / `.psb` / streaming `.artflow` interchange.

> **Release Qualification & Honest Scope Notice**:
> - **Historical vs Baseline `main`**: Preceding `main` commit `541a6c1` passed 8/8 jobs in historical Run [43176819261](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/runs/43176819261), while baseline `main` commit `d340bc6` failed 1/8 jobs (`Device tests (API 35 independent 16 KB repeat)`) in Run [43189321650](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/runs/43189321650).
> - **Production Sign-Off Decision**: **NO-GO for unconditional public Play production rollout** from repository artifacts alone. Engineering CI, data safety, streaming packaging, codec hardening, and supply-chain checks are verified in repository/CI gates, while **private publisher upload signing (`keystore.properties`), physical Android 8/15/16 + stylus + 16 KB hardware campaigns, upgrade-from-prior-build testing, and Play Console Data Safety/privacy URL/store compliance** remain **OPEN** external publisher deliverables.
> - See [`docs/PRODUCTION_READINESS_AUDIT.md`](docs/PRODUCTION_READINESS_AUDIT.md), [`docs/RELEASE_READINESS.md`](docs/RELEASE_READINESS.md), and [`docs/release/RELEASE_SIGNOFF.md`](docs/release/RELEASE_SIGNOFF.md).

---

## Key Features & Architecture

### 1. Recovery & Storage Integrity
- **Authoritative Recovery State Machine**: Decouples UI dialog visibility from authoritative snapshot state ([`CanvasRecoveryController`](app/src/main/java/com/artflow/studio/presentation/ui/viewmodel/CanvasRecoveryController.kt) & [`ProjectStorage`](app/src/main/java/com/artflow/studio/data/local/ProjectStorage.kt)). Dismissing a recovery prompt never authorizes overwriting recovery data.
- **Repository-Level Recovery Guard**: `unresolvedRecoveryProjects` in [`CanvasRepositoryImpl`](app/src/main/java/com/artflow/studio/data/repository/canvas/CanvasRepositoryImpl.kt) blocks periodic autosaves, background saves, and manual saves synchronously in memory when unresolved crash snapshots exist.
- **Atomic Document Persistence & Non-Symlink Storage**: Internal documents (`.artflow` storage format up to `CURRENT_VERSION = 7`) and immutable layer raster generations are staged and moved atomically with SHA-256 verification and `NOFOLLOW_LINKS` path guards ([`StorageFileTree`](app/src/main/java/com/artflow/studio/data/local/StorageFileTree.kt)).
- **Atomic Font Staging & Portable Font IDs**: [`FontLibrary`](app/src/main/java/com/artflow/studio/data/local/FontLibrary.kt) validates imported `.ttf`/`.otf` streams in isolated staging files before atomic promotion with non-destructive suffix collision handling (`Name (2).ttf`), preventing invalid uploads from deleting existing fonts. Fonts use portable `font:<filename>` IDs (with backward compatibility for legacy `file:` paths) and expose in-editor missing-font warnings.
- **Backup & Portable Streaming `.artflow` Packages**: [`backup_rules.xml`](app/src/main/res/xml/backup_rules.xml) and [`data_extraction_rules.xml`](app/src/main/res/xml/data_extraction_rules.xml) cover `filesDir/fonts/` and `filesDir/grains/` alongside projects and Room databases (~25 MB Android Auto Backup cloud quota vs unlimited local device-to-device transfer documented in [`privacy-policy.md`](docs/privacy-policy.md)). [`ArtflowPackage`](app/src/main/java/com/artflow/studio/data/export/ArtflowPackage.kt) streams packages via temporary files and `ZipFile`, bundling referenced custom `.grain` files and `dependencies.json` font metadata (`id`/`label`) **without redistributing third-party font binaries**.

### 2. Graphics Presentation & Virtual Tile Canvas
- **OpenGL ES 2.0 Display + CPU Tile Compositing**: [`ArtFlowCanvasView`](app/src/main/java/com/artflow/studio/presentation/ui/components/canvas/ArtFlowCanvasView.kt) configures `setEGLContextClientVersion(2)` and presents canvas textures through [`OpenGLCanvasRenderer`](app/src/main/java/com/artflow/studio/data/renderer/opengl/OpenGLCanvasRenderer.kt) (`GLES20`), while [`VirtualTileCanvas`](app/src/main/java/com/artflow/studio/data/renderer/gpu/VirtualTileCanvas.kt) and [`Compositor`](app/src/main/java/com/artflow/studio/core/pixels/Compositor.kt) composite $128 \times 128$ / $256 \times 256$ copy-on-write tiles on CPU within an 8,192 $\times$ 8,192 (40 MP) ceiling.
- **Honest Backend Abstraction**: [`RenderBackendFactory`](app/src/main/java/com/artflow/studio/data/renderer/gpu/RenderBackendFactory.kt) defaults to `RenderBackendType.GLES2` (`GlesRenderBackend`), returns [`SoftwareRenderBackend`](app/src/main/java/com/artflow/studio/data/renderer/gpu/SoftwareRenderBackend.kt) for `SOFTWARE`, and labels [`VulkanRenderBackend`](app/src/main/java/com/artflow/studio/data/renderer/gpu/VulkanRenderBackend.kt) accurately as an experimental CPU-composited preview path.
- **Unwired Native C++ Prototype**: `app/src/main/jni` retains an unintegrated C++20 NDK prototype (`CMakeLists.txt` is not wired in `app/build.gradle.kts`). The only native shared object packaged in debug APK / release AAB artifacts is `libandroidx.graphics.path.so`, verified ELF 16 KB page-size aligned across all ABIs by [`verify_android_artifacts.py`](.github/scripts/verify_android_artifacts.py).

### 3. Hardened Import/Export & Binary Codecs
- **Bounded Stream Reading & ZIP Hardening**: [`GalleryImports`](app/src/main/java/com/artflow/studio/presentation/ui/screens/gallery/GalleryImports.kt), [`ProcreateImport`](app/src/main/java/com/artflow/studio/data/export/ProcreateImport.kt), [`ArtflowPackage`](app/src/main/java/com/artflow/studio/data/export/ArtflowPackage.kt), and [`ModelPackage`](app/src/main/java/com/artflow/studio/core/three/ModelPackage.kt) enforce byte, entry, and path traversal bounds.
- **Codec Bounds & Overflow Guards**: Hardened `ApngEncoder`, `GltfReader`, `AbrReader`, `KeyedArchive`/`BinaryPlist`, `UsdReader`, `Lz4`, `Lzo1x`, `PsdCodec` (including PSB 64-bit section/channel lengths and 32-bit RLE row tables), and `ModelPackage` against negative sizes, integer overflows, forged trailer counts, and cyclic path/chunk loops with deterministic fuzz and malformed-input unit tests in [`CodecHardeningTest`](app/src/test/java/com/artflow/studio/core/export/CodecHardeningTest.kt).

### 4. Brush Engine, Analytical ICC Color, 3D PBR & Editing
- **Wet Mix & Dual Brush**: 6-parameter wet-mix dynamics (`WetBrushPickup`), `COMBINE` / `INTENSE_BLEND` / `DISPLACE` dual-brush modes, and 35 auxiliary pressure/speed/tilt curves ([`BrushParams`](app/src/main/java/com/artflow/studio/domain/model/brush/BrushParams.kt)).
- **Analytical ICC-Referenced CMYK Separation**: `CmykBuffer` and analytical `IccColorEngine` supporting FOGRA39, SWOP v2, Japan Color 2001, GRACoL 2006, Display P3, and sRGB profiles with soft-proofing and out-of-gamut overlay (analytical model, not LittleCMS native bindings).
- **3D PBR Surface Painting**: `PbrMaterialAtlas` (BaseColor, Metallic, Roughness) with Cook-Torrance specular shader (`ModelRenderer`) and OBJ/glTF/USDZ material import/export.
- **Typography, Selections & Liquify**: Non-destructive vector [`TextLayerContent`](app/src/main/java/com/artflow/studio/core/text/TextLayerContent.kt), multi-layer linking (`LayerBatchController`), Liquify with freeze/thaw masks, and hardware touch-major palm rejection (`PalmRejection`).

---

## Capability Matrix

| Subsystem | Verified Implementation |
| --- | --- |
| **Data Safety & Backup** | Authoritative recovery state machine (`CanvasRecoveryController`); atomic crash-safe storage (`ProjectStorage`); atomic staged font imports (`FontLibrary`); `backup_rules.xml` & `data_extraction_rules.xml` covering `projects/`, `fonts/`, `grains/`, Room DBs; documented 25 MB cloud quota vs D2D transfer. |
| **Canvas & GPU** | OpenGL ES 2.0 `GLSurfaceView` presentation (`OpenGLCanvasRenderer`); CPU `VirtualTileCanvas` compositing ($128 \times 128$ / $256 \times 256$, $\le 40$ MP safe bounds); `GLES2` / `SOFTWARE` / experimental `VULKAN` factory; verified 16 KB ELF alignment for `libandroidx.graphics.path.so`. |
| **File Formats** | Streaming ZIP `.artflow` packages (custom `.grain` bundle + font metadata in `dependencies.json`, zero third-party font binary redistribution); `.procreate` (v5/v6 group blends, animation metadata, guides, LZ4 tiles); `.psd`/`.psb` (8/16-bit, masks, folders, 64-bit PSB lengths, 32-bit RLE row tables); PNG, JPEG, WebP, PDF, GIF, APNG, MP4. |
| **Brush, Color, 3D** | 6-parameter wet-mix; dual-brush modes; 35 auxiliary curves; analytical ICC-referenced CMYK (`FOGRA39`, `SWOP v2`, `Japan Color 2001`, `GRACoL 2006`, `Display P3`, `sRGB`); 3D PBR (`BaseColor`, `Metallic`, `Roughness`) + Cook-Torrance shader. |
| **CI & Supply Chain** | All 14 `.github/workflows/*.yml` pinned to 40-char commit SHAs (`verify_workflow_pins.py`); tracked secret/keystore scan (`scan_secrets.py`); 42-component Gradle license/GPLv3 check & CycloneDX 1.5 SBOM (`check_licenses_and_sbom.py`); Python 3.11 tombstone collection & nanosecond timestamp support. |

---

## Build, Verification & Supply-Chain Commands

### Prerequisites
- **JDK 17**
- **Android SDK Platform 36** (Build-Tools 36.0.0)
- **Gradle 8.14.3** / Android Gradle Plugin 8.10.1

### Local Verification Commands

```bash
# 1. Gradle JVM unit tests (1,706 tests / 284 suites locally; 2 corpora-gated tests execute in CI)
./gradlew testDebugUnitTest --stacktrace

# 2. Formatting, detekt (passes against 67-entry reduced baseline.xml, down from 82), and Android Lint
./gradlew ktlintCheck detekt lintDebug lintRelease --stacktrace

# 3. Offline check helpers
bash tools/offline-check/unit.sh
bash tools/offline-check/lint.sh

# 4. Python test suites & supply-chain security checks
python3 -m unittest discover -s .github/scripts -p 'test_*.py'
python3 -m unittest discover -s tools -p 'test_*.py'
python3 .github/scripts/verify_workflow_pins.py .github/workflows
python3 .github/scripts/scan_secrets.py .
python3 .github/scripts/check_licenses_and_sbom.py . --revision "$(git rev-parse HEAD)"

# 5. Unsigned CI build checks & fail-closed signing guard
./gradlew assembleDebug bundleRelease --stacktrace
python3 .github/scripts/verify_android_artifacts.py app/build/outputs/apk/debug/*.apk app/build/outputs/bundle/release/*.aab
./gradlew bundleRelease -PrequireReleaseSigning=true   # Expected to fail unless keystore.properties is provided
```

---

## Technical Audits & Release Documentation

- [`docs/PRODUCTION_READINESS_AUDIT.md`](docs/PRODUCTION_READINESS_AUDIT.md): Architecture audit, security hardening, and capability reconciliation.
- [`docs/RELEASE_READINESS.md`](docs/RELEASE_READINESS.md): Longitudinal release qualification evidence, historical CI run log, and open publisher gates.
- [`docs/release/READINESS_MATRIX.md`](docs/release/READINESS_MATRIX.md), [`docs/release/RISK_REGISTER.md`](docs/release/RISK_REGISTER.md), [`docs/release/TEST_EVIDENCE.md`](docs/release/TEST_EVIDENCE.md), [`docs/release/RELEASE_SIGNOFF.md`](docs/release/RELEASE_SIGNOFF.md): Gate matrix, risk register, executed evidence, and formal Go/No-Go checklist.

---

## Privacy & License

- **Offline-Only Architecture**: No `android.permission.INTERNET`, analytics, or telemetry SDKs. See [`docs/privacy-policy.md`](docs/privacy-policy.md).
- **License**: GNU General Public License v3.0 ([`LICENSE`](LICENSE)). Third-party Gradle components are checked for GPLv3 compatibility and exported to CycloneDX 1.5 JSON in CI.
