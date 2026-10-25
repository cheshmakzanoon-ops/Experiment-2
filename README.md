# ArtFlow — Digital Art Studio for Android

A native, offline painting studio built with Kotlin, Jetpack Compose and a shared pixel engine.
Paint with pressure-sensitive brushes, edit layers and masks, animate frames, paint imported 3D
models, and save or export real documents.

**Working editor. Full Procreate feature and quality parity remains unestablished.**
The [comparison](docs/PROCREATE_PARITY.md) records implemented workflows and remaining gaps;
[release readiness](docs/RELEASE_READINESS.md) tracks the separate distribution requirements.

## Current development — 25 October 2026

The current work closes concrete gaps found through emulator tests and native-file inspection:

- **Smudge input and continuity:** use the selected Smudge brush's opacity, flow, pressure and
  tilt response; carry dab spacing across input events and include the release position.
  Fast gestures must not leave unstamped gaps, and the opacity controls must edit the active tool.
  The follow-up corrects fractional patch placement exposed by the cross-API image comparison.
- **Clipboard integrity:** prepare Paste, Copy & Paste and Cut & Paste before changing the
  document, commit each as one undo action, and preserve source pixels, prior clipboard and history
  when the project has reached its layer limit. Material maps retain their channel when copied.
- **3D material painting:** independently edit Color, Metallic and Roughness on persisted layers,
  preview their effect on the model, and preserve the maps through undo, save/reopen and model export.
- **Editable native masks:** preserve concealed paint, independent coverage and the enabled state
  in `.procreate` layer-mask export, including a fresh import and mask toggle.
- **OBJ material reimport:** prepare and install the application's own single-material color,
  metallic and roughness maps together, preserve their UV layout and retain a pending import until
  its first save succeeds. The [material reimport record](docs/OBJ_MATERIAL_REIMPORT_2026-10-25.md)
  distinguishes the implementation from its pending exact-source device acceptance.

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

[Run 317](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/runs/40140188018) on `8ce26b9`
passes 1,563 JVM tests, 156 Python checks, static analysis and package gates. Six device lanes pass
their complete inventory checks: API 26 has 262 passes and two permitted skips; the others have
264 passes each. The independent 16 KB repeat
requires one preserved recovery. The main Android 15 / 16 KB lane stops after 43 passes with one
empty ART-crash failure and 220 missing tests, so the run remains failed overall. All eight new
feature cases pass in each final passing lane. Cross-API image review exposed the fractional Smudge
placement issue corrected by the next application revision.

[Run 318](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/runs/40145078365) on `8074d9d`
tests that correction with 265 device identities per lane and no removed tests. Its build passes
1,569 JVM tests, 156 Python checks, static analysis and package gates. Both Android 15 / 16 KB
jobs fail: main has two native ART crashes across its original and single recovered attempt;
the repeat has a distinct nonempty coroutine-class `VerifyError`. The exact installed APK was not
retained, so the verifier failure's cause remains unresolved. The other five lanes pass their complete
inventories and minified launches. Final totals are 1,440 passes, two failures, two permitted skips
and 411 missing tests. The new subpixel case passes in every final attempt with identical PNGs
across each lane's integer-boundary pair; see the
[verification record](docs/PARITY_FOLLOW_THROUGH_2026-10-24.md).

[Run 319](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/runs/40148722269) on `635ebcc`
passes the complete seven-lane matrix: **1,853 device passes and two permitted API 26 skips**,
with no failed, missing or unexpected cases, seven successful minified launches and no recovery.
Its build passes 1,569 JVM tests and 177 Python checks. The diagnostic follow-up captures installed
app/test APKs before UTP removes them; all seven runner/host handoffs are complete and their
18 app DEX and seven test DEX hashes match across lanes. Success retains verified metadata;
failed-attempt payload retention is covered by script tests. Application code, runtime settings,
assertions, coverage gates and recovery rules are unchanged from run 318. Passing this diagnostic
revision does not establish the cause or repair of the earlier intermittent runtime failures.
Original artifacts, hashes and the seven-lane image audit are in the
[verification record](docs/PARITY_FOLLOW_THROUGH_2026-10-24.md).

