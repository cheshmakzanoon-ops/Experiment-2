# Emulator acceptance and parity corrections — 24 October 2026

This work starts from `169473f80b1097663e65bd8359d6d3bd1dc8b83f` on `main`.
It repairs demonstrated drawing and document behavior, and strengthens the evidence required
from GitHub Actions. It does not establish complete Procreate feature or rendering equivalence.

## Drawing and selection corrections

- **Stroke endpoints:** spacing now remembers the last dab's distance along the entire path.
  A short final segment no longer loses its closing dab when its length happens to match an
  earlier segment's local dab offset. Live preview, erasing and committed painting agree.
- **Zero spacing:** intense/uniform rendering modes use the same opacity and edge rules as
  ordinary dabs. Blending retains paint accumulation. Tilt-dependent colour changes no longer
  use a shortcut that omits those controls.
- **Pen orientation:** flat and imported tips interpolate their orientation between samples,
  using the shortest angular turn across the minus-pi/plus-pi boundary. This follows [Android's stylus orientation representation](https://developer.android.com/reference/android/view/MotionEvent#AXIS_ORIENTATION); it is not recovered Procreate filtering.
- **Count Jitter:** the documented Count 10 / 50% setting can now reach every count from 5 to 10.
  The previous exclusive upper bound on the reduction made the minimum 6. The 0% and 100%
  contracts are preserved. See the [reference handbook](https://help.procreate.com/procreate/handbook/5.4/brushes/brush-studio-settings).
- **Native stamp count:** new imports use the [inspected truncation contract](NATIVE_BRUSH_COUNT.md)
  for the base count, so `shapeCount=0.5` means eight stamps rather than nine. Source import version 2
  identifies the corrected interpretation. Previously saved versions 0 and 1 keep their existing
  count and their historical comparison baseline for unchanged source fields. The brush Share
  dialog lists any primary or secondary count that will change in a Procreate file, including
  local counts above the native limit of 16 and preserved legacy values. ArtFlow files keep the
  current counts exactly; selecting a different export format does not change the brush.
- **Imported pressure curves:** new imports opt into the [inspected natural spline and 1,024-sample lookup](NATIVE_PRESSURE_SPLINE.md), including its scaling, endpoint and rounding rules. Existing stored curves keep their explicit legacy linear behavior, and the omitted default preserves historical signed-document checksums. The numerical fixtures are derived from static native instructions, not captured reference-app output.
- **Brush interchange:** export retains independent tilt opacity, including explicit zero and
  edits to imported settings. PNG shape/grain and Photoshop ABR mask imports preserve authored
  coverage instead of stretching contrast; white tips remain visible and subtle grains retain
  their intensity. Non-divisible image reductions now account for each source pixel's fractional
  area instead of omitting rows and columns, including the final edges. No-resize, integer-factor
  reductions and upsampling retain their existing mapping; previously saved tiles are not rewritten.
  Photo-derived grain creation still has its separate normalization behavior.
  Gallery/external-open and Brush Studio imports share the same path, preserving secondary
  components and remapping both components' image identities when importing `.artbrush` files.
  [Native compound-mode precedence](REA_DUAL_MODE_PRECEDENCE_2026-10-24.md) establishes that the
  extended mode field wins over the legacy field. Import follows that precedence, while unchanged
  export retains both original fields and an edited mode removes the stale override.
- **Automatic selection:** the colour source is the active layer, including transparency.
  Covering artwork, the canvas background and a fill-reference layer do not substitute their
  pixels. The existing transaction rejects work made obsolete by a layer/document change.
- **Blend picker:** every mode demonstrates the same two colours using the production blend
  equations. The previous arbitrary per-mode tints and development-phase badge are removed.
- **Guide status:** the canvas reminder uses the same active-guide and visibility conditions as
  the drawing overlay. It no longer announces guides when both guide types are off. An opaque
  themed background keeps it readable over artwork and the dark work area, opposite the brush
  sidebar, and focus mode hides it with the other controls.
- **Zoom tool:** the actual tool strip now includes the previously omitted Zoom entry. Its
  dedicated pointer path supports tapping to zoom in and dragging vertically to zoom in or out
  around the starting point, using the existing viewport limits. Navigation does not paint or
  add undo entries. This completes an ArtFlow tool interaction; it is not a claim that Procreate
  uses the same gesture for a dedicated Zoom tool.
- **Colour preview disclosure:** the Actions control now says "Simulate CMYK inks" and explains
  that no printer colour profile is applied. Its optional grey overlay identifies simulated colour
  shifts rather than claiming to identify colours that cannot print. The fixed-ink algorithm is
  unchanged; profile-managed CMYK remains an implementation gap.
- **Reference companion:** the floating window now draws immediately after either brush sidebar,
  keeping its title, Canvas/Image tabs and close control reachable when they overlap. Bottom
  toolbars, page controls and later modal panels retain their previous precedence. In focus mode,
  a top inset derived from the same 48/56 dp exit-button size keeps both controls accessible.
  Reference's stable callsite and saved fractional placement remain intact.

## Secondary Grain Blend and stored drawings

The secondary component of a compound brush previously ignored its Grain Blend selection and
always multiplied texture coverage. Subtract and Hard Mix now use the same local coverage rule
as a solo brush, before secondary opacity. For raw coverage 153/255, grain 128/255 and opacity 0.5,
the center alpha is 13 for Subtract and 128 for Hard Mix; both incorrectly produced 38 before the
repair. Multiply still produces 38, and its original floating-point multiplication order is retained.
Primary grain processing, the three existing dual-combine modes and final primary opacity keep
their existing order. This fixes an ArtFlow setting; it does not implement the distinct native
amount/transfer and wet-paint pipeline.

Eight renderer regressions cover independent solo/compound values, the strict Hard Mix threshold,
all three dual modes, primary/secondary opacity, erasing, neutral or missing grain, cropped origins
and six whole-buffer historical Multiply hashes captured with the unchanged published renderer.
Seven document regressions cover signed historical fixtures, original-checksum rejection, all
legacy versions and frame stacks, new v4 behavior, group isolation, raster/metadata preservation
and independent saved-brush definitions. The added Android storage case checks historical pixels,
checksum rejection, poisoned texture-cache disk reload, v4 save/reopen, new Subtract/Hard Mix
pixels and the exact saved PNG composite. These declarations require fresh exact-revision CI;
results from another source revision are not reused as acceptance evidence.

## Native document import corrections

The genuine-file audit found three concrete import defects:

- [One-byte layer masks](PROCREATE_MASK_IMPORT_2026-10-24.md) were discarded by the RGBA-only tile
  decoder. Coverage now survives, including zero, intermediate values, missing tiles and calibrated
  orientation. Existing four-byte masks keep their interpretation; ambiguous edge-only byte lengths
  remain documented. The actual decoder reproduces all 4,194,304 coverage bytes in the genuine mask
  file. Its composite still differs at 270 pixels by one RGB unit, so this is not pixel equality.
- [Native groups](PROCREATE_HIERARCHY_IMPORT_2026-10-24.md) live in `unwrappedLayers`, separately
  from the flat pixel-layer index. Import preserves their hierarchy and native visibility field,
  retaining the existing legacy representation. All original leaves remain present once and in
  order. This does not establish native group blend/mask semantics.
- [Recognized RGB profiles](PROCREATE_COLOR_IMPORT_2026-10-24.md) now accompany raw layer,
  composite and background channels. New P3 artworks keep their native encoding; existing-canvas
  insertion and flattened fallback convert once into the destination profile. Android thumbnail
  calibration explicitly compares sRGB on both sides. Unsupported profiles keep the historical
  sRGB fallback with a visible notice.

Four new Android identities cover masks, hierarchy and two profile workflows through actual
import/storage/fresh-repository paths. These imported files do not require another stored-document
version change. Existing artworks and standard blend equations remain unchanged. The genuine
sample fetch is pinned to an exact repository revision and all five complete file hashes are
checked; one file is a duplicate, giving four distinct archive inputs.

## Group compositing and file compatibility

Explicit **Normal** groups now composite their members against transparency before blending
with the surrounding stack. An opaque, unmasked **Pass Through** group lets its members blend
with outside layers. Pass Through is available in the group picker and excluded for pixel layers.
New organizational groups default to Pass Through so grouping alone does not alter the picture.
PSD import/export preserves the distinction.

The `.artflow` format is now **v4**. The original checksum, when present, is verified before any migration.
Historical unsigned documents retain their existing unverified acceptance.
Using the original document version, v1/v2 opaque, unmasked Normal groups become explicit Pass
Through in every animation frame; previously isolated faded/masked groups and v3 Normal groups
keep their existing scope. Stored vector strokes from v1/v2/v3 also retain their actual historical
secondary-grain behavior by migrating that immediate secondary setting to Multiply. Both the
main layer list and every frame are covered. New v4 strokes retain corrected Subtract and Hard Mix.

Existing raster pixels, primary grain, nested source settings and imported metadata are not
rewritten. Brush-library and `.artbrush` definitions remain outside document migration. Opening
migrates the in-memory model; the next save writes a checksummed v4 file. Older application builds
reject new v4 files instead of silently replaying their corrected stroke or group semantics.

Native `.procreate` group blend/mask metadata remains unverified. Exporting an isolated or
actively masked group therefore uses the existing rendered-appearance fallback, with editable
source layers retained hidden below it. Ordinary Pass Through groups remain on the editable
path, and disabled masks alone do not trigger the fallback. This preserves appearance within
the supported exporter; it is not fully editable native group interchange or validation inside
the Procreate application.

## What the emulator must exercise

| Acceptance area | Production path and evidence |
| --- | --- |
| Stroke sampling | Short resampled stroke, visible endpoint, live preview, commit, undo/redo, save and reopen |
| Brush import | Production import route, Android image decoding, compound settings, authored mask coverage, real image files and storage reload |
| Brush sharing | Actual draft and saved-set entry points, named primary/secondary count conversions, format changes, live draft updates and exact ArtFlow preservation |
| Imported pressure | Real `.brushset` import, constant/descending/curved native lookup values on Android, and exact `.artbrush` persistence |
| Automatic selection | Real MotionEvents, covered active layer, transparent areas, empty layers, obsolete pending work |
| Groups and interchange | PSD import, distinct scopes, real save/reopen, PSD export, enabled/disabled masks and bounded `.procreate` appearance fallback |
| Historical vector grain | Signed v3 fixture from the published renderer, raw checksum rejection before migration, real grain-file reload, exact historical layer pixels, v4 save/reopen, corrected new strokes and Android PNG decoding |
| Saved selections | Real file storage, fresh repository/controller, fractional mask coverage, constrained Fill Layer, undo/redo, independent Android PNG decoding, slot deletion and document switching |
| Blend picker | Group-only Pass Through navigation and independently expected pixels captured from the actual Compose previews |
| Floating Reference | Real editor coordinate taps/drags inside verified left/right sidebar intersections, modal precedence, outside-window sidebar and Zoom input, both focus target sizes, restored position and unchanged brush/artwork/history |
| Existing editor | Full declared instrumentation suite, including GL restoration, pressure/palm input, Zoom pointer behavior, all 20 tools through the actual large-text editor route, active guide status, fills, transforms, recovery, animation, platform export codecs and adaptive controls |
| Packaged application | Native library loading, measured process page size and sustained launch of the R8-minified benchmark build |

The six original lanes remain: API 26/4 KB, API 35/16 KB, an independent API 35/16 KB repeat,
API 36/4 KB, API 36/16 KB and API 36 tablet. The added standard API 35/4 KB lane uses the default
runtime, bringing the gate to seven independent hosted VMs/AVDs that can run in parallel.
Only the Android 15 / 16 KB lanes retain the existing verified JIT-disabled emulator mitigation;
their timings do not certify normal-runtime Android 15 performance. The standard-image control
adds coverage without replacing the failed 16 KB requirement.

Each lane now publishes `verification.json` and `verification.md` in its device artifact and
a table in the Actions job summary. The gate compares **every declared device test** against
the JUnit results, rejects missing/duplicate/stale cases and unexpected skips, verifies measured
API/page size, and requires the minified launch record. The only permitted skips are the existing
Android 13 cancelled-pointer contract below API 33 and scoped MediaStore publication below API 29.
Unsupported test-declaration syntax requires an explicit parser update rather than reducing coverage.
Dark, light and large-text workspace screenshots now use the runner-collected output directory;
the previous app-owned directory was removed during uninstall before the workflow could collect it.
Brush Studio theme captures additionally wait for actual dialog pixels to reach the expected light
or dark surface. An already visible practice stroke alone cannot satisfy the theme assertion.
Workspace captures also require known artwork pixels from the real GL surface; Compose idleness
alone allowed an empty canvas in the earlier dark screenshot. The standalone legacy tool-palette
fixture now uses the themed Surface supplied by the real activity. Its component coverage is
separate from the added large-text test that selects every tool through the actual editor's
All tools strip. The guide reminder is tested through active, hidden and focus-mode transitions.
The real artwork workflow waits for the painted layer thumbnail before collecting layer-panel
evidence; repository ordering alone can settle before the existing thumbnail debounce and rendering.
The guide label's contrast check requires opaque background spans above and below the rendered glyphs and
measures the actual foreground/background contrast at a minimum of 4.5:1. A central text crop's
background-area ratio is not an opacity or readability measure. Guide screenshots also wait for
the white document and contrasting dark GL work area to be visible, including after focus changes.

The JVM gate also fetches the existing user-selected reference archive and verifies its pinned size
and SHA-256 before running the 394-brush / 534-settings-component corpus check. Gradle tracks the
configured reference inputs so a previously skipped corpus result cannot substitute for execution.
This corpus tests import and serialization contracts, not reference-renderer pixels. Reference
archive bytes and assets are not included in the application or uploaded as CI artifacts.
The same pinned input also gates the recovered archive/property mappings and stamp-count conversion, native spline anchors
and every committed spline fixture, plus the compound source-mode and shader dataflow contracts.
These checks inspect reference bytes as data; they do not execute the reference application.
CI uses Python 3.11, Capstone 5.0.6 and llvmlite 0.44.0 and retains only small derived JSON reports
inside `verification-reports`. Fixture checking is read-only and cannot silently regenerate the
expected results to match changed implementation.

The narrow Android 15 runtime-crash classifier now examines JUnit failure attributes as well as
stack text and rejects test errors or application assertions. Its new regressions reproduced seven
incorrect retry decisions before the repair. Original failure artifacts and the single-retry limit
remain intact. Suite-level error counters also fail closed when detail is missing or invalid.

## Timing evidence scope

`StrokeTimingDeviceTest` retains the existing 2048-square fixture: a filled Paper layer, four
striped painted layers and an Ink layer, with 80 samples for each of three brushes in a fixed order.
Earlier committed strokes remain on Ink. `stroke-timing.json` now records ordered nanoseconds for
pre-stroke preview, begin, each continuation and preview, end, final preview and adjacent combined
intervals. The readable report includes phase p50/p95/max with the original percentile indices.

`System.nanoTime` measures elapsed wall intervals, including called raster/compositing work,
allocations, dispatcher scheduling and mutex waits. Sample preparation, fixture construction and
report writing are outside the timed calls. Device input delivery, GL upload/draw and display
presentation are not measured. This is not CPU time, physical pen latency or a Procreate benchmark.
There is no speed acceptance threshold; the existing positive preview-p50 sanity check remains.
Runs 306 and 307 contain the earlier narrower preview-only samples and a combined end/final-preview
value, and must not be presented as if they captured the new phase breakdown.

## Execution evidence

Use the [Android CI workflow](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/workflows/android-ci.yml)
for the exact published commit, then inspect each lane's generated verification summary.
A queued, skipped, cancelled or failed run is not a completed gate. An artifact from an earlier
commit does not validate later source changes.

### Completed run 308

[Run 308](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/runs/40104512305) completed
successfully on `c5cf20d7e6ca075d1a49be76026d401dafeada1e`: the build and all seven emulator jobs
passed on their first instrumentation attempts. The executed build contained 1,432 JVM tests,
126 Python checks, static analysis and APK/AAB/native/signing checks. Every lane accounted for
all 242 declared identities, measured its required runtime and passed the minified launch.
Totals were 1,692 passes and two permitted API 26 skips, with no retries, missing or extra cases.

The [complete evidence record](CI_RUN_308_2026-10-24.md) includes artifact hashes, source/archive
identity, exact v3/v4 migration files, independently checked raw timing and scoped original-image
review for all seven configurations. It retains the separate Search-process SIGILL in the passing
main API 35 lane and the slow JIT-disabled timings. This successful pair does not erase the earlier
runtime failures or establish Procreate responsiveness. The subsequent native-import changes add
four device identities and require their own exact-revision run.

### Completed run 307

[Run 307](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/runs/40078354945) tested
`2d57dbd2ae476aeaf66fd9c2fda5b259cf1fb572`, including the Reference correction and the added
standard Android 15 lane. All seven device jobs finished. The overall run **failed**: six lanes
passed their complete suites and minified launches on the first instrumentation attempt, while
the independent Android 15 / 16 KB repeat failed after its existing single automatic retry.

| Configuration | Final reported / declared | Passed | Failed | Allowed skips | Automatic retries | Minified launch |
| --- | ---: | ---: | ---: | ---: | ---: | --- |
| API 26 / 4 KB | 241 / 241 | 239 | 0 | 2 | 0 | Passed |
| API 35 / 16 KB | 241 / 241 | 241 | 0 | 0 | 0 | Passed |
| API 35 / 4 KB standard | 241 / 241 | 241 | 0 | 0 | 0 | Passed |
| API 36 / 4 KB phone | 241 / 241 | 241 | 0 | 0 | 0 | Passed |
| API 35 / 16 KB independent repeat | 4 / 241 | 3 | 1 | 0 | 1 | Not reached |
| API 36 / 16 KB phone | 241 / 241 | 241 | 0 | 0 | 0 | Passed |
| API 36 / 4 KB tablet | 241 / 241 | 241 | 0 | 0 | 0 | Passed |

The failed repeat's first attempt reported 86 passes and one empty failure, with an application
`HeapTaskDaemon` SIGSEGV in ART `MarkCompact::ScanObject<true>+36`. Its final retry reported three
passes and a `NoSuchFieldError` for `RememberEventDispatcher.remembering` during Compose setup;
237 cases remained missing. The exact eventual failure passed in the first attempt and all six
successful configurations. That narrows the evidence but does not establish an application,
runtime or emulator cause. Neither attempt is combined with another to fill missing coverage.

The build job executed **1,417 JVM tests in 234 suites** with zero failures, errors or skips,
plus **126 Python checks**. Ktlint, detekt, APK/AAB integrity, native alignment and the signing
negative guard passed. Lint retained 153 debug / 151 release warnings and no errors or fatal
findings. The pinned metadata corpus, five genuine documents and static native contracts passed
within the same bounded scope described for run 306. The uploaded source archive was independently
compared byte-for-byte with `git archive` of the named commit.

All seven original device archives were checked against their byte lengths and SHA-256 digests,
the exact revision and a frozen 241-identity manifest. Both Reference interaction cases passed in
all six successful lanes. Original screenshots confirmed title/tab/close visibility above actual
sidebar intersections, modal precedence, focus exit clearance and live canvas/Reference pixels.
The tablet retained 16 physical pixels of focus clearance and 7,871 practice-stroke pixels in
each theme. The reviewed Count, guides, thumbnails, workspace and large-text fixtures had no new
concrete visual defect. This is bounded fixture acceptance, not complete product equivalence.

The frozen revision-plus-identities JSON SHA-256 was
`8a8ef04b1f6df1040595a26a8b61a53e85b3053f48b184ebf2fe26688368a9bd`.
The build report archive was artifact `12554318521`, SHA-256
`faeac3f5c54efd628d7a68a70d1ae197d4932db8efa6c6d0c037d28e7321ff67`.
Later secondary-grain, document-v4 and timing changes require their own exact-revision run;
run 307 does not accept them.

### Completed run 306

[Run 306](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/runs/40071698959) tested
`e0470f0861681d2a84f1383f6724e427a1eaf67c`. All six jobs finished and their original artifacts were
independently checked against the frozen 239-case source inventory. The overall run **failed**:
five lanes completed their required final suites and minified launch, while the independent
Android 15 / 16 KB repeat remained incomplete after its one existing automatic retry.

| Configuration | Final reported / declared | Passed | Failed | Allowed skips | Automatic retries | Minified launch |
| --- | ---: | ---: | ---: | ---: | ---: | --- |
| API 26 / 4 KB | 239 / 239 | 237 | 0 | 2 | 0 | Passed |
| API 35 / 16 KB | 239 / 239 | 239 | 0 | 0 | 1 | Passed |
| API 36 / 4 KB phone | 239 / 239 | 239 | 0 | 0 | 0 | Passed |
| API 35 / 16 KB independent repeat | 1 / 239 | 0 | 1 | 0 | 1 | Not reached |
| API 36 / 16 KB phone | 239 / 239 | 239 | 0 | 0 | 0 | Passed |
| API 36 / 4 KB tablet | 239 / 239 | 239 | 0 | 0 | 0 | Passed |

All five complete suites measured their expected SDK and process page size and completed the
minified smoke test. The API 35 modes were verified JIT-disabled; the other modes were default.
The successful API 35 lane's first attempt reported two passes and a native crash. The independent
repeat's first attempt reported 169 passes and a native crash; its retry reported one failed case
and 238 missing cases. Those original attempts remain separate and are not combined into an
accepted suite. No manual workflow or job reruns occurred.

The build job executed **1,417 JVM tests in 234 suites**, with no failures, errors or skips, and
**126 Python checks**. The pinned corpus exercised 394 brushes / 534 settings components, and all
five genuine `.procreate` sample documents ran. The corpus verifies metadata import/persistence;
the native contract reports are static/derived checks and do not execute the reference renderer.
Ktlint and detekt passed. Android lint had no errors or fatal findings, with 153 debug and
151 release warnings retained. Debug APK / release AAB integrity and bundled native alignment
passed, and the production-signing negative guard rejected missing signing inputs as required.

Original screenshots verified the Count disclosures, presented GL canvas, guide contrast/focus
behavior, painted layer thumbnails, large-text tool access and Brush Studio themes. A subsequent
review also identified a preexisting compact Reference-panel/sidebar overlap, which requires a
separate editor correction and fresh device acceptance. The follow-up reorder and focus clearance
above address that finding; their two new cases raise the next declared inventory to 241.

The [Android 15 runtime comparison](ANDROID15_RUNTIME_COMPARISON_2026-10-24.md) preserves the
native/system crash chronology and the separate unchanged-application diagnostics. Both fresh
16 KB default-runtime comparison lanes failed, so reverting the JIT setting is not an established
repair. The prior startup failure below also remains unresolved.

These results apply to the named application revision. Later Reference-panel changes must be
validated by their own complete workflow; neither this run nor the diagnostic branch accepts
unexecuted changes.

### Completed run 305

[Run 305](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/runs/40067128914), on
`36c84c223fa8d992e4ddf522574ee694250c30b9`, exposed the earlier guide-test sampling error on API 35
and both API 36 phone configurations: each reported all 236 cases, with that assertion as the sole failure. The captured
foreground/background were `#181818` / `#F8F8F8`, approximately 16.7:1 contrast; the test had rejected
the crop's pixel-area ratio before calculating it. The follow-up changes the spatial evidence check
and retains the contrast requirement, guide interactions and failure artifacts. API 26 passed all
234 applicable cases plus the two permitted API skips and completed the minified launch. These
results explain the correction; they are not acceptance evidence for later Count or test changes.

The completed run retained all six lane outcomes:

| Run 305 configuration | Reported / declared cases | Passed | Failed | Allowed skips | Minified launch |
| --- | ---: | ---: | ---: | ---: | --- |
| API 26 / 4 KB | 236 / 236 | 234 | 0 | 2 | Passed |
| API 35 / 16 KB | 236 / 236 | 235 | 1 | 0 | Not reached |
| API 36 / 4 KB phone | 236 / 236 | 235 | 1 | 0 | Not reached |
| API 35 / 16 KB independent repeat | 53 / 236 | 52 | 1 | 0 | Not reached |
| API 36 / 16 KB phone | 236 / 236 | 235 | 1 | 0 | Not reached |
| API 36 / 4 KB tablet | 236 / 236 | 236 | 0 | 0 | Passed |

No lane was retried. API and process page size were measured in all five complete suites;
the truncated repeat did not reach its runtime-measurement test. The tablet passed the full
guide-state, touch-through and focus workflow as well as the complete-coverage gate.

The independent API 35 / 16 KB repeat in that run stopped after 53 reported cases: 52 passed,
one failed and 183 were missing. Its main-thread exception came from Android's
`MessageQueue.removeSyncBarrier` during `ViewRootImpl.doTraversal`. The instrumentation thread was
still inside `ActivityScenario.launch` for the empty canvas-test activity, before the saved-reload
test reached repository operations or canvas construction. The prior activity had reached
`DESTROYED`; these facts narrow the failure phase but do not establish its root cause. The coverage
gate rejected the incomplete run and no retry exception was added for it. A subsequent successful
run does not erase this unresolved startup failure or establish normal-runtime Android 15 stability.
The [bounded startup diagnosis](ANDROID15_STARTUP_FAILURE_2026-10-24.md) records the source, artifact,
timeline and limits of that finding.

For context, baseline [run 301](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/runs/40056947818)
executed 215 tests on API 26: 213 passed and two API-limited cases skipped, followed by a successful
minified launch. Its measured elapsed repository-preview p95 was 10.3 ms for a 12 px pen, 8.8 ms for an 80 px
textured brush and 39.3 ms for a 300 px soft airbrush on a 2048-square document with paper, four painted layers and an Ink layer.
Those are baseline emulator measurements, not physical input-to-display latency or Procreate
comparisons.

## What still prevents a full-parity claim

The [comparison register](PROCREATE_PARITY.md) remains open for the native wet-paint and compound-brush
coverage pipeline, 24 retained-only pressure graphs, continuous grain sampling, broader native document
metadata and large-canvas performance. The pixel store is 8-bit ARGB; native CMYK documents and
profile-managed print proof are not implemented. The 3D editor paints a base-colour atlas and uses
global metallic/roughness controls; separate paintable material channels remain missing. Matching the
reference application's rendering requires paired, versioned reference outputs for identical
inputs. Current self-consistency and external-format tests cannot establish that equivalence.
Production consumers currently use nine of the 33 observed pressure graph keys when their
corresponding dynamics are active. The other 24 remain available for persistence and export.
Correct spline interpolation does not supply the missing channel/scalar composition rules.

[Compound scalar equations and source-to-shader mode translation](REA_DUAL_BLEND_CONTRACT_2026-10-24.md)
have now been reconstructed. They are not substituted into the current three-mode renderer: native
synthesis uses separate amount/transfer data, different accumulation, primary edge/grain processing
before combination, a secondary threshold and a primary-edge safeguard. Connecting a scalar helper
to the current buffer without that surrounding pipeline would not reproduce the inspected behavior.

Physical stylus pressure/tilt, palm rejection, input-to-display latency, sustained memory/thermal
behavior and artist acceptance also need physical devices. The elapsed repository-call timing test reports evidence;
it does not turn an emulator timing into a production quality guarantee.
