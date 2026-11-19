# Android 15 / 16 KB kernel comparison — 19 November 2026

## Status and revision boundary

**Both independent kernel-control jobs qualified on 19 November 2026.**
[Control run 44494467700](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/runs/44494467700)
tested `a617da6d2447f5a3fb0bdb899971f3d2cfc66018`, tree
`3eaef32a30d21dffd6419ce6c6f23c009d244c09`, against application baseline
`a0456ea2d5ea2a98eea4337f42c4e12f0a550cb4`. Original artifacts were independently
verified. The baseline's failed required Android 15 / 16 KB results remain failed.
These controls support the CI configuration adopted below; **qualification of the final
main revision is still pending** and is not supplied by this control run.

The [control workflow](../.github/workflows/android15-kernel-control.yml) starts two independent
Ubuntu 22.04 jobs and rejects changes to the application, tests, or Gradle inputs relative to
that main revision. It preserves the complete **283-test** inventory. The changes concern the
emulator kernel and a separate correction to the CI readiness gate, not application behavior.

| Independent control | Exact test identities | Failed / errors / skipped / missing / unexpected / duplicate | Instrumentation invocations / recoveries | Minified launch |
| --- | --- | --- | --- | --- |
| [Trial 1, job 135458026750](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/runs/44494467700/job/135458026750) | 283 / 283 passed | 0 / 0 / 0 / 0 / 0 / 0 | 1 / 0 | PASS |
| [Trial 2, job 135458027269](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/runs/44494467700/job/135458027269) | 283 / 283 passed | 0 / 0 / 0 / 0 / 0 / 0 | 1 / 0 | PASS |

The downloaded artifact ZIP sizes and SHA256 digests match GitHub's records:

| Artifact | Bytes | SHA256 |
| --- | ---: | --- |
| [Trial 1: 14517315284](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/runs/44494467700/artifacts/14517315284) | 33001957 | `74c90034377d40f8b89359a8a5972906cc4301f9a06eb104bb25ec27305ceea2` |
| [Trial 2: 14516415481](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/runs/44494467700/artifacts/14516415481) | 34060528 | `beea6b69ecbe5e05258b8c5fce4bf0a0eea30115a0f5e972965c89f1717a8ea2` |

In each artifact, all 1,026 source blobs and file modes match the published control tree.
Application and build inputs are unchanged from the baseline. Complete runner-captured hashes
match installed APKs to their corresponding builds, and all 25 application/test DEX hashes
match the retained original-main manifests. The success path retains these hashes rather than
the installed APK bytes; this is the limit of the installed-package evidence.

### Observed runtime in both controls

Both actual instrumentation processes measured **API35 and 16,384-byte pages** with the unchanged
fingerprint listed below. The retained kernel files were independently rehashed and match the
pin. Both guests ran the expected donor release, loaded the same 54-module set, and showed no
module symbol, version, or loading errors in the retained evidence. Their boot IDs differ,
consistent with the two independent jobs.

The instrumented and minified app processes, configured zygote, and system server logged
`CollectorTypeCC`. No extra collector override was applied. Initial boot cache metadata records
`dalvik.vm.usejit=true`, but the later configured zygote explicitly consumed `-Xusejit:false`
before application startup: PID 2428 at 09:21:26.554 UTC in trial 1 and PID 2648 at
09:19:11.561 UTC in trial 2. The disabled-JIT assertion rests on those later startup records.

No native fatal, unhandled Java exception, ANR, Scudo/ART abort, kernel fault, or previously
observed framework-corruption signature was found in the retained preflight kernel/logcat,
instrumentation/final logcat, and serial/job logs. This is a finding about those saved logs;
successful instrumentation does not collect the full native-tombstone bundle. A warning about
missing `odrefresh` metrics XML concerned metrics collection and did not record an ART boot-image
failure. These observations establish compatibility in the tested configuration, not universal
fault absence or the specific cause of the earlier ART interruptions.

## Failures motivating the comparison

[Required main run 44482684355](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/runs/44482684355)
passed its build job and five device configurations. Both API35 / 16 KB jobs failed. The retained
PID-matched tombstones identify three native ART SIGSEGV interruptions, all with ART Build ID
`e4a3c6c284a8b17d09fd84d661cf181b` and 16,384-byte pages:

| Job / instrumentation invocation | Passed | Interrupted record | Missing of 283 | Native frame zero |
| --- | ---: | ---: | ---: | --- |
| [Primary](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/runs/44482684355/job/135425817729), first | 193 | 1 | 89 | `Class::FindClassMethod+3876` |
| Primary, after its one existing framework recovery | 34 | 1 | 248 | `NterpGetStaticField+294` |
| [Independent repeat](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/runs/44482684355/job/135425817721), first; no recovery | 37 | 1 | 245 | `MarkCompact::ScanObject<true>+36` |

Neither job reached minified-launch acceptance or the normal completed runtime report. Tombstone
page-size evidence does not substitute for that report. Installed APKs and their DEX entries were
independently checked against the corresponding builds. The interrupted XML records have empty
failure markers; their associated test names do not establish application assertion defects or
the underlying native cause. Original artifacts: [primary 14511871218](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/runs/44482684355/artifacts/14511871218)
and [independent 14510078986](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/runs/44482684355/artifacts/14510078986).

The [same-source one/two-core control 44482684453](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/runs/44482684453)
also failed both full-suite arms. Each used one internal recovery:

| Cores | First invocation: passed / failed / missing | Recovered invocation: passed / failed / missing | Observed interruption |
| --- | --- | --- | --- |
| [1](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/runs/44482684453/job/135418475085) | 260 / 1 / 22 | 259 / 1 / 23 | ART `DoCall<false>+183`, then `MarkCompact::ScanObject<true>+36`. |
| [2](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/runs/44482684453/job/135418473885) | 4 / 1 / 278 | 70 / 1 / 212 | ART `DoCall<false>+183`, then a Java `ReferenceQueue.enqueuePending` null-reference failure; no second native stack is claimed. |

Neither arm passed the full suite or minified launch. The one-core invocations did measure
16 KB pages before their later failures. The separate two-core app-free collector recorded three
native SIGSEGVs without ArtFlow installed; its green job means collection completed, not a clean
runtime pass. Changing core count has not established a repair.

## Separate readiness defect and correction

The previous `wait_for_system_settle()` could exhaust every boot/service check and still return
success through its final successful `sleep`. A local fake-adb probe reproduced this without an
emulator. [run-device-tests.sh](../.github/scripts/run-device-tests.sh) now requires boot completion
and responsive package, activity, and settings services within a bounded wait, and exits nonzero
when readiness is not achieved. This applies before initial instrumentation and after recovery;
broadcast-idle remains advisory. Regression tests cover unavailable services and prove that
instrumentation is not launched after rejection.

This fixes a concrete harness error. It does **not** explain the three main ART crashes. Because
the diagnostic also includes this correction, a passing control alone cannot attribute causality
solely to the kernel change.

## Why a kernel-only comparison is justified

