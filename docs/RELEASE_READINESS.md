# Release readiness and evidence

This is a verification record, not a certification that an app has no bugs. `agent.md` preserves
all 50 original development phases. The currently implemented offline editor is narrower than
that roadmap; the missing features listed below have **not** been silently removed from the goals.

## Reliability repairs

The repair candidate includes atomic project writes and recovery, immutable edit snapshots,
rejection of stale/cancelled edits, correct GL lifecycle restoration, deterministic brush output,
protected fills, layer clipping/adjustments, unsaved mask compositing, independent project
copies with rollback, and coherent export snapshots.

Export repairs cover PNG alpha and DPI, JPEG density, WebP, PSD layer/merged alpha and names,
PDF page cleanup/aspect ratio, GIF, complete PNG sequences and MP4 frame submission/timestamps/
end-of-stream. MP4 uses AndroidX Media3 1.10.1 portable muxing for consistent final-frame
duration on older Android devices. Gallery publishing separates images from video, removes partial outputs, and
uses Android storage rules. Share/view/document-save actions operate on exported copies;
FileProvider excludes editable project files. Export controls apply whole presets and display
format-specific settings. Settings includes the offline privacy policy and explains Android backup.

Scaling now extends edge pixels rather than introducing transparent resize borders. Hilt 2.54
is used with KSP2 to generate Hilt wiring and Room schemas without the old processor classpath
conflict.

The compiler's memory budget was corrected, uncalled native code was removed from the application
build (source retained), destructive Room migration fallback was removed, and production builds
can require real signing inputs with `-PrequireReleaseSigning=true`.

## Evidence discipline

A passing workflow must build the exact source revision under review. A successful build of the
old source does not validate a reconstructed repair candidate. The saved candidate patch was checksum verified and exercised in CI before being committed
as ordinary source at the repository owner's explicit request. Committing that work does not
mean that every release check passed. Temporary patch-transport files have been removed; permanent
CI tests the source directly without relying on expiring repair artifacts.

The workflow reports and artifacts are the authoritative execution evidence. A benchmark-variant
launch uses release minification with a disposable debug key; it does not certify publisher signing.
Record the final run, commit, test counts, artifacts and SHA-256 values here when verified. Do not
substitute a scheduled job, a job that was cancelled, a skipped job, or a source-code assertion for
executed evidence. `detekt` passes against the existing baseline; that is not zero historical
findings. API-specific assumptions must remain visible in the test reports.

## Source publication checkpoint — September 17, 2026

All saved application, test, release-check and documentation changes from candidate run
[35216159218](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/runs/35216159218)
are committed as ordinary repository files, rather than only as compressed patch payloads.
Generated Python bytecode is excluded; it is not application source. The original candidate
patch SHA-256 is `b6269fd00f8cb911ec3a7db748e944c22e0ec045bbd31fd69938f3749c32f299`.

Observed results of that candidate's full verification run:

| Check | Result |
| --- | --- |
| Candidate formatting and static analysis | Passed |
| Missing-production-signing rejection | Passed |
| JVM tests, debug/release lint, debug APK and release AAB build step | Passed |
| Packaged artifact integrity/native alignment check | Failed |
| API 26 device test job | Failed |
| API 36 device tests and minified launch job | Passed |
| API 35, 16 KB device tests and minified launch job | Passed |
| Automatic verified-source promotion | Skipped because not all gates passed |

The repository owner subsequently requested that all work be committed and pushed despite the
incomplete release verification. Source publication is not a Google Play release or a production
sign-off. Keep the failing checks enabled and resolve their reports before release. Subsequent CI
runs on the committed source supersede this checkpoint only where they actually complete.

## Document mutation and filter repair — September 17, 2026

This batch starts from published commit `526cd27dacb3b9ca3845f62691ee0c15db25cc88`;
it preserves the previously published brush, mask, export and persistence repairs.

- Merge preparation excludes the canvas background and resets already-baked mask/opacity/effect
  state. Partial blends or clipping configurations are rejected when the replacement would change
  the rendered image (at most one 8-bit rounding unit is accepted). Retained source copies become
  hidden so their opacity is not applied twice. No active drag is merged.
