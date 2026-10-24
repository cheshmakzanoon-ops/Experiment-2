# ArtFlow — Digital Art Studio for Android

A native, offline painting studio built with Kotlin, Jetpack Compose and a shared pixel engine.
Paint with pressure-sensitive brushes, edit layers and masks, animate frames, paint imported 3D
models, and save or export real documents.

**Working editor. Full Procreate feature and quality parity remains unestablished.**
The [comparison](docs/PROCREATE_PARITY.md) records implemented workflows and remaining gaps;
[release readiness](docs/RELEASE_READINESS.md) tracks the separate distribution requirements.

## Current development — 24 October 2026

This revision targets three concrete gaps discovered while following the emulator tests:

- **Smudge input and continuity:** use the selected Smudge brush's opacity, flow, pressure and
  tilt response; carry dab spacing across input events and include the release position.
  Fast gestures must not leave unstamped gaps, and the opacity controls must edit the active tool.
- **Clipboard integrity:** prepare Paste, Copy & Paste and Cut & Paste before changing the
  document, commit each as one undo action, and preserve source pixels, prior clipboard and history
  when the project has reached its layer limit. Material maps retain their channel when copied.
- **3D material painting:** independently edit Color, Metallic and Roughness on persisted layers,
  preview their effect on the model, and preserve the maps through undo, save/reopen and model export.

The [implementation and verification record](docs/PARITY_FOLLOW_THROUGH_2026-10-24.md) distinguishes
implemented changes, executed local checks and published-revision device results. Emulator results
cannot establish physical stylus feel, native Procreate rendering equivalence or artist acceptance.

## GitHub Actions acceptance

The required matrix covers Android 8 (API 26), Android 15 (API 35, 4 KB and two independent 16 KB
lanes), and Android 16 (API 36, 4 KB, 16 KB and a Pixel C tablet). Every lane inventories the declared
tests, rejects missing or unexpected outcomes, retains original failure evidence and requires a
minified launch. The two existing Android 8 skips concern APIs unavailable on that OS.

[Run 314](docs/CI_RUN_314_2026-10-24.md) passed its final seven-lane matrix on `47612ae`, including
one narrowly classified Android 15 recovery. That result applies to its own source revision.
The subsequent [run 315](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/runs/40134242551)
on `9b868dc` passed its build checks but both Android 15 / 16 KB lanes crashed inside ART with
incomplete test coverage. The retained runtime failures remain unresolved; neither a feature
implementation nor a later successful lane establishes an Android runtime repair.

[Run 316](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/runs/40139301647) on `086ebd8`
stopped at two new clipboard concurrency-test preconditions, before the emulator matrix. Its
follow-up controls worker scheduling explicitly and preserves the full safety assertions; see the
[verification record](docs/PARITY_FOLLOW_THROUGH_2026-10-24.md) for the original failure evidence.

## Editor workflows

The workspace has a canvas toolbar, size/opacity sidebar, left/right-handed layouts, hide-interface
mode, gesture shortcuts and a reference-image companion. Brush Studio stages changes until
**Use brush**, with a live drawing pad, exact-value controls, saved copies, custom brush sets,
imported tips/grains, and brush sharing. Paint, Smudge and Erase have selectable brushes.

Layers support masks, clipping, alpha lock and nested groups. Selection and transformation tools
operate on real pixels with cancel and undo. Actions exposes document, export, animation, time-lapse,
input and display settings. The application owns its assets and identity; Procreate is the workflow
reference, not a claim of original-app source recovery.

## Reference and historical evidence

Native file and brush investigations use the supplied, SHA-256-pinned Procreate 5.4.4 / 56832 IPA
as an analysis input. Raw paid assets and executables are not redistributed. Archive-data retention
and reconstructed arithmetic are distinct from executing the original application.

- [All 39 native brush pressure curves](docs/NATIVE_PRESSURE_CURVE_PRESERVATION_2026-10-24.md),
  [native spline](docs/NATIVE_PRESSURE_SPLINE.md), [Count](docs/NATIVE_BRUSH_COUNT.md), and
  [spacing interchange](docs/NATIVE_SPACING_EXPORT_2026-10-24.md).
