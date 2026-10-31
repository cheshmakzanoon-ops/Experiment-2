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

## Observed results — first workflow attempt

The diagnostic [GitHub Actions run 41150385515](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/runs/41150385515) executed on main commit [`dd44cf7701192aa1bdecdb171d81b578714d31f1`](https://github.com/cheshmakzanoon-ops/Experiment-2/commit/dd44cf7701192aa1bdecdb171d81b578714d31f1), with both verified guest CPU counts and all other declared inputs matched. Its overall conclusion is **failure**, because the 1-vCPU full suite needed framework recovery; the 2-vCPU full suite passed without recovery. All four jobs preserved evidence in GitHub Actions artifacts.

| Guest cores | App-free Settings control (150 cycles) | Full unchanged suite: **first attempt** | Full-suite job |
| --- | --- | --- | --- |
| **1** | Zero counted native SIGSEGVs; **one fatal exception in Android system process**; no ArtFlow installed. [Job 124385262121](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/runs/41150385515/job/124385262121). | **165 passed, one failed, 104 missing** of 270; instrumentation process crashed during `ClipboardTransactionDeviceTest#cutPasteAtLayerCapacityKeepsTheSavedSourceAndDoesNotAddHistory`. Recovery rebooted the emulator and a subsequent **270/270** run verified 16,384-byte pages and a minified launch, but did **not** turn the first attempt into a pass. | **Failure**, explicitly rejected recovered evidence. [Job 124385262126](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/runs/41150385515/job/124385262126). |
| **2** | Zero counted native signals, system-process or Java fatal events in the control window; no ArtFlow installed. [Job 124385262172](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/runs/41150385515/job/124385262172). | **270/270 passed**, zero failures/errors/skips, verified API 35, **16,384-byte** page size, x86_64 and arm64-v8a runtime evidence, and minified launch. No framework recovery directory. | **Success**, first attempt. [Job 124385261951](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/runs/41150385515/job/124385261951). |

The app-free controls report counts within their captured window, not proof of absent future faults. The 1-core system-process fatal is independent of the application's installation. The 1-core recovered suite's test result is useful to verify unchanged test inventory, but **cannot qualify first-attempt reliability**. The different core-count outcomes are one trial per configuration, not a causal proof of CPU sensitivity.

### Preserved artifact archives

GitHub reports these **uploaded artifact ZIP SHA-256 digests**; they are not individual APK/DEX hashes. Each full-suite artifact retains original and (when present) recovered runtime/APK/DEX provenance as well as test reports, logcats and tombstones.

| Arm | GitHub artifact | GitHub-reported ZIP SHA-256 |
| --- | --- | --- |
| 1-core app-free | [13022642762](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/runs/41150385515/artifacts/13022642762) | `68e8239eb318a000e91ca4ae10eaf540f557d58aa83cd9501048f394ae597ead` |
| 2-core app-free | [13023415582](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/runs/41150385515/artifacts/13023415582) | `62bc3de8f50176e195a4aa66e177d0e470b87d2f07d8e7b4bce128493762e01b` |
| 1-core full suite | [13024251519](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/runs/41150385515/artifacts/13024251519) | `ac26435b79ee5f5c0bffe3177f37601d4c102e4b54d3cdebeb3bdbbb5824ae9f` |
| 2-core full suite | [13023059850](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/runs/41150385515/artifacts/13023059850) | `604d058350da1200e462d7683aa23ac600114b9ce1da81affc771d006228bedc` |

These artifacts have 14-day retention (through 14 November 2026 as scheduled by GitHub).

### Independent production-matrix check, same source

The unchanged required [main Android CI run 41150385563](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/runs/41150385563) on the same `dd44cf7` source **failed on its first attempt**: six of eight jobs passed. The **primary** API35/16KB lane passed 270/270, confirmed 16 KB and minified launch. Its independent **repeat** lane crashed after **41 passes, two failures and 227 missing tests**; the errors included a coroutine `VerifyError`, and unrelated Google-app and ArtFlow native SIGSEGVs were logged. The separate **API36/16KB** lane stopped after **six passes, one failure and 263 missing tests** with a Compose layout reentrancy error. This API36 issue needs its own investigation and should not be attributed automatically to API35 ART.

**Decision:** do **not** change the required production lane to one vCPU, reduce coverage or treat a recovered run as a green first attempt. Neither tested core count establishes reliability, and the issue remains open in [#38](https://github.com/cheshmakzanoon-ops/Experiment-2/issues/38). A genuinely different supported 16 KB runtime/device or validated upstream emulator fix remains the next reliability investigation; application-side Compose failures deserve separate triage. No emulator was installed in the local workspace.