- Canvas quarter-turn, flip, resize and crop operations rasterize legacy vector ink before the
  transformation, transform masks on every frame, and publish atomically. Cancellation or a newer
  document revision cannot publish a stale result. Invalid angles do not add undo entries.
- Pixel sessions include historical ink, reject changed vector sources, and clear baked vector
  records after commit. Selection ownership is isolated; empty coverage is not unrestricted paint.
- Applied adjustments operate on editable raw layers, retain transparency and masks, and prepare
  their results before adding history. Locked, hidden, reference and effect layers are excluded.
- Empty filter layers now affect the stack below. Filter opacity/masks work independently, clipped
  blur retains base alpha, and partial-alpha mixing uses premultiplied colour to avoid dark fringes.
- Layer and frame creation respect the same total-layer limit as project loading. The generated
  version-1 Room schema is committed as the starting point for explicit future migration tests.

`CanvasMutationTest` and `FilterLayerTest` exercise production repository/compositor code on the
JVM. Their initial runs reproduced 13 mutation failures, then seven selection/adjustment failures,
then three filter/alpha failures before the corresponding repairs. Additional cases cover project
capacity, all-frame/mask transformations, provisional edits and compatible filter baking.
`DocumentMutationDeviceTest` adds actual Android storage/codec round trips. Its source is not proof
of execution: use the completed API 26/35/36 reports for the exact candidate being evaluated.

Earlier tables remain historical evidence, not current sign-off. A verification job that publishes
source must bind the patch checksum, base commit and resulting Git tree, then pass all build and
emulator jobs before publication. The resulting main commit contains ordinary source, not the
transport patch. Private signing and physical-device/Play Console gates below still apply.

## Required publisher and device gates

The following items require separate evidence before a production rollout:

- Build the final signed AAB with the publisher's private upload key. Verify its signature,
  application ID and increasing version code; preserve the existing key/app identity when updating
  an already published app. CI's unsigned bundle is **not** upload-ready.
- Run the final minified, signed app on representative physical phones/tablets, a pressure-sensitive
  stylus, a low-memory device and a 16 KB page-size environment. Exercise process death/relaunch,
  upgrades with existing artwork, storage exhaustion, denied/revoked URI access, rotation,
  background/foreground transitions, many frames/layers and large-canvas editing. Archive the
  exact device/OS/app version, steps, expected result and observed result. Binary alignment is not
  equivalent to runtime coverage.
- Complete Play Console's applicable verification/testing requirements, content rating, target
  audience, ads declaration, Data safety form and app access answers. Publish an active public
  privacy-policy URL, check the publisher identity/contact against Console, and provide authentic
  screenshots and owned store assets. Review the pre-launch report on the uploaded release.

No signing key, Play Console approval, physical-device campaign or unconditional crash-free
operation is claimed by this repository. An internal testing candidate and a public production
release are different deliverables.

## Pointer and drag repair candidate — September 18, 2026

The input repair tracks the owning pointer by ID rather than assuming array index zero.
Stylus and hardware-eraser strokes continue alongside resting fingers; owner cancellation
(including Android 13+ `FLAG_CANCELED`) discards provisional ink. Extra fingers cancel a
finger-painted stroke before navigation, and trailing navigation fingers cannot become paint.
Two-/three-finger history taps finish only on the last pointer-up; pinch, rotation, long holds,
batched movement and cancelled streams are not history taps. Tool/destination/layer changes,
frame switches and rendering pause cancel in-flight tool interactions.

Selection geometry survives pointer-up cleanup, and pointer-up contributes the final drag segment.
Clearing a selection cancels its pending computation. Cancelled clone-source taps no longer set a
source. Move drags follow the pointer rather than its inverse. Unfilled shapes have visible outlines,
polygons fit their drag bounds, and liquify commits the complete displacement map rather than the
last throttled preview. Batched stylus samples and pressure are replayed in order.

