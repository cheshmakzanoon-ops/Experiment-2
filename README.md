# ArtFlow — Digital Art Studio for Android

ArtFlow is an Android digital-painting application built with Kotlin and Jetpack Compose.
It is a **working editor**: you can create a project, paint with a pressure-sensitive brush
engine, build up layers with masks, groups and adjustments, fill and transform pixels, draw with
symmetry and perspective guides, add text and frames, then save and export real files.

> **Documentation accuracy note.** Earlier revisions of this README both over- and under-stated the
> project: one described a finished product (cloud sync, collaboration, Vulkan rendering) that does
> not exist, a later one described a bare skeleton after the editor had been built, and a still
> later one listed a large "unreachable" prototype layer that has since been deleted outright. This
> file describes what is actually in the repository: [Project status](#-project-status) for the
> per-area breakdown, and [Known gaps](#-known-gaps) for what is genuinely missing.

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
- Edge-to-edge layout from API 35: the Material 3 top bars and `Scaffold` slots consume the system
  insets, and the editor's tool strip pads itself with the safe-drawing insets, so the chrome is
  not covered by the status or navigation bars

### Brush engine
- Strokes are rasterised by `StrokeRasterizer` + `Compositor` into per-layer buffers, driven by the
  parameters in `BrushParams`; `ArtFlowCanvasView` captures stylus/finger input and calls the
  repository, which keeps a copy-on-write buffer for the edit in progress
- Stroke smoothing and start/end tapering
- Advanced parameters: size / opacity / hue / saturation / brightness jitter, scatter, count,
  spacing, wet mix, flow, tilt influence, velocity dynamics
- Pressure-curve types (linear, ease-in, ease-out, ease-in-out); `PressureCurve.CUSTOM` falls back
  to a linear curve — this is the one `TODO` left in `app/src/main`
- `BrushParams` carries texture *fields*, but no texture assets are bundled and nothing applies a
  texture to a stroke, so textured brushes are not available (see [Known gaps](#-known-gaps))

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
- `ToolType` is the single source of truth for the tool bar, quick menu and gesture bindings, and
  defines 19 tools across paint, fill, vector, selection, transform and utility groups
- **Paint**: Brush, Eraser, Smudge, Clone Stamp, Healing (with spot-heal source search), Liquify
- **Fill**: Paint Bucket (flood fill with tolerance / contiguous / pattern / all-layers) and
  Gradient (linear, radial, angular, diamond, with a gradient editor and presets)
- **Vector**: Text (font, size, style, alignment, kerning/leading, rasterize to layer) and Shapes
  (rectangle, ellipse, polygon, line; fill or stroke)
- **Selection**: rectangle, ellipse, freehand, lasso and a magic wand, combinable with
  replace / add / subtract / intersect
- **Transform**: 🟡 translate only — the `MOVE` and `TRANSFORM` tools shift pixels by the drag
  delta. Rotate, scale, skew, perspective and distortion are not implemented
- Smudge, clone, heal and liquify run through `PixelBrushes` / `LiquifyTool` on `PixelBuffer`s

### Layers
- The layer stack lives in `CanvasRepositoryImpl`: add / remove / reorder / duplicate / merge (down,
  visible, flatten), visibility, opacity, rename, lock, alpha lock, clipping masks, blend modes and
  layer thumbnails
- Layer masks: add (optionally from the current selection), paint, invert, enable/disable, density
  and feather, with mask previews in the layer sheet
- Adjustment layers: 9 adjustment types (`AdjustmentProcessor`) with validated parameter ranges,
  applied non-destructively by the compositor
- Filter layers (`addFilterLayer` / `rasterizeFilterLayer`) through `ImageFilters`
- Reference layers: 🟡 a `reference` flag that `Compositor` keeps out of the flattened output, shown
  as a `· reference` label in the layer sheet — but no screen sets the flag, so the feature is not
  reachable in practice. There is no reference window and no copy-from-reference
- Layer linking exists in the repository (`linkGroupId`, set/unset per layer) but has no UI
- **Layer groups are not available** — see [Known gaps](#-known-gaps)

### Undo / redo
- Snapshot-based history in `CanvasRepositoryImpl` — every destructive operation (pixel tools,
  adjustments, canvas ops, layer edits) snapshots before it runs, with `undoDepth` / `redoDepth`

### Colour, guides and canvas operations
- Advanced colour picker: colour wheel, RGB / HSV / CMYK sliders, hex, colour harmonies, recents
  and saved palettes (`Palette` with `PaletteCodec` for persistence, `ColorHarmony`)
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
- Both ViewModels talk to the repository interfaces directly; there is no separate use-case layer
- Hilt dependency injection, Timber logging, Coil for image loading, Navigation Compose for routing
- JVM unit tests for the pure-Kotlin engines (`core/pixels`, `core/canvas`, `core/symmetry`)

---

## 🚧 Project status

The table below reflects the code that is currently in the repository.

| Area | Status |
|------|--------|
| Project gallery | Implemented — list, create, rename, delete, real thumbnails, search, sort, favourites |
| Canvas screen + GL surface | Implemented |
| Brush engine + parameters | Implemented via `StrokeRasterizer`/`Compositor` + `BrushParams` |
| Brush textures | **Not implemented** — `BrushParams` carries texture fields, but no assets are bundled and nothing applies them |
| Pixel engine (buffers, blend modes, adjustments, filters) | Implemented |
| Tools (smudge, clone, heal, liquify, fill, gradient, text, shapes) | Implemented |
| Selection / transform | Selection implemented (magic wand + boolean combining); transform is **translate only** |
| Layers / masks / adjustments / filter layers | Implemented |
| Layer groups / layer linking | **Not available** — no grouping UI or repository operation; `linkGroupId` is stored but never set from the UI |
| Undo / redo | Implemented (snapshot history) |
| Save / load projects | Implemented — `.artflow` documents with layer pixels, autosave and recovery |
| Export (PNG/JPEG/WebP/PDF/PSD/GIF/MP4/frame sequence) | Implemented |
| Animation timeline + animation export | Implemented |
| Symmetry, perspective guides, canvas properties | Implemented |
| Colour picker, harmonies, palettes | Implemented |
| Settings, help centre, onboarding | Implemented |
| Cloud sync / collaboration | **Not implemented** |
| Reference layers | 🟡 Model + compositor support and a `· reference` label, but no control sets the flag; no reference window, no copy-from-reference |
| Layer linking, smart objects | **Not implemented** (no UI; no smart-object concept in the model) |
| Native C++ brush engine | Built via CMake, **never loaded** — no `System.loadLibrary` call in the Kotlin sources |
| Timelapse recording | **Not implemented** (the `.artflow` format reserves timelapse metadata) |
| Instrumentation tests | A Hilt-backed launch smoke test (`AppLaunchTest`); no benchmark module (a `benchmark` build *type* exists) |
| CI | GitHub Actions: unit tests, ktlint + detekt, debug APK and a release bundle |

One `TODO` marker remains in the source:

```bash
grep -rn "TODO" app/src/main
```

It is a documented gap rather than hidden work: `PressureCurve.CUSTOM` falls back to a linear curve.

---

## 🔌 Known gaps

These are the features the app does **not** have. Nothing in `app/src/main` is known to be
unreachable — the prototype layer that an earlier revision of this file described as dead code
(`core/layer`, `core/selection`, `core/transform`, `core/shape`, `core/brush`, `domain/usecase`,
`data/repository/texture`, `data/renderer/native`, the texture library UI and the Hilt modules that
only provided them) has been deleted.

- **Layer groups.** `Layer.parentGroupId` exists, but no repository operation or UI sets it, so
  layers cannot be nested or collapsed.
- **Transform beyond translation.** Rotate, scale, skew, perspective, distortion and snapping are
  not implemented; `MOVE`/`TRANSFORM` translate pixels only.
- **Texture brushes.** No texture assets ship and no render path consumes the texture fields in
  `BrushParams`.
- **The native module.** `app/src/main/jni` builds `libartflow-brush.so` through CMake, but no
  Kotlin code loads it, so it cannot affect rendering. Building it also requires an NDK; CI and the
  default local workflow pass `-PnoNativeBuild` to skip it.
- **Reference layers.** The flag is honoured when compositing, but no screen sets it, so the
  feature cannot actually be used.
- **PSD import.** PSD *export* works; importing a Photoshop document does not.
- **Cloud sync, collaboration, smart objects, layer linking UI, timelapse recording.**
- **Analytics and crash reporting.** Deliberately absent: the app declares no `INTERNET`
  permission, which is what the privacy policy and the Play data-safety answers rely on.
- **Benchmark module and broader test coverage.** The presentation layer, the repositories and the
  native bridge have no automated coverage; the JVM tests cover `core/canvas`, `core/pixels` and
  `core/symmetry`.

---

## 📱 System requirements

- **OS**: Android 8.0 (API 26) or higher
- **Target SDK**: 36 (required by Google Play for new apps and updates since 2026-08-31)
- **RAM**: 4 GB recommended for large canvases or many frames
- **Stylus**: pressure-sensitive stylus optional; finger input falls back to
  `MotionEvent.size` as a pressure proxy

---

## 🏗️ Architecture

### Tech stack

- **Language**: Kotlin (there are **no Java sources**)
- **UI**: Jetpack Compose with Material 3 (dark-first theme)
- **Graphics**: OpenGL ES 2.0 through `GLSurfaceView` / `GLES20`, CPU-side pixel pipeline
- **Native**: C++ via CMake + NDK — `libartflow-brush` is built but no Kotlin code loads it
- **Architecture**: MVVM with a layered `domain` / `data` / `presentation` split
- **DI**: Hilt (Dagger)
- **Async**: Kotlin Coroutines + Flow / StateFlow
- **Persistence**: Room + kotlinx.serialization documents
- **Image loading**: Coil
- **Logging**: Timber

### Project structure

```
app/src/main/java/com/artflow/studio/
├── core/                       # Engines; the pixel ones deliberately avoid android.* so they
│   │                           # stay testable on the JVM
│   ├── animation/              # AnimationTimeline
│   ├── canvas/                 # CanvasOperations (resize / crop / rotate / trim)
│   ├── color/                  # Palette, ColorHarmony
│   ├── export/                 # ExportOptions, PsdCodec, GifEncoder
│   ├── perspective/            # PerspectiveGuide
│   ├── pixels/                 # PixelBuffer, BlendModes, AdjustmentProcessor, ImageFilters,
│   │                           # SelectionMask, Stamping
│   ├── render/                 # Compositor, StrokeRasterizer
│   ├── symmetry/               # SymmetryEngine
│   ├── text/                   # TextLayout
│   └── tool/                   # ToolType, FillTool, GradientTool, LiquifyTool, PixelBrushes
├── domain/
│   ├── model/                  # Color, Project, animation/, brush/, layer/, settings/
│   └── repository/             # Project / canvas / settings repository interfaces
├── data/
│   ├── export/                 # ArtworkExporter (PNG/JPEG/WebP/PDF/PSD/GIF/MP4/zip)
│   ├── local/                  # Room database, DAOs, entities, ProjectStorage (.artflow files)
│   ├── renderer/               # OpenGLCanvasRenderer, BitmapPixelBridge
│   └── repository/             # Repository implementations (canvas/, settings/)
├── di/                         # Hilt modules: CanvasRepository, Database, Repository, Settings
└── presentation/
    └── ui/                     # Compose app (ArtFlowApp, MainActivity), components/{brush,canvas,
                                # color,editor,export,layer}, screens/{canvas,gallery,help,settings},
                                # theme, viewmodel

app/src/main/jni/               # C++ sources built by CMakeLists.txt (never loaded from Kotlin)
app/src/test/                   # JVM unit tests (JUnit)
app/src/androidTest/            # Instrumentation smoke test + Hilt test runner
```

---

## 🚀 Getting started

### Prerequisites
- Android Studio Hedgehog (2023.1.1) or later, **or** a local Gradle install
- JDK 17
- Android SDK with API 36
- NDK (only required to build the native `libartflow-brush` module)

### Build

The Gradle wrapper **is** committed, so a fresh clone builds with:

```bash
./gradlew assembleDebug
```

Configuration notes:
- `minSdk = 26`, `targetSdk = 36`, `compileSdk = 36`
- Build variants: `debug`, `release`, `benchmark`
- Java/Kotlin target: 17
- `app/src/main/jni/CMakeLists.txt` builds the shared library `libartflow-brush.so`; pass
  `-PnoNativeBuild` to skip it, which is how CI builds without an NDK installed
- `local.properties` with `sdk.dir=…` is required for a CLI build and is git-ignored
- The release build enables R8 (`minifyEnabled`) and resource shrinking; see
  [Building for release](#-building-for-release)
- `gradle.properties` bounds the daemon heap (3 GB), runs the Kotlin compiler in-process and caps
  the worker pool at 4. Those numbers are sized so that a full `assembleRelease` — R8 is the peak —
  fits a 4 GB / 2 vCPU container instead of being OOM-killed. Raise them on a larger machine if you
  want faster builds.

---

## 🧪 Testing and static analysis

JVM unit tests live in `app/src/test`:

```bash
./gradlew testDebugUnitTest
```

Coverage today — three classes, all against the pure-Kotlin engines:

- `core/pixels/PixelBufferTest` — buffer maths, blending and region operations
- `core/canvas/CanvasOperationsTest` — resize, crop, rotate and trim maths
- `core/symmetry/SymmetryEngineTest` — symmetry-axis generation

Instrumentation tests live in `app/src/androidTest`, currently a single Hilt-backed smoke test
(`AppLaunchTest`) that launches `MainActivity`:

```bash
./gradlew connectedDebugAndroidTest    # needs a device or emulator
```

Static analysis is configured and enforced:

```bash
./gradlew ktlintCheck    # Kotlin style; rules come from .editorconfig
./gradlew detekt         # bug-prone patterns; pre-existing findings are baselined
./gradlew lintDebug      # Android platform lint
./gradlew check          # ktlint + detekt + platform lint
```

`detekt` reads `config/detekt/detekt.yml` and `config/detekt/baseline.xml`, so it fails only on new
findings. There is no benchmark module, so the `benchmark` build variant has nothing to run.

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
./gradlew assembleRelease     # signed APK (v2 signature scheme)
./gradlew bundleRelease       # AAB for Google Play
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

`ktlint` and `detekt` **are** configured (root `.editorconfig`, `config/detekt/`), so run
`./gradlew ktlintCheck detekt` before opening a pull request — CI runs both. `detekt` only fails on
findings that are not in the baseline, so new code is held to the standard without rewriting
existing code first.

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
  engine, layers — ✅
- **Core drawing (9–16)**: advanced brush params, colour dynamics, blend modes, selection,
  alpha lock, masks — ✅; texture brushes — ⬜ *not implemented*; transform — 🟡 *translate only*
- **Professional tools (17–24)**: smudge, liquify, clone, heal, gradient, fill, text, shapes — ✅
- **Advanced layers (25–30)**: adjustments and filter layers — ✅; layer groups, layer linking UI
  and smart objects — ⬜ *not implemented*
- **Colour & canvas (31–36)**: pickers, palettes, symmetry, perspective, canvas properties,
  quick menu — ✅; shortcut remapping — ⬜
- **File operations (37–42)**: save, PNG/JPEG/WebP/PSD/PDF export, gallery — ✅; PSD import and
  cloud sync — ⬜
- **Animation & advanced (43–50)**: timeline, animation export, settings, help centre, onboarding,
  CI — ✅; timelapse recording, analytics/crash reporting and a benchmark module — ⬜

---

## 📞 Support

- Issue tracker: open an issue on the repository hosting this project
- The `https://docs.artflow.studio`, Discord, and email addresses used in earlier revisions of
  this document are placeholders and do not resolve

---

<div align="center">

**Built with Kotlin and Jetpack Compose for Android artists**

</div>
