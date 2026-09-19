# ArtFlow — Digital Art Studio for Android

A native, offline painting studio built with **Kotlin, Jetpack Compose and a shared pixel engine**.
Paint with pressure-sensitive brushes, compose layers and masks, animate frames, and save or export real documents.

**Working editor. Not yet Procreate-equivalent or approved for a production release.**
The [Procreate comparison](docs/PROCREATE_PARITY.md) separates implemented workflows from missing capabilities.
[Release readiness](docs/RELEASE_READINESS.md) tracks the separate distribution requirements.

## Latest improvement: real colour pickup and multi-colour practice

**Brush → Settings → Wet Paint → Wet Mix** now changes newly painted pixels. Previously the
parameter could be edited and saved but the active renderer never read it. Zero deposits dry ink;
higher values pick up more existing colour from the **active layer**. Transparent hidden RGB is
ignored and ink alpha is retained. Selection coverage, alpha lock, grain and stroke opacity are
still applied by their existing stages. Erasing and painting layer masks do not pick up pigment.

Pickup uses five alpha-weighted samples per dab from the layer as it stood before that stroke;
there is no per-dab layer copy. Preview and commit use the same path. New strokes bake into pixels;
**older saved vector strokes retain their original dry replay**, even when their stored wet-mix
value was nonzero. Reopening old artwork must not silently reinterpret how it was painted.

The drawing pad has a **Practice ink** menu with Clay, Ocean, Ochre and Graphite. Changing ink
colours only subsequent test strokes; earlier marks retain their recorded colours. Change Wet Mix
and return to the pad to inspect the same marks against each other. Clear removes test paths but
keeps the selected practice ink. Neither practice ink nor its marks change document ink or pixels.

This is bounded RGB colour pickup, **not full Procreate wet-media equivalence**: no paint reservoir,
dilution/charge/attack model, directional pigment transport, wet-edge simulation or spectral mixing.
It samples the active layer, not the composite. The practice paper participates in pickup; library
samples on transparency contain no existing pigment and therefore do not demonstrate mixing.

Seven new core/JUnit checks cover known colour mixtures, transparent RGB, invalid input, legacy
replay, coverage, ownership and 10,000 deterministic generated samples. Five repository/device
cases cover actual preview/commit, cancellation, save/reopen, undo/redo, masks and layer isolation.
Two UI cases check per-stroke practice ink and actual rendered colour changes. The core checks and
existing seven Brush Studio checks passed in standalone Kotlin; full Android CI is a separate gate.

**Audit correction:** smoothing, brush-tip rotation and tilt-response fields are stored but are
not consumed by the active round-dab renderer. Grain rotation is implemented and is a different
setting. Their controls/persistence are not counted as delivered painting effects. Advanced
transforms in open PR #34 also remain separate from this main-line milestone; they are not lost
or misrepresented as already merged. See the [gap register](docs/PROCREATE_PARITY.md).

## Verification follow-through: adaptive studio

The `019648e` candidate's unit tests and four device lanes passed, but it was not a
verified milestone: two deeply nested test helpers blocked Detekt, a tablet tab label
failed the no-overflow assertion, and API 35 terminated with a native SIGSEGV. The tab
now sizes its content without a fixed text-baseline slot, preserving its full label.
The palette checks are factored into helpers with every original assertion retained.

Run `35453272406` and its original artifacts remain the failure record. The API 35
stack was on the main thread in generated Compose animation code, **not** a demonstrated
Profile Saver abort. Its root cause is unresolved; the independent 16 KB repeat passed.
No JIT setting, test assertion, device lane or analysis threshold was relaxed.
Promotion requires the candidate review branch to pass the complete CI matrix.

## Neutral, readable and adaptive studio

ArtFlow now defines every Material surface/container role rather than mixing its own dark palette
with the default tinted containers. Neutral grey surfaces keep the artwork visually dominant;
accent colours remain on selected controls and actions. The default Ink accent previously measured
only **1.21:1** on the old dark surface. The new opaque UI palette adjusts each accent for readable
foreground/background combinations: tested normal text pairs meet **4.5:1**, with **7:1** for the
high-contrast mode. These are measured palette contracts, not a claim of whole-app WCAG compliance.
Artwork pixels and working colours are not modified by this UI colour adjustment.