Regression sources: `PointerGestureRouterTest` exercises pure pointer routing and history-tap
classification; `CanvasInputDeviceTest` drives real Android MotionEvents through the canvas view
and document for selections, painting, cancellation, navigation, hardware erasing, move, shape and
liquify behavior. The latter uses synthetic input, not a physical stylus/device certification.
The publication commit identifies the candidate tree and the exact CI run; only that run's completed
gates provide execution evidence. None of these repairs resolves the outstanding roadmap and
publisher/device gates below.

## Overlay preservation repair

Text and shape placement now composite source-over onto the existing layer instead of replacing
its entire raster. Transparent glyph/shape backgrounds leave prior artwork unchanged; feathered
selections multiply source coverage without erasing the backdrop. Alpha-locked layers retain their
original coverage. Empty overlays create no undo entry. The raster transaction is bound to the
original layer/document before asynchronous rasterization; stale commits remain rejected.
`RasterOverlayTest` covers the pixel math and `CanvasInputDeviceTest` checks real shape/text
placement, exact undo restoration, feathering, alpha lock and a destination change during rendering.

## Liquify direction and reconstruction repair

Push, twirl, pinch and bloat now use inverse-sampling directions consistent with their visible
operation. All modes follow the drag path; push strength integrates travel distance rather than
multiplying by the number of touch events. Live pressure and soft selection coverage scale the
operation, and gesture-owned masks cannot change underneath a running tool.

Reconstruct now gradually restores the pre-liquify image within the current uninterrupted liquify
editing context. It remains an undoable pixel edit, honors selection/freeze coverage, and uses a
fixed reference rather than repeatedly blurring the last preview. Leaving the tool, changing
layer/frame/dimensions, undo/redo, another document mutation, disposal or reopening invalidates
that transient reference. It is not persistent liquify history or a smart object. Missing references
produce an explanatory message without changing artwork/history. Zero-effect pixel drags add no
undo entry. Monotone document revisions also distinguish a reload that reuses the same IDs.

`LiquifyToolTest` contains pixel-ramp direction checks plus pressure, coverage, freeze, sampling,
reconstruction and invalid-dimension cases. Additional `CanvasInputDeviceTest` cases exercise
real-view reconstruction, exact undo, cancellation, stale references and document reloads. Source
presence is not execution evidence; use the publication's exact completed validation run.

## Original roadmap gaps

| Goal | Current limitation |
| --- | --- |
| Advanced transform tools | Whole-layer numerical move/scale/rotate/horizontal-skew/flip with editable-mask resampling is implemented. Handles, selected-pixel transforms, perspective, distortion and snapping remain missing. Canvas-wide operations are separate. |
| Layer groups, linking and references | Grouping UI and operations are absent; linking/reference model support is not a complete user workflow. |
| Custom texture workflows | Three procedural grains and editable monotone custom pressure are implemented. Imported/dual textures and a user texture library remain absent. |
| Native engine and benchmarks | C++ is an unintegrated prototype. No benchmark module or measured device-performance acceptance report exists. |
| PSD import and smart objects | Import/smart objects are absent. Export has explicit fidelity limits for effects and does not promise Photoshop round-trip equivalence. |
| Timelapse recording | Animation export is not recording the drawing process. Timelapse recording is absent. |
| Cloud synchronization and collaboration | Not implemented. The app has no network permission; Android backup is not an app-operated synchronization service. |
| Exhaustive quality assurance | Regression coverage is substantially expanded but is not coverage of every screen, device, gesture or failure scenario. |

Do not advertise these missing functions as delivered or mark all phase tasks complete. The store
listing is deliberately constrained to implemented functions, not the complete future roadmap.

## Reproduction commands

```bash
./gradlew testDebugUnitTest ktlintCheck detekt lintDebug lintRelease assembleDebug bundleRelease
./gradlew connectedDebugAndroidTest
python -m unittest discover -s .github/scripts -p 'test_*.py'
python .github/scripts/verify_android_artifacts.py app/build/outputs/apk/debug/app-debug.apk app/build/outputs/bundle/release/app-release.aab
./gradlew bundleRelease -PrequireReleaseSigning=true
```

