# Production follow-through — November 19, 2026

## Scope

This is the follow-through requested after the audit of main `54fa5e0` found an
autosave-only recovery failure, a native-package snapshot race, and uncounted
selection memory. The existing PR #41 candidate `772d2f1` has been incorporated
locally for independent review; its prior twenty-wave record remains separate.
This follow-through uses twenty new waves of three distinct agent assignments.

The engineering target is reliable supported offline workflows, bounded memory,
verified optimized/release payloads, preserved upgrades, and passing CI on the
published main revision. Physical hardware, publisher signing and store approval
remain evidence requirements; they must not be claimed from emulator results.

## New wave record

| Wave | Assignments | Result |
| --- | --- | --- |
| 01 | `w01_recovery`; `w01_export`; `w01_memory` | Existing recovery/export fixes independently reviewed; new array accountant and selection admission helper implemented; 11 focused JVM checks and assigned-file formatting passed |
| 02 | `w02_history`; `w02_saved_selection_ui`; `w02_document_bounds` | Combined history accounting and hard cap, transactional slot UI, bounded document publication implemented; seven focused controller tests passed; integrated checks pending |
| 03 | `w03_history_review`; `w03_metadata_review`; `w03_selection_review` | Independent review confirmed history ownership and publication safety; found stale saved-slot UI after recovery and missing serialized-slot admission |
| 04 | `w04_recovery_slots`; `w04_serialized_slots`; `w04_history_regressions` | Recovery refresh repaired with two real-callback regressions; serialized slot admission preserves legacy loading/removal; 11 helper checks passed; historical array ownership regressions added |
| 05 | `w05_encoded_budget_review`; `w05_android_boundaries`; `w05_memory_help` | Bound and legacy exceptions independently accepted; three real Android regressions added to debug and optimized inventories (46 optimized cases); 39 qualification-tool tests passed; in-app help and verification documentation updated |
| 06 | `w06_upgrade_review`; `w06_backup_review`; `w06_incoming_import_review` | Upgrade and backup boundaries reviewed; historical UI seed does not qualify imported font/grain usability; identified provider metadata IPC still running on Main |
| 07 | `w07_provider_metadata`; `w07_parser_review`; `w07_asset_portability` | Provider metadata moved off Main with three regressions (eight focused tests passed; original code failed the new cases); two compact Procreate allocation-amplification probes reproduced; found font identity and cold-start grain readiness gaps |
| 08 | `w08_procreate_expansion`; `w08_font_identity`; `w08_grain_startup` | Parser traversal/decoded-allocation limits implemented (48 focused cases passed); font fingerprints and accurate notices implemented (28 focused cases passed); asynchronous asset readiness/error/retry/explicit continuation implemented (12 focused cases passed), with three device cases added to optimized qualification |
| 09 | `w09_procreate_open_budget`; `w09_font_identity_review`; `w09_startup_review` | Opening-budget implementation applied and handed to wave ten for final integration; reviews accepted identity/readiness boundaries and reproduced dependency-work and grain-retention gaps |
| 10 | `w10_grain_capacity`; `w10_dependency_work`; `w10_opening_integration` | Retained/buffered grain admission implemented (37 focused cases passed); bounded declarations and one font hash per file per operation implemented (39 cases passed); early Procreate allocation and ZIP directory preflight integrated (92 cases passed); focused static checks passed without baseline changes |
| 11 | `w11_brush_transaction`; `w11_grain_review`; `w11_native_stream_route` | Atomic brush-batch publication through the durable library commit implemented (35 focused cases passed); independent grain ownership review passed 45 cases and identified early ABR allocation; byte-array native imports now use the staged streaming path (26 cases passed), with two additional Room device regressions |
| 12 | `w12_brush_decode_budget`; `w12_procreate_device`; `w12_native_zip` | In progress: pre-allocation brush decoding limits, real Android Procreate opening regressions, and native-package ZIP directory admission |
| 13–20 | Assigned sequentially from confirmed defects and verification needs | Pending |

## Verification record

- Initial main `54fa5e0`: two complete green eight-job Android CI executions; the
  audit nevertheless reproduced the recovery and export defects in focused JVM probes.
- Existing PR #41 `772d2f1`: live Android CI `44598636753`, optimized qualification
  `44598636951`, installed upgrade `44598636733`, and normal-release payload UI
  `44598636724` report success. These qualify that candidate, not later changes.
- New changes require their own compilation, focused regressions, static analysis,
  packaged builds and exact-revision emulator evidence before acceptance.
- Local cached JDK 17 / SDK 36 / Gradle 8.14.3 setup passed Gradle configuration.
  An initial host Python run exposed an unset local JDK path in a Java fixture;
  this was an environment setup failure, not a CI result or application failure.
