# Android 15 runtime comparison — 24 October 2026

**The Android 15 / 16 KB acceptance failure is unresolved.** Both the existing JIT-disabled
configuration and a controlled default-runtime comparison encountered native or system-process
crashes. Changing JIT mode is not an established repair, and the captured failures do not establish
an application or emulator root cause. A separate standard-image API 35 / 4 KB control completed
all 239 cases and minified launch on its first attempt. That result adds coverage without resolving
the failed 16 KB requirement.

## Source identity and controls

The application candidate is `e0470f0861681d2a84f1383f6724e427a1eaf67c`, tested by
[main run 306](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/runs/40071698959).
The separate [default-runtime run](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/runs/40075711665)
uses workflow commit `375c5dbbbc98e4e997b889b719d3933cd7c4f1c5` on
`codex/android15-default-runtime-e0470f0`. Its only source-tree difference is its added workflow.

Every diagnostic job checks that all tracked application, test, build and device-script files match
the application candidate. The unchanged identities are:

| Input | Git object / digest |
| --- | --- |
| Application tree (`app`) | `52de01973c7d697ba2683d0fb3b3c9066a8598d7` |
| Device scripts tree (`.github/scripts`) | `329021722418681af5a1183153dd258652276b26` |
| Instrumentation source tree (`app/src/androidTest/java`) | `3151e67c17c9951ffa2d742dfaf7a291a8f4fb73` |
| Frozen JSON manifest SHA-256 (revision and 239 identities) | `e8637f3f8751a71158a4ad60a5719aa13d3f7bd60b3ca312f8464eca323dd60c` |

That digest covers the complete UTF-8 JSON file, with `revision` first and `tests` second,
the test identities sorted, two-space indentation and one final newline. It is not an
identity-only digest; changing the recorded revision also changes the full-file hash.

The two default-runtime lanes use independent fresh hosted VMs and AVDs, the same API 35 / 16 KB
image and emulator options as main, and the same complete-coverage and minified-launch gates.
The intended test-configuration difference is the runtime mode; independently allocated hosts
are not a controlled identical machine. The existing runner does not retry instrumentation in
default mode. Neither diagnostic lane was rerun.

## Completed 16 KB observations

Attempts are counted separately. Combining cases from a failed attempt with a later attempt does
not produce a complete accepted suite.

| Run / configuration / attempt | Passed | Failed cases | Unreported of 239 | Minified launch |
| --- | ---: | ---: | ---: | --- |
| Main 306 API 35, JIT disabled, first attempt | 2 | 1 | 236 | Not reached |
| Main 306 API 35, JIT disabled, one automatic retry | 239 | 0 | 0 | Passed |
| Main 306 independent API 35, JIT disabled, first attempt | 169 | 1 | 69 | Not reached |
| Main 306 independent API 35, JIT disabled, one automatic retry | 0 | 1 | 238 | Not reached |
| Default-runtime A, one attempt | 24 | 1 | 214 | Not reached |
| Default-runtime B, setup failure before any case | 0 | 0 | 239 | Not reached |

Default-runtime B is a failed job with no executed test cases, not a passing zero-test suite.
Neither incomplete diagnostic lane meets the strict coverage requirement. No assertions,
supported case identities, expected page sizes, or skip rules were relaxed.

### Existing JIT-disabled lanes

The [main API 35 lane](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/runs/40071698959/job/120809647988)
first stopped in `arch_disk_io_1` at 07:12:23.072 UTC with SIGSEGV and
an ART interpreter `DoCall<false>` frame. Its XML-associated case was
`AnimationPlaybackUiTest#teardownWaitsForAnAtomicWriteAlreadyInFlight`. The available evidence
does not localize the crash to that test's setup or body or establish a cause. The failure matched
the existing classifier, permitting one recovery and retry; that final attempt passed all 239
cases and minified launch. Matching the classifier does not establish a platform root cause.

