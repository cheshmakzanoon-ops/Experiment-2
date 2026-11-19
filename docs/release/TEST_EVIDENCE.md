# Test and build evidence

## November 18 integration

Implementation is being validated on the branch containing PR #40. New build/emulator results
must be recorded below with the source revision and workflow URL before claiming qualification.
Local Android builds are unavailable in this session; actual Gradle and emulator execution uses
GitHub Actions. Pending checks are not passes.

| Integration revision | Run | Observed result |
| --- | --- | --- |
| `d2d407a` | [44451621348](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/runs/44451621348) | Build stopped at unit-test compilation: a legacy model-publication fixture's trailing lambda bound to the new budget argument. Fixed by naming the callback; no assertions removed. JVM tests, static analysis, and emulator execution were not reached. |
| `bbdfee0` | [44452446543](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/runs/44452446543) | 1,751 JVM tests in 292 suites passed with zero failures/errors/skips; all 283 instrumentation tests compiled. Android Lint had no errors/fatals. The static-analysis gate failed on formatting and one staged-copy loop readability finding, so APK/AAB and emulator stages were not reached. |
| `a9afcd3` | [44454662010](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/runs/44454662010) | All build gates passed: 1,751 JVM tests in 292 suites, instrumentation compilation, ktlint, detekt, debug/release Lint, APK/AAB integrity, and native alignment. Emulator execution exposed a linked-menu test targeting error and a separate API35/16 KB runtime field-resolution failure; see the retained attempt details below. |

The first run passed Python checks, workflow/secret/dependency checks, signing rejection, and
reference-corpus/native-contract verification before compilation failed. Its logs remain available.
The integration includes reviewed shared-grain rollback, layered-import preflight, composite-only
fallback, and empty-linked-layer regressions. Their build and device evidence belongs to each
tested revision; earlier passing counts cannot qualify a later revision.

The downloaded `bbdfee0` verification artifact confirms the JVM counts and unchanged Lint totals:
154 debug / 152 release warnings plus five hints in each. Formatting and the loop structure are
corrected in the next candidate without expanding the baseline or excluding tests. A JVM pass
does not substitute for the still-required emulator and packaged-artifact gates.

### Retained `a9afcd3` emulator findings

The workflow checks out PR merge revision `92000116fb245f6dc0aeb9f63abb20de56c64d49`,
merging branch head `a9afcd3` into `d340bc6`. The source tree matches the published candidate.

The API26, API35 standard 4 KB, and API36 16 KB artifacts each report all 283 unique test
identities. API26 has 280 passes, two permitted skips, and one failure; the other two have 282
passes and one failure. All three fail the same new
`LinkedLayersUiTest.selectedLayersLinkAndTheActiveLayerMenuUnlinksThem` menu assertion.
The test touched the merged active-layer card, whose bounds include its opacity slider. The
correction touches the actual visible layer label, asserts the menu opened, and scrolls the
unlink item into view before the real click. Link/unlink, history, and undo assertions remain.
The production menu already places **Unlink layer** second; its ordering is unchanged.

Separately, [API35/16 KB job 135333792714](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/runs/44454662010/job/135333792714)
stopped after 79 passes and one `NoSuchFieldError` during activity/database initialization in
`EditorRenderingTest`, leaving 203 identities missing. The captured installed APK matches the
built APK; its `classes12.dex` declares `DatabaseModule.INSTANCE` with the exact requested type,
and the generated provider reads that same field. All 18 app DEX files and seven test DEX files
are byte-for-byte identical to the API35/4 KB peer, which passed all three rendering cases.
JIT was verified disabled. No matching app native tombstone was present.

These observations exclude a field absent from the installed bytecode; runtime/class-resolution
state corruption is an inference, not an established mechanism. The existing classifier correctly
rejects the app-frame Java failure as non-retryable. Keep the failed attempt, leave the DI contract
and classifier unchanged, and require a fresh complete matrix after correcting the independent
UI-test targeting error. A passing peer does not waive the interrupted lane.

