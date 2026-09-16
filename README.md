# ArtFlow — Digital Art Studio for Android

ArtFlow is an Android digital-painting application built with Kotlin and Jetpack Compose.
It is a **working editor**: you can create a project, paint with a pressure-sensitive brush
engine, build up layers with masks, groups and adjustments, fill and transform pixels, draw with
symmetry and perspective guides, add text and frames, then save and export real files.

> **Documentation accuracy note.** Earlier revisions of this README both over- and under-stated the
> project: one described a finished product (cloud sync, collaboration, Vulkan rendering) that does
> not exist, a later one described a bare skeleton after the editor had been built, and the phase
> table in [`agent.md`](agent.md) claimed areas were missing that the app had already shipped. This
> file describes what is actually in the repository: [Project status](#-project-status) for the
> per-area breakdown, and [What is not wired](#-what-is-not-wired) for the code that exists but that
> nothing calls.

---

## 🎨 What is implemented

### Canvas & rendering
- `GLSurfaceView`-based canvas (`ArtFlowCanvasView`) driven by `OpenGLCanvasRenderer`
- OpenGL **ES 2.0** rendering (`GLES20`); strokes are rasterised on the CPU into layer buffers and
  uploaded as textures
- A `Compositor` that flattens the layer stack with blend modes, opacity, clipping and masks, and a
  `StrokeRasterizer` that turns stroke samples into dabs
- Zoom / pan / pinch / rotation handled in `ArtFlowCanvasView.onTouchEvent`
- **Two-finger tap = undo, three-finger tap = redo**, also handled inline in `onTouchEvent`
- A `BitmapPixelBridge` for reading the framebuffer back into `PixelBuffer`s

### Brush engine
- Strokes are rasterised by `StrokeRasterizer` + `Compositor` into per-layer buffers, driven by the
  parameters in `BrushParams`; `ArtFlowCanvasView` captures stylus/finger input and calls the
  repository, which keeps a copy-on-write buffer for the edit in progress
- Stroke smoothing and start/end tapering
- Advanced parameters: size / opacity / hue / saturation / brightness jitter, scatter, count,
  spacing, wet mix, flow, tilt influence, velocity dynamics
- Pressure-curve types (linear, ease-in, ease-out, ease-in-out); `PressureCurve.CUSTOM` currently
  falls back to a linear curve
- Texture *models* only — no texture assets are bundled and nothing consumes `TextureMapper`, so
  strokes cannot apply textures
- ⛔ `core/brush/BrushEngine`, `core/brush/TextureMapper`, `CreateBrushPreset` and the brush use
  cases are unreachable; the render path does not use them

### Pixel engine (`core/pixels`)
- `PixelBuffer`: ARGB buffer with region maths, tiling and `IntBounds` clipping
- `BlendModes`: Normal, Multiply, Screen, Overlay, Darken, Lighten, Color Dodge, Color Burn,
  Hard Light, Soft Light, Difference, Exclusion, Hue, Saturation, Color, Luminosity (+ Pass Through
  for groups)
- `AdjustmentProcessor`: Brightness/Contrast, Hue/Saturation, Color Balance, Curves, Levels,
  Invert, Posterize, Selective Color, Gradient Map
- `ImageFilters`: Gaussian / box / motion blur, sharpen, noise, chromatic aberration, vignette,
  tilt-shift, find edges, emboss and generic 3×3 convolution
- `SelectionMask`: selection storage with feathering, inversion and boolean combination
- `Stamping`: stamp-based dab compositing

### Tools (`core/tool`)
- `ToolType` is the single source of truth for the tool bar, quick menu and gesture bindings
- **Paint**: Brush, Eraser, Smudge, Clone Stamp, Healing (with spot-heal source search), Liquify
- **Fill**: Paint Bucket (flood fill with tolerance / contiguous / pattern / all-layers) and
  Gradient (linear, radial, angular, diamond, with a gradient editor and presets)
- **Vector**: Text (font, size, style, alignment, kerning/leading, rasterize to layer) and Shapes
  (rectangle, ellipse, polygon, line; fill or stroke). Shapes are drawn in `ArtFlowCanvasView`;
  corner radius and boolean ops only exist in the unreachable `ShapeManager`
- **Selection**: rectangle, ellipse, freehand, lasso and a magic wand, combinable with
  replace / add / subtract / intersect
- **Transform**: 🟡 translate only — the `MOVE` and `TRANSFORM` tools shift pixels by the drag
  delta. Rotate, scale, skew, perspective, distortion and snapping live in the unreachable
  `core/transform/TransformManager`
- Smudge, clone, heal and liquify run through `PixelBrushes` / `LiquifyTool` on `PixelBuffer`s

### Layers
- The live layer stack is `CanvasRepositoryImpl`: add / remove / reorder / duplicate / merge (down,
  visible, flatten), visibility, opacity, rename, lock, alpha lock, clipping masks, blend modes and
  layer thumbnails
- Layer masks: add (optionally from the current selection), paint, invert, enable/disable, density
  and feather, with mask previews in the layer sheet
- Adjustment layers: 9 adjustment types (`AdjustmentProcessor`) with validated parameter ranges,
  applied non-destructively by the compositor
- Filter layers (`addFilterLayer` / `rasterizeFilterLayer`) through `ImageFilters`
- Reference layers: 🟡 a `reference` flag with a status label in the layer sheet — there is no
  reference window and no copy-from-reference
- ⛔ `LayerManager`, `LayerGroupManager`, `LayerMaskManager` and `AdjustmentLayerManager` are
  unreachable, so **layer groups are not available in the app**

### Undo / redo
- Snapshot-based history in `CanvasRepositoryImpl` — every destructive operation (pixel tools,
  adjustments, canvas ops, layer edits) snapshots before it runs, with `undoDepth` / `redoDepth`

### Colour, guides and canvas operations
- Advanced colour picker: colour wheel, RGB / HSV / CMYK sliders, hex, colour harmonies, recents
  and saved palettes (`Palette`, `ColorHarmony`)
- Symmetry: vertical, horizontal, quadrant and radial (`SymmetryEngine`)
- Perspective guides: 1-point, 2-point, 3-point and isometric, with draggable vanishing points
- Canvas operations: resize, crop, rotate (quarter turns and free), straighten, flip, expand /
  trim to content, fit to content, DPI changes and preset canvas sizes

### Animation
- `AnimationTimeline` + `AnimationFrame` model: frames, duplication, reordering, onion skinning,
  playback and FPS control

### Persistence and export
- `.artflow` document format: JSON metadata (kotlinx.serialization) plus per-layer raster blobs,
  written **atomically**
- Autosave, crash recovery, thumbnails and a flattened preview PNG per project
- Export via `ArtworkExporter`: **PNG, JPEG, WebP, PDF, PSD, animated GIF, MP4 and a PNG
  frame-sequence zip**, with scale, quality, area (full canvas / frame / all frames / selection /
  trim to content) and transparency handling
- Export publishing to the gallery plus share and view intents

### App shell
- Project gallery: list, create, rename, delete, thumbnails, search, sort and favourites
- Full-screen editor with tool strip, brush options, quick menu and sheet-based panels for layers,
  selection, canvas ops, guides, text and animation
- Settings screen backed by a Room-stored preferences repository, plus a help centre and
  first-run onboarding

### Data & architecture
- Room persistence for projects, brushes and settings; `.artflow` documents hold the canvas pixels
- Layering: `domain` (models + repository interfaces), `data` (Room, repositories, renderers,
  export), `presentation` (Compose screens + ViewModels + the GL canvas view)
- 🟡 The `domain/usecase` layer exists but nothing calls it — both ViewModels go straight to the
  repositories (see [What is not wired](#-what-is-not-wired))
- Hilt dependency injection, Timber logging, Coil for image loading, Navigation Compose for routing
- JVM unit tests for the pure-Kotlin engines (`core/pixels`, `core/render`, `core/tool`,
  `core/canvas`, `core/symmetry`, `core/color`, `core/animation`, `core/export`)

---

## 🚧 Project status

The table below reflects the code that is currently in the repository.

| Area | Status |
|------|--------|
| Project gallery | Implemented — list, create, rename, delete, real thumbnails, search, sort, favourites |
| Canvas screen + GL surface | Implemented |
| Brush engine + parameters | Implemented via `StrokeRasterizer`/`Compositor` + `BrushParams`; the older `BrushEngine` module is ⛔ unreachable |
| Brush textures | ⛔ **Unreachable** — models exist, but nothing calls `TextureMapper`, nothing composes the texture library and no assets are bundled |
| Pixel engine (buffers, blend modes, adjustments, filters) | Implemented |
| Tools (smudge, clone, heal, liquify, fill, gradient, text, shapes) | Implemented |
| Selection / transform | Selection implemented (magic wand + boolean combining); transform is **translate only** |
| Layers / masks / adjustments / filter layers | Implemented; **layer groups are not reachable from the UI** |
| Undo / redo | Implemented (snapshot history) |
| Save / load projects | Implemented — `.artflow` documents with layer pixels, autosave and recovery |
| Export (PNG/JPEG/WebP/PDF/PSD/GIF/MP4/frame sequence) | Implemented |
| Animation timeline + animation export | Implemented |
| Symmetry, perspective guides, canvas properties | Implemented |
| Colour picker, harmonies, palettes | Implemented |
| Settings, help centre, onboarding | Implemented |
| Cloud sync / collaboration | **Not implemented** |
| Reference layers, layer linking, smart objects | Reference flag only; layer linking exists in the repository with no UI; smart objects are just an unused id field |
| Native C++ brush engine | Built via CMake, **not wired** into the Kotlin render path |
| Instrumentation tests / benchmark module | **Not present** (a `benchmark` build *type* exists) |
| CI | GitHub Actions workflow runs unit tests and a debug build |

Only two `TODO` markers remain in the source:

```bash
grep -rn "TODO" app/src/main
```

Both are documented gaps rather than hidden work: `PressureCurve.CUSTOM` falls back to a linear
curve, and `LayerMaskManager.removeLayerMask(discardChanges = false)` cannot apply a mask to layer
alpha — that class is unreachable anyway.

---

## 🔌 What is not wired

About 26% of the Kotlin lines — 75 of 155 files — are unreachable: nothing under `presentation`
refers to them. They compile (and some are covered by unit tests), but they cannot change what a
user sees. Treat them as design sketches, not features.

- **`domain/usecase/**`** — all 54 files. `CanvasViewModel`/`MainViewModel` call the repositories
  directly. The seven layer-group use cases are referenced only by `di/LayerGroupModule.kt`, and no
  class injects them either.
- **`core/layer/`** — `LayerManager`, `LayerGroupManager`, `LayerMaskManager`,
  `AdjustmentLayerManager`.
- **`core/selection/`** — `SelectionManager`, `ColorSelectionAlgorithm` (the live magic wand is
  `SelectionMask.magicWand`, called from `ArtFlowCanvasView`).
- **`core/transform/TransformManager`**, **`core/shape/ShapeManager`**, **`core/brush/BrushEngine`**,
  **`core/brush/TextureMapper`**, **`domain/model/shape/VectorShape`**.
- **`data/renderer/CanvasRasterizer`**, **`data/renderer/native/NativeBrushEngine`** (the C++ module
  is built but never loaded).
- **`data/repository/texture/TextureRepositoryImpl`** and its interface — injected only by the dead
  `BrushEngine` and the dead texture use cases.
- **`presentation/ui/components/texture/TextureLibrary.kt`** — a composable with no callers.
- **Seven Hilt modules that provide only the above**: `LayerManagerModule`, `LayerGroupModule`,
  `ShapeManagerModule`, `SelectionManagerModule`, `SelectionAlgorithmModule`, `BrushEngineModule`,
  `TextureRepositoryModule`.

Wiring this layer up (or deleting it) is the largest single improvement available — see
[`agent.md`](agent.md#what-is-not-wired-into-the-app) for the per-phase view.

---

## 📱 System requirements

- **OS**: Android 8.0 (API 26) or higher
- **Target SDK**: 34
- **RAM**: 4 GB recommended for large canvases or many frames
- **Stylus**: pressure-sensitive stylus optional; finger input falls back to
  `MotionEvent.size` as a pressure proxy

---

## 🏗️ Architecture

### Tech stack

- **Language**: Kotlin (there are **no Java sources**)
- **UI**: Jetpack Compose with Material 3 (dark-first theme)
- **Graphics**: OpenGL ES 2.0 through `GLSurfaceView` / `GLES20`, CPU-side pixel pipeline
- **Native**: C++ via CMake + NDK (JNI) — `NativeBrushEngine` exists but is unused by the render path
- **Architecture**: MVVM with a layered `domain` / `data` / `presentation` split
- **DI**: Hilt (Dagger)
- **Async**: Kotlin Coroutines + Flow / StateFlow
- **Persistence**: Room + kotlinx.serialization documents
- **Image loading**: Coil
- **Logging**: Timber

### Project structure

```
app/src/main/java/com/artflow/studio/
├── core/                       # Stateless and stateful engines
│   ├── animation/              # AnimationTimeline
│   ├── brush/                  # BrushEngine, TextureMapper
│   ├── canvas/                 # CanvasOperations (resize / crop / rotate / trim)
│   ├── color/                  # Palette, ColorHarmony
│   ├── export/                 # ExportOptions, PsdCodec, GifEncoder
│   ├── layer/                  # Layer, LayerGroup, LayerMask, AdjustmentLayer managers
│   ├── perspective/            # PerspectiveGuide
│   ├── pixels/                 # PixelBuffer, BlendModes, AdjustmentProcessor, ImageFilters,
│   │                           # SelectionMask, Stamping
│   ├── render/                 # Compositor, StrokeRasterizer
│   ├── selection/              # SelectionManager, ColorSelectionAlgorithm
│   ├── shape/                  # ShapeManager
│   ├── symmetry/               # SymmetryEngine
│   ├── text/                   # TextLayout
│   ├── tool/                   # ToolType, FillTool, GradientTool, LiquifyTool, PixelBrushes
│   └── transform/              # TransformManager
├── domain/
│   ├── model/                  # Color, Project, animation/, brush/, layer/, selection/, settings/,
│   │                           # shape/, texture/, transform/
│   ├── repository/             # Project / canvas / settings / texture repository interfaces
│   └── usecase/                # One class per operation (brush, canvas, layer, selection, shape,
│                               # transform, texture)
├── data/
│   ├── export/                 # ArtworkExporter (PNG/JPEG/WebP/PDF/PSD/GIF/MP4/zip)
│   ├── local/                  # Room database, DAOs, entities, ProjectStorage (.artflow files)
│   ├── renderer/               # OpenGL renderer, BitmapPixelBridge, native/ JNI wrapper
│   └── repository/             # Repository implementations (canvas/, settings/, texture/)
├── di/                         # Hilt modules
└── presentation/
    └── ui/                     # Compose app, components/{brush,canvas,color,editor,export,layer,
                                # texture}, screens/{canvas,gallery,help,settings}, theme, viewmodels

app/src/main/jni/               # C++ sources built by CMakeLists.txt
app/src/test/                   # JVM unit tests (JUnit)
```

---

## 🚀 Getting started

### Prerequisites
- Android Studio Hedgehog (2023.1.1) or later, **or** a local Gradle install
- JDK 17
- Android SDK with API 34
- NDK (only required to build the native `libartflow-brush` module)

### Build

The Gradle wrapper **is** committed, so a fresh clone builds with:

```bash
./gradlew assembleDebug
```

Configuration notes:
- `minSdk = 26`, `targetSdk = 34`, `compileSdk = 34`
- Build variants: `debug`, `release`, `benchmark`
- Java/Kotlin target: 17
- `app/src/main/jni/CMakeLists.txt` builds the shared library `libartflow-brush.so`; pass
  `-PnoNativeBuild` to skip it, which is how CI builds without an NDK installed
- `local.properties` with `sdk.dir=…` is required for a CLI build and is git-ignored
- The release build enables R8 (`minifyEnabled`) and resource shrinking; see
  [Building for release](#-building-for-release)

---

## 🧪 Testing

JVM unit tests live in `app/src/test`:

```bash
./gradlew testDebugUnitTest
```

Coverage today:

- `core/pixels/PixelBufferTest` — buffer maths, blending and region operations
- `core/layer/LayerGroupManagerTest` — group nesting and membership
- `domain/usecase/brush/UpdateAdvancedBrushParamsTest` — parameter validation
- `domain/usecase/brush/UpdateColorDynamicsTest` — colour dynamics

There is currently **no instrumentation test source set** (`app/src/androidTest` does not
exist), and no benchmark module, so `connectedAndroidTest` and the `benchmark` variant have
nothing to run yet.

---

## 📦 Building for release

1. Generate a keystore:

```bash
keytool -genkey -v -keystore artflow-release.keystore -alias artflow -keyalg RSA -keysize 2048 -validity 10000
```

2. Create a git-ignored `keystore.properties` at the repository root:

```properties
storeFile=/absolute/path/to/artflow-release.keystore
storePassword=…
keyAlias=artflow
keyPassword=…
```

`app/build.gradle.kts` reads it automatically. Without the file the release build still runs and
produces an **unsigned** APK, so a fresh clone never fails for want of a keystore. The release build
type enables `minifyEnabled` and `isShrinkResources`, using the rules in `app/proguard-rules.pro`.

3. Build:

```bash
./gradlew assembleRelease
./gradlew bundleRelease
```

`local.properties` (SDK/NDK paths) and keystores are git-ignored — never commit them.

---

## 🤝 Contributing

1. Fork the repository and create a feature branch.
2. Make your changes and add/update tests.
3. Ensure `./gradlew testDebugUnitTest` passes and the project compiles.
4. Open a pull request.

### Code style
Follow the [Kotlin coding conventions](https://kotlinlang.org/docs/coding-conventions.html).

`ktlint` and `detekt` are **not** configured in this repository, so `./gradlew ktlintCheck`
and `./gradlew ktlintFormat` do not exist. Adding them would require the corresponding plugins
and configuration.

### Commit messages
Conventional commits are preferred: `feat:`, `fix:`, `docs:`, `style:`, `refactor:`,
`test:`, `chore:`.

---

## 📄 License

This project is licensed under the **GNU General Public License v3.0** — see the
[LICENSE](LICENSE) file for the full text.

---

## 🗺️ Roadmap

The detailed 50-phase plan lives in [`agent.md`](agent.md), which carries the per-phase status
table and is kept in step with the code. Summarised:

- **Foundation (1–8)**: project setup, DI, architecture, design system, canvas, input, stroke
  engine, layers — ✅ *wired*; the standalone `BrushEngine`/`LayerManager` are ⛔ unreachable
- **Core drawing (9–16)**: advanced brush params, colour dynamics, blend modes, selection,
  alpha lock, masks — ✅ *wired*; texture brushes — ⛔ *unreachable*; transform — 🟡 *translate only*
- **Professional tools (17–24)**: smudge, liquify, clone, heal, gradient, fill, text, shapes — ✅
- **Advanced layers (25–30)**: adjustments and filter layers — ✅; layer groups, reference layers,
  layer linking and smart objects — ⬜ *not started* (the manager classes are ⛔ unreachable)
- **Colour & canvas (31–36)**: pickers, palettes, symmetry, perspective, canvas properties,
  quick menu — ✅; shortcut remapping — ⬜
- **File operations (37–42)**: save, PNG/JPEG/WebP/PSD/PDF export, gallery — ✅; PSD import and
  cloud sync — ⬜
- **Animation & advanced (43–50)**: timeline, animation export, settings, help centre, onboarding,
  CI — ✅; timelapse recording, analytics/crash reporting, instrumentation tests and a benchmark
  module — ⬜

---

## 📞 Support

- Issue tracker: open an issue on the repository hosting this project
- The `https://docs.artflow.studio`, Discord, and email addresses used in earlier revisions of
  this document are placeholders and do not resolve

---

<div align="center">

**Built with Kotlin and Jetpack Compose for Android artists**

</div>