Use `gradlew.bat` on Windows. The device command requires a connected emulator/device; the final
signing command requires the git-ignored root `keystore.properties` and real private keystore.
Room schemas are exported to `app/schemas`; future schema changes need explicit migrations and
upgrade tests, not destructive recreation.

## Primary release references

Reviewed September 17, 2026. Recheck these pages and the app's own Play Console before submission.

- Target API policy: https://support.google.com/googleplay/android-developer/answer/11926878?hl=en
- Data safety, including apps collecting no data: https://support.google.com/googleplay/android-developer/answer/10787469?hl=en
- Privacy policy and user data: https://support.google.com/googleplay/android-developer/answer/10144311?hl=en
- Android backup behavior: https://developer.android.com/identity/data/autobackup
- 16 KB alignment and runtime validation: https://developer.android.com/guide/practices/page-sizes

### Layered export appearance after stack filtering

Stack filters use the same PSD appearance fallback as adjustment layers: the rendered Artwork
layer is visible, original pixel layers are retained hidden, and export returns an explicit
warning that the effects were baked. This prevents a PSD reader that recomposites layers from
losing the filtered appearance shown in the editor and stored composite. Filter parameters
are not exported as editable Photoshop filters. Regression coverage checks the snapshot flag,
hidden-layer inclusion, and a real Android PSD write/read with visible-layer pixel equality.

## Retouch coverage and sampling repair — September 18, 2026

Smudge, Clone, Healing and Liquify now take alpha-lock from the real layer at gesture start;
locked zero-alpha pixels and feathered coverage are preserved. Clone/Healing/Smudge stamp
using source-atop when locked; transparent source samples cannot erase locked artwork.
Liquify preserves the original silhouette while retaining colour displacement. Changing a
layer's alpha-lock during an open raster edit invalidates that edit rather than committing
under an obsolete policy.

Clone's `Sample all layers` switch now selects between the captured composite and the
original active-layer pixels. Healing samples the active layer. Selection masks are copied
at pointer-down. Early movement, final pointer-up and cancellation remain queued until an
asynchronous clone source has been captured, rather than being lost before the tool exists.

Regression tests cover alpha masks, transparent source samples, unlocked controls, empty
selections, real layer flags, policy changes, clone sampling and immediate-release gestures.
These repairs do not complete the original roadmap, certify physical-device performance,
or replace the required publisher and release gates.

## Fill policy and opacity repair — September 18, 2026

Bucket and gradient gestures now capture the target layer and parameters before yielding to a
worker. Both pass the actual layer alpha-lock into the pixel engine; a changed lock policy rejects
the pending edit. Empty selections, identical fills and transparent gradients do not change
artwork, document revision or undo history. Provisional sessions are released even when the
operation is cancelled or fails. Changing controls after release does not replace the captured
colour or gradient stops of the submitted operation.

Gradient opacity multiplies source/selection coverage exactly once. Alpha lock keeps each
original alpha value while blending colours source-atop; it does not progressively make soft
edges opaque. Small selection masks use coordinates rather than a mismatched row stride.
Non-finite coordinates/opacity fail without painting. Regression sources are `GradientToolTest`
and the real-view `FillGestureDeviceTest`; completed CI reports remain the execution evidence.

## Atomic layer parameters and history — September 18, 2026

This independent repair starts from published main commit
`2c1437345c97354c72c78a294c3695eb70840c16`. It does not include the unpublished
selection/stroke candidate described below.

Adjustment and filter parameter changes now create their own undo/redo steps instead of
falling through to an earlier document operation. A bulk adjustment validates every key and
finite value before modifying the live map, prepares an owned replacement, and commits one
history entry. Unknown keys and NaN/infinite values are rejected without partial mutations.
Finite out-of-range inputs still clamp to the documented ranges. Opacity and mask density/
feather inputs receive the same finite-value checks. Identical values, empty adjustment
batches and an already-default reset leave revision, dirty state and undo/redo unchanged.

