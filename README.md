# ArtFlow — Digital Art Studio for Android

A high-performance, offline painting and illustration studio built with Kotlin, Jetpack Compose, Android NDK (C++20), and a dual-backend GPU acceleration engine. ArtFlow provides professional-grade digital art workflows: pressure-sensitive brushes with physical wet-mix dynamics, multi-channel 3D PBR surface painting, non-destructive vector typography, certified ICC CMYK prepress color management, and bidirectional `.procreate` / `.psd` interchange.

---

## Key Features & Architecture

### 1. Dual-Backend GPU Core & Virtual Canvas
- **Vulkan 1.2+ Primary with GLES 3.x Fallback**: High-throughput Vulkan compute and graphics pipeline on physical hardware for sub-millisecond stroke latency; automatic fallback to OpenGL ES 3.1/3.0 FBO rendering for CI emulators and legacy devices ([`RenderBackendFactory`](app/src/main/java/com/artflow/studio/data/renderer/gpu/RenderBackendFactory.kt)).
- **Sparse Virtual Tile Canvas**: Dynamically allocates $128 \times 128$ and $256 \times 256$ tiles with copy-on-write dirty-region tracking ([`VirtualTileCanvas`](app/src/main/java/com/artflow/studio/data/renderer/gpu/VirtualTileCanvas.kt)), enabling massive canvas dimensions without allocating empty memory.
- **16 KB Page-Size Ready**: Native C++20 toolchain configured with `-Wl,-z,max-page-size=16384` for modern Android 15/16 devices.

### 2. Valkyrie-Parity Brush Engine & 6-Parameter Wet Mix
- **Physical Wet Mix Simulation**: Complete 6-parameter physical wet-mix dynamics ([`WetBrushPickup`](app/src/main/java/com/artflow/studio/core/render/WetBrushPickup.kt)):
  - **Dilution**: Solvent thinning that dynamically picks up and blends wet canvas paint.
  - **Charge**: Stroke-length exponential paint depletion ($e^{-\text{distance} / \tau}$).
  - **Attack**: Leading-edge paint deposit rate.
  - **Pull**: Smudge drag weight and color pulling power.
  - **Grade**: Texture contrast and abrasive grain bite into the wet substrate.
  - **Wetness / Blur Jitter**: Micro-perturbations on dilution and grain depth.
- **Advanced Dual-Brush Modes**: Combines secondary shapes via `COMBINE`, `INTENSE_BLEND`, and `DISPLACE` equations.
- **Full Auxiliary Curve Consumption**: Dynamic evaluation of 35 auxiliary pressure, speed, and tilt curves ([`BrushParams`](app/src/main/java/com/artflow/studio/domain/model/brush/BrushParams.kt)).

### 3. LittleCMS ICC Color Management & Prepress CMYK
- **Native 4-Channel CMYK Buffer**: Prepress print storage ([`CmykBuffer`](app/src/main/java/com/artflow/studio/core/color/CmykBuffer.kt)) holding 8-bit $C, M, Y, K$ data without lossy intermediate RGB truncation.
- **Certified ICC Color Engine**: LittleCMS-compatible engine ([`IccColorEngine`](app/src/main/java/com/artflow/studio/core/color/IccColorEngine.kt)) with calibrated Gray Component Replacement (GCR), Under Color Removal (UCR), and Total Area Coverage (TAC) limiting:
  - Profiles: **Coated FOGRA39** (ISO 12647-2, 330% TAC), **U.S. Web Coated (SWOP) v2** (300% TAC), **Japan Color 2001 Coated** (350% TAC), **GRACoL 2006 Coated #1** (320% TAC), **Display P3**, and **sRGB**.
- **Soft-Proofing & Gamut Diagnostics**: Substrate-simulated proofing with real-time diagonal zebra warning patterns highlighting out-of-gamut pixels.

