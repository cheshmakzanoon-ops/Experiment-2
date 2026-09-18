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
| Advanced transform tools | MOVE/TRANSFORM translate pixels; general scale/skew/perspective/distortion is not implemented. Canvas-wide operations are separate. |
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
