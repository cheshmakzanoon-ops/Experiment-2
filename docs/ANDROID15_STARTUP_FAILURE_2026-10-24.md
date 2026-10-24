# Android 15 startup failure — 2026-10-24

**Status: unresolved; the device lane failed and is incomplete.** The process
crashed during activity launch, before the reported test created its canvas or
ran its saved-document assertions. Focused log and source review did not
demonstrate a specific application, dependency, or platform cause. No retry,
skip, classifier change, or application fix was introduced for this diagnosis.

## Exact run and evidence

| Item | Identity |
| --- | --- |
| Candidate | [`36c84c223fa8d992e4ddf522574ee694250c30b9`](https://github.com/cheshmakzanoon-ops/Experiment-2/commit/36c84c223fa8d992e4ddf522574ee694250c30b9) |
| Workflow | [Android CI run 305 / `40067128914`](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/runs/40067128914) |
| Lane | [Independent API 35 / 16 KB repeat, job `120796302874`](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/runs/40067128914/job/120796302874) |
| Artifact | [`device-test-results-api-35-16k-repeat`, ID `12550208400`](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/runs/40067128914/artifacts/12550208400) |
| Verified ZIP | 3,629,639 bytes; SHA-256 `53ad557de29439f257f499c4ce606892fff81f6495db6e9ca4cc0577da3bba8b` |
| Reported cases | **53 of 236: 52 passed, 1 failed, 183 unreported** |

The artifact's recorded retention expires on 2026-11-07. Its full device log is
`_temp/artflow-device/device-logcat.txt`; its per-case log ends in
`app/build/outputs/androidTest-results/connected/debug/emulator-5554 - 15/logcat-com.artflow.studio.CanvasInputDeviceTest-savedReloadChangesRevisionEvenWhenProjectAndLayerIdsAreReused.txt`.
The measured device fingerprint was
`google/sdk_gphone16k_x86_64/emu64xa16k:15/AE3A.240806.043/12960925:userdebug/dev-keys`.

## Observed phase

All timestamps below are from the original 2026-10-24 logcat, process 5253.

| Time | Observation |
| --- | --- |
| 06:21:17.964 | The preceding `outlinedShapesHaveVisibleBordersAndPolygonStaysWithinDragBounds` activity reached `DESTROYED`. |
| 06:21:17.979 | That test reported finished, after its fixture teardown. |
| 06:21:17.981 | `savedReloadChangesRevisionEvenWhenProjectAndLayerIdsAreReused` started. |
| 06:21:18.063 | The next `SelectionCanvasTestActivity` reached `RESUMED`. |
| 06:21:18.091 | Main threw the synchronization-barrier exception. |
| 06:21:18.102 | The thread dump showed instrumentation still waiting in `Instrumentation.startActivitySync` → `ActivityScenario.launch` → `CanvasInputDeviceTest.withCanvas:899`. |

Necessary main-thread stack excerpt:

```text
java.lang.IllegalStateException: The specified message queue synchronization  barrier token has not been posted or has already been removed.
  at android.os.MessageQueue.removeSyncBarrier(MessageQueue.java:562)
  at android.view.ViewRootImpl.doTraversal(ViewRootImpl.java:2911)
  at android.view.ViewRootImpl$TraversalRunnable.run(ViewRootImpl.java:10324)
  at android.view.Choreographer$CallbackRecord.run(Choreographer.java:1406)
  at android.view.Choreographer$CallbackRecord.run(Choreographer.java:1415)
```

The launch call precedes repository fixture initialization, canvas construction
and attachment, and the test body. The host's `onCreate` only installs an empty
`FrameLayout`. Therefore this report does not identify a saved-reload assertion
failure. The stack does not identify the failing `ViewRootImpl`; it cannot
establish whether that traversal belonged to the newly launched window or an
earlier window.

## Bounded lifecycle review

- [`app/src/androidTest/java/com/artflow/studio/CanvasInputDeviceTest.kt`](https://github.com/cheshmakzanoon-ops/Experiment-2/blob/36c84c223fa8d992e4ddf522574ee694250c30b9/app/src/androidTest/java/com/artflow/studio/CanvasInputDeviceTest.kt#L893):
  `withCanvas` constructs/attaches the view through `scenario.onActivity`, runs
  repository setup and the test body on `Dispatchers.Main`, then closes the
  scenario and disposes the repository on Main.
- [`app/src/debug/java/com/artflow/studio/SelectionCanvasTestActivity.kt`](https://github.com/cheshmakzanoon-ops/Experiment-2/blob/36c84c223fa8d992e4ddf522574ee694250c30b9/app/src/debug/java/com/artflow/studio/SelectionCanvasTestActivity.kt#L16):
  the empty host adds no canvas during its own lifecycle callbacks.
- [`app/src/main/java/com/artflow/studio/presentation/ui/components/canvas/ArtFlowCanvasView.kt`](https://github.com/cheshmakzanoon-ops/Experiment-2/blob/36c84c223fa8d992e4ddf522574ee694250c30b9/app/src/main/java/com/artflow/studio/presentation/ui/components/canvas/ArtFlowCanvasView.kt#L674):
  detach cancels delayed gesture/history work, the animator, and the Main scope,
  then calls superclass detach before renderer image disposal. The preceding
  test waits for each shape commit and undoes it before teardown.
- The crash dump contains no canvas `GLThread`; all four recorded
  `DefaultDispatcher` workers are parked. Platform `RenderThread` and HWUI
  threads remain. `ReferenceQueueDaemon` was in
  `HardwareRenderer.nDeleteProxy`, but the log does not connect that cleanup to
  the missing token.
- Repository application/test code has no direct synchronization-barrier calls
  or `MessageQueue`/`ViewRootImpl` manipulation. This does not rule out indirect
  effects or earlier work.

## Framework interpretation and limits

Official AOSP `android-15.0.0_r1` source matches both reported failure locations:

- [`core/java/android/view/ViewRootImpl.java`](https://android.googlesource.com/platform/frameworks/base/+/android-15.0.0_r1/core/java/android/view/ViewRootImpl.java#2911)
  removes the recorded traversal barrier before `performTraversals`.
- [`core/java/android/os/MessageQueue.java`](https://android.googlesource.com/platform/frameworks/base/+/android-15.0.0_r1/core/java/android/os/MessageQueue.java#562)
  throws when a synchronized queue search finds no targetless message bearing
  that token.
- [`opengl/java/android/opengl/GLSurfaceView.java`](https://android.googlesource.com/platform/frameworks/base/+/android-15.0.0_r1/opengl/java/android/opengl/GLSurfaceView.java#630)
  waits for its GL thread to exit during detach. Its `requestRender` sets a
  render-request flag and signals the GL-thread manager.

This comparison does not establish that the entire emulator image is identical
to that AOSP tag. The exception proves inconsistent barrier state at traversal;
it does not identify why the token was absent. The logs lack the token value,
queue contents before failure, ViewRoot identity, and barrier-mutation history.
Neither an application lifecycle defect nor a platform/runtime fault is proven.
A later successful full run would provide new evidence without erasing this
failure or turning its 183 unreported cases into passes.
