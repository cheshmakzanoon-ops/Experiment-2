# Photo and model import admission and ownership

## Preparation boundary

`ImportPreparation.withAdmission` permits one synchronous photo or model preparation at a time. It uses a nonblocking, reentrant admission: another worker receives an actionable retry error immediately, while nested texture decoders on the admitted worker can proceed. Its callback is non-inline and non-suspending, and admission is released in `finally` on success, ordinary errors, cancellation exceptions, or VM errors.

The admission covers:

- Photo file and URI preparation in `BitmapPixelBridge`, beginning before opening the source and growing its encoded snapshot, through metadata inspection, bitmap decoding, pixel copying, and orientation correction.
- Known model imports in `GalleryImports.readModel`, beginning before opening the source, through reading, package parsing, texture preparation, atlas construction, and size validation.
- Direct `PreparedModelImport.prepare` callers and nested `BitmapPixelBridge.decodeModelTexture` calls. Direct callers remain responsible for bytes they allocated before calling these functions.

Photo and model preparation refresh the current memory budget after admission. A model's initial encoded read is limited to the smaller of 64 MiB and one quarter of the current import budget, allowing space for stream-buffer growth and its returned copy before metadata exists. The model's existing texture estimates still check all retained sources, decoder copies, atlas/resampling work, and installation copies.

A provider that never returns retains the single preparation admission. New photo/model attempts fail promptly instead of accumulating blocked jobs or additional snapshots. Document loading, saved-raster decoding, painting, and saving do not use this admission. Existing `fromEncodedBytes` artwork decoding keeps its exact dimensions.

This boundary does not guarantee that the entire process cannot run out of memory. Native decoder allocations and unrelated application work can exceed Java-heap estimates. PSD/Procreate preparation and unclassified archive inspection have their own boundaries. Model archive expansion and geometry/parser allocations need their separate limits; the preparation admission serializes that work for known model imports but does not itself estimate its peak.

## Model handoff lifetime

An accepted model open now claims its pending bundle before project lookup or document loading can suspend. Gallery cancellation or the unclaimed-handoff deadline therefore cannot remove the bundle while the editor opens it. A failed or cancelled open keeps the claimed bundle for retry; a project lookup that definitively reports deletion releases the matching bundle.

`ModelImports.install` also claims its matching handoff before preparation begins. The claim checks object identity so an old installer cannot claim a newer handoff for the same project. The receipt remains available until the initial save completes, preserving the checks that prevent retries from duplicating planes or overwriting later edits. Successful deletion remains the explicit cleanup path for an abandoned claimed retry.

## Focused verification

The pre-fix frozen revision `af7ff79bad6b1899b375a6b4017a5d30c2a66a05` reproduced six failures across the 17 original model initialization/repository tests: four initialization timeouts and two retry-integrity failures. Applying only the ownership production changes made all 17 original tests pass without changing their assertions, owners, clocks, or timeouts.

A controlled allocation-boundary probe reproduced the preparation race before the admission fix. Three concurrent model preparations each passed a 16,777,216-byte budget and entered their decoder callback. Each had an estimated peak of 15,809,657 bytes; their combined 47,428,971 bytes exceeded the controlled 33,554,432-byte heap headroom. The probe stopped at the decoder callback and allocated no texture pixel arrays.

The final standalone Kotlin/JUnit run passed **104 tests** across:

- `ImportPreparationAdmissionTest`
- `PreparedModelImportTest`
- `ModelTextureBudgetTest`
- `ImageDecodeBudgetTest`
- `ModelMaterialImportRepositoryTest`
- `CanvasModelInitializationTest`
- `PendingRasterImportOwnershipTest`
- `AtomicLayerImportTest`
- `CanvasImportControllerTest`

The new cases prove immediate rejection before provider opening or model metadata inspection, exclusion of overlapping individually affordable model preparations, reentrant nested decoding, cleanup on provider/model failures and cancellation, exact saved-raster loading while a provider is stalled, early editor ownership while lookup is suspended, deletion cleanup, and protection against stale claims. Existing atomic-import cases retain their original coverage for project/frame/selection/revision changes, late mask failures, cancellation, and undo/redo preservation.

The focused run used the repository's Kotlin 2.0.21 compiler, Gradle's mockable Android runtime, and the checked-in test fixtures. Ktlint, changed-file detekt with the unchanged baseline, and `git diff --check` passed. This is targeted JVM evidence; integrated Gradle and real Android decoder execution remain separate verification gates.
