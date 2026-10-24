# Model editor input and modal corrections — 24 October 2026

This change addresses observed source defects in ArtFlow's existing model editor. It does not establish Procreate 3D rendering, material, gesture, or performance parity. Device acceptance belongs to the combined source's completed exact-revision Actions run; earlier run 311 contains no model-editor acceptance case.

## Modal precedence

The floating model previously had unconditional z-order 4, while the editor's Layers popover, phone bottom panel, and their scrims were later ordinary z-order 0 siblings. A model could therefore cover both a modal control and its pointer target. `CanvasScreen` now lowers the model behind those later modal hosts while a panel or Copy & Paste menu is open. The model view remains composed, retaining its camera and local controls. Its painting/navigation input is disabled during that modal interval, which also cancels any provisional gesture. Normal sidebar precedence is unchanged. Focus mode continues to hide the model; its exit control remains available and leaving focus recreates the model from the current artwork.

## Provisional gesture lifecycle

`SurfaceSink.end` separates a UV-island or off-mesh segment; `finish` completes the pointer gesture and `cancel` discards it. `ModelController` retains the segment IDs until completion. A segment is sealed through `CanvasRepository.sealStrokeSegment`: its points remain available to preview, while its prepared raster copy and live raster cache are released and cannot be rebuilt as a live cache. The final successful gesture ends segments in their original order. Successful seam segments retain their existing individual undo entries; this is not an atomic one-entry undo change.

Cancellation discards every still-owned segment, including segments already separated at seams, without calling undo. Captured project/history/layer state rejects a stale gesture. Segment ownership also checks the first immutable point's identity, because stroke IDs can be reused after document replacement. Thus a stale model callback cannot move, seal, commit, or cancel a newly created stroke with the same numeric ID.

`ModelView` cancels on `ACTION_CANCEL`, a cancelled-pointer flag where supported, transition to two fingers, replacement of its mesh/painter, disabled input, a mode change, or detachment. Trailing moves cannot restart the cancelled stroke. Historical MOVE/UP samples are replayed, and the final UP coordinate is consumed before completion.

## Preview scheduling and scope

The shared Reference/model preview controller has one serial renderer and conflates growing model requests. Ongoing input does not continually cancel rendering or reset the ordinary 250 ms settle timer. Completed/cancelled model gestures invalidate outstanding provisional output and request committed pixels. Normal Reference refreshes retain their settle behavior. A 32 ms interval bounds repeated provisional updates; this is scheduling, not a physical latency or frame-rate claim.

Sealing bounds retained full-canvas segment caches. The points still occupy memory, and previewing many sealed segments can require repeated full raster work. This change does not establish a bound on total gesture length, total point memory, or large-model rendering cost. Later canvas-sampling segments must read preceding provisional paint; the repository regressions separately cover that ordering.

## Regression evidence

The [combined local gate](evidence/preview-model-local.json) passed 1,511 JVM tests and Android test compilation, including this work's nine new JVM cases. Formatting and static analysis passed with the existing baseline unchanged. The two Android identities are compiled declarations until their exact published source is executed and its artifacts inspected.

JVM coverage uses the real repository/compositor for cancellation, exact sequential-commit pixels and per-segment undo/redo, unrelated-history preservation, and reused-ID ownership. Controlled coroutine tests verify serial/coalesced growing previews, ordinary debounce, and rejection of a late cancelled render. Surface-stroker tests distinguish segment end, gesture finish, replacement down, and cancel.

The two new `ModelEditorUiTest` device identities use the real Hilt `CanvasScreen` with an attached authored two-island OBJ. They require an actual model/modal intersection before coordinate-tapping Add layer, test close/reopen and focus, and inject real MotionEvents into the attached `ModelView`. One cancelled path reaches the second island only in a batched historical sample; a successful path reaches it only in ACTION_UP. Exact stored pixels, provisional rollback, the untouched UV gap, and undo/redo are asserted. A two-finger pinch must reveal artwork at a previously empty GL point without changing stored pixels or history.

Retained UI captures are `model-editor-layers.png`, `model-editor-focus.png`, `model-editor-restored.png`, `model-editor-provisional.png`, `model-editor-cancelled.png`, `model-editor-committed.png`, and `model-editor-navigation.png`. The 96 × 64 committed layer is retained separately as `model-editor-layer.png`. Model captures wait for both colored artwork and the distinct gray GL background; the focus capture waits for the actual flat-canvas artwork. Timeout captures preserve failed readiness evidence. Screenshot readiness demonstrates the tested app frame, not a comparison with native Procreate output.
