# ArtFlow — Digital Art Studio for Android

A high-performance, offline painting and illustration studio built with Kotlin, Jetpack Compose, Android NDK (C++20), and an OpenGL ES 3.x hardware acceleration engine (with an experimental Vulkan preview architecture in development). ArtFlow provides professional-grade digital art workflows: pressure-sensitive brushes with physical wet-mix dynamics, multi-channel 3D PBR surface painting, non-destructive vector typography, an authoritative zero-data-loss recovery state machine, ICC profile-referenced CMYK prepress color management, and bidirectional `.procreate` / `.psd` interchange.

---

## Key Features & Architecture

### 1. Priority Zero — Authoritative Recovery & Zero Data Loss
- **Persistent Authoritative Recovery State Machine**: Decouples UI dialog visibility from authoritative snapshot state ([`CanvasRecoveryController`](app/src/main/java/com/artflow/studio/presentation/ui/viewmodel/CanvasRecoveryController.kt) & [`ProjectStorage`](app/src/main/java/com/artflow/studio/data/local/ProjectStorage.kt)). Dismissing a recovery prompt never authorizes destroying recovery data.
- **Race-Condition & Overwrite Immunity**: Periodic autosave, background save, manual saving, and export pipelines strictly guard against overwriting unresolved crash snapshots.
- **Atomic Persistence & Crash Consistency**: Document files and metadata are written atomically via temporary staging files with monotonic timestamp guarantees.

### 2. OpenGL ES 3.x Production Engine & Virtual Canvas
- **Production-Validated OpenGL ES 3.x Core**: Validated hardware acceleration pipeline on physical devices and CI emulators ([`RenderBackendFactory`](app/src/main/java/com/artflow/studio/data/renderer/gpu/RenderBackendFactory.kt) & [`OpenGLCanvasRenderer`](app/src/main/java/com/artflow/studio/data/renderer/opengl/OpenGLCanvasRenderer.kt)).
- **Experimental Vulkan Preview**: Future-facing Vulkan 1.2+ preview backend under active development.
- **Bounded Virtual Tile Canvas**: Dynamically allocates $128 \times 128$ and $256 \times 256$ tiles with copy-on-write dirty-region tracking ([`VirtualTileCanvas`](app/src/main/java/com/artflow/studio/data/renderer/gpu/VirtualTileCanvas.kt)), safely enforcing tested memory limits up to 8,192 $\times$ 8,192 (40 MP).
- **16 KB Page-Size Ready**: Native C++20 toolchain configured with `-Wl,-z,max-page-size=16384` for modern Android 15/16 devices.

### 3. Hardened Import/Export & Photoshop PSB Interoperability
- **Bounded Streaming Architecture**: Enforced byte and entry bounds across all incoming streams (PSD, Procreate, ArtFlow packages, brushes, 3D models, fonts, palettes) to prevent Out-Of-Memory (OOM) and decompression-bomb denial of service ([`BoundedRead`](app/src/main/java/com/artflow/studio/core/export/BoundedRead.kt)).
- **Photoshop PSD & PSB Large Document Codec**: Version-aware PSD / PSB (`8BPB`) import engine ([`PsdCodec`](app/src/main/java/com/artflow/studio/core/export/PsdCodec.kt)) supporting 64-bit length fields, 64-bit channel record lengths, and 32-bit (4-byte) RLE PackBits row-length tables.
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
| **Data Safety** | Authoritative recovery state machine; UI prompt decoupling; protected periodic autosave, backgrounding, and manual saves; atomic crash-consistent storage. |
| **Brush Engine** | Valkyrie-parity 6-parameter wet mix (dilution, charge decay, attack, pull, grade, wet blur jitter); dual-brush combination modes (`COMBINE`, `INTENSE_BLEND`, `DISPLACE`); 35 auxiliary curve mappings; live Brush Studio pad. |
| **Canvas & GPU** | Production OpenGL ES 3.x renderer with GLES 3.0 FBO instancing; experimental Vulkan preview; bounded virtual tile canvas ($128 \times 128$ / $256 \times 256$, $\le$ 40 MP safe bounds); 16 KB page-size ELF alignment. |
| **Color Engine** | Profile-referenced analytical ICC engine; FOGRA39, SWOP v2, Japan Color 2001, GRACoL 2006, Display P3, sRGB; 4-channel native `CmykBuffer`; GCR/UCR separation; TAC ink limiting; paper soft-proofing; out-of-gamut zebra overlay. |
| **3D Painting** | Multi-channel `PbrMaterialAtlas` (simultaneous BaseColor, Metallic, Roughness painting); Cook-Torrance microfacet specular BRDF shader (GGX, Schlick, Smith); split-sum IBL reflection; OBJ/glTF/USD texture import/export. |
| **Typography** | Non-destructive vector text; live affine transformation matrix; kerning, tracking, baseline shift, leading, and outline styling. |
| **Layers & Input** | Multi-layer linking (`LayerBatchController`); nested groups; clipping masks; independent 8-bit layer masks; Liquify with freeze/thaw masks; touch-major ellipse palm rejection; Kalman/polynomial stroke prediction. |
| **File Formats** | Bidirectional `.procreate` (v5/v6 group blends, animation metadata, drawing guides, LZ4 tiles); `.psd` / `.psb` (8/16-bit, masks, folders, 64-bit PSB lengths, 32-bit RLE row tables); `.artflow` atomic v6 saves; PNG, JPEG, WebP, PDF, TIFF, GIF, APNG, MP4. |

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

## Privacy & Safety

- **100% Offline & Private**: No network permissions (`android.permission.INTERNET` is not declared or used). No telemetry, tracking, or crash analytics SDKs.
- **Authoritative Recovery**: Artwork recovery state is authoritatively tracked independently of UI prompts, preventing autosaves from destroying recoverable artwork.
- **Atomic Persistence**: Project saves are written atomically with SHA-256 integrity verification to prevent corruption during unexpected shutdowns.
- **Memory-Bounded**: Large canvases, brush marks, and undo/redo stacks operate within fixed memory budgets to guarantee stability on memory-constrained Android devices.

---

## License

GNU General Public License v3.0. See [LICENSE](LICENSE) for details.
