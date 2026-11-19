# Recovery session safety

## Reproduced failure

Wave 16's independent data review reproduced two lost-live-edit races against the
pre-fix implementation using the real `ProjectStorage` and a dispatcher that holds
one storage operation:

1. Begin recovery for artwork A, open artwork B, and edit B before releasing A's
   read. Recovery then replaced B's live canvas with A. The assertion failed with
   `expected:<79002> but was:<79001>`.
2. Begin recovery and edit the same artwork while its read is held. Releasing the
   read erased that newer live edit.

Both new regression tests failed before the production repair. These failures
concerned unsaved in-memory artwork; the retained recovery and saved manifests
were not deleted by the reproduction.

## Repair

`CanvasRepositoryImpl.recoverAutosave` now captures the active project's revision
before reading. After all recovery layers have been decoded and immediately before
publication, it checks that the project and revision still match and that no
stroke or raster edit is in progress. A stale recovery fails visibly, preserving
the live canvas, history, dirty state, and recovery file. An explicit later retry
can restore that recovery. Frame clipboard clearing also occurs only after this
publication check succeeds.

`CanvasViewModel.open` invalidates the previous recovery operation before the next
project lookup can suspend. `CanvasRecoveryController` cancels that operation and
scopes recovery/discard callbacks to their original session. A delayed callback
cannot clear another artwork's recovery guard or relabel an old recovered canvas
as the newly requested artwork. Saving stays guarded until opening establishes
the new project's recovery state.

Background recovery scheduling now resides in `CanvasRecoveryController`, retaining
the existing dirty/unresolved-recovery checks and non-cancellable admitted write.
The ViewModel still owns the setting, explicit-discard, readiness, and playback
checks. This cohesive extraction keeps the existing ViewModel size gate unchanged.

## Focused verification

Standalone Kotlin compilation and **45 JVM tests passed**, with zero failures:

| Suite | Tests |
| --- | ---: |
| `RecoverySessionSafetyTest` | 4 |
| `AuthoritativeRecoveryTest` | 10 |
| `CorruptDocumentLoadTest` | 3 |
| `CommittedSaveMetadataTest` | 6 |
| `CanvasModelInitializationTest` | 6 |
| `ProjectMetadataLifecycleTest` | 3 |
| `ModelMaterialImportRepositoryTest` | 13 |

The four new cases cover both reproduced races, ViewModel cancellation while the
next project's database lookup is still suspended, and completion of a real
admitted background write after the editor scope closes. Ktlint, scoped Detekt
with the unchanged configuration/baseline, and `git diff --check` passed.

The retained local evidence is under `tooling/w16-recovery` in the review workspace:
`before-tests.log` records the two real regression failures; `final-tests.log`
records the successful 45-case run. Two intermediate runner invocations stopped
before suite execution due to local class-list setup errors and remain in
`runner-setup-failures.log`; they are not counted as product test results.

This is focused JVM evidence. Full integrated Gradle and Android runtime acceptance
of the final source revision remain separate gates. The review also inspected
manifest/raster retention, package snapshot ownership, import rollback, orphan-ID
reservation, and post-commit gallery callback failures. No additional concrete
data-loss defect was confirmed in those inspected paths. Gallery callback errors
remain visible and retryable while committed document files remain authoritative.
