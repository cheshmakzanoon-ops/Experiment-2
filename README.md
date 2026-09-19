# ArtFlow — Digital Art Studio for Android

A native Android painting studio built with **Kotlin, Jetpack Compose and a shared pixel engine**.
Create an artwork, paint with pressure-sensitive tools, work with layers and masks, animate frames,
and save or export real documents. The application works offline; it declares no internet permission.

**Status: working editor, not yet Procreate-equivalent or signed off for Google Play.**
Passing tests, a large feature list and a similar colour scheme are not proof of equivalent drawing
feel, visual polish or reliability. [Procreate comparison and acceptance gates](docs/PROCREATE_PARITY.md)
record the remaining product gaps. [Release readiness](docs/RELEASE_READINESS.md) records release work.

## Latest improvement — canvas-first workspace

The painting dock now keeps **Brush, Smudge and Eraser** immediately available. **All tools** expands
the existing collection; selecting another tool keeps it visible after the collection collapses.
Tool buttons expose their toggle state to accessibility services and honour larger touch targets.

**Workspace menu → Focus mode** hides the editor bars without recreating the drawing surface.
The visible exit button or Android Back restores the workspace before any unsaved-document exit
prompt. Active input is cancelled before layout changes so a gesture cannot bridge changed canvas
geometry. Project titles truncate instead of wrapping into controls. The saved-state icon no longer
suggests nonexistent cloud storage.

`StudioToolDockTest` exercises primary/secondary tool navigation. `ArtworkWorkflowTest` additionally
checks focus entry/exit, the same GL view instance, increased drawing area, unchanged pixels and
unchanged undo history. Device CI collects normal/focus screenshots for inspection. The presence
of these tests is not a recorded pass: consult the completed run for the exact commit.

## What works, and where it stops

| Area | Implemented | Important limits |
| --- | --- | --- |
| Workspace | Compact painting dock, expandable tools, focus mode, brush options, quick menu, sheet panels, gallery and settings | No movable/handedness-aware sidebar or artist-validated tablet panel layout |
| Painting | Pressure-sensitive strokes, smoothing, taper, scatter/jitter, flow, wet mix, tilt/velocity dynamics, editable monotone pressure curve | Three procedural grains only; no imported/dual textures or measured Procreate-level stylus response |
| Pixel tools | Brush, eraser, smudge, clone, healing, liquify, bucket, gradients, text and shapes | A shape tool is not QuickShape recognition |
| Selections | Rectangle, ellipse, freehand/lasso, magic wand, boolean combining, invert and feather | No claim of equivalent selection gesture ergonomics |
| Transform | MOVE/TRANSFORM drag translation | No reachable rotate/scale/skew/perspective/warp/snapping workflow |
| Layers | Add, remove, reorder, duplicate, visibility, opacity, blend modes, alpha lock, clipping, masks, adjustments and filters | No layer groups or linking UI; the reference flag has no user control |
| Colour and guides | Wheel, RGB/HSV/CMYK controls, hex, harmonies, saved palettes, symmetry and perspective guides | CMYK sliders are not an ICC-managed print workflow |
| Canvas operations | Resize, crop, rotate, flip, trim/expand, DPI and presets | Large-document/device performance still needs measurement |
| Animation | Frames, duplication, ordering, duration/FPS, onion skin and playback | Drawing-process timelapse is absent |
| Documents | Atomic `.artflow` saves, per-layer pixels, frames, autosave/recovery and thumbnails | Physical interruption/low-storage/long-session acceptance is still required |
| Export | PNG, JPEG, WebP, PDF, PSD, GIF, MP4 and PNG frame-sequence ZIP; document picker, gallery and sharing | PSD **import** is absent; external-application interoperability needs broader fixtures |

### Engine and document safety

`StrokeRasterizer` renders into `PixelBuffer` layer data. `Compositor` applies blend modes,
opacity, clipping, masks, adjustments and filters. `OpenGLCanvasRenderer` displays the result
through **OpenGL ES 2.0**. Strokes and composition are CPU-side; this is not a Vulkan renderer.
The C++ prototype in `app/src/main/jni` is not built or packaged by the application.

Provisional pixel edits are separate from committed artwork. Saving/exporting uses committed
content. Destructive document operations prepare replacements before committing one undo step;
stale edit sessions are rejected. Merges reject unsupported backdrop-dependent combinations rather
than silently changing artwork. Empty selections select nothing, not the whole canvas. Canvas
transforms retain legacy vector ink and masks across animation frames. Snapshot history supplies
undo/redo; it is not a measured unbounded-memory history system.

Adjustment layers support brightness/contrast, hue/saturation, colour balance, curves, levels,
invert, posterize, selective colour and gradient mapping. Filters include blur, sharpen, noise,
chromatic aberration, vignette, tilt-shift, edges and emboss. Empty filter layers affect the stack
below; layers with their own pixels retain per-layer effects. Masks/opacity control the result.

### Explicitly unfinished

Layer groups; transforms beyond translation; custom/dual brush textures; a reference companion;
layer-linking controls; PSD import; QuickShape-style recognition; ICC-managed colour; drawing
timelapse; Page Assist and 3D painting; measured physical-stylus/performance parity.
Cloud sync, collaboration and smart objects are separate roadmap ideas, not asserted Procreate
features. Analytics/crash reporting are intentionally absent; telemetry is not a parity requirement.
See the [workflow comparison](docs/PROCREATE_PARITY.md) instead of treating source markers as progress.

