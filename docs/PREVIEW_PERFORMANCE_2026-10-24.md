# Preview raster transfer and ownership — 24 October 2026

Base revision: `e2aa900b91298fe08eca8529d0345220faa9e2eb`.

This change removes two redundant operations in the existing CPU preview path while retaining
the same raster bytes and repository/history ownership. It does not establish a frame-time budget,
physical input-to-display latency or Procreate performance parity.

## Why this path

The retained [run 309](CI_RUN_309_2026-10-24.md) timing fixture separates elapsed repository calls.
For the 300 px soft airbrush on its standard Android 15 / 4 KB lane, continuation was approximately
0.016 / 0.026 ms at p50 / p95, while `compositePreviewFrame` was 61.131 / 81.212 ms.
The commit call took 124.233 ms and its following preview 557.015 ms. These include coroutine,
lock and scheduling waits; they are not CPU profiles, displayed frames or native-app comparisons.
Run 309 failed a separate P3 test and is retained as a failed run.

The implementation already caches live dabs, lower layers and dirty regions. The final preview
after a stroke ends must redraw the previous stroke bounds, so that slow call alone does not
establish a cache defect. This patch addresses two operations whose redundancy can be demonstrated
from the actual source and buffer contracts.

## Production changes

### Place a raster on a cleared pooled buffer

`Compositor.placeOnClear` previously called general source-over blending for every partly
transparent pixel. Its target is already transparent black: `PixelBufferPool.obtain` clears reused
buffers, and a new array also begins at zero. Source-over therefore retains each nonzero-alpha
source pixel's straight RGB and alpha bytes. Those pixels can be copied directly.

Fully transparent pixels continue to leave canonical zero in the destination, including source
pixels with hidden RGB. Differently sized rasters still use the existing `PixelBuffer.drawInto`
fallback. The input raster remains read-only.

The JVM regression compares the actual compositor against the unchanged general blend arithmetic
for every alpha value and every value of each independent RGB channel. It also dirties and reuses
the one-buffer pool before rendering an all-transparent raster. This is exact byte equality,
without a new tolerance.

### Reuse only an already-private regional raster

`CanvasRepositoryImpl.paintLive` previously copied a raster that `cropPreview` had just allocated
and cropped for that render. It now reuses that private raster when the snapshot has a region.

`cropPreview` is the only setter of `PreviewSnapshot.region`; `PixelBuffer.crop` allocates a new
array and copies rows. A full preview still copies its shared raster. An eligible live stroke's wet
pickup reads the unchanged full-canvas source held in `fullRasters`. Multiple active strokes retain
their sequential draw order. The later dependent-segment correction below makes their wet pickup
respect that same order. Commit and undo keep their independent raster snapshots.

The repository regressions render both regional and full previews, then check the stored raster
and history exactly. Cancellation restores the original frame. Commit must change pixels, and
undo/redo must restore every original/committed byte. Paint exercises live regional reuse; erasers
exercise the existing fallback because they are excluded from `canDrawLive`. Existing cropped
wet-rendering tests remain in the full suite.

## Initial performance-only local verification

One coordinated gate completed successfully in 4 min 56 sec, with the build cache disabled and
both hash-pinned reference inputs present:

- `testDebugUnitTest`: **1,488 tests / 246 suites**, zero failures, errors or skips. Three new
  identities were added; none of run 311's 1,485 identities was removed.
- `compileDebugAndroidTestKotlin`: all instrumentation sources compiled.
- `ktlintCheck`: all five applicable reports empty.
- `detekt`: zero new errors; the existing baseline is unchanged.

The checked production hashes are
`d0fce3b5eaf917f7bddd7868e4b07f2fc859061543dcac367e53dcaeb03e8d58` for
`Compositor.kt` and
`89cc2c266b3ba26f639f48f1308d157927c69b13904588fe7cae1269273fe9ff` for
`CanvasRepositoryImpl.kt`.

A subsequent one-branch UI correction suppresses Zoom's misleading generic adjustment-panel
hint; the existing actual tap/drag instructions remain. It changes no pointer behavior. That
`EditorChrome.kt` revision, SHA-256
`724e2e5a87d02ac81bb362e1d5bdc7d9218d18d551bfb0ea57b71949aabfc363`, passed
`compileDebugKotlin ktlintCheck` in 1 min 47 sec, with compilation and the changed-source formatting
task executed. The first sandboxed invocation stopped before build startup because Gradle could
not create its local socket; the authorized invocation completed. The 1,488-test gate above
precedes this wording-only follow-up. It also predates the additional model/segment changes below;
the recorded repository hash identifies that earlier source rather than the final combined source.

## Provisional segments and canvas pickup

The [model gesture correction](MODEL_EDITOR_2026-10-24.md) keeps seam-separated segments provisional
until the whole pointer gesture finishes. `sealStrokeSegment` retains their points but drops the
prepared raster copy and live raster cache. A concurrent eligibility map identifies each growing
stroke by its first immutable point, so an old preview cannot recreate a sealed cache or use a new
document's reused numeric ID. End, cancel, new/load, restore and disposal remove the eligibility.

