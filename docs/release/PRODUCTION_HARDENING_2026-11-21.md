# Production hardening — November 21, 2026

## Scope and release decision

This maintenance change follows an audit of main
`7477eaa8fbc26b03e70618409505d9780fd4acfc`. It addresses four reproducible ownership and
resource-lifetime gaps in supported offline workflows. It does not expand the advertised
feature set, change the document format, or declare the application approved for public rollout.

The existing [release sign-off](RELEASE_SIGNOFF.md) still requires final-revision engineering
qualification, publisher signing, physical-device/stylus measurements, and store acceptance.
The passing workflows for the audit baseline do not qualify this later change.

## Changes and regression coverage

| Problem | Resulting behavior | Regression coverage |
| --- | --- | --- |
| Gallery duplication read manifests and rasters at different times; the prior editor's admitted background autosave could replace and prune those generations during copying. | Duplication captures saved and recovery metadata together, retains their raster generations through the copy, and captures preview/model file descriptors. Exact compatible manifest bytes and recovery ordering survive; failed or cancelled copies release their own resources and destination. | [ProjectCopySnapshotTest](../../app/src/test/java/com/artflow/studio/data/local/ProjectCopySnapshotTest.kt), existing [ProjectRecoveryOrderingTest](../../app/src/test/java/com/artflow/studio/data/local/ProjectRecoveryOrderingTest.kt), [ProjectPackageSnapshotTest](../../app/src/test/java/com/artflow/studio/data/local/ProjectPackageSnapshotTest.kt). |
| Animation and timelapse MP4 export read the complete encoded video into memory before saving it. | Finished MP4 files publish through an 8 KiB copy buffer and the existing atomic writer. Empty, truncated, length-changing or cancelled source copies preserve the previous export. The result reports the committed file size. | [EncodedExportPublicationTest](../../app/src/test/java/com/artflow/studio/data/local/EncodedExportPublicationTest.kt), real MediaCodec/muxer cases in [ExportDeviceTest](../../app/src/androidTest/java/com/artflow/studio/data/ExportDeviceTest.kt). |
| Timelapse exports could share a timestamp-named snapshot directory, and cancelled dispatcher handoffs could skip cleanup. | Every export owns a unique snapshot directory; temporary-file cleanup remains in its creating I/O scope. Cancellation propagates instead of becoming an ordinary export failure. | [TimelapseExportLifecycleTest](../../app/src/test/java/com/artflow/studio/data/export/TimelapseExportLifecycleTest.kt), actual three-frame timelapse encoding with timestamp/final-duration and recording-preservation assertions in [ExportDeviceTest](../../app/src/androidTest/java/com/artflow/studio/data/ExportDeviceTest.kt). |
| A delayed or suspended recording collector could continue after the editor changed project identity. | Opening another artwork cancels the old collector before the identity changes. Each collector captures its own project ID and checks cancellation after composition. Failed opens and retries cannot transfer another artwork's pixels into its recording. | [CanvasTimelapseLifecycleTest](../../app/src/test/java/com/artflow/studio/presentation/CanvasTimelapseLifecycleTest.kt), covering a suspended lookup, a late-returning compositor, and failed-open/retry behavior. |
| Incoming shares accumulated without limit while the gallery was not receiving. | The activity accepts at most 32 pending file URIs in FIFO order, preserves previously accepted work, and reports accepted/rejected counts with retry guidance. Processing or revisiting Gallery frees capacity. | [IncomingFileQueueTest](../../app/src/test/java/com/artflow/studio/presentation/ui/IncomingFileQueueTest.kt), including 1,000 refused shares with preserved FIFO contents, plus real Android intent cases in [IncomingFilesTest](../../app/src/androidTest/java/com/artflow/studio/IncomingFilesTest.kt). |

## Verification requirements

The implementation and regression presence are separate from execution evidence. The pull
request's checks and retained Actions artifacts identify the tested revision and original
results. Local environment/setup failures must not be presented as application passes or
hidden by weakening tests, inventory rules or static-analysis baselines.

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
