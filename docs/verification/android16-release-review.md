# Android 16 and normal release review — wave 18

## Retained runtime failures

Android CI run `44555384021`, at pull-request checkout
`1ebd1f69813e3aa193ae094864ac70adae2a24a2`, failed its Android 16 / 16 KB lane
with **314 passed, two failed and zero skipped** out of 316 declared identities.
The standard Android 16 lane reported the same two failures. These original
results remain failures; later repairs require new exact-revision execution.

The artwork workflow timed out on its first stroke at `ArtworkWorkflowTest.kt:80`.
Wave 16 already corrected its readiness precondition to require an actually
available renderer preview before sending pointer input.

The other failure was the first assertion in
`CanvasPreviewFailureDeviceTest.aLostPreviewCancelsPaintingBlocksNewInputAndAllowsSaveAndRecovery`.
It sent DOWN/MOVE without UP, then expected `compositeBuffer()` to include the
in-progress stroke. That API deliberately renders committed artwork. The renderer
uses `compositePreview()`, which includes active strokes and provisional raster edits.

## Corrected observation and preserved assertions

The device test now requires the provisional preview to differ after the active
pointer stroke, while separately requiring committed pixels to remain unchanged.
After the real injected GL failure, it waits for provisional preview pixels to
return exactly to the original and also checks the committed image. It retains
the original pointer events, failure injection, history checks, blocked-input
checks, save/recovery flow and timeouts. Production behavior is unchanged.

A standalone probe with the real repository reproduced the observation mismatch:
the active stroke changed 260 preview pixels while every committed pixel remained
identical. The old assertion therefore failed as expected. Cancelling the stroke
restored every preview pixel and left undo depth unchanged. That probe and three
existing provisional-stroke tests passed. The changed Android test compiled
against API 36 and passed ktlint and `git diff --check`.

Two initial standalone compilation attempts stopped at local classpath setup
errors before tests executed; their logs are preserved separately. They are not
counted as product results. The corrected compilation and four-case JVM run are
retained under `tooling/w18-api36` in the shared review workspace.

## Ordinary release boundary

The earlier normal release campaign `44555384282` stopped at the unlabeled New
artwork button before painting. Wave 16 added its localized accessibility label.
Review of the later driver steps confirmed that the workflow still uses actual
visible controls, the real chooser read grant, separate application/driver
processes, a fresh application process and complete comparison of all 4,096
exported pixels. The verifier independently decodes the bounded PNG and requires
exactly one successful instrumentation identity.

Static inspection did not establish the remaining settings, text-field, export
or process-death behavior on the ordinary release binary. A new original runtime
attempt is required; this review does not claim normal-release acceptance,
publisher signing, physical-device performance or store qualification.

## Artifact identity

The Android 16 / 16 KB original artifact is `14549840960`, downloaded from the
above run and verified against the provider digest:

`a1843a73bc21f9038eae49c849e8036a792da5b9ffb0bdaebded43883971adaf`

The archive, original XML/log evidence, observation probe and validation metadata
are retained under `tooling/w18-api36`. APK binaries were not needed for this
assertion diagnosis; the original artifact ZIP remains intact.
