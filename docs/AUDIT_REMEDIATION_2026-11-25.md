# Production-audit remediation, 25 November 2026

This records what was changed in response to the 60-agent production audit of revision
`0b2192e2e6887e27f732e5b566534b0a91f24ad4`, what each change was checked with, and, just as
important, what the audit asked for that is **not** done. Nothing here is a release approval: the
physical-device, publisher-artifact, upgrade and distribution gates in
[RELEASE_READINESS.md](RELEASE_READINESS.md) are untouched and still open.

Verification here means the JVM unit-test suite, static analysis and a Kotlin compile on Linux. No
emulator, physical stylus, Photoshop/Procreate round trip or calibrated display was available, so
anything that can only be judged on those is marked *unverified on device*.

## Fixed, with the evidence for each

| Audit item | Change | Checked with |
|---|---|---|
| A. Copies and packages omit physical paint | `ProjectStorage.referencedFiles()` now names every asset kind (raster, mask, physical plane) and is the single enumeration used by duplication, packaging, import validation, path checks, pinning and pruning. `ArtflowPackage` admits `.phys` entries. `ModelImports` clears the physical reference with the other raster paths. | `PhysicalAssetCompletenessTest` (copy, package round trip, missing-asset import rejection, pruning) |
| B. Physical paint mutates after a snapshot | `LayerData.snapshotCopy()` and `duplicate()` mark the **live** layer shared (`markShared()`), not only the copy; `pushUndo()` marks physical planes shared as well as masks. | `ArtworkIntegrityTest.diffusionAfterACaptureDoesNotRewriteTheCapturedRevision` |
| C. Geometry and erase ignore physical fields | New `PhysicalPaintPlane.toPackedPixels()/fromPackedPixels()` carry wetness, relief and pigment through the exact pixel mapping of `transformLayer` and every canvas crop/rotate/flip/resize/resample. Erase, cut, clear, eraser strokes and raster edits drop physical state beneath transparent colour (`reconcilePhysicalPlane`). Region previews use a matching `cropped()` plane instead of the whole plane. Decoding rejects tiles outside the plane. | `ArtworkIntegrityTest`, `PhysicalPlanePrimitivesTest` |
| D. Frame operations | Paste Frame uses `duplicateBlock` (fresh ids with group parents and link groups remapped). Copy/Paste Frame keep editable text (`ownPixelBuffers()`). Transparent trim takes the union of every frame, not the active one. | `ArtworkIntegrityTest.pastedFrameKeepsGroupStructureAndEditableText`, `trimKeepsContentFromEveryFrame` |
| E. Damaged primary hides healthy recovery | `hasUnsavedRecovery` also reports recovery when the saved document is unreadable; `loadOrCreate` falls back to the protected placeholder (save/export blocked, autosave preserved) instead of an error. Dismissing the prompt no longer strands the recovery: the Quick menu offers "Review unsaved autosave". | `ArtworkIntegrityTest.unreadablePrimaryDoesNotHideAHealthyAutosave`, existing `AuthoritativeRecoveryTest` |
| F. Operation identity | ColorDrop identifies its fill by `historyMark`, not undo depth. Liquify Reset uses a new trim-proof `historyPosition`. Adjustment Apply is an awaited job; each Apply closes only its own session and a new session waits for it. Gallery navigation waits for Apply to settle before reading the dirty state. | Compiles and existing suites; *behavioural tests for the UI-bound parts are not added* |
| G. Privacy | `pasteLayer` carries privacy provenance; the ordinary Copy/Cut/Copy-merged commands record whether the source was private. A delayed time-lapse capture is re-checked after its delay and inside the recorder's lock via `permitted`. | Compiles and existing suites |
| Wetness quiescence | The activity threshold and the processing floor are now one constant (`ACTIVE_WETNESS`); sub-floor residue settles to dry; edge cells evaporate; tiles with nothing wet are skipped. | `PhysicalPlanePrimitivesTest.wetPaintAlwaysReachesQuiescenceEvenAtTheCanvasEdge` |
| Physical memory in budgets | History accounting, retained-plane counting and load admission include physical planes. | Compiles and existing suites |
| Unchanged selection rebuilds overlay | `selectionRevision` gates copying, counting and overlay rebuilding; the overlay bitmap is built off the main thread. | Compiles and existing suites |
| Spline overshoot with uneven spacing | Tangents are scaled by neighbouring chord lengths and bounded relative to the span. | `StrokeSplineSpacingTest` |
| Smudge picker and keyboard | The top-bar picker edits Smudge's own brush; size/opacity keys step from the active brush. | Compiles |
| Add Text keeps the previous target | Add Text clears the Edit Text target and any pending position. | Compiles |
| Levels endpoints throw | `buildLevelsLut` no longer builds an empty range. | `PhysicalPlanePrimitivesTest.levelsAcceptEveryEndpointWithoutThrowing` |
| Lasso Fill colour conversion | Uses `documentColor` like the other fills. | Compiles |
| GIF disposal bits | Method 2 is now written into bits 2-4 (`0x08`), not `0x04` (method 1). | `PhysicalPlanePrimitivesTest.gifFramesDisposeToBackground...` |
| Brush share files | Each share writes an immutable file in its own directory (atomic move); stale shares are pruned after a day. | `BrushSharingTest` |
| GLB without geometry | The `.glb` export now includes positions, normals, flipped-V UVs, a mesh, node, scene and the material assignment. | `GlbGeometryTest` |
| PSD empty clipping bases | An empty-bounds layer that is, or anchors, a clipping chain is kept as a transparent layer. | Compiles; *no PSD fixture added* |
| Procreate malformed tiles | A present tile that cannot be decoded or placed is counted, and the importer refuses the file with a specific message instead of leaving a silent hole. | Compiles |
| `BrushStudioUiTest` phone failure | The test scrolls the category chip into view first (the six CI phone-lane failures). | *Needs the device matrix to confirm* |

