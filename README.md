# ArtFlow — Digital Art Studio for Android

A native, offline painting studio built with **Kotlin, Jetpack Compose and a shared pixel engine**.
Create artwork, paint with pressure-sensitive brushes, compose layers and masks, animate frames,
and save or export real documents.

**Working editor. Not yet Procreate-equivalent or approved for a production release.**
A similar appearance, passing builds or a long feature list do not prove equivalent drawing feel,
reliability or usability. The [Procreate workflow comparison](docs/PROCREATE_PARITY.md) records
what remains; [release readiness](docs/RELEASE_READINESS.md) records the separate distribution gates.

## New: Brush Studio

Open **Brush** in the editor for three connected workspaces:

| Workspace | What you can do |
| --- | --- |
| **Library** | Search eight original starter presets, filter Sketch / Ink / Texture / Paint, and compare real-engine stroke samples. |
| **Settings** | Edit size, opacity, spacing, smoothing, pressure curves, taper, grains, scatter, jitter, rotation and wet mix. Tap a displayed value for exact numerical entry. |
| **Drawing pad** | Test your own marks without touching the artwork. Change settings and return to see the same marks re-rendered with the new brush. Clear the pad independently. |

Edits are **staged**: **Use brush** applies the draft; Close, Back or outside dismissal discards it.
**Restore initial** restores the settings present when the studio opened. Applying selects the
painting tool while preserving an already-active brush mask destination. Selecting a preset or
scribbling on the pad does not create document edits or undo entries. The studio cancels an active
canvas gesture before opening, and its settings area scrolls within the available screen.

Numerical entry supports decimal points and decimal commas. Percentage values map to the engine's
0–1 ranges. Invalid/out-of-range entries cannot be confirmed; count and integer rotation controls
reject fractional values. Validation does not silently replace an invalid entry with a boundary.

The presets are ArtFlow-authored starting points, **not** Procreate assets or an artist-validated
professional brush collection. Samples and practice marks use the real `StrokeRasterizer` on a
worker dispatcher. The practice surface is **320×180**, keeps the last **8 strokes**, caps each at
**128 points**, and fits brush sizes to **48 preview pixels**. It is not a full-resolution canvas or
a physical stylus benchmark. Custom preset persistence/import, imported shapes/grains, dual brushes
and a comparable curated brush library remain unfinished.

## Other recent studio improvements

**Canvas-first controls.** Brush, Smudge and Eraser stay in the compact dock. **All tools** expands
the collection; a selected secondary tool stays visible afterwards. **Workspace menu → Focus mode**
hides editor bars without replacing the GL drawing surface. A visible exit control or Android Back
restores the workspace before an unsaved-document prompt. Titles truncate rather than crowding controls.

**Reference companion.** **Workspace menu → Reference image → Choose image** opens the Android
picker. Drag the window header, pan/pinch the image, use zoom/fit, and sample with pick mode or a
long press. A centre-sample accessibility action is available. Preview dimensions are bounded to
1024 pixels per axis; letterboxing cannot supply false colour samples. Only granted `content://`
images are decoded, without new storage/network permissions. References do not enter layers or
exports. Source embedding, indefinite URI grants, manual window resizing and a live canvas reference
are not implemented; sampling is from the decoded preview, not an ICC-managed full-size source.

**Connected editing workflows.** Guides, Animation, Canvas and Text now have actual workspace-menu
routes and explicit close buttons. Text follows **edit → Place on canvas → tap position → confirm**;
cancelling an anchor does not stamp text. Layers display topmost-first with working up/down ordering
and an opacity control on the active row. Canvas background state follows edits and undo/redo rather
than always showing white. Large-target preferences and named controls improve accessibility.

## Capabilities and limits

| Area | Implemented | Still missing or unverified |
| --- | --- | --- |
| Painting | Pressure, smoothing/taper, flow, wet mix, jitter/scatter, procedural grains, custom monotone pressure response, draft Brush Studio and test pad | Imported/dual textures, persistent custom preset management and measured physical-stylus parity |
| Tools | Brush, eraser, smudge, clone, healing, liquify, bucket, gradients, text and shapes | Shape tools are not QuickShape recognition; no ColorDrop-style drag workflow |
| Selections | Rectangle, ellipse, freehand/lasso, magic wand, boolean combining, invert and feather | Equivalent selection ergonomics and reusable saved selections |
| Transform | MOVE/TRANSFORM drag translation | Interactive rotate/scale/skew/perspective/warp/snapping with preview/apply/cancel |
| Layers | Add/remove/duplicate/reorder, visibility, opacity, blending, alpha lock, clipping, masks, adjustments and filters | Nested groups, linking UI and fill-reference layer workflow |
| Colour and guides | Wheel, RGB/HSV/CMYK controls, hex, harmonies, palettes, symmetry and perspective | CMYK sliders are not ICC-managed painting or print fidelity |
| Canvas | Resize/crop/rotate/flip, trim, DPI and background controls | Large-document performance and broader device validation |
| Animation | Frames, duplication/order/duration/FPS, onion skin, playback and animated exports | Drawing-process timelapse, Page Assist and 3D painting |
| Documents | Atomic `.artflow` storage, pixels/masks/frames, thumbnails, autosave and recovery | Broader process-death, low-storage and long-session acceptance |
| Export | PNG, JPEG, WebP, PDF, PSD, GIF, MP4, frame-sequence ZIP; gallery, picker and sharing | PSD import and broader external-application interoperability fixtures |

