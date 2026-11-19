# Incoming gallery import lifecycle

## Confirmed defects

The gallery's incoming-file collector called `GalleryImports.open`, which launched a child and
returned immediately. Most recognized artwork types then launched another child. A multi-file
share could therefore drain the queue and start several provider reads/imports/navigation calls
before the first import finished. The photo/model decoder admission boundary did not make this
collector sequential, and the PSD/Procreate paths had their own preparation lifetimes.

A controlled provider regression reproduced the detached completion without allocating large
images: while its first stream remained blocked, the old collector had already completed both
incoming items. The test failed its awaited-import assertion after a successful compilation.
This demonstrates the lifecycle error; it is not a claim that a particular device ran out of
memory or lost an artwork during that probe.

The gallery's palette and brush adapters also returned before persistence. Both reported normal
completion while a controlled DAO write remained paused, and both hid an injected write failure
from their gallery caller. Four regressions reproduced these failures with the real settings and
brush repositories, preserving the original rows for comparison.

## Repairs

`GalleryImports.open` now suspends through classification and the actual import/handoff. It returns
whether an artwork navigation occurred. The gallery collector awaits each item and ends its
current visit immediately after navigation, leaving later incoming URIs queued until the artist
returns. It continues after a rejected file or a completed library import. The ordinary picker
entry points still launch their existing work in the gallery scope; unrelated editor/UI work is
not serialized by a global lock.

The gallery-facing palette write and brush `importWhenReady` now await their repository operation.
Success messages follow persistence, and failures reach the existing per-file reporting boundary.
Ordinary brush edit actions keep their launch-based UI wrappers and share the same completion
helper. Existing store transactions still preserve their cancellation/atomic-publication behavior.

Cancellation propagates instead of advancing to later files. An active provider stream is closed
when its read returns; this does not promise interruption of a provider that never returns. Owned
staging and pending-artwork cleanup remain in their existing `finally`/handoff paths.

## Verification

The final standalone Kotlin compilation includes the changed import and ViewModel sources, both
new JVM suites, and the existing `GalleryPackageImportDeviceTest` caller. **105 JVM tests passed**:
eight new lifecycle/commit regressions and 97 existing staging, Procreate ownership, settings,
brush-library, font-library and storage-tree cases. The new cases cover:

- Waiting for a blocked provider instead of completing a detached collector.
- Reporting an invalid PSD and continuing to the next incoming file.
- A real PSD parser/handoff preserving its exact source bytes and leaving the next URI queued.
- Cancellation closing the active stream and retaining the later queue item.
- Palette and brush completion waiting for a paused real repository write.
- Palette and brush failures reaching the caller, preserving previous rows, and allowing retry.

All six changed Kotlin files passed ktlint and detekt with the existing configuration/baseline;
the scoped diff check passed. No Gradle, Android device execution, commits, pushes, skipped cases
or weakened assertions were used in this assignment. The integrated Android build/device gates
remain separate, including real gallery package navigation and provider behavior.

Detailed logs are retained under `tooling/w17-source` in the shared review workspace:
`before-tests.log`, `library-before-tests.log`, `final-safety-compile.log`,
`final-safety-tests.log`, `final-ktlint.log` and `detekt.log`.

## Other inspected source boundaries

The source review also inspected the exported activity, FileProvider roots/read grants, artwork
archive allowlists/CRC and size checks, font/grain identities, storage traversal/deletion helpers,
and explicit project-deletion dialogs. No additional reproducible high-impact defect was found
in those inspected paths. This is a scoped source review, not a comprehensive security
certification or proof about platform backup/restore behavior.

The gallery empty-state wording now says that ArtFlow saves artwork in private storage. It no
longer implies copies cannot leave the device through Android backup or a selected export. A
separate wave owns the observed timestamp-based palette identity collision; this record does not
claim that issue is fixed by awaiting the write.
