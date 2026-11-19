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
| 07 | `w07_provider_metadata`; `w07_parser_review`; `w07_asset_portability` | In progress: provider responsiveness repair, parser admission and asset portability review |
| 08–20 | Assigned sequentially from confirmed defects and verification needs | Pending |

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

## Continuation

Keep work in this checkout, preserve the seven required emulator configurations,
and retain failed attempts. Do not expand static-analysis baselines or turn missing
evidence into a pass. The integrator owns commits and publication to main. Record
every wave's actual contributions and verification outcome here. Do not mix local
probe results, hosted emulator results, physical measurements or publisher approval.