- [Native document masks](docs/PROCREATE_MASK_IMPORT_2026-10-24.md),
  [hierarchy](docs/PROCREATE_HIERARCHY_IMPORT_2026-10-24.md),
  [color profiles](docs/PROCREATE_COLOR_IMPORT_2026-10-24.md),
  [Darken blending](docs/NATIVE_LAYER_DARKEN_2026-10-24.md), and
  [native export](docs/PROCREATE_NATIVE_EXPORT_2026-10-24.md).
- [Wet-preview integrity](docs/WET_PREVIEW_HISTORY_2026-10-24.md),
  [model input and display](docs/MODEL_EDITOR_2026-10-24.md), and
  [measured preview-call performance](docs/PREVIEW_PERFORMANCE_2026-10-24.md).
- [Earlier development](docs/DEVELOPMENT_LOG_2026-10-17.md),
  [emulator corrections](docs/EMULATOR_PARITY_2026-10-24.md), and
  [Android 15 repair investigation](docs/ANDROID15_OFFICIAL_REPAIR_REVIEW_2026-10-24.md).

## Capability boundaries

| Area | Available | Important gaps |
| --- | --- | --- |
| Painting | Pressure/dynamics, smoothing, taper, flow, wet mix, jitter, procedural and imported grains, tilt, dual brushes, staged Brush Studio | Native wet/compound/grain equivalence and unused retained pressure graphs; large-canvas responsiveness, curated-library and physical stylus validation |
| Tools | Brush, eraser, continuous pressure/opacity/flow-aware Smudge, clone, healing, liquify, bucket, gradients, text and shapes; QuickShape recognition with hold-to-adjust and Edit Shape; ColorDrop with adjustable threshold and Continue Filling | Artist validation of shape recognition and handle gestures; comparative fill thresholds and anti-aliased edge quality |
| Selections | Automatic samples the active layer with an adjustable threshold; freehand, rectangle and ellipse; Add/Remove/Intersect, invert/feather and Save & Load; Remove without an existing selection keeps it empty | Gesture ergonomics and artist validation; comparative reference-app thresholds and selection-edge quality |
| Transform | Freeform, uniform, distort and warp with snapping, cancel and undo | Device validation of large multi-layer moves |
| Layers | Pixel/mask/effect workflows, nested groups, clipping, alpha lock, reorder; atomic clipboard commands | Merge Down bakes opacity and blend mode into pixels and does not carry alpha lock or clipping |
| Colour | Disc, Classic (HSB), Value (RGB/HSV/CMYK), palettes, harmonies, Display P3 canvases | Profile-managed CMYK and arbitrary ICC transforms; Classic model parity and real print-proof validation |
| Animation | Frames, duration/FPS, loop, one-shot and ping-pong; preview and animation export share playback range, frame visibility and order; background/foreground pins, separate previous/next onion colours, timelapse and GIF/MP4/APNG export | Reference hold-count semantics and artist-tested timing; long-animation playback/encoding performance and physical-device qualification; native `.procreate` animation metadata |
| 3D materials | Independent Color/Metallic/Roughness paint layers, live model preview, undo, persistence and OBJ scalar-map export | Single UV atlas and approximate lighting; imported scalar textures, independent source material sets, native/glTF/USDZ material export and physical GPU validation |
| Documents | Atomic signed `.artflow` v6 saves, thumbnails, autosave and recovery; older checksums verified before migration | Broader interruption, low-storage and long-session acceptance |
| Export | PNG/JPEG/WebP/PDF/PSD/TIFF/GIF/APNG/MP4, layer PNG zip and frame-sequence ZIP; `.procreate` with supported sRGB/Display P3 channels, a generated Display P3 profile, layer hierarchy and the native Darken contract; picker/gallery/sharing | Opening exported files in Procreate and broader external interoperability; native group blend/mask metadata, arbitrary ICC transforms and PSB. PSD retains supported independent masks; post-mask effects use baked masks, while resized enabled masks and native Darken use a rendered appearance layer with hidden editable sources |