Executed local regression evidence: all 12 `LayerParameterEditTest` cases failed against the
unchanged published implementation (the production sources were not modified for that run).
After the repair, the same 12 cases passed with zero failures, errors or skips. The tests call
the real repository and compositor; they cover exact pixel restoration as well as parameter
values, owned maps, invalid batches, redo preservation and unchanged revisions. Local runs
used the repository's Gradle 8.14.3 and dependency versions, offline Android 36 SDK and JDK 21
with the existing Java 17 source/target. CI separately uses JDK 17.

Two added `DocumentMutationDeviceTest` cases exercise real Android save/reopen after effect
undo/redo, and clean persisted documents after invalid/no-op parameter calls. Their source
alone is not execution evidence: the publication commit must identify the completed host
and API 26/35-16k/36/36-16k run for this exact candidate.

Runtime diagnostics now write SDK, ABI, process page size and build fingerprint to per-test
logcat as well as an app-owned file. The harness collects instrumentation logs before the
minified APK installation, since test-runner cleanup may remove app-owned files. All-buffer
logs and original test-failure exit codes are preserved. A host regression checks capture
ordering. The x86_64 16 KB images emulate the userspace page-size contract; this is not proof
of a physical ARM device's kernel page size or stylus behavior. See Android's explanation:
https://android-developers.googleblog.com/2024/08/adding-16-kb-page-size-to-android.html

## Unpublished selection/stroke candidate remains blocked

Run [35343177188](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/runs/35343177188)
tested ordinary source tree `013a25342846017615a6a18fe399450ecbe7dc3e` on temporary branch
`repair/selection`. All 267 JVM tests and host analysis/build/artifact checks passed. API 26,
API 36, API 36 with the 16 KB contract, the unchanged API 35 16 KB base control and candidate
API 35 16 KB run B completed instrumentation and minified launch successfully. Candidate
API 35 16 KB run A still crashed in a native ART/JIT path while traversing files, with a null
program counter and `kotlin.io.FileTreeWalk` in the backtrace. Publication was correctly
skipped because that mandatory lane failed.

Earlier independent attempts crashed in different ART/Hilt/Compose paths. More guest RAM
did not consistently resolve the issue. These observations do not prove a root cause, and
a successful sibling run does not cancel a failed run. Keep that candidate and its failure
evidence separate from the independently verified layer-parameter repair. Do not publish it
by suppressing tests, disabling JIT, or treating the fault as conclusively external.

All original roadmap gaps and required publisher/physical-device gates above remain open.
This repair is not a signed Google Play release or a claim of unconditional crash-free use.


## Storage isolation and transparent-edge resampling — September 18, 2026

This repair is based on published main `229e0fee08d3c4b74546679b1dbc41e548fe5dc2`.
It does not include or approve the separate selection/stroke candidate. Reconciled selection run
35352111382 again failed an Android 15 16 KB lane, this time in native ART/JIT frames involving
`kotlin.collections.ArrayDeque.get` and `java.util.AbstractList$Itr.next`. Removing FileTreeWalk
was not sufficient to resolve that crash. The failure remains a release blocker, not a waived check.

The old housekeeping implementation followed symbolic directory links when pruning rasters;
a controlled local fixture reproduced deletion of another project's artwork through such a link.
Storage traversal now uses NIO without FOLLOW_LINKS. ProjectStorage passes its trusted files
boundary so linked ancestors (for example a linked project with a real `layers` child) are also
rejected before pruning, counting or deletion. Root links are unlinked rather than traversed;
nested links never contribute their target's bytes. Saved and recovery manifests retain their
referenced raster generations. Failed copy cleanup preserves the original failure. Missing roots
are harmless, while traversal/deletion errors propagate. These checks protect existing links in
app-private storage; they do not claim to defeat hostile concurrent filesystem replacement.

Local Kotlin/JDK probes reproduced the legacy deletion and the ancestor-link edge case, then
passed 1,000 preservation cycles for each repaired scenario. Android tests exercise actual
ProjectStorage with linked project roots, containers, nested links and 200 repeated cleanup cycles.
`StorageFileTreeTest` adds 14 JVM cases; `StorageTraversalDeviceTest` has five Android cases.

