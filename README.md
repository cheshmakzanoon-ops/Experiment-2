### Native-informed parity implementation (21 October 2026)

The [latest implementation report](docs/PARITY_IMPLEMENTATION_2026-10-21.md) records changes
based on the supplied Procreate **5.4.4 / 56832** IPA and actual Android Gradle verification.
[Hash-pinned native evidence](docs/REA_NATIVE_CONTRACTS_2026-10-21.md) establishes corrected
pressure-flow/opacity and wet-control mappings. Those controls now reach painting and brush
interchange; five additional pressure graphs affect colour and bleed.

Animation preview and export share playback ranges, hidden frames, multiple stable background/
foreground pins and ping-pong ordering. PSD export retains independent editable layer and folder
masks, including disabled masks, with unmasked artwork alpha preserved. Brush sharing exposes
ArtFlow and Procreate formats in the editor. Persisted gesture preferences and stylus-button
controls are connected through the editor's existing input pipeline.

Local verification passed **1,325 JVM tests with zero failures or skips**, including the supplied
534-component brush corpus and five genuine Procreate documents. Debug and instrumentation APKs,
the unsigned release bundle, formatting, detekt, debug/release lint and packaged-artifact checks
also passed. Instrumentation was compiled but not executed locally; device acceptance still
requires the published revision's completed CI and physical-device checks.

**Full Procreate parity remains unestablished.** Native wet-paint equations, 24 retained pressure
graphs, several compound-brush modes and physical-device behavior still need work. The report
separates executed JVM/build checks from device tests and reference-app pixel validation. Older
dated entries below describe their own earlier revisions and should not override that record.

### REA source-value drift audit (21 October 2026)

An offline, hash-pinned audit found **477 of 534** component settings records contain at least one source scalar that ArtFlow's approximate importer would normally clamp or quantize. The [exporter repair](https://github.com/cheshmakzanoon-ops/Experiment-2/commit/9dec2c473702ec6deb113a7b80321fdd58e13b97) now preserves original primitive values for unedited brush controls, including source spacing and grain scaling outside ArtFlow's limits. See [the reproducible drift-risk report](docs/REA_SCALAR_DRIFT_RISKS.md). This is archive-data retention, not reference-app rendering or runtime equivalence.

### REA source schema capture (21 October 2026)

Read-only REA analysis of the supplied IPA documents **264 distinct brush-setting fields**: 211 scalar, 33 pressure-curve, 19 structured-object, and one 16-byte color-data field. The current importer now has bounded preservation paths for those observed shapes, including saved-brush storage and synthetic keyed-archive export. **Structural capture is not identical NSKeyedArchiver bytes, original brush rendering, or full reverse engineering of the iOS application.** See the [complete source schema census](docs/REA_BRUSH_FULL_SCHEMA_CENSUS_2026-10-21.md); Android build/device verification remains pending.

### REA supplemental pressure-curve recovery (21 October 2026)

The reference corpus has 33 curve fields. The importer now retains 30 auxiliary source fields in addition to the original three, with **4,138 syntactically valid auxiliary curve instances** observed offline. Synthetic import/save/export round-trip tests were added; native brush behavior, pixel parity and exact-commit Android CI remain unverified. See [REA auxiliary curve recovery](docs/REA_AUXILIARY_CURVES_2026-10-21.md) and [reader mapping audit](docs/REA_BRUSH_READER_COVERAGE.md).

### REA reference-analysis record — 21 October 2026

