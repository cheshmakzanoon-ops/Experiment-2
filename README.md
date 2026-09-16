# ArtFlow — Digital Art Studio for Android

ArtFlow is an Android digital-painting application built with Kotlin and Jetpack Compose.
It is a **working editor**: you can create a project, paint with a pressure-sensitive brush
engine, build up layers with masks, groups and adjustments, fill and transform pixels, draw with
symmetry and perspective guides, add text and frames, then save and export real files.

> **Documentation accuracy note.** Earlier revisions of this README both over- and under-stated
> the project: one described a finished product (cloud sync, collaboration, Vulkan rendering) that
> does not exist, and a later one described a bare skeleton after the editor had been built. This
> file describes what is actually in the repository now. See
> [Project status](#-project-status) for the precise list of what works and what does not.

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
- `BrushEngine` + `StrokeBuilder` producing pressure-sensitive strokes
- Stroke smoothing and start/end tapering
- Advanced parameters: size / opacity / hue / saturation / brightness jitter, scatter, count,
  spacing, wet mix, flow, tilt influence, velocity dynamics
- Pressure-curve types (linear, ease-in, ease-out, ease-in-out)
- Brush preset factory (`CreateBrushPreset`) plus `applyPreset` on the advanced-parameter and
  colour-dynamics use cases
- Texture *models*, a `TextureMapper` and a texture library UI (no texture assets are bundled)

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
  (rectangle, ellipse, polygon, line; fill/stroke, corner radius, boolean ops)
- **Selection**: rectangle, ellipse, freehand, lasso and a magic wand
- **Transform**: translate, rotate, scale, skew, perspective, distortion and snapping
- Smudge, clone, heal and liquify run through `PixelBrushes` / `LiquifyTool` on `PixelBuffer`s

### Layers
- `LayerManager`: add / remove / reorder / duplicate / merge, visibility, opacity, rename, lock,
  alpha lock, clipping masks
- `LayerMaskManager`: create, paint, fill, invert, feather and thumbnail grayscale masks
- `LayerGroupManager`: nested groups, layer-to-group membership, expansion state
- `AdjustmentLayerManager` + 9 adjustment types with validated parameter ranges
- Filter layers (`addFilterLayer` / `rasterizeFilterLayer`)

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
- Room persistence for projects, brushes and settings
- Clean-ish layering: `domain` (models + repository interfaces + use cases),
  `data` (Room, repositories, renderers, export), `presentation` (Compose UI + ViewModels)
- Hilt dependency injection across all layers
- Timber logging, Coil for image loading, Navigation Compose for routing
- JVM unit tests for the pixel engine, layer groups and brush use cases

---

## 🚧 Project status

The table below reflects the code that is currently in the repository.

| Area | Status |
|------|--------|
| Project gallery | Implemented — list, create, rename, delete, real thumbnails, search, sort, favourites |
| Canvas screen + GL surface | Implemented |
| Brush engine + parameters | Implemented, including advanced dynamics and presets |
| Brush textures | Models, mapper and library UI implemented; **no texture assets shipped** |
| Pixel engine (buffers, blend modes, adjustments, filters) | Implemented |
| Tools (smudge, clone, heal, liquify, fill, gradient, text, shapes) | Implemented |
| Selection / transform | Implemented, including magic wand and snapping |
| Layers / masks / groups / adjustments / filter layers | Implemented |
| Undo / redo | Implemented (snapshot history) |
| Save / load projects | Implemented — `.artflow` documents with layer pixels, autosave and recovery |
| Export (PNG/JPEG/WebP/PDF/PSD/GIF/MP4/frame sequence) | Implemented |
| Animation timeline + animation export | Implemented |
| Symmetry, perspective guides, canvas properties | Implemented |
| Colour picker, harmonies, palettes | Implemented |
| Settings, help centre, onboarding | Implemented |
| Cloud sync / collaboration | **Not implemented** |
| Reference layers, layer linking, smart objects | **Not implemented** |
| Native C++ brush engine | Built via CMake, **not wired** into the Kotlin render path |
| Instrumentation tests / benchmark module | **Not present** (a `benchmark` build *type* exists) |

`TODO` markers in the source indicate the remaining follow-up work. Use:

```bash
grep -rn "TODO" app/src/main
```

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
- `app/src/main/jni/CMakeLists.txt` builds the shared library `libartflow-brush.so`
- `local.properties` with `sdk.dir=…` is required for a CLI build and is git-ignored

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

2. Provide signing credentials through `~/.gradle/gradle.properties` (the release build type
   declares `minifyEnabled = true`; add a `signingConfig` block to `app/build.gradle.kts`
   if you want signed output).

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

The detailed 50-phase plan lives in [`agent.md`](agent.md). Summarised:

- **Foundation (1–8)**: project setup, DI, core architecture, design system, canvas, input,
  brush engine, layers — *done*
- **Core drawing (9–16)**: advanced brush params, textures, colour dynamics, blend modes,
  selection, transform, alpha lock, masks — *done, except bundled texture assets*
- **Professional tools (17–24)**: smudge, liquify, clone, heal, gradient, fill, text, shapes — *done*
- **Advanced layers (25–30)**: adjustments, groups, filter layers — *done*; reference layers,
  layer linking and smart objects — *not started*
- **Colour & canvas (31–36)**: pickers, palettes, symmetry, perspective, canvas properties,
  quick menu — *done*
- **File operations (37–42)**: save, PNG/JPEG/WebP, PSD, PDF, gallery — *done*; cloud sync — *not started*
- **Animation & advanced (43–50)**: timeline, animation export and settings/help — *done*;
  timelapse recording, performance work, and full QA/release automation — *remaining*

---

## 📞 Support

- Issue tracker: open an issue on the repository hosting this project
- The `https://docs.artflow.studio`, Discord, and email addresses used in earlier revisions of
  this document are placeholders and do not resolve

---

<div align="center">

**Built with Kotlin and Jetpack Compose for Android artists**

</div>