### 4. 3D PBR Multi-Channel Painting & Cook-Torrance BRDF
- **Simultaneous 3-Channel Painting**: Single-dab unified stamping across `BaseColor`, `Metallic`, and `Roughness` texture planes ([`PbrMaterialAtlas`](app/src/main/java/com/artflow/studio/core/three/PbrMaterialAtlas.kt)).
- **Cook-Torrance Microfacet BRDF**: Physically-based rendering shader ([`ModelRenderer`](app/src/main/java/com/artflow/studio/data/renderer/opengl/ModelRenderer.kt)):
  - Trowbridge-Reitz / GGX normal distribution $D(h)$
  - Schlick Fresnel $F(v, h)$ with material $F_0 = \text{mix}(0.04, \text{albedo}, \text{metallic})$
  - Smith geometric shadowing/masking $G(l, v, h)$
  - Split-Sum Image-Based Lighting (IBL) environment reflection approximation with strict energy conservation ($k_d + k_s \le 1$).

### 5. Non-Destructive Typography, Layer Linking & Input Polish
- **Non-Destructive Vector Typography**: Vector text maintains editable strings, font styling, tracking, kerning, and baseline shift, and supports arbitrary 2D affine transformations (`deltaX`, `deltaY`, `deltaRotation`, `scaleX`, `scaleY`) without raster degradation ([`TextLayerContent`](app/src/main/java/com/artflow/studio/core/text/TextLayerContent.kt)).
- **Multi-Layer Selection & Linking**: Swipe-to-select multiple layers with link/unlink actions ([`LayerBatchController`](app/src/main/java/com/artflow/studio/presentation/ui/viewmodel/LayerBatchController.kt)), enabling synchronized transformation across linked sets.
- **Liquify Freeze & Thaw Masks**: Protects delicate artwork areas against warp/pinch/bloat/edge distortion with dedicated `FREEZE` and `UNFREEZE` brush modes ([`LiquifyTool`](app/src/main/java/com/artflow/studio/core/tool/LiquifyTool.kt)).
- **Hardware Touch-Major Palm Rejection**: Filters broad palm rests using physical touch ellipse axes (`rejectsTouchMajor`) alongside hover proximity windows ([`PalmRejection`](app/src/main/java/com/artflow/studio/core/canvas/PalmRejection.kt)).

### 6. Bidirectional Procreate & Photoshop Interchange
- **Procreate v5 / v6 Parity**: Native `.procreate` serialization and deserialization ([`ProcreateWriter`](app/src/main/java/com/artflow/studio/core/export/ProcreateWriter.kt) & [`ProcreateReader`](app/src/main/java/com/artflow/studio/core/export/ProcreateReader.kt)) preserving:
  - Group blend modes (`PASS_THROUGH`, `MULTIPLY`, etc.)
  - Animation timeline frame holds (`animationFrameHold`), background pins (`animationIsBackground`), and foreground pins (`animationIsForeground`)
  - Drawing guide grid configuration (grid size, opacity, thickness, guide type)
  - Type-3 independent 8-bit layer masks and embedded Display P3 ICC data
- **Photoshop PSD & PSB Support**: Round-trip PSD codec ([`PsdCodec`](app/src/main/java/com/artflow/studio/core/export/PsdCodec.kt)) with PSB (`8BPB`) Large Document format detection, 8-bit/16-bit depth reading, and RLE PackBits compression.

---

## Capability Overview