The [independent repeat](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/runs/40071698959/job/120809647935)'s
first SIGSEGV was in `HeapTaskDaemon` at 07:37:50.142 UTC, with
`MarkCompact::ScanObject<true>` at frame zero. The mask test associated with its empty XML failure
did not start until 07:37:50.394, 252 ms later. Its name is therefore not evidence that mask
cancellation caused the native crash.

The repeat's final attempt lost `system_server` at 07:38:56.158. A subsequent
`PackageProperty.mApplicationProperties` `NoSuchFieldError` and widespread `DeadSystemException`
occurred during activity construction, before a `CREATED` or `RESUMED` transition in that case's
log. The native dump did not recover a final-attempt frame zero. This final failure must not be
described as an ART-confirmed native stack. The lane correctly remained failed.

### Default-runtime lane A

In [lane A](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/runs/40075711665/job/120818474009),
the interrupted case was
`BrushStudioTabsTest#labelsAndSelectionSurviveRightToLeftAndFontChanges`, which started at
07:57:31.658. The application process received SIGSEGV on its `HiltTestRunner` thread at
07:57:33.955. The native dump starts at PC zero (`<unknown>`) and includes JIT-compiled
`libcore.io.IoBridge.open` and `FileOutputStream` constructors, then an ART invocation stub.

Those file-output frames and the missing `studio-tabs-rtl.png` are consistent with the test's
evidence-writing path, but higher callers are absent. They do not prove that exact operation
caused the crash, nor establish a UI clipping or selection assertion failure. All 25 reported
identities belong to the unchanged source manifest and are unique.

Eleven original screenshots survived. They are pixel-identical to main run 306's API 35 captures
outside the system bars. This is partial visual evidence only; it does not accept the failed lane.
That review also exposed the preexisting compact Reference-panel/sidebar overlap in both runs,
which is a separate, concrete editor layout defect.

### Default-runtime lane B

In [lane B](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/runs/40075711665/job/120818474217),
`system_server` received SIGSEGV at 07:55:39.385 UTC. Its dump reports a 16,384-byte page size
but contains only PC zero (`<unknown>`), with no named ART function. APK installation subsequently
failed during `install-commit` with a broken package-service pipe. The captured logs contain no
`TestRunner` event or application-process start. There is no interrupted test identity in this lane.

Neither default-runtime lane reached `RuntimeEnvironmentTest`, elapsed repository stroke timing, or the
minified launch. Lane A's application tombstone and lane B's system-server tombstone report
16 KB pages; these must be distinguished from a completed application runtime-measurement test.

## Runtime and infrastructure evidence

Both default-runtime jobs recorded `dalvik.vm.usejit=true`. Their initial zygotes also logged
`-Xusejit:true`, and subsequent compiler activity confirms JIT execution. This is evidence of
the running mode, beyond merely requesting a property. Default-runtime B failed without the
JIT-disable `stop` / `setprop` / `start` sequence used by the main API 35 lanes.

The completed 16 KB comparisons used Android Emulator **37.2.12.0**, build **16428233**, on
GitHub's Ubuntu **24.04.5** image **20261012.334.1**. The system image is
`system-images;android-35;google_apis_ps16k;x86_64`, revision **5**, fingerprint:

```text
google/sdk_gphone16k_x86_64/emu64xa16k:15/AE3A.240806.043/12960925:userdebug/dev-keys
```

Google's same-target SDK metadata inspected on 24 October 2026 still offers revision 5, and its
archive metadata matches that fingerprint. The stable emulator documented on that date was
already installed.
These observations do not identify an available update that repairs the failures.

