# Committed save metadata

## Regression and behavior

The editor previously read canvas dimensions, DPI, and layer count after suspended
save I/O. An edit made during that I/O could therefore leave the saved document
and thumbnail at 8 × 6 while the gallery reported the newer 12 × 10 canvas.
The controlled reproduction failed with `expected:<8> but was:<12>` before the
fix.

`CanvasRepository.saveCanvasSnapshot` now returns an immutable `SavedCanvas`
bound to the committed project, captured revision, dimensions, DPI, active-frame
layer count, thumbnail path, and document timestamp. Legacy `saveCanvas` callers
continue receiving the thumbnail path through delegation. The editor's manual
save, initial model save, and native artwork export publish gallery metadata
from that result through the existing field-scoped repository transaction.

The metadata callback executes after the document and both previews are durable
and before the save mutex releases. This keeps successive document and gallery
publications ordered. The callback must not start another save. A callback
exception or cancellation propagates without rolling back committed files.
ViewModel cache updates, dirty-state refresh, notifications, and navigation occur
outside that mutex; cache and dirty-state refresh explicitly run on Main.
Newer live edits remain dirty and prevent save-and-leave navigation.

## Focused evidence

The standalone Kotlin compilation included all changed production, JVM test,
and Android test sources. A JVM run passed **38 tests, zero failures**, comprising:

| Suite | Tests | Coverage |
| --- | ---: | --- |
| `CommittedSaveMetadataTest` | 6 | Paused real storage I/O; committed manifest, all thumbnail pixels, and gallery agreement; retained live edits and navigation guard; native package export; active-frame/revision result; post-commit callback failure and cancellation; ordered two-save publication |
| `CanvasModelInitializationTest` | 6 | Existing initial-save, retry, cancellation, and pending-payload ownership behavior |
| `ProjectMetadataLifecycleTest` | 3 | Existing field-scoped metadata publication and gallery organization preservation |
| `AuthoritativeRecoveryTest` | 10 | Existing recovery, autosave, save/export guards, and atomic file behavior |
| `ModelMaterialImportRepositoryTest` | 13 | Existing material-plane publication, retry, cancellation, and exact-pixel behavior |

The changed Kotlin files also passed ktlint, Detekt with the existing configuration
and baseline, and `git diff --check`. No test assertion or static-analysis
threshold was weakened.

This is focused JVM evidence, not a claim of Android device execution or a full
Gradle gate. The existing device test that injects a failure after a real model
save was adapted to the new save result and compiled; its device run remains a
separate gate. No document schema or database migration was introduced.