A later wet segment exposed an existing multi-stroke preview inconsistency: the live cache sampled
the committed layer before any preceding provisional paint, while sequential commits sampled the
earlier segment's result. The preview now draws that dependent stroke against the preceding paint.
It requests a full frame because carried/pulled pickup may depend on points outside an ordinary
dirty tail. The dependency is confined to earlier non-mask paint on the same layer, including
erasers. Independent layers, mask-only predecessors and ordinary single-stroke live rendering keep
their existing paths.

Three repository cases cover eighteen sealed segments without retained live/prepared buffers,
exact independent history through commit/undo/redo, cancellation and document-ID reuse. Two more
wet-paint cases compare every full/frame preview with a separate sequential-commit repository,
before and after sealing and after new samples. They also pin independently calculated overlap
colours and restore every original byte and history mark on cancellation. The ordinary and
carried/pulled/blurred fixtures have fixed input times and exact comparisons. The reference is the
existing ArtFlow renderer; no native Procreate wet-paint parity is asserted.

Only retained per-segment raster caches are bounded by sealing. Points still occupy memory,
many-segment previews can repeat full raster work, and an already-running synchronous copy may
finish before cancellation releases its temporary allocation. These checks do not establish a
total heap or frame-time budget.

## Combined local gate

After combining preview, model lifecycle/input, dependent wet-segment and brush-export changes,
the complete local gate passed in **3 min 27 sec** with the build cache disabled and both pinned
reference inputs required:

- **1,511 JVM tests / 251 suites**, zero failures, errors or skips; 26 identities added to run 311,
  with none removed.
- All Android test sources compiled, with **253 declared identities**: the two model cases and
  paired raster-transfer case are the only additions to the prior 250.
- Five empty ktlint check reports, zero detekt findings and an unchanged detekt baseline.
- **139 Python tests** (82 workflow/evidence and 57 reference/tool cases), all passing. The required
  pinned native archive inspection passed the new six-method spacing-version contract.

The first combined invocation passed its 1,511 JVM cases and Android compilation but failed two
`LoopWithTooManyJumpStatements` findings in the new repository code. The follow-up extracted one
stroke's preview operation and simplified the dependency scan, retaining order and ownership.
No suppression or baseline change was made. The device fixture also standardized pointer IDs
across historical samples and retained separate per-method render-timeout captures.

The [local source/evidence record](evidence/preview-model-local.json) contains every changed code
file's SHA-256, all 26 added JVM identities, unchanged coverage, both gate outcomes and source-log
hashes. Its 10,334 bytes have SHA-256
`468c0c2773122fb38e1ba4b4468623b841b7643df0c7a5d4cf1e87362b939572`.
The combined repository SHA-256 is
`148ca22b85896084fb20d7a31a06f65c84d993704f8958f81e36d12d243695e9`.
Local checks do not execute an emulator; the exact published source's complete Actions matrix
and raw artifacts govern device acceptance.

## Device measurement and acceptance contract

The new device identity is
`CompositorRasterTransferDeviceTest#pooledRasterTransferKeepsEveryByteAndRecordsPairedElapsedCalls`.
It adds one case to the unchanged seven-lane matrix. Together with the two model-editor cases,
the combined candidate has 253 declared device identities; all original 250 remain present.

On each device, the case uses a 256 × 256 alpha/channel fixture and a capacity-one pool, deliberately
dirtied before every call. Six warmup pairs and 20 measured pairs alternate which path runs first.
The current call is the actual `Compositor.renderLayerContent`; the reference call reproduces the
previous equal-size transfer loop from the pinned base revision. Both include pool obtain/clear,
although the current call also includes its compositor method checks. It is not a whole-binary
or whole-preview benchmark.

Every returned pixel and the unchanged input are asserted after all 52 calls. Assertions, output
copying, buffer dirtying/release and file encoding sit outside the elapsed interval. The artifacts
retain:

- `compositor-raster-transfer.argb`: the actual final current output, 262,144 bytes; independently
  generated expected SHA-256
  `d0c182f79088483e024627e0df21c0f696823c416d33b208fa4be21531af409e`.
- `compositor-raster-transfer-timing.json`: all pair order and raw nanoseconds, measured medians,
  warmup separation, runtime context, and source hashes for the previous loop, blend and pool.

The raw output can be independently reconstructed. Intermediate exact checks require the passing
device test; the single retained raster does not reconstruct all intermediate calls. No speed
threshold has been introduced. Compare paired isolated calls on their own device, and keep raw
samples; cross-run host differences cannot establish a whole-preview improvement.

The published revision's completed [Android CI run](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/workflows/android-ci.yml)
is required for emulator acceptance. Local compilation is not a substitute. Physical pen delivery,
GL upload/draw, presentation, large-canvas memory and thermal behavior, and artist-tested brush
feel remain separate acceptance work.