Bilinear resampling now weights RGB by alpha before interpolation and unpremultiplies once.
Previously an opaque red pixel beside invisible blue produced a purple half-covered midpoint;
transparent exterior taps also darkened edges. Resize, translated/rotated pixels and Liquify share
the repaired sampler. Exact texel samples preserve the original bits, including invisible RGB,
so an identity transform remains a no-op. Fully transparent interpolated mixtures are canonical
transparent black. Non-finite sampling/transform parameters and non-invertible scales are rejected;
huge exterior coordinates are checked before integer-neighbour conversion.

A standalone production-kernel probe passed 100,000 seeded cases against an independent
Double-precision premultiplied-alpha oracle, allowing one 8-bit quantization unit per channel.
This local probe used Kotlin 1.9/JDK 21, not the release toolchain. The repository's 14 new JVM
resampling tests and two Android tests cover resize, displacement, exact undo/redo, saved PNG
pixels and scaled PNG export. Source presence and local probes are not substitutes for completed
CI; the publication commit identifies the exact frozen source and required completed run.

The original roadmap, private signing, physical-device campaign and Play Console requirements
remain unchanged. The previous native-runtime failures are not reclassified as successful tests.


### Deterministic fill-policy ordering regression

Run 35354218869 passed the full host build/analysis/binary gates and the API 36, API 35 16 KB
B and unchanged-main control lanes. Its API 26 lane timed out in the existing stale bucket-policy
test; API 35 16 KB A separately failed with application and unrelated system-process crashes.
Neither failure is a pass, and the source publication remains gated.

The stale-fill test assumed a different coroutine dispatcher always suspends the caller. A local
Kotlin/coroutines scheduling probe observed the fast worker finish before the next caller statement
in 1,042 of 20,000 executions. That order is legal: a completed fill followed by a lock change is
not a stale commit. The regression now holds at commit using a test-only delegating repository,
changes the actual layer policy, releases the barrier, and executes the real production commit.
It repeats that schedule ten times for both bucket and gradient, requires the same rejection,
checks unchanged pixels/revision/history, and confirms the provisional session is released.
Two positive controls verify that a fill completed before a later lock change remains valid and
both operations undo independently. No production scheduler or acceptance check is disabled.
The local scheduling probe is not evidence that Android execution of the revised tests passed.

The failing 16 KB A image logged repeated ReferenceQueueDaemon crashes in Play services before
app instrumentation, followed by launcher, app and system_server failures. These cross-process
observations suggest an environment problem but do not prove a root cause or certify the app.
Normal-runtime repeated device lanes remain required; do not replace them with JIT-disabled runs.


### Read/write and export path isolation

Read/write path checks now reject existing symbolic links at project roots, metadata files,
raster leaves, intermediate directories and export roots. Resolving a raster no longer accepts
a linked project as a new trusted root. Atomic writes validate the original owned path before
creating a temporary file. Only trusted Context.filesDir aliases above the managed boundary
are allowed. Occupied dangling project links keep their IDs reserved.

Legacy export-folder cleanup also validates parents and excludes linked files. Previously a
legacy exports directory linked to another project could expose its pixels to expiry deletion;
a linked top-level export root could reclassify editable files as shareable exports. Six additional
Android storage regressions exercise these paths, plus a pure JVM read/write-path guard test.
The guards do not claim race-free defense against hostile concurrent filesystem replacement.

## Local selection-safety candidate — September 18, 2026

This **unpublished, not release-approved** candidate starts from main commit
`427248b828ad70c7df525902ef4e2577898a474c`, source tree
`008353bd73ddbd9238481227cf27af959e061d45`. It preserves the independently published storage,
resampling and atomic-layer-parameter repairs. It does not integrate the blocked stroke-ownership
implementation from `repair/selection`, and does not waive that branch's native-runtime failures.
The current work session could read GitHub but had no available repository-write action or
usable authenticated CLI. These new changes were prepared locally, not pushed to GitHub.

