# Production hardening — November 21, 2026

## Scope and release decision

This maintenance change follows an audit of main
`7477eaa8fbc26b03e70618409505d9780fd4acfc`. It addresses ownership and resource-lifetime gaps
in four supported offline workflow areas. It does not expand the advertised
feature set, change the document format, or declare the application approved for public rollout.

The existing [release sign-off](RELEASE_SIGNOFF.md) still requires final-revision engineering
qualification, publisher signing, physical-device/stylus measurements, and store acceptance.
The passing workflows for the audit baseline do not qualify this later change.

## Changes and regression coverage

| Problem | Resulting behavior | Regression coverage |
| --- | --- | --- |
| Gallery duplication read manifests and rasters at different times; the prior editor's admitted background autosave could replace and prune those generations during copying. | Duplication captures saved and recovery metadata together, retains their raster generations through the copy, and captures preview/model file descriptors. Exact compatible manifest bytes and recovery ordering survive; failed or cancelled copies release their own resources and destination. | [ProjectCopySnapshotTest](../../app/src/test/java/com/artflow/studio/data/local/ProjectCopySnapshotTest.kt), existing [ProjectRecoveryOrderingTest](../../app/src/test/java/com/artflow/studio/data/local/ProjectRecoveryOrderingTest.kt), [ProjectPackageSnapshotTest](../../app/src/test/java/com/artflow/studio/data/local/ProjectPackageSnapshotTest.kt). |
| Animation and timelapse MP4 export read the complete encoded video into memory before saving it. | Finished MP4 files publish through an 8 KiB copy buffer and the existing atomic writer. Empty, truncated or length-changing source copies, and cancellation detected before publication, preserve the previous export. The result reports the committed file size. | [EncodedExportPublicationTest](../../app/src/test/java/com/artflow/studio/data/local/EncodedExportPublicationTest.kt), real MediaCodec/muxer cases in [ExportDeviceTest](../../app/src/androidTest/java/com/artflow/studio/data/ExportDeviceTest.kt). |
| Timelapse exports could share a timestamp-named snapshot directory, and cancelled dispatcher handoffs could skip cleanup. | Every export owns a unique snapshot directory; temporary-file cleanup remains in its creating I/O scope. Cancellation propagates instead of becoming an ordinary export failure. | [TimelapseExportLifecycleTest](../../app/src/test/java/com/artflow/studio/data/export/TimelapseExportLifecycleTest.kt), actual three-frame timelapse encoding with timestamp/final-duration and recording-preservation assertions in [ExportDeviceTest](../../app/src/androidTest/java/com/artflow/studio/data/ExportDeviceTest.kt). |
| A delayed or suspended recording collector could continue after the editor changed project identity. | Opening another artwork cancels the old collector before the identity changes. Each collector captures its own project ID and checks cancellation after composition. Failed opens and retries cannot transfer another artwork's pixels into its recording. | [CanvasTimelapseLifecycleTest](../../app/src/test/java/com/artflow/studio/presentation/CanvasTimelapseLifecycleTest.kt), covering a suspended lookup, a late-returning compositor, and failed-open/retry behavior. |
| Incoming shares accumulated without limit while the gallery was not receiving. | The activity accepts at most 32 pending file URIs in FIFO order, preserves previously accepted work, and reports accepted/rejected counts with retry guidance. Processing or revisiting Gallery frees capacity. | [IncomingFileQueueTest](../../app/src/test/java/com/artflow/studio/presentation/ui/IncomingFileQueueTest.kt), including 1,000 refused shares with preserved FIFO contents, plus real Android intent cases in [IncomingFilesTest](../../app/src/androidTest/java/com/artflow/studio/IncomingFilesTest.kt). |
| Export submission left the filename field focused while the keyboard and result layout could change. | Artwork and timelapse export submission clear text focus and dismiss the keyboard before dispatching the export. | The existing [OptimizedArtworkWorkflowTest](../../app/src/androidTest/java/com/artflow/studio/OptimizedArtworkWorkflowTest.kt) explicitly exercises focused filename entry and verifies released focus, retaining visible-result and every-pixel assertions. |

## Verification requirements

The implementation and regression presence are separate from execution evidence. The pull
request's checks and retained Actions artifacts identify the tested revision and original
results. Local environment/setup failures must not be presented as application passes or
hidden by weakening tests, inventory rules or static-analysis baselines.

The first published checkpoint, `c625381ee2c67947dc6d30dc7db201f2191787ae`, completed
2,331 JVM cases in [Android CI](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/runs/44916676253):
2,328 passed and three new editor-lifecycle cases failed at their initial ready-state assertion.
Their storage mock did not supply a Boolean for the suspending recovery query. The fixture now
models an artwork with no recovery explicitly; all recording and ownership assertions remain.
The original failed run is retained. That checkpoint separately passed
[optimized qualification](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/runs/44916676209),
[normal release payload UI](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/runs/44916676151),
and [installed application upgrade](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/runs/44916676174).
The corrected-fixture checkpoint, `1b320676d48c4f3c6c8880e41d726e741c8e4b5e`, passed the
complete build job in [Android CI](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/runs/44918014214):
2,331 JVM cases in 368 suites with zero failures, errors or skips, Android test compilation,
static analysis, packaged builds and all required artifact/provenance gates. The retained
checkout was PR merge `db1490160b77d2bf9cbc148f16b7a413ef992dc6`, with the same source tree as
the branch head. This is PR candidate evidence, not final-main qualification. The checkpoint
also passed [normal release payload UI](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/runs/44918014163)
and [installed application upgrade](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/runs/44918014293).
Its [optimized run](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/runs/44918014142)
executed 69 cases: 68 passed and one failed at the exported filename's visibility assertion.
The filename node existed, but Android logged the keyboard opening immediately before the
failure. No screenshot or semantic bounds were retained for that failed assertion, so the
record does not claim measured clipping or a definitive layout diagnosis. The export form's
focus handoff is now explicit. The UI case checks released focus and settled keyboard insets
before checking the result; the original visibility/pixel gates remain required. Any new
failure also retains a screenshot and semantic tree before the activity closes.
The failed attempt is retained; its later missing workflow receipt is not a successful export
qualification. See [PR #42](https://github.com/cheshmakzanoon-ops/Experiment-2/pull/42) for the
subsequent candidate's exact checkout, complete workflow results, and any remaining gates.

Focused JVM verification:

```bash
./gradlew testDebugUnitTest \
  --tests '*ProjectCopySnapshotTest' \
  --tests '*ProjectRecoveryOrderingTest' \
  --tests '*ProjectPackageSnapshotTest' \
  --tests '*ProjectDestinationOwnershipTest' \
  --tests '*EncodedExportPublicationTest' \
  --tests '*TimelapseExportLifecycleTest' \
  --tests '*CanvasTimelapseLifecycleTest' \
  --tests '*IncomingFileQueueTest'
```

The complete JVM suite, formatting, detekt, Android lint, Android test compilation and
packaged debug/release builds remain required. Source-derived device inventories must include
the added test identities. Full Android CI, optimized qualification, ordinary release-payload
UI qualification and the installed-application upgrade workflow remain distinct gates.

The new MP4 publication path does not claim bounded memory for every video operation or
every export format: decoded-frame preparation, platform codecs, GIF/APNG, frame-sequence ZIP,
and PDF retain their own budgets and implementation boundaries. The incoming-file queue is
process-local pending work, not durable import storage. Physical long-recording, low-storage,
process-death, stylus and ARM64 acceptance remains part of the release campaign.
Cancellation after the atomic export commit may still reach the caller even though the new
export is already durable; cancellation does not roll back a completed publication.