The linked [reference archive](https://drive.google.com/file/d/1xPr6e53L76Cq8exGFWOSvq96wt-NlLVC/view) is a 777,430,382-byte ZIP / IPA, identified by SHA-256 `7b1dad3ac95f8aeca3c22b432b5d6fd94182a3bf9f84fb93588adb126368b54e`. A successful prior REA/keyed-archive workflow extracted 394 brush containers (140 dual-component; 534 settings components). New evidence-based reports include the [264-key schema inventory](docs/REA_IPA_BRUSH_SCHEMA_INVENTORY_2026-10-21.md) and [numeric range and importer-loss audit](docs/REA_IPA_BRUSH_RANGE_AUDIT_2026-10-21.md). Raw paid source assets and executables are not redistributed.

The [full archive static inventory workflow](.github/workflows/reference-ipa-static-inventory.yml) uses the original SHA-256 to validate provenance before inspecting bundle plists, declared executable headers and Mach-O encryption markers, and attempts REA CLI artifact inspection. Its completion and results must be checked at the exact workflow run; the presence of a workflow is **not proof** it has finished.

The consolidated main historically failed Android Kotlin compilation (706 compiler diagnostics, 34 files, often cascading). The [compiler audit](docs/CI_COMPILER_TRIAGE_2026-10-21.md) records that failure's exact CI job. The new implementation record at the top of this file supersedes its local build status; historical CI results are retained as evidence. **Whole-app source recovery and 1:1 Procreate parity are not achieved.**

### Run 84 Android-system recovery

Run `35509598187` proved that an app-only restart is insufficient after a classifier-proven Android 15/16 KB runtime crash: the first instrumentation attempt matched the known framework crash, then the retry aborted immediately with `INSTRUMENTATION_ABORTED: System has crashed`. The harness now preserves the first attempt, performs a real emulator reboot, proves the boot ID changed and Android reached `sys.boot_completed=1`, reapplies and verifies the Android 15 runtime configuration, clears only disposable test-package state, and then performs the single retry. Recovery itself is fail-closed and covered by standalone tests; application assertions and unrelated crashes are still never retried.

### Run 81 CI stabilization

Run `35501934611` left only the two Android 15/16 KB jobs red. The independent repeat did not reach tests: Maven Central returned HTTP 403 while Gradle was resolving `failureaccess-1.0.2.jar`. Device jobs now wait for the successful build job, run at most two in parallel, and precompile the debug/instrumentation artifacts with bounded retries before the emulator starts. This reduces concurrent dependency traffic and moves compilation/network failures out of the expensive emulator phase.

The primary API 35 lane reached instrumentation but Android 15 itself segfaulted in `libart.so` on the Hilt runner thread before the named test body executed. The existing fail-closed runtime classifier now also recognizes only that proven environment: Android 15's x86_64 16 KB emulator, 16 KB page size, `com.artflow.studio` process, SIGSEGV, and a frame-zero `libart.so` tombstone. JUnit failure elements must be empty for this native case. Any ArtFlow assertion, Java/Kotlin exception, non-ART top frame, other Android version/page size, malformed evidence, or failed retry remains red.

### Run 80 current-tip repair

Run `35499537275` left two current failures. The primary API 35/16 KB process died inside ART while the low-level canvas test was unnecessarily launching the full Compose gallery; `CanvasInputDeviceTest` now uses the existing debug-only `SelectionCanvasTestActivity`, which hosts the real production canvas and Hilt repository without starting unrelated gallery composition. The independent API 35 repeat failed earlier because `adb root` restarted adbd and returned a transient closed transport; the runtime configurator now waits for reconnection and treats the verified post-reconnect root UID as authoritative. The configurator still fails closed if root was not actually granted.

### Run 79 framework-crash containment

Run `35485945078` reduced current CI to one red job: build/static analysis and five other device jobs passed, including the second API 35/16 KB lane. The remaining primary API 35 failure was not an ArtFlow assertion: Android 15's own `ReferenceQueueDaemon` threw a framework `NullPointerException` in `ReferenceQueue.enqueuePending`; Google Play Services showed the same runtime failure in that emulator session. The harness now retries once only when the preserved crash report and every JUnit failure element match that exact framework signature. Any ArtFlow assertion, different crash, malformed evidence, or second-attempt failure remains red. The first failed attempt is retained in the uploaded device artifact.

# ArtFlow — Digital Art Studio for Android

A native, offline painting studio built with **Kotlin, Jetpack Compose and a shared pixel engine**.
Paint with pressure-sensitive brushes, compose layers and masks, animate frames, and save or export real documents.

**Working editor. Not yet Procreate-equivalent or approved for a production release.**
The [Procreate comparison](docs/PROCREATE_PARITY.md) separates implemented workflows from missing capabilities.
[Release readiness](docs/RELEASE_READINESS.md) tracks the separate distribution requirements.

## Latest development session — 17 October 2026

Every change made in the session that set out to reach Procreate parity, and was then wound down, is
recorded with its evidence level in [docs/DEVELOPMENT_LOG_2026-10-17.md](docs/DEVELOPMENT_LOG_2026-10-17.md).
In short: Procreate `.swatches` palettes, palette management, typed colour values, hardware-keyboard
shortcuts, opening and sharing files into ArtFlow from other apps, a New artwork dialog with inch/cm/mm
sizes, a Screen size preset and saved sizes, gallery stack commands, hand-arranged gallery order and bulk
actions, a corrected Freehand selection and Clear, Justify text, a time-lapse recording switch and replay
speeds. **Parity with Procreate has not been reached**; the log ends with the list of what is missing.
These changes are compile- and lint-checked offline and their pure logic is covered by JVM tests; none has
been run on a device and CI has not yet run on them.

## Current CI follow-through

Run **78** (`27b4c6cf`) passed all build/static/artifact checks and five of six device jobs.
All prior tab and selection regressions passed on every device. The tablet completed 175 cases,
with one failure in `AnimationPlaybackUiTest` **teardown**: a background atomic autosave was still
renaming its temporary file while the test deleted the directory. Cancelling the ViewModel scope
requests cancellation but does not wait for a blocking write to leave I/O.

The follow-through now **cancels and joins the test-owned ViewModel jobs before disposing the
repository or deleting project files**. A deterministic device regression holds a real atomic write
open, verifies teardown cannot finish prematurely, then releases it and checks completion. The two
original playback tests and production storage error propagation are unchanged. A local Kotlin/JVM
probe reproduced the old cancel-only race and verified joining across 20 atomic-write schedules;
all 38 Python checks passed. This next revision still needs its own completed Android CI result.

The preceding repair sizes actual tab text from measured labels, restores the delegated selection
pause hook, and configures/verifies the intended API 35 runtime mitigation. Both API 35 lanes in run
78 completed 175 tests without failures and passed minified launch, with 16 KB pages and verified
JIT-disabled settings. API 36/16 KB passed with its normal JIT enabled. This is a **CI environment
mitigation**, not proof of production API 35 + JIT readiness or a permanent native-crash fix.

The [failure ledger and repair record](docs/CI_REPAIR_FOLLOW_THROUGH.md) retains exact baseline runs,
failed cases, artifact IDs and verification boundaries. Earlier red runs are not erased. All six
device lanes, original test cases, page-size checks and minified launches remain required.

## Brush attribute navigation and connected dynamics

Brush Studio provides **ten named setting groups** plus **All settings**. Wide panes use a scrollable
sidebar; compact panes use a horizontal selector. Returning from Library or Drawing pad retains the
selected group and draft. Changing groups starts the new panel at the top without resetting values.
Navigation and the pressure toggle honour the larger-touch-target preference.

**Speed & colour** exposes speed-controlled size, opacity and hue, plus pressure-dependent
brightness, using the same exact-value entry and staged Use brush/Cancel workflow. Saved copies
preserve the complete parameter model. Stylus tilt widens and lightens dabs, with tilt opacity set
apart from tilt size; tilt-to-rotation and the Rotation setting turn flat and imported tips.
Procedural **grain rotation** works. The stylus azimuth sign and tilt curves are unvalidated on hardware.

## Neutral, adaptive workspace

Neutral Material surfaces keep artwork visually dominant. The UI palette adjusts accent colours
for readable foreground/background pairs; tested normal-text pairs meet 4.5:1 and high-contrast
pairs meet 7:1. These are palette contracts, not whole-app accessibility certification. Artwork
pixels and working colours are not changed by UI contrast adjustment.

Interface scale applies once to controls/text while retaining Android's independent font-size
preference. On sufficiently wide and tall windows, Brush Studio shows Library or Settings beside
the drawing pad; compact windows retain the three-tab layout. Practice marks preserve their aspect
ratio. Draft settings and retained paths survive layout and light/dark theme changes.

## Saved custom brushes

Brush Studio now has **Save a copy** and a **Saved** library category. Give a tuned brush a name,
reopen the studio or restart the app, and select that saved copy again. The row menu supports
rename and **confirmed deletion**. Built-in presets cannot be renamed or deleted by these controls.
Saved brushes participate in search; copies have independent identities even when names match.

A saved copy preserves the **entire brush parameter model**, including custom pressure response,
procedural grain, colour/velocity settings, tilt settings and wet mix. Saving a copy is an explicit library
operation independent of **Use brush**: it does not apply the draft or edit artwork. Closing the
studio still discards unapplied draft changes but does not undo a confirmed library save.

Storage uses a versioned, bounded JSON document in one existing Room settings row, separate from
artwork and older partial brush records. Mutations serialize; state is published only after the
Room write succeeds. A failed write leaves the prior library intact. Corrupt, oversized, invalid
or newer-format data produces a visible error and retry action, never a silent reset/overwrite.
The library currently supports **128 saved copies**, with names up to **80 characters**. Sharing,
import/export, custom texture assets, favorites and user-defined brush sets remain unfinished.

## Brush Studio

Open **Brush** in the editor. **Library** searches eight original starter presets and filters
Sketch, Ink, Texture and Paint, with samples rendered by the painting engine. **Settings** edits
size, opacity, spacing, smoothing, pressure curves, taper, procedural grain, scatter, jitter and wet
mix. Attribute navigation and speed/colour controls are connected. General tip rotation
turns flat and imported tips; grain rotation is active. Tap a displayed value for exact numeric entry. **Drawing pad** tests your own
marks without touching the artwork; return after changing settings to see those marks re-rendered.

Changes remain a **draft** until **Use brush**. Close, Back or outside dismissal discards the draft;
**Restore initial** restores the opening parameters. Applying selects Brush and preserves an
already-active brush mask destination. Preset selection and practice strokes do not alter document
pixels or undo history. Opening the studio cancels an active canvas gesture.

Numeric entry accepts decimal points/commas, maps percentages to engine values, rejects out-of-range
values, and rejects fractions for integer controls. The practice buffer is **320×180**, retains the
last **8 strokes**, caps each at **128 points**, and limits preview brush sizes to **48 pixels**.
These are preview limits, not document limits. Presets are original starting points, not Procreate
assets or a professionally validated equivalent library. Imported shape and grain textures and dual brushes are available, and Procreate and Photoshop brush files import.

## Studio workflows

**Workspace.** Brush, Smudge and Eraser stay in a compact dock; All tools exposes the other tools.
Focus mode hides the bars without replacing the drawing surface. A visible exit button or Android
Back restores the workspace before the unsaved-document prompt. Guides, Animation, Canvas and Text
have actual menu routes and explicit close controls.

**Reference.** Choose an image through the Android picker, drag its floating window, pan/pinch the
image, zoom/fit and sample colours by long press or pick mode. A centre-sample accessibility action
is available. Decoding is limited to a 1024-pixel preview per axis. Only granted content URIs are
accepted; references never enter layers, history or exports. Source embedding, indefinite grants,
live canvas references and manual window resizing are not implemented.

**Layers and text.** Layers display topmost-first with working up/down ordering, visibility,
opacity, blending, alpha lock, clipping, masks, adjustments and filters. Text uses
edit → Place on canvas → tap position → confirm; cancelling an anchor does not stamp text.
Canvas background state follows the document through edits and undo/redo.

## Capability boundaries

| Area | Available | Important gaps |
| --- | --- | --- |
| Painting | Pressure/dynamics, smoothing, taper, flow, wet mix, jitter, procedural and imported grains, tilt, dual brushes, staged Brush Studio | Curated-library and stylus-feel parity; tilt azimuth sign and curves unvalidated on hardware |
| Tools | Brush, eraser, smudge, clone, healing, liquify, bucket, gradients, text and shapes | QuickShape recognition and ColorDrop-style interaction |
| Selections | Automatic, freehand, rectangle, ellipse, combine/invert/feather, save and load | Gesture ergonomics; automatic-selection sampling and Remove without a selection unverified |
| Transform | Freeform, uniform, distort and warp with snapping, cancel and undo | Device validation of large multi-layer moves |
| Layers | Pixel/mask/effect workflows, nested groups, clipping, alpha lock, reorder | Merge Down bakes opacity and blend mode into pixels and does not carry alpha lock or clipping |
| Colour | Disc, Classic (HSB), Value (RGB/HSV/CMYK), palettes, harmonies, Display P3 canvases | Classic model parity unverified; print proof not validated against real press output |
| Animation | Frames, duration/FPS, loop and ping-pong playback, tinted onion skin, timelapse, GIF/MP4/APNG export | Export ignores ping-pong and playback range; no onion colour control or frame hide; preview timing on device unmeasured |
| Documents | Atomic `.artflow` saves, thumbnails, autosave and recovery | Broader interruption, low-storage and long-session acceptance |
| Export | PNG/JPEG/WebP/PDF/PSD/TIFF/GIF/APNG/MP4, layer PNG zip and frame-sequence ZIP; picker/gallery/sharing | PSD export bakes masks into pixels; broader external interoperability fixtures |

The latest parity sweep (one worker per area) is recorded under *Parity sweep candidate* in
[docs/PROCREATE_PARITY.md](docs/PROCREATE_PARITY.md). It was not compiled with Gradle or run on a device
in the environment that wrote it; the Android build, JVM tests and device lanes on CI are the first gate.

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

CI retains API 26, API 35/16 KB, API 36/4 KB, an independent API 35/16 KB repeat and API 36/16 KB,
and adds API 36 with a Pixel C tablet profile. Every lane retains the minified launch checks. `benchmark` is a release-like smoke-test variant, not a performance suite.
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