The [parity register](docs/PROCREATE_PARITY.md) tracks the current implementation and remaining work.
Its dated sections, including *Parity sweep candidate*, retain their historical scope. The
[emulator verification record](docs/EMULATOR_PARITY_2026-10-24.md) records executed builds and device
results against exact revisions. Acceptance applies to the completed run's source and tested scope;
later changes require their own gate, and emulator coverage does not establish full Procreate parity.

Cloud collaboration and smart objects are separate roadmap ideas, not asserted Procreate features.
No internet permission, analytics or crash telemetry is added.

## Architecture and safety

`core/` holds pixels, strokes, compositing, tools, guides and codecs. `domain/` holds models and
repository contracts. `data/` contains Room metadata, atomic artwork storage, repositories and
export. `presentation/` contains Compose screens, ViewModels and `ArtFlowCanvasView`; `di/` uses Hilt.
The stack also uses Coroutines/Flow, kotlinx.serialization, Coil, Navigation Compose and Timber.

Strokes/composition run CPU-side on `PixelBuffer`; OpenGL ES 2.0 displays the result. This is not
Vulkan. The C++ prototype is not built or packaged. Provisional edits remain separate from committed
artwork. Destructive edits prepare replacements before one undo commit, reject stale sessions and
refuse unsupported backdrop-dependent merges. History is snapshot-based, not unbounded.

## Build and verify

Use **JDK 17**, committed **Gradle 8.14.3**, SDK **36**, platform-tools and AGP 8.10.1-compatible Android
Studio. Set `ANDROID_HOME` or an ignored `local.properties` with `sdk.dir`. Minimum Android is API 26.
No NDK is required. On Windows substitute `gradlew.bat`.

```bash
./gradlew testDebugUnitTest ktlintCheck detekt lintDebug lintRelease
./gradlew assembleDebug bundleRelease
./gradlew connectedDebugAndroidTest
python -m unittest discover -s .github/scripts -p 'test_*.py'
python .github/scripts/verify_android_artifacts.py app/build/outputs/apk/debug/app-debug.apk app/build/outputs/bundle/release/app-release.aab
```

CI requires API 26, API 35/4 KB, API 35/16 KB, an independent API 35/16 KB repeat, API 36/4 KB,
API 36/16 KB, and API 36 with a Pixel C tablet profile. Every lane retains the minified launch checks. `benchmark` is a release-like smoke-test variant, not a performance suite.
Read completed reports for the **exact commit**. Existing detekt baselines, lint warnings and physical
latency/artist-validation gaps are not erased by a passing build. `TestEvidence` stores running-app
captures via UTP's `additionalTestOutputDir`; CI archives `connected_android_test_additional_output`.

## Signed releases

Keep signing keys outside Git and preserve the upload-key identity of an existing Play app. Store
private signing inputs in ignored root `keystore.properties`:

```properties
storeFile=/absolute/path/to/private-upload-key.jks
storePassword=YOUR_PRIVATE_STORE_PASSWORD
keyAlias=YOUR_UPLOAD_ALIAS
keyPassword=YOUR_PRIVATE_KEY_PASSWORD
```

```bash
./gradlew bundleRelease -PrequireReleaseSigning=true
```

The production command rejects missing signing inputs. Unsigned CI bundles are not Play-upload-ready.
Verify signatures, application ID/version, generated splits and Play Console requirements separately.

## Documentation and privacy

[Comparison](docs/PROCREATE_PARITY.md) · [Readiness](docs/RELEASE_READINESS.md) · [Original plan](agent.md) ·
[Store listing](docs/play-listing.md) · [Privacy](docs/privacy-policy.md) · [Contributing](CONTRIBUTING.md)

The privacy policy is also available offline in Settings. Android backup/transfer follows the device
owner's settings; external providers/apps may handle exports. Complete Play Data safety declarations.
Every landed improvement must update this README with its limits and executed evidence.

## License

GNU General Public License v3.0. See [LICENSE](LICENSE).