The observed stock API35 kernel is `6.6.50-android15-8-g8adecb593e9b-ab12525588`.
The donor is the official API36 / 16 KB package's stock
`6.6.66-android15-8-gd0c43a640eab-ab13812146` kernel. Android Emulator documents the
[`-kernel` override](https://developer.android.com/studio/run/emulator-commandline), separately
from `-system` and `-ramdisk`. Both exact source configurations declare `BRANCH=android15-6.6`
and `KMI_GENERATION=8`: [old source](https://android.googlesource.com/kernel/common/+/8adecb593e9b/build.config.common)
and [donor source](https://android.googlesource.com/kernel/common/+/d0c43a640eab/build.config.common).

[AOSP's kernel compatibility contract](https://source.android.com/docs/core/architecture/kernel/android-common)
supports updates within this family. Its [stable-KMI constraints](https://source.android.com/docs/core/architecture/kernel/stable-kmi)
also require compatible configurations, toolchains, and module symbols. Matching family and
generation justify testing; they do not certify this mixed SDK-image configuration.

The donor source includes the [December 2024 UFFD guard](https://android.googlesource.com/kernel/common/+/38447e018c92f6ae182067a02a6954fa92b33a73)
missing from the old kernel. However, the [August 2026 re-enabling change](https://android.googlesource.com/kernel/common/+/7aa7a88586d9f98dc3f97c55ec4e06961e2dabd0)
disputes the original incompatibility rationale. Earlier [forced-CC controls](ANDROID15_COLLECTOR_CONTROL_2026-10-24.md)
also failed on the old kernel. This is a broader kernel-upgrade hypothesis, not proof that UFFD,
CMC, or one application operation caused the faults. Prior [runtime/emulator controls](ANDROID15_RUNTIME_COMPARISON_2026-10-24.md)
and the [bounded official repair review](ANDROID15_OFFICIAL_REPAIR_REVIEW_2026-10-24.md) remain relevant.

## Exact donor pin and retained API35 identity

The machine-readable pin is [config/ci/android15-16kb-kernel.json](../config/ci/android15-16kb-kernel.json).
The [official SDK index](https://dl.google.com/android/repository/sys-img/google_apis/sys-img2-3.xml)
retrieved on 19 November identifies:

| Field | Value |
| --- | --- |
| Donor package | `system-images;android-36;google_apis_ps16k;x86_64`, revision 7 |
| Archive | [x86_64-ps16k-36_r07.zip](https://dl.google.com/android/repository/sys-img/google_apis/x86_64-ps16k-36_r07.zip) |
| Archive bytes / official SHA1 | `1849428145` / `dd783282e84bf475a02eba6777c79fc5695e1583` |
| Archive SHA256 measured by both full-download preparations | `56005e428f0d30105789bde96f60bbd32c5c62e710321abd7c10f9e591889ff7` |
| Only extracted image member | `x86_64/kernel-ranchu` |
| Kernel bytes | `20489216` |
| Kernel SHA256 | `3f2c91f26b27e7c3a6f4a158383d7fb9a4cc87a2c321fc3ad77dc833b9e61a86` |

Discovery extracted that member through HTTP ranges, verified its ZIP CRC and SHA256, and checked
the actual bzImage version header. It did not download or independently verify the complete ZIP.
The [CI preparation helper](../.github/scripts/prepare_emulator_kernel.py) instead downloaded the
whole archive in each control, checked its pinned size/SHA1, and recorded the same measured
SHA256 shown above before extracting only the kernel. The original donor ZIP was not retained
inside the control artifacts; its full-download digest is the preparation helper's recorded
measurement. The main-adoption pin now also requires that SHA256. Cached kernels must pass
byte-count, SHA256, and release checks again.

The launched AVD remains API35 `google_apis_ps16k;x86_64` revision 5, with its original system,
vendor, ramdisk, and initial data image. Ubuntu 22.04, two vCPUs, 4096 MiB guest RAM, and disabled
ART JIT are retained. No forced-collector override is added. Expected userspace fingerprint:

```text
google/sdk_gphone16k_x86_64/emu64xa16k:15/AE3A.240806.043/12960925:userdebug/dev-keys
```

## Acceptance and continuation

This change integrates the qualified configuration into the two API35 / 16 KB lanes of
[Android CI](../.github/workflows/android-ci.yml): Ubuntu 22.04, the verified donor kernel passed
through `-kernel`, and the existing API35 userspace. Kernel preparation and runtime preflight
apply only to those two lanes; the other five device configurations remain unchanged. The
readiness correction applies to initial instrumentation and any existing permitted recovery.
Application code, test inventory, minified-launch acceptance, and the recovery limit are unchanged.

[Runtime preflight](../.github/scripts/verify_emulator_kernel.py) checks the prepared kernel hash,
actual running release, SDK35, fingerprint, emulator/debuggable identity, and module-load errors.
It retains kernel logs, module state, boot ID, ART cache information, and collector lines, including
available failure evidence. Missing collector lines are a warning to inspect, not a claim that a
collector switch was verified. Page-size acceptance remains the real instrumentation process's
`RuntimeEnvironmentTest`, not a requested emulator option or optional shell `getconf` output.

Both trials passed all 283 expected identities with no failed, errored, skipped, missing,
unexpected, or duplicate cases; verified API35, 16384-byte pages, runtime/ABI and APK provenance;
and completed the unchanged minified launch. Neither used internal recovery. The control rejects
an internally recovered run as first-attempt reliability evidence, even if its final invocation
passes. Preserve every attempt.

A qualified control permits this reviewed CI change; it does not itself accept main or prove the
native root cause. The next required evidence is the complete eight-job workflow on the final
main revision, followed by the unchanged repeatability checks on that same revision. The
[release repeatability contract](release/RELEASE_SIGNOFF.md) still requires
two consecutive all-green eight-job workflows and three consecutive green API35 / 16 KB lane
executions on the final source SHA, with recovery history explicit. Physical-device and production
release requirements remain separate.

If a later execution fails, diagnose its exact boot/kernel/module/runtime/test evidence before changing
another variable. Do not blindly rerun unchanged jobs, decrease coverage, substitute API36 or
4 KB acceptance, broaden recovery, or erase previous failures to obtain green status.
