# ArtFlow — Digital Art Studio for Android

ArtFlow is an Android digital-painting application built with Kotlin and Jetpack Compose.
It is a **working editor**: you can create a project, paint with a pressure-sensitive brush
engine, build up layers with masks and adjustments, fill and transform pixels, draw with
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
- A `BitmapPixelBridge` for lossless project rasters and platform image encoding
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
- Pressure-curve types (linear, ease-in, ease-out, ease-in-out), plus a monotone custom response
  with editable output controls at 25%, 50% and 75% input pressure
- Deterministic procedural paper, canvas and charcoal grains, with scale/rotation controls;
  preview and commit use the same raw-pixel renderer. Imported/dual textures remain a roadmap gap.

Destructive document operations prepare replacement pixels before committing a single undo step.
Merges preserve transparency and reject combinations whose blend/clipping context cannot be baked
faithfully. A merged copy hides, rather than deletes, its retained sources. Empty selections block
painting instead of becoming unrestricted edits. Quarter-turn canvas transforms retain legacy
vector ink and transform masks across every animation frame.

Empty filter layers affect the stack below; layers with their own pixels retain per-layer filters.
Filter masks and opacity control the effect, and baking uses the same compositor as the preview.
Backdrop-dependent baking may be refused rather than silently changing the artwork.

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
- Export publishing to the correct image/video gallery collection, Android document-picker save, and read-granted share/view intents. Editable project files are excluded from FileProvider access

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
| Project gallery | Implemented — list, create, rename, delete, independent artwork duplication, real thumbnails, search, sort, favourites |
| Canvas screen + GL surface | Implemented |
| Brush engine + parameters | Implemented via `StrokeRasterizer`/`Compositor` + `BrushParams` |
| Brush textures | Three built-in procedural grains with scale/rotation; custom texture import and dual textures are not implemented |
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
| Native C++ brush engine | Unintegrated source prototype; not built or packaged by the application Gradle configuration |
| Timelapse recording | **Not implemented** (the `.artflow` format reserves timelapse metadata) |
| Instrumentation tests | Launch, GL rendering, storage/recovery, independent duplication, export formats/provider access and export/privacy UI regression tests |
| CI | GitHub Actions: JVM tests, ktlint, detekt, Android lint, APK/AAB builds and API 26/35 (16 KB)/36 instrumentation plus minified launch; consult completed run evidence |

Source markers are not a completion measure. The custom pressure curve and procedural brush grains
are implemented; the original roadmap still includes missing workflows listed below. A passing
build is not a signed Google Play release.

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
- **Imported and dual brush textures.** Built-in procedural grains are implemented; a custom
  texture importer/library and dual-texture mixing are not.
- **The native module.** `app/src/main/jni` remains an unintegrated C++ prototype. The application
  no longer builds or packages that unused module. The active engine is Kotlin; no NDK or
  `-PnoNativeBuild` switch is needed for the normal application build.
- **Reference layers.** The flag is honoured when compositing, but no screen sets it, so the
  feature cannot actually be used.
- **PSD import.** PSD *export* works; importing a Photoshop document does not.
- **Cloud sync, collaboration, smart objects, layer linking UI, timelapse recording.**
- **Analytics and crash reporting.** Deliberately absent: the app declares no `INTERNET`
  permission, which is what the privacy policy and the Play data-safety answers rely on.
- **Benchmarks and broader device coverage.** Repository, rendering, export and selected Compose
  UI regressions now have automated tests. This is not exhaustive UI coverage or measured
  performance on low-memory phones, physical styluses and every GPU/codec. No benchmark module
  is present. See [release readiness](docs/RELEASE_READINESS.md) before distribution.

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
- **Native**: unintegrated C++ prototype retained as source; not part of the application build
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