Interface scale now applies once to both controls and text while preserving Android's independent
font-size preference. Previously multiplying density and font scale applied the interface factor
twice to text. Invalid scale values fall back safely and valid settings retain their supported range.

On sufficiently wide and tall windows, **Brush Studio shows Library or Settings beside the drawing
pad**. Change parameters while the same practice marks remain visible. Compact windows retain the
three-tab layout; Drawing pad can still occupy its own page. The practice surface fits both width
and height without stretching strokes. Draft settings and retained practice paths survive switching
between the layout modes and light/dark themes.

Six pure-Kotlin palette checks executed locally, including all **24 accent/theme combinations** and
**1,000 generated accent seeds**. Seven JUnit checks connect the math to the actual Material colour
roles. New device checks cover scaling and theme/draft/practice retention, with a new **API 36 tablet**
CI lane alongside all five existing configurations. Exact-commit Android execution is still required.

The previous `925550d` revision passed **418 JVM tests** and **all five device configurations**.
Its remaining ktlint findings are corrected here without changing rules, baselines or assertions.
The API 36 report contains 165 instrumentation cases with zero failures/errors/skips, including
saved brush UI/storage and the repaired whole-screen drawing-pad checks.

## Saved custom brushes

Brush Studio now has **Save a copy** and a **Saved** library category. Give a tuned brush a name,
reopen the studio or restart the app, and select that saved copy again. The row menu supports
rename and **confirmed deletion**. Built-in presets cannot be renamed or deleted by these controls.
Saved brushes participate in search; copies have independent identities even when names match.

A saved copy preserves the **entire brush parameter model**, including custom pressure response,
procedural grain, colour/velocity/tilt dynamics and wet mix. Saving a copy is an explicit library
operation independent of **Use brush**: it does not apply the draft or edit artwork. Closing the
studio still discards unapplied draft changes but does not undo a confirmed library save.

Storage uses a versioned, bounded JSON document in one existing Room settings row, separate from
artwork and older partial brush records. Mutations serialize; state is published only after the
Room write succeeds. A failed write leaves the prior library intact. Corrupt, oversized, invalid
or newer-format data produces a visible error and retry action, never a silent reset/overwrite.
The library currently supports **128 saved copies**, with names up to **80 characters**. Sharing,
import/export, custom texture assets, favorites and user-defined brush sets remain unfinished.

Nine JVM regressions exercise full round-trips, Unicode names, malformed/future data, failed writes,
concurrent saves, cancellation, owned snapshots and limits. A device test closes/reopens a real Room
database; three UI tests cover save/rename/delete/cancel and failure feedback. The earlier aspect-ratio
test's missing DpRect extensions are corrected using its actual edge coordinates. All new checks
require the completed CI result for this exact revision; their existence is not a passing result.

### Follow-through on the preceding candidate

Commit `b27de13` / run `35451246630` passed **409 JVM tests**, ktlint, detekt, debug/release lint,
APK/AAB builds and packaged-artifact verification. Device lanes stopped during test compilation,
before running any tests, on the aspect-ratio assertion corrected above. Earlier pending fixes
include clearing search focus on preset/tab changes, a wrapping Drawing pad label, a correctly
proportioned practice surface and API 26-compatible whole-screen pixel assertions. No test lane,
assertion or analysis rule has been disabled to obtain a pass.

## Brush Studio

Open **Brush** in the editor. **Library** searches eight original starter presets and filters
Sketch, Ink, Texture and Paint, with samples rendered by the painting engine. **Settings** edits
size, opacity, spacing, smoothing, pressure curves, taper, procedural grain, scatter, jitter,
rotation and wet mix. Stored smoothing/tip rotation do not yet affect rendering. Tap a displayed value for exact numeric entry. **Drawing pad** tests your own
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
| Painting | Pressure/velocity dynamics, taper, flow, active-layer colour pickup, jitter, procedural grains and staged Brush Studio | Active smoothing/tilt/tip rotation; imported/dual textures; media simulation and physical-stylus parity |
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