[Run 320](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/runs/40151899389) tests the
native-mask correction on `09940e4`, with 1,585 passing JVM tests, 190 Python checks and passing
build/package gates. Six emulator configurations account for all 266 declared identities (API 26
has 264 passes and its two allowed skips), and pass minified launches and the independent
native-mask archive/pixel audit.
The main API 35 / 16 KB lane crashes in both its original and single recovered attempt. Final
matrix totals are **1,595 passes, one failure, two allowed skips and 264 missing cases**, so the run
fails acceptance. Both failed attempts now retain their exact installed APKs; all 18 app and seven
test DEX payloads match the passing lanes. The [mask record](docs/PROCREATE_MASK_EXPORT_2026-10-25.md)
preserves the full matrix, crash chronology, artifact hashes and the unresolved causal limits.
The subsequent [OBJ material-reimport correction](docs/OBJ_MATERIAL_REIMPORT_2026-10-25.md) passes
its 1,630-test local JVM suite and full local gate; its three new device cases require their own
published-source Actions run.

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
- [Native mask import](docs/PROCREATE_MASK_IMPORT_2026-10-24.md) and
  [editable mask export](docs/PROCREATE_MASK_EXPORT_2026-10-25.md),
  [hierarchy](docs/PROCREATE_HIERARCHY_IMPORT_2026-10-24.md),
  [color profiles](docs/PROCREATE_COLOR_IMPORT_2026-10-24.md),
  [Darken blending](docs/NATIVE_LAYER_DARKEN_2026-10-24.md), and
  [native export](docs/PROCREATE_NATIVE_EXPORT_2026-10-24.md).
- [Wet-preview integrity](docs/WET_PREVIEW_HISTORY_2026-10-24.md),
  [model input and display](docs/MODEL_EDITOR_2026-10-24.md), and
  [measured preview-call performance](docs/PREVIEW_PERFORMANCE_2026-10-24.md).
- [Editable OBJ material reimport](docs/OBJ_MATERIAL_REIMPORT_2026-10-25.md), including independent
  input fixtures, bounded scalar-map decoding and initial-save retry behavior.
- [Earlier development](docs/DEVELOPMENT_LOG_2026-10-17.md),
  [emulator corrections](docs/EMULATOR_PARITY_2026-10-24.md), and
  [Android 15 repair investigation](docs/ANDROID15_OFFICIAL_REPAIR_REVIEW_2026-10-24.md).

## Capability boundaries

| Area | Available | Important gaps |
| --- | --- | --- |
| Painting | Pressure/dynamics, smoothing, taper, flow, wet mix, jitter, procedural and imported grains, tilt, dual brushes, staged Brush Studio | Native wet/compound/grain equivalence and unused retained pressure graphs; large-canvas responsiveness, curated-library and physical stylus validation |
| Tools | Brush, eraser, continuous pressure/opacity/flow-aware Smudge, clone, healing, liquify, bucket, gradients, text and shapes; QuickShape recognition with hold-to-adjust and Edit Shape; ColorDrop with adjustable threshold and Continue Filling | Artist validation of shape recognition and handle gestures; comparative fill thresholds and anti-aliased edge quality |
| Selections | Automatic samples the active layer with an adjustable threshold; freehand, rectangle and ellipse; Add/Remove/Intersect, invert/feather and Save & Load; Remove without an existing selection keeps it empty | Gesture ergonomics and artist validation; comparative reference-app thresholds and selection-edge quality |
| Transform | Freeform, uniform, distort and warp with snapping, cancel and undo | Transforms rasterise editable text; retaining text editability after transformation remains unimplemented. Device validation of large multi-layer moves. |
| Layers | Pixel/mask/effect workflows, nested groups, clipping, alpha lock, reorder; atomic clipboard commands | Merge Down bakes opacity and blend mode into pixels and does not carry alpha lock or clipping |
| Colour | Disc, Classic (HSB), Value (RGB/HSV/CMYK), palettes, harmonies, Display P3 canvases | Profile-managed CMYK and arbitrary ICC transforms; Classic model parity and real print-proof validation |
| Animation | Frames, duration/FPS, loop, one-shot and ping-pong; preview and animation export share playback range, frame visibility and order; background/foreground pins, separate previous/next onion colours, timelapse and GIF/MP4/APNG export | Reference hold-count semantics and artist-tested timing; long-animation playback/encoding performance and physical-device qualification; native `.procreate` animation metadata |
| 3D materials | Independent Color/Metallic/Roughness paint layers, live model preview, undo, persistence, OBJ scalar-map export and reimport of the supported single-material OBJ maps | Single UV atlas and approximate lighting; broader scalar texture formats/options, independent source material sets, native/glTF/USDZ material interchange and physical GPU validation |
| Documents | Atomic signed `.artflow` v6 saves, thumbnails, autosave and recovery; older checksums verified before migration | Broader interruption, low-storage and long-session acceptance |
| Export | PNG/JPEG/WebP/PDF/PSD/TIFF/GIF/APNG/MP4, layer PNG zip and frame-sequence ZIP; `.procreate` with supported sRGB/Display P3 channels, a generated Display P3 profile, layer hierarchy, native Darken and independent type-3 layer masks with enabled/disabled state; picker/gallery/sharing | Opening exported files in Procreate and broader external interoperability; native group blend/mask metadata, arbitrary ICC transforms and PSB. PSD and native export retain supported independent layer masks; post-mask effects use baked masks, while resized enabled masks use a rendered appearance layer with hidden editable sources. Native Darken also uses that appearance fallback in PSD |

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