Cloud collaboration and smart objects are separate roadmap ideas, not asserted Procreate features.
The app declares no internet permission; analytics and crash telemetry are deliberately absent.
A missing telemetry service is not a painting-quality defect.

## Architecture and artwork safety

```text
app/src/main/java/com/artflow/studio/
├── core/           Pixel buffers, strokes, compositing, tools, guides and codecs
├── domain/         Models, original brush presets and repository contracts
├── data/           Room, atomic document storage, repositories, rendering and export
├── di/             Hilt modules
└── presentation/   Compose screens/components, ViewModels and ArtFlowCanvasView

app/src/test/         JVM regression suites
app/src/androidTest/  Real input, storage, rendering, export and Compose regressions
.github/              CI, artifact validation and diagnostic collection
```

`StrokeRasterizer` paints CPU-side `PixelBuffer` data. `Compositor` applies blending, opacity,
clipping, masks, adjustments and filters; `OpenGLCanvasRenderer` displays the result through
**OpenGL ES 2.0**. This is not Vulkan. The C++ source in `app/src/main/jni` is an unintegrated
prototype, not built or packaged. ViewModels use repository interfaces directly; there is no
separate use-case layer. Room stores metadata; `.artflow` stores artwork data. The stack also uses
Coroutines/Flow, kotlinx.serialization, Hilt, Coil, Navigation Compose and Timber.

Provisional edits are separate from committed artwork; saving/exporting reads committed content.
Destructive operations prepare replacements before one undo commit, reject stale edit sessions,
and refuse unsupported backdrop-dependent merges instead of silently changing the image. Empty
selections select nothing. Canvas transforms retain legacy vector ink and masks across animation
frames. History is snapshot-based, not a proven unbounded-memory system. Reference images and the
Brush Studio practice surface are deliberately outside document history.

## Build and verification

Use **JDK 17**, the committed **Gradle 8.14.3** wrapper, Android SDK **36** and platform-tools.
Set `ANDROID_HOME` or a git-ignored `local.properties` with `sdk.dir=...`. Android Studio must
support AGP 8.10.1. Normal builds require no NDK or `-PnoNativeBuild`. Minimum Android is **API 26**;
compile/target SDK is 36. On Windows use `gradlew.bat`.

```bash
./gradlew testDebugUnitTest ktlintCheck detekt lintDebug lintRelease
./gradlew assembleDebug bundleRelease
./gradlew connectedDebugAndroidTest
python -m unittest discover -s .github/scripts -p 'test_*.py'
python .github/scripts/verify_android_artifacts.py app/build/outputs/apk/debug/app-debug.apk app/build/outputs/bundle/release/app-release.aab
```

CI retains all five device configurations: API 26, API 35/16 KB, API 36/4 KB, an independent
API 35/16 KB repeat and API 36/16 KB. Each includes the instrumentation suite and minified launch.
`benchmark` is a release-like smoke-test variant, **not** a performance benchmark suite.

The Brush Studio milestone adds seven shared core/JUnit checks and UI tests for search, staged
apply/cancel/reset, exact values, drawing-pad retention/cancellation and bounded history. The real
artwork workflow also tests that practice does not alter canvas pixels or undo depth. Local
standalone compilation executed the actual brush renderer/math with compile-only platform adapters;
all seven groups passed, as did 30 Python verifier tests. This is not a substitute for exact-commit
Gradle, JUnit, Android or static-analysis results.

Prior candidate `68d29a3` passed its JVM step after repairing asynchronous metadata-test timing;
remaining formatting findings in reference/evidence code were corrected without suppressing rules.
Consult the **completed run for the exact revision** before calling a candidate verified. Failed
attempts remain documented in the [comparison register](docs/PROCREATE_PARITY.md).

`detekt` retains the existing baseline. A pass does not erase historical findings, Android lint
warnings or untested device conditions. `TestEvidence` saves actual screenshots through UTP's
`additionalTestOutputDir`; CI archives `connected_android_test_additional_output`. These are running
app captures, not design mockups. Emulator results do not establish physical latency or artist approval.

## Signed release candidates

Keep the upload keystore outside Git. Put private signing inputs in ignored `keystore.properties`:

```properties
storeFile=/absolute/path/to/private-upload-key.jks
storePassword=YOUR_PRIVATE_STORE_PASSWORD
keyAlias=YOUR_UPLOAD_ALIAS
keyPassword=YOUR_PRIVATE_KEY_PASSWORD
```

Relative `storeFile` paths resolve from the repository root. Preserve the upload-key identity for
an existing Play app. Never commit keys/passwords. Require signing explicitly:

```bash
./gradlew bundleRelease -PrequireReleaseSigning=true
```

This rejects missing signing inputs. A normal unsigned CI AAB is **not** a Play-upload-ready release.
Verify the final signature, application ID/version, generated splits and Play Console requirements.
The artifact verifier does not replace runtime 16 KB testing or distribution review.

## Documentation and privacy

[Procreate comparison](docs/PROCREATE_PARITY.md) · [Release readiness](docs/RELEASE_READINESS.md) ·
[Original plan](agent.md) · [Store listing](docs/play-listing.md) ·
[Privacy policy](docs/privacy-policy.md) · [Contributing](CONTRIBUTING.md)

The privacy policy is available offline in Settings. Android backup/transfer follows the device
owner's settings and may use their cloud account. External apps/providers can handle exported files.
The publisher still needs to complete Play's Data safety declarations.

Update this README and the comparison register with every landed improvement, including limits and
executed evidence. A class, placeholder control or unexecuted test is not a completed workflow.

## License

GNU General Public License v3.0. See [LICENSE](LICENSE).
