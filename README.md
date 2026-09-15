# ArtFlow — Digital Art Studio for Android

ArtFlow is an Android digital-painting application built with Kotlin and Jetpack Compose.
The repository is a **work-in-progress skeleton**: the architecture, domain models, managers
and dependency-injection graph exist, but many user-facing features are still stubs.

> **Documentation accuracy note.** Earlier revisions of this README described a finished
> product (100+ brushes, PSD/animation export, cloud sync, collaboration, Vulkan rendering).
> None of that is implemented. This file now describes what is actually in the repository.
> See [Project status](#-project-status) for the precise list of what works and what does not.

---

## 🎨 What is implemented

### Canvas & rendering
- `GLSurfaceView`-based canvas (`ArtFlowCanvasView`) driven by `OpenGLCanvasRenderer`
- OpenGL **ES 2.0** rendering (`GLES20`) that draws stroke points as point sprites via
  `glDrawArrays(GL_POINTS, …)`
- Separation between the logical canvas (`CanvasRepositoryImpl`) and the GL renderer
- Zoom / pan / pinch gestures handled in `ArtFlowCanvasView.onTouchEvent`
- A dedicated gesture helper (`CanvasGestureDetector`) for two/three-finger taps and rotation

### Brush engine
- `BrushEngine` + `StrokeBuilder` producing pressure-sensitive strokes
- Stroke smoothing and start/end tapering
- Advanced parameters: size / opacity / hue / saturation / brightness jitter, scatter,
  count, spacing, wet mix, flow, tilt influence, velocity dynamics
- Pressure-curve types (linear, ease-in, ease-out, ease-in-out)
- Brush preset factory (`CreateBrushPreset`) and preset catalog (`UpdateAdvancedBrushParams`)
- Texture *models* and a `TextureMapper` for textured dabs (texture assets are not bundled)

### Layers
- `LayerManager`: add / remove / reorder / duplicate / merge, visibility, opacity, rename,
  lock, alpha lock, clipping masks, and a partial undo/redo history
- `LayerMaskManager`: create, paint, fill, invert, feather and thumbnail grayscale masks
- `LayerGroupManager`: nested groups, layer-to-group membership, expansion state
- `AdjustmentLayerManager`: eight adjustment types with validated parameter ranges

### Selection, shapes and transforms
- `SelectionManager`: rectangle, ellipse, freehand, lasso
- `ColorSelectionAlgorithm`: magic-wand selection (flood fill + global colour matching)
  with tolerance, anti-aliasing and progress reporting
- `ShapeManager` + vector shape models (rectangle, ellipse, polygon, line)
- `TransformManager`: translate, rotate, scale, skew, perspective, distortion and snapping

### Data & architecture
- Room persistence for projects, brushes and settings (`ProjectDao`, `BrushDao`, `SettingsDao`)
- Clean-ish layering: `domain` (models + repository interfaces + use cases),
  `data` (Room, repositories, renderers), `presentation` (Compose UI + ViewModels)
- Hilt dependency injection across all layers
- Timber logging, Coil for image loading, Navigation Compose for routing
- Project gallery with project creation and canvas navigation

---

## 🚧 Project status

The table below reflects the code that is currently in the repository.

| Area | Status |
|------|--------|
| Project gallery (list + create) | Implemented, thumbnails are placeholders |
| Canvas screen + GL surface | Implemented; renderer draws flat point sprites |
| Brush engine + parameters | Implemented (CPU-side models and logic) |
| Timed brush textures | Models + mapper implemented; **no texture assets** shipped |
| Layers / masks / groups / adjustments | Managers implemented; UI panels present but not all wired |
| Selection / shapes / transforms | Managers implemented; UI panels are partial |
| Undo / redo | Partial (layer and transform histories; merge/redo incomplete) |
| Save / load projects | Room metadata only — canvas pixels are **not** serialized |
| Export (PNG/JPEG/PSD/PDF/GIF/MP4) | **Not implemented** (see `Phase 38–40` in `agent.md`) |
| Cloud sync / collaboration | **Not implemented** |
| Animation timeline / timelapse | **Not implemented** |
| Settings, tutorials, accessibility | **Not implemented** |
| Native C++ brush engine | Built via CMake, **not wired** into the Kotlin render path |

`TODO` markers in the source indicate the intended follow-up work. Use:

```bash
grep -rn "TODO" app/src/main
```

---

## 📱 System requirements

- **OS**: Android 8.0 (API 26) or higher
- **Target SDK**: 34
- **RAM**: 4 GB recommended for large canvases
- **Stylus**: pressure-sensitive stylus optional; finger input falls back to
  `MotionEvent.size` as a pressure proxy

---

## 🏗️ Architecture

### Tech stack

- **Language**: Kotlin (there are **no Java sources**)
- **UI**: Jetpack Compose with Material 3 (dark-first theme)
- **Graphics**: OpenGL ES 2.0 through `GLSurfaceView` / `GLES20`
- **Native**: C++ via CMake + NDK (JNI), currently unused by the render path
- **Architecture**: MVVM with a layered `domain` / `data` / `presentation` split
- **DI**: Hilt (Dagger)
- **Async**: Kotlin Coroutines + Flow / StateFlow
- **Persistence**: Room
- **Image loading**: Coil
- **Logging**: Timber

The top-level Gradle build also declares the Kotlin serialization plugin as
`apply false`; no module currently enables it.

### Actual project structure

```
app/src/main/java/com/artflow/studio/
├── core/                       # Stateful engine/managers (singletons)
│   ├── brush/                  # BrushEngine, TextureMapper
│   ├── layer/                  # Layer, LayerGroup, LayerMask, AdjustmentLayer managers
│   ├── selection/              # SelectionManager, ColorSelectionAlgorithm
│   ├── shape/                  # ShapeManager
│   └── transform/              # TransformManager
├── domain/
│   ├── model/                  # Color, Project, brush/, layer/, selection/, shape/, texture/, transform/
│   ├── repository/             # Project / canvas / texture repository interfaces
│   └── usecase/                # One class per operation (canvas, layer, brush, selection, shape, transform, texture)
├── data/
│   ├── local/                  # Room database, DAOs, entities
│   ├── repository/             # Repository implementations
│   └── renderer/               # OpenGL renderer + JNI wrapper
├── di/                         # Hilt modules
└── presentation/
    └── ui/                     # Compose app, screens, components, theme, ViewModels

app/src/main/jni/               # C++ sources built by CMakeLists.txt
app/src/test/                   # JVM unit tests (JUnit)
```

There are no `core/renderer`, `core/color` or `core/geometry` packages (an earlier version
of this document claimed otherwise); the renderer lives in `data/renderer`.

---

## 🚀 Getting started

### Prerequisites
- Android Studio Hedgehog (2023.1.1) or later, **or** a local Gradle install
- JDK 17
- Android SDK with API 34
- NDK (only required to build the native `libartflow-brush` module)

### ⚠️ No Gradle wrapper is committed

`gradlew` / `gradle/wrapper/` are **not** part of the repository, so the historically
documented `./gradlew …` commands do not work in a fresh clone. Either:

1. Open the project in Android Studio (it will supply a compatible Gradle), or
2. Generate the wrapper once, then use `./gradlew`:

```bash
gradle wrapper --gradle-version 8.2
```

Configuration notes:
- `minSdk = 26`, `targetSdk = 34`, `compileSdk = 34`
- Build variants: `debug`, `release`, `benchmark`
- Java/Kotlin target: 17
- `app/src/main/jni/CMakeLists.txt` builds the shared library `libartflow-brush.so`

---

## 🧪 Testing

JVM unit tests live in `app/src/test`:

```bash
# Using the wrapper (after generating it)
./gradlew testDebugUnitTest

# Or with a local Gradle install
gradle testDebugUnitTest
```

There is currently **no instrumentation test source set** (`app/src/androidTest` does not
exist), and no benchmark module, so `connectedAndroidTest` and `benchmark` have nothing to
run yet.

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
gradle assembleRelease
gradle bundleRelease
```

`local.properties` (SDK/NDK paths) and keystores are git-ignored — never commit them.

---

## 🤝 Contributing

1. Fork the repository and create a feature branch.
2. Make your changes and add/update tests.
3. Ensure the project builds and tests pass.
4. Open a pull request.

### Code style
Follow the [Kotlin coding conventions](https://kotlinlang.org/docs/coding-conventions.html).

`ktlint` and `detekt` are **not** configured in this repository, so `./gradlew ktlintCheck`
and `./gradlew ktlintFormat` (mentioned in older revisions of this document) do not exist.
Adding them would require the corresponding plugins and configuration.

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
  brush engine, layers — *substantially present*
- **Core drawing (9–16)**: advanced brush params, textures, colour dynamics, blend modes,
  selection, transform, alpha lock, masks — *models and managers present, UI partial*
- **Professional tools (17–24)**: smudge, liquify, clone, heal, gradient, fill, text, shapes —
  *mostly not implemented*
- **Advanced layers (25–30)**: adjustments, groups, reference layers, filters, smart objects —
  *adjustments and groups implemented, the rest not*
- **Colour & canvas (31–36)**: pickers, palettes, symmetry, perspective, canvas properties,
  quick menu — *not implemented*
- **File operations (37–42)**: save, PNG/JPEG, PSD, PDF, gallery, cloud sync — *gallery only*
- **Animation & advanced (43–50)**: timeline, animation export, timelapse, performance,
  tutorials, settings, QA, release — *not implemented*

---

## 📞 Support

- Issue tracker: open an issue on the repository hosting this project
- The `https://docs.artflow.studio`, Discord, and email addresses used in earlier revisions of
  this document are placeholders and do not resolve

---

<div align="center">

**Built with Kotlin and Jetpack Compose for Android artists**

</div>