- Configured local Python verification passed: 429 CI-tool tests (six skips) and
  76 reference/tooling tests. A second setup attempt replaced the host PATH and
  broke fixture subprocess discovery; preserving PATH and prepending JDK 17
  resolved that local environment failure without modifying tests.
- First integrated build compiled app and device-test code and executed 2,027 JVM
  tests. Two new navigation tests confused cumulative moves with retained masks;
  they were corrected to assert actual fixture-known retained bytes after every
  move, the final inverse depth and the exact restored state. One new test-chain
  formatting error and excessive accounting-loop nesting were repaired without
  baseline changes. This failed attempt is retained locally; it is not a pass.
- The revised wave-five JVM suite passed 2,040 cases in 329 suites with zero
  failures/errors and two local reference-input skips (reference IPA and real
  Procreate sample files). Formatting and detekt passed without baseline changes.
  Debug/release lint and updated Android-test compilation were still running at
  the intermediate publication; hosted device evidence is required separately.
- Intermediate main publication: `6b65a17647544673115443f729ed7b2cf346261d`,
  tree `06af4d4a5e1d8b8c199d10ad6bd1f5e382e4d263`. GitHub accepted a non-forced
  update from the original main, and the local checkout matches that tree.
  Android CI `44633824461`, optimized qualification `44633824296`, installed
  upgrade `44633824463`, and normal release UI `44633824396` started for this
  intermediate revision. Later changes need their own qualification.
- Local debug lint completed; the Gradle daemon disappeared during release lint.
  That incomplete batch is not counted as a passing release-lint or updated
  device-test compilation result. The completed unit/static results remain
  separate from hosted workflow outcomes.
- The optimized selection inventory now includes 49 source cases, including
  the three startup UI regressions. All 39 inventory/qualification-tool tests
  passed after that expansion; device execution on the new source remains due.
- Wave-eight integration passed 2,080 JVM cases in 333 suites (zero failures or
  errors; the same two local reference-input skips), Android-test compilation and
  formatting. Detekt identified six source-structure findings; provider and
  initializer error handling were refactored, with font/grain refinements owned
  by wave ten. No baseline was expanded.
- On `6b65a17`, normal release UI `44633824396` and installed upgrade
  `44633824463` passed. Optimized qualification `44633824296` retained its
  original failure: 45 of 46 tests passed; the new saved-selection device fixture
  requested a 4 KiB heap that could not admit its canvas's six working planes.
  Main `33048d7199118706fe0bfc65e40c55bab7b5eb67` corrects the fixture to an
  admitted 12 KiB heap, fills three slots and rejects a fourth, preserving the
  original state, byte, pixel and reopen assertions. Production guards and test
  identities were unchanged. New qualification runs started for that revision.
- On `33048d7`, optimized qualification `44640020739`, installed upgrade
  `44640020211`, and normal release UI `44640020318` passed. Its full Android CI
  remained in progress at this checkpoint. The original `6b65a17` optimized
  fixture failure remains part of the record.
- The next combined local batch compiled the wave-ten application sources, then
  lost its Gradle daemon during test compilation. No JVM, Android-test or static
  completion is claimed for that incomplete batch. The separate focused wave-ten
  results above remain valid; subsequent integrated checks use smaller batches.
- The optimized inventory includes all four native-package grain rollback cases,
  including the byte-array route, for 53 selected cases. The 39 qualification
  verifier tests pass. Six independent shared-grain lifecycle checks were also
  retained in the regular JVM suite after the wave-eleven review.
- The smaller local Gradle batch completed 2,156 JVM cases with one new fixture
  failure and the same two absent-reference-input skips. The cancellation test
  assumed object identity across a coroutine boundary; JVM debug stack recovery
  copies the exception and keeps the original as its cause. The regression now
  requires the cancellation type, message and exact original exception identity
  (directly or as cause), alongside unchanged file-cleanup assertions. The failed
  batch is retained; a subsequent integrated run verifies the correction.
- The corrected wave-eleven integrated JVM run completed 2,156 declared cases
  with zero failures/errors and the same two local reference-input skips. Android
  test compilation and formatting also passed. Detekt found one over-nested CRC
  fixture expression in the new byte-array import test; the checksum calculation
  was flattened without changing its bytes or assertions. The original static
  failure is retained. The complete detekt and formatting tasks then passed
  without expanding any baseline. This checkpoint is ready for source publication;
  hosted release/device evidence is required for the published revision.

## Continuation

Keep work in this checkout, preserve the seven required emulator configurations,
and retain failed attempts. Do not expand static-analysis baselines or turn missing
evidence into a pass. The integrator owns commits and publication to main. Record
every wave's actual contributions and verification outcome here. Do not mix local
probe results, hosted emulator results, physical measurements or publisher approval.
