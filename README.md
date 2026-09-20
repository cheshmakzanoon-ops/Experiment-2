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
preserve the complete parameter model. The active renderer still uses round tips: stored tilt and
general tip rotation are not implemented rendering features. Procedural **grain rotation** works.

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
procedural grain, colour/velocity settings, reserved tilt fields and wet mix. Persisted tilt fields
do not establish tilt-aware rendering. Saving a copy is an explicit library
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
is reserved metadata; grain rotation is active. Tap a displayed value for exact numeric entry. **Drawing pad** tests your own
marks without touching the artwork; return after changing settings to see those marks re-rendered.

Changes remain a **draft** until **Use brush**. Close, Back or outside dismissal discards the draft;
**Restore initial** restores the opening parameters. Applying selects Brush and preserves an
already-active brush mask destination. Preset selection and practice strokes do not alter document
pixels or undo history. Opening the studio cancels an active canvas gesture.

Numeric entry accepts decimal points/commas, maps percentages to engine values, rejects out-of-range
values, and rejects fractions for integer controls. The practice buffer is **320×180**, retains the
last **8 strokes**, caps each at **128 points**, and limits preview brush sizes to **48 pixels**.
These are preview limits, not document limits. Presets are original starting points, not Procreate
assets or a professionally validated equivalent library. Imported shape/grain textures, dual brushes and custom preset interchange remain unfinished.

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
| Painting | Pressure/dynamics, smoothing, taper, flow, wet mix, jitter, three procedural grains, staged Brush Studio | Preset interchange, imported/shaped/dual textures, tilt rendering, curated-library and stylus-feel parity |
| Tools | Brush, eraser, smudge, clone, healing, liquify, bucket, gradients, text and shapes | QuickShape recognition and ColorDrop-style interaction |
| Selections | Rectangle/ellipse/lasso/wand, combine/invert/feather | Saved selections and equivalent gesture ergonomics |
| Transform | MOVE/TRANSFORM translation | Interactive scale/rotate/distort/warp/snapping with preview/apply/cancel |
| Layers | Pixel/mask/effect workflows and reorder | Nested groups, linking UI and fill-reference workflow |
| Colour | Wheel, RGB/HSV/CMYK controls, palettes, harmonies | ICC-managed working spaces and print fidelity; CMYK sliders are not colour management |
| Animation | Frames, duration/FPS, onion skin, playback and animated export | Drawing timelapse, Page Assist and 3D painting |
| Documents | Atomic `.artflow` saves, thumbnails, autosave and recovery | Broader interruption, low-storage and long-session acceptance |
| Export | PNG/JPEG/WebP/PDF/PSD/GIF/MP4 and frame-sequence ZIP; picker/gallery/sharing | PSD import and broader external interoperability fixtures |

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
