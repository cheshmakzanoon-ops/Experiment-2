# ArtFlow — Digital Art Studio for Android

[![Android CI](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/workflows/android-ci.yml/badge.svg)](https://github.com/cheshmakzanoon-ops/Experiment-2/actions)
[![CI Matrix: 100% Green](https://img.shields.io/badge/CI%20Matrix-8%2F8%20Jobs%20Passed-brightgreen)](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/runs/43176819261)
[![Unit Tests](https://img.shields.io/badge/Unit%20Tests-1%2C688%20Passed-brightgreen)](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/runs/43176819261)
[![Static Analysis](https://img.shields.io/badge/ktlint%20%26%20detekt-0%20Issues-brightgreen)](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/runs/43176819261)
[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)

A high-performance, offline painting and illustration studio built with Kotlin, Jetpack Compose, Android NDK (C++20), and an OpenGL ES 3.x hardware acceleration engine (with an experimental Vulkan preview architecture in development). ArtFlow provides professional-grade digital art workflows: pressure-sensitive brushes with physical wet-mix dynamics, multi-channel 3D PBR surface painting, non-destructive vector typography, an authoritative zero-data-loss recovery state machine, ICC profile-referenced CMYK prepress color management, and bidirectional `.procreate` / `.psd` interchange.

> **Release Qualification Status**: Fully qualified on `main` at commit [`541a6c1`](https://github.com/cheshmakzanoon-ops/Experiment-2/commit/541a6c1). All 8 GitHub Actions CI jobs passed in [Run 43176819261](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/runs/43176819261), executing 1,688 unit tests, static analysis gates, release bundle verification, and the complete 7-lane emulator matrix (API 26, 35, 35 standard 4k, 35 16k repeat, 36, 36 16k, and 36 tablet). Complete engineering root causes and empirical evidence are documented in [Production Readiness Audit](docs/PRODUCTION_READINESS_AUDIT.md) and [Release Readiness](docs/RELEASE_READINESS.md).

---

## Key Features & Architecture

### 1. Priority Zero — Authoritative Recovery & Zero Data Loss
- **Persistent Authoritative Recovery State Machine**: Decouples UI dialog visibility from authoritative snapshot state ([`CanvasRecoveryController`](app/src/main/java/com/artflow/studio/presentation/ui/viewmodel/CanvasRecoveryController.kt) & [`ProjectStorage`](app/src/main/java/com/artflow/studio/data/local/ProjectStorage.kt)). Dismissing a recovery prompt never authorizes destroying recovery data.
- **Repository-Level In-Memory Recovery Set**: Replaced coarse storage checks with `unresolvedRecoveryProjects` in [`CanvasRepositoryImpl`](app/src/main/java/com/artflow/studio/data/repository/canvas/CanvasRepositoryImpl.kt). A project is flagged only when discovering an unrecovered crash autosave on disk.
- **Synchronous Snapshot Capture**: `saveCanvas` and periodic autosaves check recovery status synchronously in memory without coroutine suspension before capturing the immutable layer snapshot, preventing intervening user edits from mutating saving state.
- **Race-Condition & Overwrite Immunity**: Periodic autosaves, background saves, manual saves, and export pipelines strictly guard against overwriting unresolved crash snapshots.
- **Monotonic Timestamp Forward-Stepping**: Injected `ioDispatcher` across `ProjectStorage` and guaranteed monotonic forward timestamp advancement (`file.setLastModified(doc.lastModified() + 1000L)`) to eliminate millisecond timestamp collisions on high-speed filesystems.
- **Atomic Persistence & SHA-256 Integrity**: Document files and metadata are written atomically via temporary staging files with checksum verification.

### 2. OpenGL ES 3.x Production Engine & Virtual Canvas
- **Production-Validated OpenGL ES 3.x Core**: Fully validated hardware acceleration pipeline on physical devices and CI emulators ([`RenderBackendFactory`](app/src/main/java/com/artflow/studio/data/renderer/gpu/RenderBackendFactory.kt) & [`OpenGLCanvasRenderer`](app/src/main/java/com/artflow/studio/data/renderer/opengl/OpenGLCanvasRenderer.kt)).
- **Experimental Vulkan Preview**: Future-facing Vulkan 1.2+ preview backend with CPU tile compositing under active development ([`VulkanRenderBackend`](app/src/main/java/com/artflow/studio/data/renderer/gpu/VulkanRenderBackend.kt)).
- **Bounded Virtual Tile Canvas**: Dynamically allocates $128 \times 128$ and $256 \times 256$ tiles with copy-on-write dirty-region tracking ([`VirtualTileCanvas`](app/src/main/java/com/artflow/studio/data/renderer/gpu/VirtualTileCanvas.kt)), safely enforcing tested memory limits up to 8,192 $\times$ 8,192 (40 MP).
- **16 KB Page-Size Ready**: Native C++20 toolchain configured with `-Wl,-z,max-page-size=16384` for modern Android 15/16 devices, verified on 16 KB emulator targets.

### 3. Hardened Import/Export & Photoshop PSB Interoperability
- **Bounded Streaming Architecture**: Enforced byte and entry bounds via `readAtMost` across all incoming streams (PSD up to 256 MB, native packages up to 512 MB, 3D models up to 64 MB, fonts up to 20 MB, palettes up to 1 MB) to prevent Out-Of-Memory (OOM) and heap exhaustion ([`BoundedRead`](app/src/main/java/com/artflow/studio/core/export/BoundedRead.kt) & [`BoundedImportTest`](app/src/test/java/com/artflow/studio/util/BoundedImportTest.kt)).
- **Decompression Bomb Protection**: Hardened `.procreate` archive ingestion with limits of 512 MB decompressed payload and 10,000 archive entries.
- **Photoshop PSD & PSB Large Document Codec**: Version-aware PSD / PSB (`8BPB`) engine ([`PsdCodec`](app/src/main/java/com/artflow/studio/core/export/PsdCodec.kt)) supporting 64-bit section headers, 64-bit channel record lengths, and 32-bit (4-byte) RLE PackBits row-length tables.
- **Decomposed Architecture**: Monolithic PSD/PSB layer parsing decomposed into 7 single-responsibility functions with cyclomatic complexity $\le 8$.
- **Procreate v5 / v6 Parity**: Native `.procreate` serialization and deserialization ([`ProcreateWriter`](app/src/main/java/com/artflow/studio/core/export/ProcreateWriter.kt) & [`ProcreateReader`](app/src/main/java/com/artflow/studio/core/export/ProcreateReader.kt)) preserving group blends, animation timeline holds/pins, drawing guides, and 8-bit layer masks.

### 4. Valkyrie-Parity Brush Engine & 6-Parameter Wet Mix
- **Physical Wet Mix Simulation**: Complete 6-parameter physical wet-mix dynamics ([`WetBrushPickup`](app/src/main/java/com/artflow/studio/core/render/WetBrushPickup.kt)):
  - **Dilution**: Solvent thinning that dynamically picks up and blends wet canvas paint.
  - **Charge**: Stroke-length exponential paint depletion ($e^{-\text{distance} / \tau}$).
  - **Attack**: Leading-edge paint deposit rate.
  - **Pull**: Smudge drag weight and color pulling power.
  - **Grade**: Texture contrast and abrasive grain bite into the wet substrate.
  - **Wetness / Blur Jitter**: Micro-perturbations on dilution and grain depth.
- **Advanced Dual-Brush Modes**: Combines secondary shapes via `COMBINE`, `INTENSE_BLEND`, and `DISPLACE` equations.
- **Full Auxiliary Curve Consumption**: Dynamic evaluation of 35 auxiliary pressure, speed, and tilt curves ([`BrushParams`](app/src/main/java/com/artflow/studio/domain/model/brush/BrushParams.kt)).

### 5. Profile-Referenced ICC Color Management & Prepress CMYK
- **Native 4-Channel CMYK Buffer**: Prepress print storage ([`CmykBuffer`](app/src/main/java/com/artflow/studio/core/color/CmykBuffer.kt)) holding 8-bit $C, M, Y, K$ data without lossy intermediate RGB truncation.
- **Profile-Referenced Color Engine**: Analytical ICC-referenced engine ([`IccColorEngine`](app/src/main/java/com/artflow/studio/core/color/IccColorEngine.kt)) with calibrated Gray Component Replacement (GCR), Under Color Removal (UCR), and Total Area Coverage (TAC) limiting:
  - Supported profiles: **Coated FOGRA39** (ISO 12647-2, 330% TAC), **U.S. Web Coated (SWOP) v2** (300% TAC), **Japan Color 2001 Coated** (350% TAC), **GRACoL 2006 Coated #1** (320% TAC), **Display P3**, and **sRGB**.
- **Soft-Proofing & Gamut Diagnostics**: Substrate-simulated proofing with real-time diagonal zebra warning patterns highlighting out-of-gamut pixels.

### 6. 3D PBR Multi-Channel Painting & Cook-Torrance BRDF
- **Simultaneous 3-Channel Painting**: Single-dab unified stamping across `BaseColor`, `Metallic`, and `Roughness` texture planes ([`PbrMaterialAtlas`](app/src/main/java/com/artflow/studio/core/three/PbrMaterialAtlas.kt)).
- **Cook-Torrance Microfacet BRDF**: Physically-based rendering shader ([`ModelRenderer`](app/src/main/java/com/artflow/studio/data/renderer/opengl/ModelRenderer.kt)):
  - Trowbridge-Reitz / GGX normal distribution $D(h)$
  - Schlick Fresnel $F(v, h)$ with material $F_0 = \text{mix}(0.04, \text{albedo}, \text{metallic})$
  - Smith geometric shadowing/masking $G(l, v, h)$
  - Split-Sum Image-Based Lighting (IBL) environment reflection approximation with energy conservation ($k_d + k_s \le 1$).

### 7. Non-Destructive Typography, Layer Linking & Input Polish
- **Non-Destructive Vector Typography**: Vector text maintains editable strings, font styling, tracking, kerning, and baseline shift, and supports arbitrary 2D affine transformations (`deltaX`, `deltaY`, `deltaRotation`, `scaleX`, `scaleY`) without raster degradation ([`TextLayerContent`](app/src/main/java/com/artflow/studio/core/text/TextLayerContent.kt)).
- **Multi-Layer Selection & Linking**: Swipe-to-select multiple layers with link/unlink actions ([`LayerBatchController`](app/src/main/java/com/artflow/studio/presentation/ui/viewmodel/LayerBatchController.kt)), enabling synchronized transformation across linked sets.
- **Liquify Freeze & Thaw Masks**: Protects delicate artwork areas against warp/pinch/bloat/edge distortion with dedicated `FREEZE` and `UNFREEZE` brush modes ([`LiquifyTool`](app/src/main/java/com/artflow/studio/core/tool/LiquifyTool.kt)).
- **Hardware Touch-Major Palm Rejection**: Filters broad palm rests using physical touch ellipse axes (`rejectsTouchMajor`) alongside hover proximity windows ([`PalmRejection`](app/src/main/java/com/artflow/studio/core/canvas/PalmRejection.kt)).

---

## Capability Matrix

| Subsystem | Verified Production Capabilities |
| --- | --- |
| **Data Safety** | Authoritative recovery state machine (`CanvasRecoveryController`); repository-level in-memory recovery set (`CanvasRepositoryImpl`); synchronous snapshot check; UI prompt decoupling; protected periodic autosave, backgrounding, and manual saves; atomic crash-consistent storage with monotonic forward timestamp advancement. |
| **Brush Engine** | Valkyrie-parity 6-parameter wet mix (dilution, charge decay, attack, pull, grade, wet blur jitter); dual-brush combination modes (`COMBINE`, `INTENSE_BLEND`, `DISPLACE`); 35 auxiliary curve mappings; live Brush Studio pad. |
| **Canvas & GPU** | Production OpenGL ES 3.x renderer with GLES 3.0 FBO instancing; experimental Vulkan preview backend; bounded virtual tile canvas ($128 \times 128$ / $256 \times 256$, $\le$ 40 MP safe bounds); 16 KB page-size ELF alignment. |
| **Color Engine** | Profile-referenced analytical ICC engine; FOGRA39, SWOP v2, Japan Color 2001, GRACoL 2006, Display P3, sRGB; 4-channel native `CmykBuffer`; GCR/UCR separation; TAC ink limiting; paper soft-proofing; out-of-gamut zebra overlay. |
| **3D Painting** | Multi-channel `PbrMaterialAtlas` (simultaneous BaseColor, Metallic, Roughness painting); Cook-Torrance microfacet specular BRDF shader (GGX, Schlick, Smith); split-sum IBL reflection; OBJ/glTF/USD texture import/export. |
| **Typography** | Non-destructive vector text; live affine transformation matrix; kerning, tracking, baseline shift, leading, and outline styling. |
| **Layers & Input** | Multi-layer linking (`LayerBatchController`); nested groups; clipping masks; independent 8-bit layer masks; Liquify with freeze/thaw masks; touch-major ellipse palm rejection; Kalman/polynomial stroke prediction. |
| **File Formats** | Bidirectional `.procreate` (v5/v6 group blends, animation metadata, drawing guides, LZ4 tiles); `.psd` / `.psb` (8/16-bit, masks, folders, 64-bit PSB lengths, 32-bit RLE row tables, decomposed parser); `.artflow` atomic v6 saves; bounded streaming with `readAtMost`; PNG, JPEG, WebP, PDF, TIFF, GIF, APNG, MP4. |

---

## Project Structure

```
app/src/main/
├── java/com/artflow/studio/
│   ├── core/
│   │   ├── animation/      # Frame sequencing, onion skins, and timeline controllers
│   │   ├── canvas/         # Palm rejection, stroke prediction, and gesture routers
│   │   ├── color/          # ICC color engine, CMYK buffer, palettes, and harmonies
│   │   ├── export/         # .procreate, .psd/.psb, APNG, GIF, and TIFF codecs
│   │   ├── pixels/         # PixelBuffer, channels, image filters, and stamping
│   │   ├── render/         # Valkyrie wet-mix, stroke rasterizer, and dual-brush marks
│   │   ├── symmetry/       # Radial, bilateral, and quadrilateral symmetry engines
│   │   ├── text/           # Non-destructive vector typography and text layout
│   │   ├── three/          # 3D mesh, PBR material atlas, camera, and lighting
│   │   └── tool/           # Liquify with freeze mask, fill bucket, and gradient tools
│   ├── data/
│   │   ├── renderer/gpu/   # OpenGL ES 3.x production renderer & experimental Vulkan preview
│   │   ├── renderer/opengl/# Cook-Torrance PBR model and canvas renderers
│   │   └── repository/     # Atomic project storage, authoritative recovery, undo/redo
│   ├── domain/model/       # Immutable layer, brush, and document domain models
│   └── presentation/ui/    # Jetpack Compose canvas UI, sheets, and ViewModels
└── jni/                    # C++20 CMake configuration (16 KB page-size compatible)
```

---

## Build & Test Verification

### Release Qualification Matrix (GitHub Actions Run [43176819261](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/runs/43176819261))

| CI Job | Status | Duration | Scope & Verification |
| --- | :---: | :---: | --- |
| **Unit tests, static analysis and builds** | **PASSED** | 17m 15s | 1,688 unit tests, `ktlintCheck`, `detekt`, `lintDebug`, `lintRelease`, `assembleDebug`, `bundleRelease`, schema & artifact validation |
| **Device tests (API 26)** | **PASSED** | 14m 12s | Min-SDK compatibility, hardware acceleration fallbacks, legacy storage |
| **Device tests (API 35)** | **PASSED** | 21m 30s | Modern Android 15 runtime, Edge-to-Edge windowing, photo picker integration |
| **Device tests (API 35 standard 4 KB)** | **PASSED** | 14m 27s | Modern 4 KB memory page baseline |
| **Device tests (API 35 independent 16 KB repeat)** | **PASSED** | 36m 06s | ELF 16 KB page-size alignment, memory allocator stress verification |
| **Device tests (API 36)** | **PASSED** | 13m 30s | Android 16 preview platform compatibility |
| **Device tests (API 36 16 KB)** | **PASSED** | 18m 54s | Android 16 with enforced 16 KB page alignment |
| **Device tests (API 36 tablet)** | **PASSED** | 25m 57s | Large-screen multi-windowing, stylus hovering, orientation changes |

### Prerequisites
- **JDK 17**
- **Android SDK Platform 36** (Build-Tools 36.0.0, NDK 27.x)
- **Gradle 8.14.3** with Android Gradle Plugin 8.10.1

### Local Verification Commands

```bash
# 1. Run full offline unit test suite (1,688 tests across 281 classes)
bash tools/offline-check/unit.sh

# 2. Run static analysis gates (ktlint & detekt)
bash tools/offline-check/lint.sh

# 3. Run Gradle test and static analysis gates
./gradlew testDebugUnitTest ktlintCheck detekt lintDebug

# 4. Build debug APK and production App Bundle
./gradlew assembleDebug bundleRelease
```

---

## Technical Audits & Documentation

- [Production Readiness Audit](docs/PRODUCTION_READINESS_AUDIT.md): 10-section comprehensive audit covering recovery state machines, memory limits, bounded streaming, PSB 64-bit parsing, and graphics architecture.
- [Release Readiness & Evidence](docs/RELEASE_READINESS.md): Release qualification criteria, detailed root cause analyses, and test execution evidence.

---

## Privacy & Safety

- **100% Offline & Private**: No network permissions (`android.permission.INTERNET` is not declared or used). No telemetry, tracking, or crash analytics SDKs.
- **Authoritative Recovery**: Artwork recovery state is authoritatively tracked independently of UI prompts, preventing autosaves from destroying recoverable artwork.
- **Atomic Persistence**: Project saves are written atomically with SHA-256 integrity verification to prevent corruption during unexpected shutdowns.
- **Memory-Bounded**: Large canvases, brush marks, and undo/redo stacks operate within fixed memory budgets to guarantee stability on memory-constrained Android devices.

---

## License

GNU General Public License v3.0. See [LICENSE](LICENSE) for details.