Sources: [Android emulator release notes](https://developer.android.com/studio/releases/emulator),
[official Google APIs image index](https://dl.google.com/android/repository/sys-img/google_apis/sys-img2-3.xml),
and [AOSP JIT configuration](https://source.android.com/docs/core/runtime/jit-compiler).

## Completed standard-image control

The separate [standard-image control](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/runs/40076170067)
uses workflow commit `87ecc9cf2de8a6e68ebb90a36f0950f8057ad1f3`, the same application candidate,
and the same source-comparison gate, on branch `codex/android15-standard-image-e0470f0`.
Its only tracked-file difference from the candidate is the added diagnostic workflow. The
[completed job](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/runs/40076170067/job/120819868438)
ran API 35 `google_apis;x86_64` with default runtime and no instrumentation retry.

**All 239 unique expected identities passed: zero failures, errors, skips, missing cases,
unexpected cases, or duplicate identities.** There was one actual instrumentation invocation,
from 08:03:29.882 to 08:07:40.147 UTC, approximately 250.3 seconds. The application runtime test
measured SDK 35 and a 4,096-byte page size; these were not inferred only from workflow inputs.

### Actual runtime and image comparison

The job recorded `dalvik.vm.usejit=true`. Its captured logs do not contain the initial zygote
`-Xusejit` option line, so no such line is asserted for this lane. They do contain explicit JIT
activity in the application process: at 08:04:00.623, PID 2808 logged allocation of 54 KB for
compiled `CanvasScreen$16.invoke` code, alongside other application-method compilation. This
supports that JIT was active during the test run.

The standard image was `system-images;android-35;google_apis;x86_64`, revision **9**, with
the fingerprint measured by the application:

```text
google/sdk_gphone64_x86_64/emu64xa:15/AE3A.240806.043/12960925:userdebug/dev-keys
```

Both image packages use Android build ID `AE3A.240806.043`, incremental build `12960925`,
and the 2024-09-05 security patch. Their official archive metadata records different builds on
23 January 2025: 17:05:06 UTC for the standard image and 17:09:06 UTC for the 16 KB image.
The product identifiers (`sdk_gphone64_x86_64` / `emu64xa` versus
`sdk_gphone16k_x86_64` / `emu64xa16k`) and package revisions differ. These metadata were read
from the official image archives referenced by the SDK index above; they do not demonstrate
identical native binaries with only a page-size switch.

The control used the same emulator version/build and Ubuntu runner-image version reported
above, but a separately provisioned VM. Its runner provisioner was `20261021.618` in `eastus2`,
while default-runtime A/B used `20261012.601` in `westus` / `centralus`. A single passing
standard-image run therefore does not isolate page size, product configuration, or host
conditions as the cause of the 16 KB failures.

No `Fatal signal` or `FATAL EXCEPTION` marker occurs in either of this control's captured
`instrumentation-logcat.txt` or `device-logcat.txt`. This is a statement about the retained log
windows and markers searched, not a general guarantee that the environment cannot crash.

### Minified launch, screenshots and timing

The minified launch passed after the complete instrumentation suite. The recorded cold launch
reported `Status: ok`, `TotalTime: 1145` ms and `WaitTime: 1149` ms. The process survived the
10-second check, its window remained present, and the screenshot was retained. This is the
release-equivalent benchmark variant with R8 and resource shrinking, signed with the debug
key for testing; it is not a publisher-signed production-release acceptance.

The artifact retains 32 original test PNGs, including RTL and fractional-density tabs. A focused
comparison of 14 images found identical pixels outside the status/navigation strips against
main run 306's final API 35 captures: RTL/scaled/fractional tabs, both Count disclosures, guide
light/focus states, layers, workspace light/dark/focus states, both large-text tool views, and
the artwork workflow. The RTL, Count-set and guide-light originals were also opened for visual
inspection. This establishes agreement between those Android captures, not agreement with
Procreate or the absence of other UI defects.

The elapsed repository preview timing diagnostic recorded:

| Brush | Preview p50 | Preview p95 | Preview maximum | Commit and preview |
| --- | ---: | ---: | ---: | ---: |
| Pen, 12 px | 1.1 ms | 13.4 ms | 24.7 ms | 311.0 ms |
| Textured, 80 px | 5.2 ms | 6.5 ms | 17.3 ms | 101.0 ms |
| Soft airbrush, 300 px | 60.6 ms | 83.0 ms | 92.7 ms | 656.3 ms |

`StrokeTimingDeviceTest` measures `compositePreviewFrame()` after each of 80 samples on a
2,048 × 2,048 canvas. The per-sample interval excludes `continueStroke()` itself. Commit timing
includes `endStroke()` and a composite preview. Neither measurement includes GPU presentation,
physical stylus input or display latency; the assertion requires only a positive preview p50.
These are diagnostics, not a performance threshold or evidence of Procreate performance parity.

## Original artifact identities

| Artifact | ID | Bytes | SHA-256 |
| --- | ---: | ---: | --- |
| [Main API 35](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/runs/40071698959/artifacts/12552024568) | 12552024568 | 11,437,191 | `9644637c55de955204354a26c0c0156ef9c49c465cfe836b094144df2b5a2d82` |
| [Main independent API 35](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/runs/40071698959/artifacts/12552742780) | 12552742780 | 6,866,860 | `d8f37b99060f462dc0891e3c2dbad6d370c60b08c17bf58de507a11856f0a621` |
| [Default-runtime A](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/runs/40075711665/artifacts/12553082878) | 12553082878 | 2,797,150 | `14365f5cd4c1e5fe2125c17b971a662d27ae20685c15aafcb95da7045ee81db1` |
| [Default-runtime B](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/runs/40075711665/artifacts/12553122644) | 12553122644 | 1,205,815 | `35d6f46053e84d2940dc505741b9ad139e0ae371c27fa19c1fee38d74009715c` |
| [Standard-image 4 KB](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/runs/40076170067/artifacts/12553617716) | 12553617716 | 8,749,621 | `dfe006e8b58990f4cf27a9502fe30fae701e334d6a3e5186ecbcb7ab9c66b4f7` |

The original ZIPs were downloaded once, with byte lengths and SHA-256 values verified against
GitHub's artifact metadata before extraction, and retained with their unchanged extracted files.
The main retry artifacts preserve both attempts. Native reference-application assets are absent.

## Revision boundary and subsequent acceptance

Every application result in this record applies to `e0470f0861681d2a84f1383f6724e427a1eaf67c`.
The diagnostic workflow commits do not contain the subsequent Reference-panel/sidebar correction
or its new coordinate-interaction regression. Those application and test changes require a new
exact-revision build and emulator run; the 239-case results here cannot be transferred to them.

The standard-image configuration is being added as a seventh permanent CI lane, retaining all
six existing lanes and their runtime modes. That matrix change adds coverage. It does not convert
the failed 16 KB observations into accepted results or establish that a later candidate has passed.

## What this establishes

The current Android 15 / 16 KB test environment is not a reliable acceptance result under either
tested JIT configuration. The standard-image control completed the unchanged application's full
239-case suite and minified launch in one attempt on its measured 4 KB image.
Neither stock JIT, a passing retry, nor another Android version establishes a repair for the
original failures. The evidence supports continued runtime/image investigation while preserving
the failed application compatibility requirement. It does not justify changing unrelated painting
or UI code, increasing retry counts, or claiming full Procreate feature or rendering equivalence.

## Repeated failure in run 307

The later [seven-lane run 307](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/runs/40078354945)
tested `2d57dbd2ae476aeaf66fd9c2fda5b259cf1fb572` with 241 cases. Its main Android 15 / 16 KB lane
and standard Android 15 / 4 KB lane both passed all 241 cases and minified launch without retry.
The independent 16 KB repeat remained failed: 86 passes and one empty failure in the first attempt,
then three passes, one substantive failure and 237 missing identities in its single automatic retry.

The first attempt's ART `MarkCompact::ScanObject<true>+36` SIGSEGV at 08:58:16.277 preceded the
associated fill test's `MainActivity` PRE_ON_CREATE transition by 50 ms. Its named bucket/alpha-lock
operation had not begun. The retry's `RememberEventDispatcher` constructor threw `NoSuchFieldError`
for its own `remembering: MutableVector` field after three earlier Compose setups succeeded in
that same process. The exact failing test had passed in the first attempt. The locally resolved
Compose runtime 1.9.0 AAR declares and initializes that field; the failed installed APK was not
contained in this device artifact, so that local inspection is supporting evidence only.
A mark-compact completion 15 ms before the exception is correlation, not a demonstrated cause.

The failed repeat artifact is `12554982246`, 5,460,489 bytes, SHA-256
`47801af5de7a5fe358c94be6d138e6601ee3938edf69c946f57e96f16cb3553a`.
Both attempts remain preserved. No concrete application/dependency repair or broader retry rule
is justified by the bounded diagnosis. All six successful configurations passed the Reference
interaction and original-image review; those successes do not waive the independent failure.

## Completed previous-stable-emulator diagnostic

[Run 40102735631](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/runs/40102735631)
used workflow commit `f51dbfe9b93bfa08061b4b262720fe11bd002e13` with unchanged application/test
source from `e0470f0`. Both fresh API 35 / 16 KB default-runtime lanes failed. Each ran instrumentation
once; neither reached the runtime-measurement test, stroke timing or minified-launch acceptance.

| Previous-emulator lane | Job | Reported / declared | Passed | Failed | Missing | Minified launch |
| --- | --- | ---: | ---: | ---: | ---: | --- |
| A | `120898046686` | 57 / 239 | 56 | 1 | 182 | Not reached |
| B | `120898046432` | 7 / 239 | 6 | 1 | 232 | Not reached |

The complete 334,378,080-byte emulator distribution passed its size and SHA-256 verification:
`95771e0ae431897b2a4bd2d97fa095f29a8b0624a7b216baf529f9306161c266`.
Actual launch logs identify **37.1.11.0 / build 15917651** in both lanes. However, both prelaunch
hooks exited 127 before writing `emulator-version.txt` or `emulator-source.properties`; the action
continued to launch afterward. Script order suggests the version-capture command failed, but its
redirected error text was lost. That remains a separate setup defect and prevents treating the
full intended diagnostic setup contract as passed. The subsequent launch messages still establish
the observed emulator version. No downgrade is adopted in the main matrix.

Lane A's associated canvas-input case began at 14:37:43.843 UTC. The application main thread
received SIGSEGV at address zero 135 ms later. The tombstone measured 16,384-byte pages and showed
PC zero / unknown at frame zero, with JIT-compiled `ActivityThread.performLaunchActivity` and
`handleLaunchActivity` frames. Guest-state-header extraction errors further limit its stack.
This is a native application-process crash during launch, not a proven named ART or ArtFlow cause.

Lane B lost `system_server` at 14:36:28.308: its `ReferenceQueueDaemon` threw `NullPointerException`
reading `Reference.queue` in `ReferenceQueue.enqueuePending`. Instrumentation then reported that
the system crashed. There is no ArtFlow method in that failure stack and no captured actual
page-size measurement in this lane; a requested 16 KB target is not substituted for that evidence.

Both jobs read SDK 35 and JIT enabled, with application-method compilation activity. Their target
and runtime fingerprint match the earlier 16 KB default-runtime control, but the newly installed
system-image package revision/hash was not preserved. Ubuntu 24.04.5, runner image 20261012.334.1
and runner 2.338.0 match. A used provisioner 20261021.618 in eastus2; B used 20261012.601 in
westcentralus. These independently allocated hosts are not an identical-machine experiment.

| Preserved artifact | Bytes | SHA-256 |
| --- | ---: | --- |
| A: `12564190004` | 3,537,360 | `ff2cb154a0201e3d3b96eb06aea28caf10303587717a9df357046d58b7620f92` |
| B: `12564100263` | 2,289,138 | `6f5a163d0cba3e37637ebab2e4a8afceb7b30b35f54cbf2818ba032246c4d555` |

The frozen diagnostic revision-plus-239-identities JSON SHA-256 is
`0bd5a09e242644fb6ef43056d0d3e6abb3fe13b2257d62d84c252285a91bdce2`.
Original archives, job logs, JUnit failures and native/system evidence remain preserved. The older
emulator did not demonstrate a repair; these failures do not accept later application revisions.