The independent API35/16 KB lane completed its first invocation with 282 passes, the same single
linked-menu failure, and no missing identities or recovery. All three `EditorRenderingTest`
cases passed. Its 18 app and seven instrumentation DEX files also exactly match the failed
API35/16 KB lane. The database field-resolution error did not recur in this independent execution;
its absence does not establish the earlier failure's mechanism.

The API36 standard phone completed all 283 identities with 281 passes and two failures on its
first invocation, with no recovery. In addition to the linked-menu assertion,
`BrushStudioUiTest.restoringTheDraftAndApplyingUseTheCorrectSnapshot` reported concurrent
`SnapshotStateObserver` access from thread 51 and the main thread. This separate rendering/test
failure remains under investigation; it is not classified as an allowed framework retry. The
failing test performs normal Compose UI actions, and the reviewed screenshot/preview workers do
not draw the Compose owner. No causal production change is supported by the captured stack. The
next revision adds an outer failure rule to retain live Java thread IDs, names, and stacks plus
the last marked brush action. It rethrows the original failure and adds no delays or retries.

The API36 tablet also completed all 283 identities with 282 passes and the single linked-menu
failure. All seven device jobs finished; none used an instrumentation recovery, and none reached
minified-launch acceptance after its failing test result. Each captured installed app/test APK
matches the corresponding per-job build. The build's success does not make this an accepted
eight-job workflow.

| `a9afcd3` lane | Passed | Failed | Allowed skips | Missing identities |
| --- | ---: | ---: | ---: | ---: |
| API26 | 280 | 1 | 2 | 0 |
| API35 standard 4 KB | 282 | 1 | 0 | 0 |
| API35 16 KB | 79 | 1 | 0 | 203 |
| API35 independent 16 KB | 282 | 1 | 0 | 0 |
| API36 standard phone | 281 | 2 | 0 | 0 |
| API36 16 KB | 282 | 1 | 0 | 0 |
| API36 tablet | 282 | 1 | 0 | 0 |

## Historical evidence, independently inspected

| Source | Run | Result and limits |
| --- | --- | --- |
| `main` `d340bc6` | [43189321650](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/runs/43189321650) | 7/8 jobs; independent API35/16 KB failed. |
| hardening `0ce19d7` | [43350635499](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/runs/43350635499) | 7/8 jobs; independent API35/16 KB failed. |
| hardening `0ce19d7` | [43366683593](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/runs/43366683593) | 8/8 jobs; independent API35/16 KB required an internal framework recovery after 43 passes, 1 failure, and 226 missing identities. |
| hardening `0ce19d7` | [43372211871](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/runs/43372211871) | 8/8 jobs; all seven device configurations completed on their first instrumentation invocation. |

The last historical run's downloaded verification artifact contains 284 JVM suites, 1,706 tests,
zero failures/errors/skips. Device results contain 1,888 passing executions of 270 distinct tests
across seven environments; the API26 lane has two permitted API-specific skips. Actual process
page size and minified launch passed in each lane. These counts describe `0ce19d7`, not later code.

Historical Android Lint reports contain 154 debug / 152 release warnings and five hints in each,
with no errors/fatals. The detekt baseline was reduced from 82 to 67 entries. These are not zero
historical findings. Packaged native objects were only `libandroidx.graphics.path.so` across four
ABIs, with alignment verified.

During the November 18 audit, the unchanged candidate's Python suites passed: 166 CI-script tests
and 76 tools tests. Workflow pins and the selected-pattern secret scanner passed. The direct
Gradle-declaration CycloneDX inventory is not a resolved transitive dependency certification.

## Evidence policy

Keep first failures, recovery invocations, exact source SHA, complete case inventory, runtime
identity, and minified launch together. GitHub `run_attempt: 1` does not exclude retries inside a
job. Do not claim physical performance or store release acceptance from emulator results.