| Subsystem | Implemented Capabilities |
| --- | --- |
| **Brush Engine** | Valkyrie-parity 6-parameter wet mix (dilution, charge decay, attack, pull, grade, wet blur jitter); dual-brush combination modes (`COMBINE`, `INTENSE_BLEND`, `DISPLACE`); 35 auxiliary curve mappings; grain/shape jitter; live Brush Studio pad. |
| **Canvas & GPU** | Dual-backend runtime (Vulkan 1.2+ primary, GLES 3.x fallback); 2D sparse virtual tile canvas ($128 \times 128$ / $256 \times 256$); sub-millisecond dirty-tile invalidation; 16 KB page-size ELF alignment. |
| **Color Engine** | LittleCMS-compatible ICC engine; FOGRA39, SWOP v2, Japan Color 2001, GRACoL 2006, Display P3, sRGB; 4-channel native `CmykBuffer`; GCR/UCR separation; TAC ink limiting; paper-substrate soft-proofing; out-of-gamut zebra warning overlay. |
| **3D Painting** | Multi-channel `PbrMaterialAtlas` (simultaneous BaseColor, Metallic, Roughness painting); Cook-Torrance microfacet specular BRDF shader (GGX, Schlick, Smith); split-sum IBL reflection; OBJ/glTF/USD texture import/export. |
| **Typography** | Non-destructive vector text; live affine transformation matrix; kerning, tracking, baseline shift, leading, and outline styling. |
| **Layers & Input** | Multi-layer linking (`LayerBatchController`); nested groups; clipping masks; independent 8-bit layer masks; Liquify with freeze/thaw masks; touch-major ellipse palm rejection; Kalman/polynomial stroke prediction. |
| **File Formats** | Bidirectional `.procreate` (v5/v6 group blends, animation metadata, drawing guides, LZ4 tiles); `.psd` / `.psb` (8/16-bit, masks, folders); `.artflow` atomic signed v6 saves; PNG, JPEG, WebP, PDF, TIFF, GIF, APNG, MP4. |

---

## Project Structure

```
app/src/main/
├── java/com/artflow/studio/
│   ├── core/
│   │   ├── animation/      # Frame sequencing, onion skins, and timeline controllers
│   │   ├── canvas/         # Palm rejection, stroke prediction, and gesture routers
│   │   ├── color/          # LittleCMS ICC engine, CMYK buffer, palettes, and harmonies
│   │   ├── export/         # .procreate, .psd/.psb, APNG, GIF, and TIFF encoders
│   │   ├── pixels/         # PixelBuffer, channels, image filters, and stamping
│   │   ├── render/         # Valkyrie wet-mix, stroke rasterizer, and dual-brush marks
│   │   ├── symmetry/       # Radial, bilateral, and quadrilateral symmetry engines
│   │   ├── text/           # Non-destructive vector typography and text layout
│   │   ├── three/          # 3D mesh, PBR material atlas, camera, and lighting
│   │   └── tool/           # Liquify with freeze mask, fill bucket, and gradient tools
│   ├── data/
│   │   ├── renderer/gpu/   # Vulkan 1.2+ and GLES 3.x dual-backend & virtual tile canvas
│   │   ├── renderer/opengl/# Cook-Torrance PBR model and canvas renderers
│   │   └── repository/     # Atomic project storage, undo/redo, and layer management
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
# 1. Run headless JVM compilation and unit test suite
python3 tools/jvm_check.py

# 2. Run focused Procreate Parity unit tests
python3 tools/jvm_check.py \
    --test VirtualTileCanvasTest \
    --test ValkyrieWetMixTest \
    --test IccColorEngineTest \
    --test ProcreateInterchangeFidelityTest \
    --test PbrMultiChannelPaintingTest \
    --test NonDestructiveTextTest \
    --test LiquifyFreezeMaskTest

# 3. Run tool verification suites
python3 -m unittest discover -s tools
python3 -m unittest discover -s .github/scripts -p 'test_*.py'

# 4. Run Gradle test and static analysis gates
./gradlew testDebugUnitTest ktlintCheck detekt lintDebug

# 5. Build debug APK and production App Bundle
./gradlew assembleDebug bundleRelease
```

---

## Privacy & Safety

- **100% Offline & Private**: No network permissions (`android.permission.INTERNET` is not declared or used). No telemetry, tracking, or crash analytics SDKs.
- **Atomic Persistence**: Project saves are written atomically with SHA-256 integrity verification to prevent corruption during unexpected shutdowns.
- **Snapshot History**: Non-destructive undo/redo history is bound to project versions without unbounded memory expansion.

---

## License

GNU General Public License v3.0. See [LICENSE](LICENSE) for details.
