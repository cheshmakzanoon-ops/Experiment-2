# Test adequacy review — wave 17

## Scope and conclusion

This independent review traced the high-risk hardening changes through their actual callers,
failure paths and assertions. It found one reproducible host-fixture timing problem and repaired
that fixture without changing the production device runner or any acceptance deadline. It did
not identify another missing correctness assertion that justified adding a redundant test.

The source review was made against committed checkpoint `c419f49` and the concurrent wave-17
working changes. It is not acceptance of an unexecuted final revision. Full Gradle, all seven
Android lanes, optimized execution, ordinary release payload execution and the installed-app
upgrade campaign remain separate exact-revision requirements.

## Host timeout diagnosis and repair

The integrator's retained run failed
`test_unready_services_fail_closed_before_instrumentation` for the unavailable settings service:
the child shell reached the fixture's 10-second outer deadline. The production readiness loop
correctly makes up to 45 attempts, with four probes per attempt when only settings is absent.
The fixture implemented every simple `adb` reply by launching a Python interpreter. On a normal
local observation, its four readiness-failure variants took approximately 2.21, 3.45, 5.18 and
5.64 seconds respectively; the settings variant logged 198 total `adb` calls.

A controlled experiment added 40 ms to child Python startup. The original fixture reached its
unchanged 10-second limit. With the simple readiness responses implemented in a shell wrapper,
the same unavailable-settings scenario completed in 2.347 seconds, returned failure, made all
45 boot and settings probes, retained all 198 logged calls and never started instrumentation.
Complex APK/tombstone mocks still use the original Python implementation.

The repair changes only `test_run_device_tests.py`. The 10-second parent timeout, 45-probe
assertion, transient-boot behavior, recovery ordering and production 90-second readiness deadline
remain unchanged. **All 19 existing device-harness tests passed** afterward, with no skips.
The original failure and controlled before/after results are retained in the review workspace;
this successful run does not relabel the earlier failed discovery.

## Test retention and oracle review

A declaration comparison against the starting source
`54fa5e0a9d8ef8502d637192c9dc9748c2000dfe` found **no removed original Kotlin `@Test` method and no
removed original test file**. The original source contains 2,034 declarations across JVM and
Android sources. The working inventory was larger; the final gate must calculate its own exact
inventory rather than use this review's changing count as a floor. No new ignored or assumed-away
case was found in the reviewed hardening diff.

The substantive adaptations to old assertions were inspected explicitly. Four capacity-spy call
sequence assertions in `LayerImportCapacityTest` belonged to the removed live `canImportLayers`
hook. The tests now exercise the actual atomic import boundary and retain the refusal, unchanged
state/history, flattened-result, saved/reopened pixel and layer-slot outcomes. The injected failure
message moved to that new publication boundary and gained untouched layer/undo assertions.
The USD oversized-token fixture retains its exact refusal assertion after correcting its
overlapping binary layout. Model budget fixtures retain the reject-before-decode and successful
bounded-fit assertions while including the newly accounted fixed geometry workspace.

| Risk and actual caller path | Reviewed evidence and outcome oracle |
| --- | --- |
| Partial or stale layered import through `CanvasImportController` and `LayerImports` | `AtomicLayerImportTest` and `CanvasImportControllerTest` snapshot full stored layer/frame pixels, selection, profile, background, revision and history around late failure, cancellation and destination changes. Successful publication has one undo step with redo restoration. The late-mask fixture explicitly requires a real nonwhite mask tile before injecting its read failure. |
| Decoder allocation or source ownership failure | PSD/model budget suites require refusal before decode/expansion, preserve independent producer fixtures and retain actual geometry/material values. `ImportPreparationAdmissionTest` blocks real provider reads and proves competing sources are not opened. Android bitmap tests check closed source snapshots, all EXIF transforms, preserved input bytes, exact native raster pixels and unchanged saved artwork after refusal. |
| Save metadata drifting away from committed artwork | `CommittedSaveMetadataTest` drives the real ViewModel/manual-save and native-export paths with paused storage I/O. It compares the document, every thumbnail pixel and gallery dimensions/DPI/layers/time, preserves later dirty edits, blocks navigation, and checks ordered callbacks and failure/cancellation after durable commit. |
| Mixed-revision export or prematurely released assets | `ProjectPackageSnapshotTest` reads actual archive bytes after newer writes, checks exact manifest/asset contents, holds overlapping export leases, and verifies cancellation/error cleanup without replacing the previous export. |
| Database upgrade destroying existing user data | `DatabaseMigrationDeviceTest` uses actual version-1 SQLite data and the production Room provider. It checks all seeded rows, high-water identities, settings/library values, complete file snapshots, saved/recovered pixels, second open, rejected missing migration, failed-validation rollback and unsupported future-version refusal. Device execution is required in addition to compilation. |
| Invisible input after preview failure | `CanvasPreviewFailureDeviceTest` drives the actual view, deliberately fails the GL path, compares complete artwork pixels/history and verifies blocked input, retained save access and protection against an old document's delayed callback. |
| Optimized and ordinary release regressions | The optimized test paints via pointer input, saves/reopens through UI and compares all exported pixels. Its renderer-ready precondition retains its deadline and dirty-state assertions. The separate ordinary-release driver checks installed app/driver identities, real share grants, distinct app/test processes, force-stop/new PID and all 4,096 saved/reopened exported pixels. These two runtime protocols have different scopes and must both pass. |

The delayed-recovery and incoming-gallery concurrency defects were independently owned by the
parallel wave-16/17 reviews; this assignment did not edit their source or substitute its review
for their regression execution.

## Evidence limits

Compilation is not Android execution. A fixture signed with a disposable certificate is not an
approved publisher artifact. The rebuilt pinned historical app is engineering upgrade evidence,
not proof about an originally distributed binary. JVM allocation admission tests do not measure
physical low-memory behavior, stylus pressure/tilt fidelity or latency. These distinctions remain
required by [release sign-off](../release/RELEASE_SIGNOFF.md).

No Gradle, emulator/device execution, baseline edit, commit or push was performed by this
assignment. Detailed local evidence is under `tooling/w17-tests` in the shared review workspace:
`harness-final.log`, `readiness-startup-probe.log` and `test-declaration-review.json`.
