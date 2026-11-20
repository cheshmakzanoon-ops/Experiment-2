# Production follow-through — November 19, 2026

## Scope

This is the follow-through requested after the audit of main `54fa5e0` found an
autosave-only recovery failure, a native-package snapshot race, and uncounted
selection memory. The existing PR #41 candidate `772d2f1` was independently
reviewed and incorporated into main; its prior twenty-wave record remains separate.
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
| 12 | `w12_brush_decode_budget`; `w12_procreate_device`; `w12_native_zip` | Early brush input/source/tile/metadata admission and aspect-preserving sampling implemented (102 focused cases passed); five Android opening/decoder cases type-checked; native ZIP constructors now have directory preflight (113 focused cases passed); optimized inventory expanded to 58, with all 39 verifier cases passing |
| 13 | `w13_brush_json`; `w13_provider_input`; `w13_runtime_docs` | Shared JSON structural/allocation preflight and bounded library/file output implemented (94 focused cases passed); generic provider admission/cancellation/progress fixed (28 cases passed, two old-source failures reproduced); README/help and source-linked verification record updated with 92 resolved links and unchanged release gates |
| 14 | `w14_palette_bounds`; `w14_export_ownership_review`; `w14_json_review` | Palette expansion/JSON/output admission implemented (95 focused cases, 30 new, and scoped static checks passed); export review accepted descriptor ownership but reproduced unadmitted still-image scaling; JSON/ZIP review accepted ZIP compatibility and reproduced isolated UTF-16 code-unit loss plus two smaller preflight ordering/syntax gaps |
| 15 | `w15_still_export_memory`; `w15_json_text_preservation`; `w15_cleanup_primary_failure` | Early still-export admission implemented (48 focused cases at 128 MiB, 12 new); lossless bounded JSON and stable input snapshots implemented (149 cases, 16 new); original publication failures preserved across cleanup faults (33 cases, seven new; four reproduce on old source), with two additional real-Room device cases; scoped static checks passed |
| 16 | `w16_cleanup_review`; `w16_json_wire_review`; `w16_still_export_review` | Independent reviews reproduced three gaps: unowned destination deletion (two failures in 41 cases), brush metadata/tile mismatch under changing caller input (one failure in five exhaustive wire probes; no live UI race established), and deferred PSD group-mask workspace undercount (one failure in 51 cases); other reviewed boundaries accepted |
| 17 | `w17_destination_ownership`; `w17_brush_file_snapshot`; `w17_psd_mask_workspace` | Storage-created cleanup ownership implemented (43 focused cases; ten new also pass with coroutine debug recovery); consistent bounded brush-file input implemented (152 cases; three new, original mismatch reproduced); deferred PSD mask workspace fixed (53 focused checks with debug recovery at 256 MiB, including two host checks of new Android cases); scoped static checks passed |
| 18 | `w18_ci_inventory_review`; `w18_publication_closure_review`; `w18_runtime_release_docs` | CI/provenance review passed 208 verifier cases and confirmed 341 debug/68 optimized identities with no removals or baseline changes; publication closure passed 45 debug-enabled cases and reproduced a remaining staged-cleanup diagnostic gap; runtime/release docs reconciled with 115 resolved relative links and unchanged release gates |
| 19 | `w19_staging_diagnostics`; `w19_export_ui_failure_review`; `w19_format_compatibility_review` | Shared staged/publication diagnostic retention implemented (45 focused debug-enabled cases, two new original-source failures retained); three actual ViewModel failure/retry/cancellation probes passed; format review accepted native/version/selection boundaries and reproduced rejection of valid old-writer dense brush libraries by the generic token ceiling |
| 20 | `w20_release_build_contract`; `w20_legacy_library_tokens`; `w20_final_compatibility_closure` | Build/variant/publisher contract review passed 70 verifier cases and confirmed 16 unchanged build inputs; saved-library-specific token repair passed 157 cases (five new); independent actual-old-writer review passed all five probes, where the identical pre-fix probes had two failures; generic/palette and shared-memory boundaries preserved |

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
- Wave-eleven publication: `1e438bbf3db3d29cc178819bab4a0f3cbc6311b7`, tree
  `05f05402081fb1cb8c20715371a912ae851f8a90`, accepted as a non-forced update
  from `33048d7`. Android CI `44643825768`, optimized qualification `44643825765`,
  installed upgrade `44643825756` and normal release UI `44643825660` started
  for this revision. The prior main's build job passed; its still-running emulator
  matrix was superseded by this new source and is not a complete green workflow.
- On `1e438bb`, optimized qualification `44643825765` (53 selected cases),
  installed upgrade `44643825756`, and normal release UI `44643825660` passed.
  Android CI `44643825768` passed its build/static/unit job, including fetched
  reference samples and brush corpus. All seven device lanes completed with the
  same two `GalleryPackageImportDeviceTest` failures at the missing-grain notice
  assertion: 328 passed and two failed on each API 35/36 configuration; API 26
  had 326 passed, two failed and two documented API-limited skips. These are
  retained failures, not successful device qualification.
- The gallery fixture declared a missing grain without referencing it in the
  artwork. The used-reference dependency audit intentionally ignores unrelated
  manifest declarations. The fixture now contains actual strokes using the
  present and missing grains, retaining every existing notice, byte, cleanup,
  navigation and database assertion. Its revised device executions remain due.
- The narrow gallery correction type-checked against the Android API and existing
  application classes and passed formatting. It was published separately as
  `8e463964abee76875b28f6dfea45005f80c79ff4`, tree
  `122cd2099f2ff9f0dd73a487c74585403af5bc7d`, without production-source changes.
  Android CI `44650019383`, optimized qualification `44650019517`, installed
  upgrade `44650019421`, and normal release UI `44650019407` started for it.
