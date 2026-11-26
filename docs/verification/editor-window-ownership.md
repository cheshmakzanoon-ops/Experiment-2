# Editor window ownership

ArtFlow currently supports **one editor activity and one editable canvas repository per process**.
The window can resize and share the display with another app. Independent simultaneous artwork
windows are not implemented: `CanvasRepositoryImpl` and its Hilt binding are singletons.

## Launch and keyboard behavior

`MainActivity` uses `singleTask` and `documentLaunchMode="never"`. An external Open with or Share to
request returns to the existing editor through `onNewIntent`; its bounded incoming-file queue waits
for the artist to return to the gallery. It must not create a second gallery/editor over the same
canvas state. Launcher requests carry no file. Activity recreation restores navigation and skips
re-offering the already accepted launch intent.

This policy also refuses a separate document task requested with `FLAG_ACTIVITY_NEW_DOCUMENT` or
`FLAG_ACTIVITY_MULTIPLE_TASK`. Android documents `singleTask` task reuse and the `never` document
policy in its [activity manifest reference](https://developer.android.com/guide/topics/manifest/activity-element)
and [task behavior guide](https://developer.android.com/guide/components/activities/tasks-and-back-stack).
The Android 16 implementation also [normalizes document flags before selecting a reusable task](https://android.googlesource.com/platform/frameworks/base/+/android16-qpr2-release/services/core/java/com/android/server/wm/ActivityStarter.java).
This is a single-editor product policy, not a new multiwindow editing feature. Returning to the app
can foreground its existing task instead of inserting an editor into the sending app's task.

Keyboard registration and overrides belong to the receiving activity's `ShortcutHost`, supplied to
its composition through `LocalShortcutHost`. Another activity or replacement composition cannot
replace its callbacks or clear a newer registration. The Android keyboard helper reads the same
host's overrides. Text editors continue to receive typing and their own undo; unhandled events and
window callbacks continue to the original delegate. The fixed default binding table is unchanged.

## Regression evidence and limits

- `ShortcutHostTest` checks separate hosts and overrides, repeated-handler registration, stale
  disposal and idempotent cleanup. These three tests and all seven existing `KeyboardShortcutsTest`
  cases passed in the scoped pure-JVM run.
- `EditorKeyboardCallbackTest` adds actual callback routing with two hosts and independent remaps,
  including disposal of the other host. It requires the Android/Mockito unit build.
- `HardwareKeyboardTest` adds platform keyboard-helper isolation with real key codes and modifier
  fields. It requires instrumentation execution.
- `SingleEditorActivityDeviceTest` launches the real Hilt application, creates an unsaved artwork,
  and sends launcher, VIEW and SEND requests through Android task dispatch. The requests include
  multiple-task and new-document flags. It asserts activity identity, unchanged artwork identity,
  history and pixels, retained URI/ClipData/read flags and readable FileProvider content. It then
  recreates the activity, saves through the real leave dialog, imports the queued palettes through
  the gallery, checks for a duplicate queued intent and reopens the saved artwork.

The new platform/Android-dependent tests have not run locally; hosted exact-revision results are
required. The URI fixture is the app's own FileProvider and therefore does not establish grants
from a separate provider UID or cloud provider across process death. Physical keyboard layouts,
DeX/ChromeOS behavior, secondary-display vendor policies, and privileged launches targeting a
particular task remain separate qualification work. Do not infer independent concurrent artwork
safety from host-isolation tests.