### Selection repairs

The published selection kernel failed seven controlled standalone reproductions: a uniform 4x4
magic wand overflowed its frontier; invisible RGB split transparent regions; a smaller explicit
mask caused an index error; a distant negative rectangle wrapped into the canvas; a large finite
ellipse failed to select its interior; minimum-integer shrinking did not erode; and maximum-integer
feathering attempted a negative array allocation. The identical seven probes now pass against
this candidate. Original-source failure and repaired-source success are recorded separately.

Colour selection now discovers and marks complete scanline runs before queuing them. Colour
distance includes alpha and premultiplied RGB, so invisible RGB cannot create a false boundary.
Contiguous antialiasing adds a one-pixel fringe without connecting another island through a
weak-colour bridge. Global wand and colour-range selection share matching semantics. An explicit
limiting mask is applied by canvas coordinates, including when its dimensions are smaller.

Selection geometry clips original finite endpoints before integer conversion. Ellipses and lassos
use 4x4 subpixel coverage; brush-path selection rasterizes continuous segments without making
iteration counts proportional to off-canvas travel. Ellipse interval calculations preserve both
small nonzero widths and small edge offsets beside very large bounds. Fourteen extreme ellipse
fixtures are compared with exact BigDecimal implicit-equation arithmetic, not the production
interval formula. Non-finite geometry is rejected before rasterization.

Feathering and binary square-radius expansion/erosion use separable sliding windows. Work and
scratch size depend on canvas dimensions rather than the radius; Int.MAX_VALUE feathering and
Int.MIN_VALUE shrinking are handled without integer overflow. Cancellation checks cover geometry,
colour matching, feathering, morphology, alpha extraction and selection combination. This is
algorithmic and standalone-JVM evidence, not a physical-device performance benchmark.

### Cross-panel ownership and input integration

A repository-owned selection session captures dimensions, an owned original mask and the document
revision. New requests, explicit clear/select actions, frame or active-layer changes, document
replacement, mutation, undo/redo and disposal prevent an older result from publishing. Cancelling
an old request cannot cancel a newer request. Selection publication remains outside artwork undo
history and dirty state. Both the canvas View and selection panel use this shared ownership rule.
Feathering, alpha extraction and colour-range work run off the main dispatcher, and UI observation
reuses one owned mask snapshot rather than copying the entire mask twice for its count.

Magic-wand taps choose the containing pixel using floor, not nearest-integer rounding. Replace
and add modes can select outside the previous selection: the old mask is combined after computing
the new region instead of incorrectly clipping the new region first. Android regressions include
subpixel boundary taps and test-only barriers at actual sampling/commit boundaries; they do not
assume that switching dispatchers necessarily suspends a fast worker.

### Verification scope of this local candidate

| Check | Observed result and scope |
| --- | --- |
| Identical seven-case baseline/repaired probe | Published baseline: 7 failures, process exit 1. Repaired kernel: 0 failures, process exit 0. |
| Standalone production selection kernels | 11 groups passed, covering 32,120 comparisons and boundary checks under a 96 MB JVM heap. |
| Existing and new Python verifier/harness tests | 28 passed. These test verification infrastructure, not Android app behavior. |
| Kotlin compiler syntax parsing | 133 Kotlin source files parsed with zero syntax errors; this is not dependency resolution or type checking. |
| Full Gradle unit/style/analysis/lint attempt | Blocked before compilation by UnknownHostException for services.gradle.org while downloading Gradle 8.14.3. |
| New JUnit source | 20 colour-selection cases, 11 shared-kernel groups and 13 repository transaction cases added. The JUnit runner was not executed in this session. |
| New Android source | 13 real-View selection cases added. They have not been executed for this candidate. |
| APK/AAB, signed release and physical-device checks | Not built or run for this candidate; old-main artifacts do not validate these new sources. |
| GitHub publication | Not performed for this local candidate. |

The standalone kernel command is:

```bash
python .github/scripts/verify_selection_kernels.py --output build/selection-kernel-checks
```

