# Test and build evidence

## November 19 production hardening

The current repair and verification campaign starts from main
`54fa5e0a9d8ef8502d637192c9dc9748c2000dfe`. Its per-wave implementation, local verification and
failed CI checkpoints are retained in the [hardening record](PRODUCTION_HARDENING_2026-11-19.md).
[PR #41](https://github.com/cheshmakzanoon-ops/Experiment-2/pull/41) is the current integration and
exact-revision verification index. The older PR #40 records below remain the evidence for that
earlier integration; they do not qualify this candidate's later changes.

The seven-lane debug matrix, selected optimized workflow lane, installed-package campaign,
physical-device campaign and publisher-artifact gate have different scopes. Record actual
execution and retained artifact identities for each. A newly configured gate, a host test of its
verifier or a successful APK build does not establish that the corresponding device campaign ran.
The [release sign-off](RELEASE_SIGNOFF.md) defines final-SHA repeat acceptance and external gates.

## November 19 CI repair follow-up

The [kernel comparison record](../ANDROID15_KERNEL_REPAIR_2026-11-19.md) documents two
independent first-attempt API35 / 16 KB controls, each completing all 283 test identities and
minified launch. The required workflow now applies that pinned same-family kernel update only
to its two API35 / 16 KB lanes, retaining the Android 15 image and all existing acceptance gates.
Those controls qualify the environment change, not the final main revision. The live verification
index in PR #40 below records subsequent exact-main-SHA workflow attempts and their artifacts;
earlier failed attempts remain part of the evidence.

## November 18 integration

This record preserves the November integration's observed attempts, including failures and
incomplete matrices. [PR #40](https://github.com/cheshmakzanoon-ops/Experiment-2/pull/40) is the live
verification index for subsequent integration and main results, their exact source revisions,
workflow attempts, and artifact references. Follow that index for results recorded after this
document's snapshot; retain earlier attempts when appending verified results here.

Qualification belongs to the tested revision and complete evidence, not to a branch name or an
implementation-status row. Record the checked-out SHA and workflow URL; identify a PR merge
revision separately from its branch head. A matching source tree is useful provenance but does
not replace the final-SHA evidence required by the [release sign-off](RELEASE_SIGNOFF.md).
During this integration session, local Android builds were unavailable; Gradle and emulator
execution used GitHub Actions. Planned, running, or uninspected checks are not passes.

| Integration revision | Run | Observed result |
| --- | --- | --- |
| `d2d407a` | [44451621348](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/runs/44451621348) | Build stopped at unit-test compilation: a legacy model-publication fixture's trailing lambda bound to the new budget argument. Fixed by naming the callback; no assertions removed. JVM tests, static analysis, and emulator execution were not reached. |
| `bbdfee0` | [44452446543](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/runs/44452446543) | 1,751 JVM tests in 292 suites passed with zero failures/errors/skips; all 283 instrumentation tests compiled. Android Lint had no errors/fatals. The static-analysis gate failed on formatting and one staged-copy loop readability finding, so APK/AAB and emulator stages were not reached. |
| `a9afcd3` | [44454662010](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/runs/44454662010) | All build gates passed: 1,751 JVM tests in 292 suites, instrumentation compilation, ktlint, detekt, debug/release Lint, APK/AAB integrity, and native alignment. Emulator execution exposed a linked-menu test targeting error and a separate API35/16 KB runtime field-resolution failure; see the retained attempt details below. |
| `91c6714` | [44460714924](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/runs/44460714924) | JVM tests and instrumentation compilation passed. Static analysis stopped on four chain-continuation formatting findings for the corrected menu test's single four-call line. The next revision splits that chain across lines without changing any action or assertion. APK/AAB and emulator stages were not reached. |
| Head `384095d`; tested PR merge `2b857405` | [44463021020, attempt 1](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/runs/44463021020/attempts/1) | All build gates passed, including 1,751 JVM tests in 292 suites with zero failures/errors/skips. Six device lanes completed all 283 identities, page-size/provenance checks, and minified launch on their first invocation. The primary API35/16 KB lane failed before app installation/test startup, executing zero tests. Original attempt: 7/8 jobs passed; not an accepted complete matrix. See the artifact summary and retained findings below. |
| Head `384095d`; tested PR merge `2b857405` | [44463021020, attempt 2](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/runs/44463021020/attempts/2) | Manual rerun of the primary API35/16 KB job only, also failed. Its first instrumentation invocation stopped after 154 passes and one process-interruption record; one internal framework recovery ran, then the second invocation stopped after 31 passes and one process-interruption record. Both have matched native ART crash evidence. Other jobs were carried forward, not rerun. No complete matrix or final-SHA acceptance. |

The first run passed Python checks, workflow/secret/dependency checks, signing rejection, and
reference-corpus/native-contract verification before compilation failed. Its logs remain available.
The integration includes reviewed shared-grain rollback, layered-import preflight, composite-only
fallback, and empty-linked-layer regressions. Their build and device evidence belongs to each
tested revision; earlier passing counts cannot qualify a later revision.

The downloaded `bbdfee0` verification artifact confirms the JVM counts and unchanged Lint totals:
154 debug / 152 release warnings plus five hints in each. Formatting and the loop structure are
corrected in the next candidate without expanding the baseline or excluding tests. A JVM pass
does not substitute for the still-required emulator and packaged-artifact gates.

### Retained `384095d` attempt-one results

[Run 44463021020, attempt 1](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/runs/44463021020/attempts/1)
checked out PR merge `2b857405a05dcefa19cc8f6c43102ae55668f5a9` for branch head
`384095da6b0feec12736fa54fe7d245777e1c37e`. The
[durable artifact summary](../evidence/ci-run-44463021020-attempt-1.json) records public job and
artifact identities, digests, per-lane counts, runtime/provenance results, and startup diagnostics.
It summarizes the retained artifacts; it does not replace their complete logs and test records.

Build job `135354090452` passed JVM tests, instrumentation compilation, ktlint, detekt,
debug/release Lint, APK/AAB builds, and packaged-integrity/native-alignment checks. Lint retained
154 debug / 152 release warnings and five hints in each, with no errors/fatals.

| Original device lane | Passed | Failed test cases | Allowed skips | Missing identities | Page size / minified launch |
| --- | ---: | ---: | ---: | ---: | --- |
| API26 | 281 | 0 | 2 | 0 | 4 KB / passed |
| API35 standard 4 KB | 283 | 0 | 0 | 0 | 4 KB / passed |
| API35 16 KB | 0 | 0 | 0 | 283 | Not measured / not reached |
| API35 independent 16 KB | 283 | 0 | 0 | 0 | 16 KB / passed |
| API36 standard phone | 283 | 0 | 0 | 0 | 4 KB / passed |
| API36 16 KB | 283 | 0 | 0 | 0 | 16 KB / passed |
| API36 tablet | 283 | 0 | 0 | 0 | 4 KB / passed |

The six successful lanes each contain all 283 source identities, with no internal framework
recovery and installed app/test APKs matching their corresponding builds. API26's two skips are
the existing API-specific cancellation-flag and scoped-MediaStore cases. Across those lanes,
there are 1,696 passing executions and two allowed skips. The linked-layer UI, brush-studio UI,
and rendering cases all passed in the six completed lanes; this does not erase their earlier
failures or establish the cause of the earlier runtime failures.

The primary [API35/16 KB job 135359821087](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/runs/44463021020/job/135359821087)
failed during APK installation, before any application test ran. Retained logs show
`system_server` crashes and framework field-resolution errors before installation; app
`install-create` then failed through a null `StorageManager.getVolumes` receiver. No app/test APK
was installed, no test-runner snapshot was captured, and all 283 test identities are missing.
The configured image was 16 KB, but there is no measured application page size or minified-launch
acceptance for this invocation. The existing classifier rejected automatic recovery and was not
changed. This pre-application system failure does not exercise or qualify application behavior.

**Manual follow-up, separate from attempt one:** workflow attempt 2 reran only the primary
API35/16 KB lane as fresh [job 135372972887](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/runs/44463021020/job/135372972887)
and also failed. The original attempt-one artifact summary preserves its queued-at-recording
snapshot; the completed result follows below. This manual job rerun is not a second complete
matrix, and the final-SHA multi-run contract in [release sign-off](RELEASE_SIGNOFF.md) remains unmet.

### Retained `384095d` manual-repeat failure

[Workflow attempt 2](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/runs/44463021020/attempts/2)
used the unchanged PR merge `2b857405a05dcefa19cc8f6c43102ae55668f5a9` for head
`384095da6b0feec12736fa54fe7d245777e1c37e`. Its
[artifact summary](../evidence/ci-run-44463021020-attempt-2.json) retains the failed job, ZIP digest,
both instrumentation invocations, APK/DEX provenance, and the matched tombstones. The build and
six successful device lanes carried forward from attempt one are not new executions.

| Instrumentation invocation inside the manual rerun | Passed | Process-interruption records | Missing identities |
| --- | ---: | ---: | ---: |
| First invocation | 154 | 1 | 128 |
| After one internal framework recovery | 31 | 1 | 251 |

The first invocation stopped during
`StudioWorkspaceUiTest.workflowPanelsAreReachableFromTheTopBarActions`; the second stopped during
`BrushStudioUiTest.practiceStrokesSurviveTabChangesButNeverApplyBrushSettingsByThemselves`.
Both XML records contain empty failure nodes. The matched process crashes, rather than assertion
messages, explain the interrupted records. The bounded framework recovery completed and changed
the boot identity, but the subsequent instrumentation run also failed. Counts from the two
incomplete invocations must not be combined into a passing inventory.

Both matched tombstones report an actual 16,384-byte page size and a null-pointer `SIGSEGV` at
ART `DoCall<false>+183`, PC `0x6e8007`, fault address `0x10`, Build ID
`e4a3c6c284a8b17d09fd84d661cf181b`. The managed callers differ:
`BrushLibraryStore.<init>` and `AdvancedBrushSettingsPanelKt.rememberImageImport`.
Installed app/test APKs match their builds, and all 18 app DEX entries and seven test DEX entries
match the passing same-revision independent 16 KB lane. These facts identify the failing runtime
instruction and rule out differing captured DEX contents; the underlying crash mechanism remains
unproven. The normal runtime-acceptance report and minified-launch acceptance are absent, so the
lane remains failed.

#### Bounded official runtime review, November 19

Fresh [Google system-image metadata](https://dl.google.com/android/repository/sys-img/google_apis/sys-img2-3.xml)
still lists API35 `google_apis_ps16k;x86_64` revision 5 with the same archive digest as the
[October repair review](../ANDROID15_OFFICIAL_REPAIR_REVIEW_2026-10-24.md).
[SDK tool metadata](https://dl.google.com/android/repository/repository2-3.xml) now lists stable
emulator 37.2.13, build 16740729, which the failed manual rerun already used. Its
[documented October 28 fix](https://developer.android.com/studio/releases/emulator) concerns a
host OpenGL ES resource leak. The available beta 37.3.9 and development/canary 37.4.1
[release entries](https://developer.android.com/studio/releases/fixed-bugs/emulator/37) do not
identify a matching API35 image or ART repair. No matching documented repair was found in this
bounded public-source review; unindexed or private fixes are not ruled out. The artifact summary
records retrieval times, index hashes, and package identities. This supplies no basis to repeat
the retained [collector](../ANDROID15_COLLECTOR_CONTROL_2026-10-24.md),
[core-count](../ANDROID15_CORE_COUNT_EXPERIMENT_2026-10-31.md), or
[runtime/JIT controls](../ANDROID15_RUNTIME_COMPARISON_2026-10-24.md), broaden retry eligibility,
or remove a required lane.

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
