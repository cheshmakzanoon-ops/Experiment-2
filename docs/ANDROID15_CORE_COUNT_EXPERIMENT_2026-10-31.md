# Android 15 / 16 KB: matched CPU-core diagnostic (31 October 2026)

## Reason for the experiment

The mandatory API 35 16 KB `google_apis_ps16k` lanes have intermittently failed in native ART and Android framework code, including identical-source runs. The app-free controls recorded fatal native crashes without ArtFlow installed. A Ubuntu 22.04/JIT-disabled 270-case run passed, but the matching production run later had one first-attempt failure and one first-attempt pass; its manual retry passed. See [issue #38](https://github.com/cheshmakzanoon-ops/Experiment-2/issues/38) and [production run 41136986530](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/runs/41136986530).

This is a controlled *scheduling/concurrency* hypothesis, **not** a claim that CPU cores cause the faults or that changing core count fixes Android. The existing emulator runner normally specifies two vCPUs. We compare one vCPU to two, with identical Android 15 system image target, 16 KB page request, Ubuntu 22.04 host, 4096 MiB guest memory, disabled ART JIT and application source.

## Fresh first-attempt baseline (31 October 2026)

The [post-merge main CI run #327](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/runs/41146206986) on `217328f4` has already produced a failed **independent API35/16 KB repeat** job ([job 124375419844](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/runs/41146206986/job/124375419844)). It recorded **130 passes, one failure and 139 missing tests of 270**, with no minified launch. The failure occurs in AndroidX `ComponentActivity` construction: an anonymous `ActivityResultRegistry` implementation cannot be cast to its base type, causing `SelectionCanvasTestActivity` to crash at startup. The same emulator session also logged a native `system_server` SIGSEGV and another native search-process crash with ART interpreter frames *before* the test crash. This is strong evidence of runtime corruption, but not proof that all three events have one cause, nor proof that the app cannot have a separate defect.

The same run's [API36 tablet job](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/runs/41146206986/job/124375419842) also failed independently: **269/270 passed**, with `BrushAttributeUiTest#studioRetainsTheChosenGroupAcrossTabsAndPublishesOnlyOnUse` reporting `ComposeRuntimeError` for concurrent `recordModificationsOf` and `applyChanges`. That tablet failure is **not** evidence of the API35 16 KB image fault and must be triaged separately rather than hidden by this experimental workflow.

Run #327 completed with **six of eight jobs passing on the first workflow attempt**. The primary API35 / 16 KB lane passed 270/270, zero failures/skips and the minified launch without framework recovery, while the independent repeat and the separate API36 tablet failed as detailed above. The paired core-count comparison is motivated by a possible scheduling/concurrency sensitivity, not by an established repair. Preserve the failing first-attempt results; never count a rerun as a successful original execution.

## GitHub Actions experiment

[Android 15 16 KB CPU-core reliability comparison](../.github/workflows/android15-16kb-core-count-control.yml) runs independently of the eight required production CI jobs:

1. **App-free controls** on separate hosted virtual machines at 1 and 2 vCPU. Neither installs ArtFlow. Each verifies its guest-reported vCPU count, launches stock Android Settings 150 times and preserves full logcat, image/boot identity and native tombstones. Native crash totals are evidence even when the job exits successfully.
2. **Full application acceptance** on separate fresh machines at 1 and 2 vCPU. Both verify the guest-reported vCPU count, build the unchanged application/test APKs, execute every declared instrumentation case, enforce the API/page-size/runtime/ABI checks, verify the minified launch and preserve original APK/DEX provenance and crash artifacts.
3. **First-attempt reliability** is separately enforced: a recovered run cannot be counted as a clean first-attempt pass. A 270/270 passing rerun does not erase a preceding failed attempt.

The experiment neither alters the mandatory `.github/workflows/android-ci.yml` matrix nor removes tests, changes the existing narrowly permitted framework-recovery classifier or qualifies a release build.

## Interpretation and follow-up

- A single pass in either arm is not enough to prove reliability. Compare crash provenance (app versus unrelated system process), emulator/ART/image fingerprints, test identities, recovered attempts, and minified launches.
- If the 1-vCPU configuration is promising, reproduce it on further **fresh** hosted VMs before considering any production workflow change.
- If both arms fail intermittently, document that core count is not a supported repair and pursue a different system-image/runtime or supported physical 16 KB device. An alternate platform cannot silently substitute for the required Google APIs API35 lane.
- Remain mindful that Android's [16 KB compatibility documentation](https://developer.android.com/guide/practices/page-sizes) also describes Cuttlefish, but a different Android release/device image is *additional* qualification, not a replacement for the API35 target.

**Status:** workflow prepared, results pending. Once the run completes, append the exact SHA, run URL, job IDs, initial-attempt results, output hashes and remaining uncertainty.