It requires installed Python, Kotlin and Java, downloads no tooling, records source SHA-256 values,
and compiles the real pixel implementations. The only adapter extracts the unchanged BlendMode
enum from its production file without its serialization annotation. It does not replace Android
with fake implementations or claim that the JUnit/Android suites ran. The observed local compiler
was Kotlin 1.9.0, targeting JVM 17, on JDK 21; the configured Android release toolchain remains
Kotlin 2.0.21/JDK 17 and still needs its own complete build and verification.

Permanent CI retains its original API 26/35/36 jobs and adds an independent Android 15 16 KB repeat
and Android 16 16 KB coverage, each with a unique artifact name. These are configured future gates,
not successful execution evidence. No JIT switch, skipped failure, relaxed assertion, altered
signing policy, force push or overwritten remote history is used to approve this candidate.

The original roadmap gaps, pending stroke/native-runtime work, real publisher signing, physical
phone/tablet/stylus testing and Play Console requirements above remain unresolved. Apply this patch
to its stated base and obtain a successful full Android verification run before promoting it.

## Procreate workflow milestone — September 19, 2026

The new `LayerTransform` kernel is Android-independent and resamples once around a consistent
canvas-centre pivot. The repository prepares pixel and mask planes off the main dispatcher and
commits one history entry only after rechecking the document revision, frame, active layer and
provisional gesture state. Empty and nonempty selections are explicitly unsupported for this
whole-layer operation. Linked, locked, hidden and effect layers are rejected.

The Transform panel keeps local drafts, validates finite/ranged input, and does not edit the document
until Apply. Cancellation before commit leaves it untouched. The old unused transform helper's
double-pivot positioning was removed. Drag translation now refuses masked/linked/selected layers
instead of moving only one plane or losing selected pixels. Use the precision panel for masks.

`python .github/scripts/verify_transform_kernels.py` passed six shared check groups with 39,714
comparisons/boundary assertions in a 96 MB JVM. It is **not** an Android, Gradle/JUnit, lint or release
result. `LayerTransformTest`, `LayerTransformTransactionTest`, `TransformUiRegressionTest` and a
real persistence regression are included for the ordinary CI suites. Their exact-commit execution
evidence must be checked separately. This feature milestone does not close the parity ledger.

### Workspace access and interaction checks

`StudioWorkspaceUiTest` opens the real `CanvasScreen`, checks focus restoration by both button and
Back, visits all nine workspace panels, selects every `ToolType`, and exercises large text plus
light/dark layouts. It captures actual Android screenshots to the existing `test-evidence` path.
The new tool palette restores missing opening routes for guides, animation, canvas setup and text.
No save/project operations are removed by focus mode. The selection outline respects reduce-motion
and does not continuously animate with no selection. The obsolete permanent tool strip was removed.

CI execution and screenshot inspection must still be recorded for the exact final source; compiling
a UI or listing tests is not equivalent to running them. This does not certify artist-rated parity.

## Studio inspection follow-up — September 19, 2026

The new text route uses an explicit Choose position / Place on canvas sequence. Placements are
bound to project, layer and content revision, consumed once, and cancelled on dismissal or document
opening. Changed targets cannot reuse a stale point. Text playback is stopped before positioning.
Transform and text action rows wrap at large text sizes; signed transform entry works without a
numeric-keyboard minus key. Normal exact-candidate CI and actual screenshots must validate this
follow-up as well; the preceding green baseline is not evidence for these changes.

Accessibility inspection also corrected text scaling: the extra text-size preference now scales
text once, without also scaling layout density and shrinking the usable workspace. The independent
large-target preference still controls touch-target size. A real Compose density regression guards
this distinction.

The first candidate run (`35439604147`) passed JVM tests, ktlint and both Android lint variants,
but failed Detekt on three new-source organization/nesting findings and did not complete device
verification. This inspection refactors those checks without reducing their 39,714 comparisons
and hosts the full editor UI tests in the injected `MainActivity` required by `ArtFlowCanvasView`.
The failed run is diagnostic evidence, not an acceptance result; a fresh full matrix is required.
