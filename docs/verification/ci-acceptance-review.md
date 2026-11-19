# CI acceptance review — wave 16

## Confirmed gap and repair

The full device runner preserves instrumentation's original exit status when diagnostic APK
capture fails. That behavior is correct for failure attribution, but the coverage summary did
not inspect the capture result. A controlled invocation with passing XML, measured runtime and
minified launch returned success even when no installed-APK provenance directory existed.

The required Android CI summary now has a separate installed-APK gate. Before instrumentation,
the shell exclusively records the generated attempt token; afterward it retains the original
exit status. Acceptance compares these receipts with both the collector manifest and the raw
runner manifest. Both installed app/test APK measurements must match freshly hashed current
build outputs, including size and DEX inventory. Missing, ambiguous, failed, stale or inconsistent
captures fail the summary independently of the original instrumentation result. Original failed
APK payloads must remain present and unchanged. Successful payloads are still removed by the
existing collector; their measured hashes and original manifests are retained.

The summary accepts only one invocation or the already-authorized single Android 15 / 16 KB
framework recovery. A recovery requires different attempt tokens, the original failed status,
its retained result/log evidence, and the unchanged crash classifier's approval. It reports
`frameworkRecovery` and `firstInvocationPassed` explicitly and labels a recovered result in the
readable summary. An application assertion is never made retryable by this change.

This establishes consistency between measurements made in the trusted CI execution and its
build outputs. It does not authenticate externally supplied files or establish reproducible
builds, publisher signing or physical-device qualification.

## Existing gates retained

- The Android workflow retains one build job and all seven device configurations, with no
  continue-on-error or fail-fast cancellation of other lanes. Source inventory at this review
  contains 320 debug identities; it is calculated from declarations, not a hard-coded lower limit.
- Missing/duplicate/unexpected test identities, failure/error records, and undeclared skips fail
  the summary. Only the two documented API-limited cases may skip below their required API.
- All five offline Gradle provenance fixtures have their own required invocation after Gradle
  provisioning; their earlier Python-only discovery skips cannot substitute for this step.
- The optimized build contract depends on generated exact test ABI rules and all six real-R8
  fixtures. Its runtime inventory remains 43 cases, one invocation, zero permitted skips, with
  separate normal-release payload and installed-package upgrade gates.
- The public production decision still requires publisher, store and physical-device evidence
  described in [release sign-off](../release/RELEASE_SIGNOFF.md).

## Executing the final repeated-run contract

Freeze and merge the final source, then qualify that exact main SHA. Record the checkout revision
from the retained source evidence; a pull-request run's metadata head SHA is not automatically
the merge commit checked out by Actions.

After the first successful eight-job main workflow, rerun its build job. GitHub also reruns jobs
that depend on it, so the seven `needs: build` device jobs execute again at the original SHA.
Verify all eight newly executed jobs through the specific attempt's jobs endpoint. A successful
rerun of only one device lane does not count as another complete matrix.

After two consecutive complete green attempts, rerun the primary API 35 / 16 KB job to obtain its
third consecutive execution at the same SHA. Keep the independent repeat lane distinct. Preserve
all original attempts and disclose any internal framework recovery separately. Jobs carried
forward from an earlier attempt are not additional executions. Use exact artifact IDs, digests,
attempt numbers and timestamps when retrieving evidence; artifact names alone can be repeated
across reruns. GitHub documents both unchanged SHA/ref and dependent-job rerun behavior in
[Re-running workflows and jobs](https://docs.github.com/en/actions/how-tos/manage-workflow-runs/re-run-workflows-and-jobs).
The official artifact client also documents duplicate names across reruns in its
[artifact lookup contract](https://github.com/actions/toolkit/blob/main/packages/artifact/src/internal/client.ts).

No source changes should follow final qualification while retaining its pass claim. A new
source revision needs its own evidence; these instructions do not count as executed acceptance.

## Validation

All 126 focused host cases passed: 12 new provenance regressions, 19 device harness cases,
9 coverage-parser cases, 18 collector cases, 28 unchanged crash-classifier cases, 37 optimized
verifier cases and 3 workflow-pin cases. The new tests exercise missing captures despite otherwise
green results, changed builds and installed bytes, original-manifest/token substitutions, missing
failed-attempt APKs, diagnostics errors and valid/invalid recovery. Shell syntax, scoped diff
checks and the inventory-only import used by diagnostic workflows also passed. No Gradle or
Android execution was performed by this assignment. Transient logs are under `tooling/w16-ci` in
the shared review workspace, including the preserved old false-pass reproduction.