- The working optimized selection now also covers all four gallery package
  routing/notice/rollback cases. Its inventory contains 62 identities and the 39
  qualification-verifier tests pass. This working selection is separate from the
  53-case inventory in the narrow gallery-only publication above.
- Wave-fifteen source is frozen for combined local verification. The optimized
  inventory now has 64 cases after the two real-Room cleanup double-fault
  regressions. Their current-source Android type-check passed; hosted execution
  is still required. Scoped checks did not expand any static-analysis baseline.
- The palette Unicode reproduction separately retained two original failures;
  the revised shared escape writer passes strict UTF-8 roundtrips for isolated
  high/low code units, complete emoji pairs and truncated UI names. The initial
  JSON focused-runner dependency omission and export runner's nonexistent test
  name were corrected in scratch setup only; no production assertion was removed.
- Combined wave-fifteen verification compiled production and JVM test code and
  executed 2,282 cases in 357 suites. Two import cleanup cases failed because a
  subsequent coroutine debug-recovery boundary discarded a transient exception
  copy's suppressed lookup error. The primary error/cancellation survived. The
  two absent local reference-input skips remain separate. Android-test compilation
  and static tasks were not reached after this unit-test failure.
- The same 33-case focused cleanup suite reproduced both failures with assertions
  and coroutine debug recovery enabled. The helper now retains the cleanup
  diagnostic on the primary throwable and its matching type/message cause copies,
  with identity-cycle protection. All 33 cases pass under those settings with the
  original assertions unchanged; independent review and combined revalidation
  follow. The original failed batch and diagnostic reproduction are retained.
- Wave-seventeen combined verification completed 2,298 JVM cases in 360 suites
  with zero failures/errors and the same two local reference-input skips. Updated
  Android test compilation passed; formatting and detekt were still running at
  this checkpoint. The final staged-diagnostic change needs its own subsequent
  integrated validation.
- The deferred-mask focused suite initially refused a later small Procreate
  success case at a 128 MiB heap with coroutine debugging enabled because live
  headroom was exhausted. It did not trigger an out-of-memory failure. The full
  53-check run passed at 256 MiB with debugging and all assertions retained;
  production and injected admission limits were not widened. This remains
  separate from the earlier 48-case 128 MiB run without coroutine debugging.
- All three release workflows passed on the gallery-fixture checkpoint
  `8e46396`: optimized `44650019517`, installed upgrade `44650019421`, and normal
  release UI `44650019407`. Its Android build/device workflow was still running
  at this checkpoint. These results do not qualify the later working-source changes.
- The combined wave-seventeen run also passed formatting. Detekt then found one
  two-break loop in the new cleanup diagnostic helper. Wave nineteen moved the
  shared helper into core lifecycle code and combined its termination checks,
  preserving behavior and the unchanged baseline. The resulting 45-case focused
  suite and default-enabled scoped static checks pass; final combined validation
  remains due.
- The old-writer compatibility probe round-tripped five dense version-one brush
  libraries below the unchanged 3,145,728-character ceiling. Four were refused
  by the new generic token limit despite sufficient memory: 524,933, 531,333,
  542,085 and 546,437 tokens; the 520,581-token control passed. The durable library
  row was preserved, but access failed. Wave twenty addresses the library-specific
  work ceiling while retaining per-token pre-allocation memory accounting and the
  existing generic/palette limits.
- Release/build-contract verification passed 70 cases with real JDK signing and
  synthetic Android tool fixtures. Its first fixture attempt failed because an
  injected local JVM banner contaminated parsed manifest XML; clearing that
  test-only environment resolved it without source or assertion changes. This is
  verifier evidence, not publisher artifact approval or a new release build.
- All sixty distinct assignments across twenty waves are complete. The final
  library policy retains the existing character ceiling as a finite token upper
  bound; every token still reserves 128 bytes before deserialization. Independent
  actual-old-writer ordinary and dual libraries preserve exact models and text,
  including Unicode names, while exact-budget-minus-one and generic/palette
  boundary controls still refuse. No format input can select its own policy.
- The three independent real ViewModel export failure/retry/cancellation cases
  were retained as `ExportMemoryUiFlowTest`, preserving all saved-file, live-pixel,
  selection, dirty-state, previous-export and terminal-state assertions. They pass
  with assertions and coroutine debugging enabled, and scoped default-enabled
  static checks pass. The final source contains 341 full-debug device identities
  and 68 selected optimized identities; no prior identity was removed.
- Final combined source verification passed: 2,308 declared JVM cases in 363
  suites, zero failures/errors, and two local absent-reference-input skips;
  Android test compilation, whole-project ktlint and detekt also passed. The
  static baseline is unchanged. Debug and release Android lint then both passed
  in a fresh local Gradle process (56 actionable tasks, 8m 36s). Final hosted
  reference-input and emulator evidence is required for the published revision.

## Qualification after the agent waves

The implementation and review assignments above are complete. Final combined
verification and hosted qualification must bind to the published revision. Earlier
green commits, scoped probes and verifier fixtures do not qualify a later source
tree. The final report must identify the published commit and its Actions runs,
including both complete Android CI executions, the additional primary API 35 /
16 KiB execution, the three release workflows and any internal framework recovery.

Public rollout still requires the publisher, physical-device and store evidence
in [RELEASE_SIGNOFF.md](RELEASE_SIGNOFF.md). These external results are not implied
by completion of the sixty assignments or by a green emulator workflow.

## Continuation

Keep work in this checkout, preserve the seven required emulator configurations,
and retain failed attempts. Do not expand static-analysis baselines or turn missing
evidence into a pass. The integrator owns commits and publication to main. Record
every wave's actual contributions and verification outcome here. Do not mix local
probe results, hosted emulator results, physical measurements or publisher approval.