## Build and run

Use **JDK 17**, the committed **Gradle 8.14.3** wrapper, Android SDK **36** and platform-tools.
Set `ANDROID_HOME` or a git-ignored `local.properties` with `sdk.dir=...`. Android Studio must
support Android Gradle Plugin 8.10.1. Normal builds do not require an NDK or `-PnoNativeBuild`.
The app supports Android **8.0 / API 26+**, with compile/target SDK 36.

Allow at least 6 GB of build memory plus emulator memory. The daemon budgets 2.5 GB heap and
1 GB metaspace, with two workers and the in-process Kotlin compiler. These are build settings,
not measured minimum app RAM. A pressure-sensitive stylus is optional; finger input is supported.

```bash
./gradlew testDebugUnitTest ktlintCheck detekt lintDebug lintRelease
./gradlew assembleDebug bundleRelease
./gradlew connectedDebugAndroidTest
python -m unittest discover -s .github/scripts -p 'test_*.py'
python .github/scripts/verify_android_artifacts.py app/build/outputs/apk/debug/app-debug.apk app/build/outputs/bundle/release/app-release.aab
```

On Windows use `gradlew.bat` and your Python command. Device tests require a running API 26+
device/emulator. Variants are `debug`, `release` and `benchmark`; the last is release-like for a
minified launch smoke test, **not** a performance benchmark suite.

## Architecture

```text
app/src/main/java/com/artflow/studio/
├── core/           Pure Kotlin pixels, strokes, compositing, tools, guides and codecs
├── domain/         Immutable models and repository contracts
├── data/           Room, atomic document storage, repositories, rendering and export
├── di/             Hilt modules
└── presentation/   Compose screens/components, ViewModels and ArtFlowCanvasView

app/src/test/         JVM regression suites
app/src/androidTest/  Real input, storage, rendering, export and Compose regressions
.github/              CI, artifact verification and diagnostic collection
```

ViewModels use repository interfaces directly; there is no separate use-case layer. Room stores
project/brush/settings metadata; `.artflow` documents store artwork data. Dependencies include
Coroutines/Flow, kotlinx.serialization, Hilt, Coil, Navigation Compose and Timber. The application
source language is Kotlin. The unintegrated C++ source is not the active painting engine.

## Verification and evidence

CI runs JVM tests, Python verifier tests, ktlint, detekt, debug/release Android lint, APK/AAB builds,
artifact checks and device suites on API 26/35/36, including 16 KB configurations and minified launch.
Read the **completed exact-commit run**, not just a badge or the latest run number.

Tests cover pixel/selection/fill kernels, deterministic rendering, codecs, document round-trips,
recovery, stale edits, duplication, actual GL output, export/provider isolation, animation timing
and selected UI workflows. This is not exhaustive coverage of every device, codec, low-memory
condition, physical stylus, process interruption or long drawing session.

`detekt` retains the existing `config/detekt/baseline.xml`; a pass does not erase historical findings.
Android lint warnings likewise must be reviewed, not described as fixed merely because lint passes.
The artifact verifier checks archive integrity, native inventory and alignment. It does not replace
16 KB runtime tests, Play-generated split checks, signing verification or Play Console review.

Workspace screenshots from instrumentation are collected under `test-evidence` by the existing
CI diagnostic script. They are evidence of the running build, not substitute design mockups.

## Private-key release build

Keep the upload keystore outside source control. Use a git-ignored root `keystore.properties`:

```properties
storeFile=/absolute/path/to/private-upload-key.jks
storePassword=YOUR_PRIVATE_STORE_PASSWORD
keyAlias=YOUR_UPLOAD_ALIAS
keyPassword=YOUR_PRIVATE_KEY_PASSWORD
```

A relative `storeFile` resolves from the repository root. Preserve the existing upload-key identity
for an existing Play app. Never publish passwords, signing keys or private files in source or logs.

```bash
./gradlew bundleRelease -PrequireReleaseSigning=true
```

This production command rejects missing signing inputs. Without the flag and without signing
properties, CI may build an **unsigned candidate**, not a Play-upload-ready release. Release uses
R8/resource shrinking. Verify the final signature, application ID, version code and generated splits.

## Documentation and privacy

[Product comparison](docs/PROCREATE_PARITY.md) · [Release readiness](docs/RELEASE_READINESS.md) ·
[Original 50-phase plan](agent.md) · [Store listing](docs/play-listing.md) ·
[Privacy policy](docs/privacy-policy.md) · [Contributing](CONTRIBUTING.md)

The app includes its policy offline in Settings. Android backup/device transfer follows the device
owner's settings and may use their cloud account. Exported files can be handled by external apps
and providers. No internet permission does not exempt a publisher from Play's Data safety form.

Update the README and comparison status with every landed improvement, including its limitations
and executed evidence. Do not mark a roadmap phase complete because a class or test exists.
Report reproducible problems through this repository's issue tracker.

## License

GNU General Public License v3.0. See [LICENSE](LICENSE).
