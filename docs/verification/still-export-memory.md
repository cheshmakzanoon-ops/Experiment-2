# Still-export memory admission

## Runtime contract

[`ArtworkExporter`](../../app/src/main/java/com/artflow/studio/data/export/ArtworkExporter.kt)
checks the requested output's allocation plan before converting profiles, scaling pixels,
encoding an image or writing an export. The dimension ceiling alone is insufficient: a valid
6,000 by 6,000 output needs a 144,000,000-byte ARGB plane before any encoder workspace.
Several planes and encoded copies can coexist.

[`StillExportMemory`](../../app/src/main/java/com/artflow/studio/data/export/StillExportMemory.kt)
uses the existing heap/headroom policy: the smaller of one third of the maximum heap and
half its currently available headroom. Existing editor and export snapshots already contribute
to observed heap usage. The planner charges additional retained composite/layer/mask storage
and the peak of sequential preparation and encoding work. An internal test allowance can
narrow this policy but cannot enlarge it.

These are conservative admission estimates, not measured peak process memory or a guarantee
against every concurrent allocation. They assume incompressible output where applicable.
An unusually compressible large image may be refused even if that particular encoding could
have completed. The app reports: **This export needs too much memory for this device. Reduce
the output scale or export fewer layers.**

## Allocation boundaries

| Output | Work included in admission |
| --- | --- |
| PNG | Prepared/flattened pixels, output growth and copies, row and compression workspace |
| TIFF | Prepared/flattened pixels and the direct encoded output array |
| JPEG / WebP | Bitmap and encoder working storage, encoded output growth, JPEG density/profile copies and lossless WebP workspace |
| PDF | Oversampled dimensions, raster/bitmap/native image storage and accumulated page images before creating the platform document |
| PSD | Prepared layers, independent masks, folder occurrences, rendered/background fallback, raw/RLE channels and channel/section/output copies |
| Procreate | Prepared layers/masks, full 256-square edge tiles, archive/index storage, ZIP growth and compression workspace |
| Layer PNG ZIP | The retained ZIP plus one current layer's preparation and PNG work |
| Model OBJ | Pinned model input and rewriting, material-map resolution/scaling, PNG output and ZIP storage |

Fit/fill intermediate dimensions are checked independently of final output dimensions. A small
target can still require a large intermediate when filling it from an extreme-aspect layer or
mask. PDF oversampling is likewise admitted before preparing the base image.

PSD folder masks are resized when their folder closes, after its child layers have been prepared.
The planner therefore checks the largest deferred group-mask resize workspace against the final
retained layer/mask total. Coverage is already included in that retained total; the later check
adds resize workspace. PSD layer-record planning and generation skip the layer and mask payloads
of excluded hidden groups.

[`RenderedExport`](../../app/src/main/java/com/artflow/studio/presentation/ui/viewmodel/RenderedExport.kt)
applies admission before allocating selection/content crops of the composite, layers and masks.
The exporter checks again at its own public entry point. These checks leave source pixels and
existing export files intact when admission fails.

For model output, the exporter opens the validated model path and checks the size of that open
descriptor before buffering its bytes. The read is bounded and cancellation-aware; texture
preparation follows it. No out-of-memory catch is used as the admission mechanism.

## Evidence and limits

[`StillExportMemoryTest`](../../app/src/test/java/com/artflow/studio/data/export/StillExportMemoryTest.kt)
exercises refusals through actual exporter/crop callers before scaler, pixel or storage access.
It also compares supported PNG, TIFF, layer ZIP, PSD raw/RLE, Procreate, model/material and
profile-converted output while retaining source ownership.
[`StillExportWorkspaceTest`](../../app/src/test/java/com/artflow/studio/data/export/StillExportWorkspaceTest.kt)
retains independent checks for deferred group-mask workspace, FIT/FILL pixels and excluded hidden
payloads. The declared Android cases in
[`ExportDeviceTest`](../../app/src/androidTest/java/com/artflow/studio/data/ExportDeviceTest.kt)
exercise oversized refusal for all nine still formats and group-mask refusal followed by a smaller
PSD export with decoded pixels and coverage. Host sanity execution or Android compilation of these
fixtures does not establish device execution. Independent review and combined results are recorded
in the [follow-through log](../release/PRODUCTION_FOLLOWTHROUGH_2026-11-19.md).

These checks supplement the real Android export cases and final-revision workflows. Physical
device qualification must still measure long-session memory, representative layer counts,
export latency and cancellation on the actual publisher artifact, as required by the
[release sign-off](../release/RELEASE_SIGNOFF.md).
