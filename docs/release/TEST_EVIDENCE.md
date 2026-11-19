# Test and build evidence

## November 18 integration

Implementation is being validated on the branch containing PR #40. New build/emulator results
must be recorded below with the source revision and workflow URL before claiming qualification.
Local Android builds are unavailable in this session; actual Gradle and emulator execution uses
GitHub Actions. Pending checks are not passes.

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