app/src/main/jni/               # Unintegrated C++ prototype, excluded from the app build
app/src/test/                   # JVM unit tests (JUnit)
app/src/androidTest/            # Device regressions, Compose tests and Hilt test runner
```

---

## 🚀 Getting started

### Prerequisites
- JDK 17 and the committed Gradle 8.14.3 wrapper; no global Gradle install is needed.
- Android SDK platform 36 and platform-tools. Set `ANDROID_HOME` or a git-ignored
  `local.properties` containing `sdk.dir=...`.
- An Android Studio release compatible with Android Gradle Plugin 8.10.1 when using the IDE.
- At least 6 GB of available build memory, plus emulator memory for device tests. The daemon
  budgets 2.5 GB heap and 1 GB metaspace, uses the in-process Kotlin compiler and two workers.
  These are build settings, not a claim about the app's minimum device RAM.

### Build and verify

```bash
./gradlew testDebugUnitTest ktlintCheck detekt lintDebug lintRelease
./gradlew assembleDebug bundleRelease
./gradlew connectedDebugAndroidTest  # requires a running device/emulator, API 26 or newer
python -m unittest discover -s .github/scripts -p 'test_*.py'
python .github/scripts/verify_android_artifacts.py app/build/outputs/apk/debug/app-debug.apk app/build/outputs/bundle/release/app-release.aab
```

On Windows use `gradlew.bat` and your Python command. Build variants are `debug`, `release` and
`benchmark`; the last is a release-like build type, not a benchmark suite. Minimum SDK is 26,
compile/target SDK is 36, and the Java/Kotlin bytecode target is 17. The unused C++ prototype does
not require the NDK for these builds.

### What the tests cover

JVM suites exercise canvas operations, pixel buffers, selections, symmetry, flood filling,
deterministic brush/compositor behavior and image codecs. PNG tests use an independent JDK decoder;
PSD tests cover raw/RLE output, alpha, Unicode layer names, clipping and resolution metadata.

Device suites exercise project round trips, incomplete saves, recovery, stale edit rejection,
independent duplication and rollback, actual GL output, file-provider isolation, image/document/
video exports, video frame timing, and selected export/privacy UI interactions. Source tests are
not proof of passing execution: consult [release evidence](docs/RELEASE_READINESS.md) and the
Actions reports for the exact commit and device. Legacy and modern Android storage paths differ;
a conditional API-specific test is not evidence for the untested path.

`detekt` uses the existing `config/detekt/baseline.xml`; a successful run means no findings beyond
that baseline, not that every historical finding has been eliminated. `ktlint` follows
`.editorconfig`. No benchmark module or exhaustive physical-device test campaign is claimed.

The APK/AAB preflight checks archive integrity, packaged native-library inventory and 16 KB binary
alignment. It does not replace a 16 KB runtime test, Play's generated-split checks, signing
verification or Play Console review.

---

## 📦 Building for release

Keep the upload keystore outside source control. Create a git-ignored `keystore.properties` at the
repository root:

```properties
storeFile=/absolute/path/to/private-upload-key.jks
storePassword=YOUR_PRIVATE_STORE_PASSWORD
keyAlias=YOUR_UPLOAD_ALIAS
keyPassword=YOUR_PRIVATE_KEY_PASSWORD
```

A relative `storeFile` is resolved from the repository root. Preserve the same upload-key identity
for existing Play applications; do not generate a replacement casually. Never paste signing
passwords or keys into issues, source files, build artifacts or public logs.

```bash
./gradlew bundleRelease -PrequireReleaseSigning=true
```

The production command fails when signing inputs are missing. An incomplete properties file or a
missing keystore also fails configuration. Without the opt-in flag and without a properties file,
CI may deliberately build an **unsigned candidate**, which is not a Play-upload-ready artifact.
Release builds run R8 and resource shrinking. Confirm the final bundle's signature, application ID,
version code and packaged SDK behavior before uploading to an internal test track.

The app includes an offline privacy policy in Settings; the matching public-policy source is
[docs/privacy-policy.md](docs/privacy-policy.md). Android backup/device transfer follows device
settings and may use the device owner's cloud account. Exported copies can be handled by external
apps/providers. A missing network permission does not exempt an app from Play's Data safety form.

See [release readiness](docs/RELEASE_READINESS.md), [store listing](docs/play-listing.md) and the
unchanged future-feature goals in [agent.md](agent.md). Build success alone does not certify
production readiness or completion of all 50 phases.

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
  alpha lock, masks and custom pressure — ✅; textures — 🟡 *three procedural grains, no custom import*;
  transform — 🟡 *translate only*
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