## Not done

The audit lists 77 work packages. The remainder are real and still open; none of them was
quietly dropped.

* **Brush engine consistency**: grain pressure and random state across live/replay/erase/dual paths,
  scatter bounds clipping, subpixel flattened-tip disappearance, angular interpolation.
* **History gestures**: continuous adjustment and mask sliders still depend on time-based merging, not an
  explicit gesture transaction.
* **Selections and transforms**: interactive Move/Transform with an active selection, and the Reset path
  that can alter soft-selection alpha.
* **Clone, heal and Liquify**: source reselection, aligned state, stationary dabs, invalid-source handling,
  persistent Freeze/Unfreeze coverage.
* **Text**: ordinary transforms still rasterise editable text; wrapping and grapheme handling.
* **Guides and symmetry**: rotational/radial settings that the shipping stroke expansion does not consume.
* **Colour**: the P3 eyedropper still passes through 8-bit sRGB (it needs a wide-gamut brush colour, a
  model change, not a patch); tagged raster insertion conversion; ICC/CMYK diagnostics.
* **Interchange**: PSD folder clipping, mask geometry, density and feather; soft-selection layered
  export (coverage must be applied once to the stack, which needs a group-level mask in the target
  format); native Procreate thumbnail calibration and unsupported-metadata disclosure; brush export of
  built-in source images and edited aliases; cancellation-aware external copying.
* **Performance**: the diffusion step still runs on the state dispatcher and still reads cells through
  hashed lookups; bounded transform/warp previews, cooperative cancellation inside expensive kernels,
  export admission before snapshot allocation, streaming lower-resolution animation export.
* **UI completion**: palette management, export preview, compact overflow, semantic labels, keyboard
  alternatives, gallery error recovery.
* **Release qualification**: full green CI across the device matrix, repeated API 35/16 KB acceptance,
  physical stylus qualification, publisher artifact, real upgrade and distribution evidence.
