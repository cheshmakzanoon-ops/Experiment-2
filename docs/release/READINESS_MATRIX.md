# Release readiness matrix

The current runtime scope is defined in [README](../../README.md). Implementation status and
execution evidence are separate: the regression links below identify coverage, while
[test evidence](TEST_EVIDENCE.md) records observed results for specific revisions and attempts.
[PR #40](https://github.com/cheshmakzanoon-ops/Experiment-2/pull/40) is the live index for later
integration and main verification. Historical qualification does not qualify a later revision.

| Area | Current disposition | Acceptance evidence |
| --- | --- | --- |
| Earlier font/codec/backup/CI hardening | Included in integration baseline; historical CI exists | `0ce19d7` runs in [test evidence](TEST_EVIDENCE.md). |
| Save/reopen raster budget and failed recovery discard | Implemented in the November integration | [Budget regressions](../../app/src/test/java/com/artflow/studio/data/repository/canvas/CanvasReopenBudgetTest.kt), [Android save/reopen](../../app/src/androidTest/java/com/artflow/studio/data/CanvasReopenBudgetDeviceTest.kt), and [failed recovery deletion](../../app/src/test/java/com/artflow/studio/data/repository/canvas/AuthoritativeRecoveryTest.kt); execution results in [the revision record](TEST_EVIDENCE.md#november-18-integration). |
| Gallery streaming import and dependency notices | Implemented for direct artwork and generic ZIP routes, with layered-import preflight and shared-grain rollback | [Real gallery routes](../../app/src/androidTest/java/com/artflow/studio/data/GalleryPackageImportDeviceTest.kt), [PSD/Procreate capacity](../../app/src/test/java/com/artflow/studio/data/export/LayerImportCapacityTest.kt), and [publication failure/cancellation](../../app/src/androidTest/java/com/artflow/studio/data/ProjectGrainImportRollbackDeviceTest.kt); execution results in [the revision record](TEST_EVIDENCE.md#november-18-integration). |
| Persistent layer linking and precision transforms | Implemented through editor controls and transactional linked transforms | [Repository transactions](../../app/src/test/java/com/artflow/studio/data/LinkedLayerTransactionTest.kt), [editor link/unlink](../../app/src/androidTest/java/com/artflow/studio/LinkedLayersUiTest.kt), and [transform/persistence coverage](V1_INTEGRATION_2026-11-18.md#implemented-user-paths-and-regression-coverage); execution results in [the revision record](TEST_EVIDENCE.md#november-18-integration). |
| Complete Gradle and emulator matrix | Qualification is specific to the tested revision and retained attempts | [Recorded results](TEST_EVIDENCE.md), the [live verification index](https://github.com/cheshmakzanoon-ops/Experiment-2/pull/40), and the unchanged [multi-run acceptance contract](RELEASE_SIGNOFF.md#engineering-acceptance). All build gates and seven full device configurations remain required; disclose any recovery. |
| Runtime capability descriptions | Reconciled to traced production callers | README, current audit, original plan status. |
| Physical device/stylus/performance/upgrade | OPEN | Actual hardware and release-owner records. |
| Publisher-signed AAB and store submission | OPEN | Signing, artifact digest, store/prelaunch/rollout evidence. |
| Advanced professional feature ambitions | OPEN, separate scope | Integrated GPU/sparse canvas, CMYK, typography/interchange/3D acceptance. |

No required matrix configuration or test coverage is waived by this table. Emulator qualification
supports engineering acceptance; public production requires the remaining release sign-off.
