# Android 15 / x86_64 16 KB collector control: source-supported diagnostic

Analysis date: 2026-10-24. This report inspects existing run 311 evidence and official source. No runtime property was changed and no emulator was launched while deriving it. A proposed controlled comparison is additional evidence; it does not replace the original seven required lanes or establish a crash cause.

## Finding

Android 15 has an explicit boot-time `runtime_native_boot.force_disable_uffd_gc` flag. Setting it to `true` and fully rebooting is a source-supported way to select Concurrent Copying (CC) rather than Concurrent Mark-Compact (CMC), provided the image actually implements the inspected ART configuration. This must be verified on the exact AVD, including the boot-cached flag, actual process collector and compatible rebuilt boot images. Merely requesting `dalvik.vm.gctype=CC`, setting the older enable flag to false, or restarting Android services is insufficient evidence.

The official introduction of the force-disable flag documents setting it with `device_config`, rebooting, observing odrefresh recompilation and then CC. Its test also disables remote DeviceConfig synchronization for the disposable test device. See [ART commit 8222aa2](https://android.googlesource.com/platform/art/+/8222aa2d2df6273da689f0edd3913e8370c0c1c2%5E%21/). The older [boolean enable-flag change](https://android.googlesource.com/platform/art/+/75a98c80f07075ba9df8e1ee28e1a30d6177a15a%5E%21/) explicitly demonstrates why `enable_uffd_gc_2=false` does not override a build-time enable.

## Exact Android 15 source

The inspected `android-15.0.0_r1` tag resolves to ART commit `bebbc3cc49f2d9d5420197df0a336fbc3fcbea40`. This is official release source, not a claim that every instruction matches the emulator's independently identified ART BuildId `e4a3c6c284a8b17d09fd84d661cf181b`.

| Source | Relevant contract |
| --- | --- |
| [runtime/gc/collector/mark_compact.cc](https://android.googlesource.com/platform/art/+/bebbc3cc49f2d9d5420197df0a336fbc3fcbea40/runtime/gc/collector/mark_compact.cc), lines 221–322 | Reads system properties from boot-generated `cache-info.xml`. The effective policy is `(enable_flag || build_enable || pre_V) && !force_disable`. Without an explicit process command-line override, CMC additionally requires kernel UFFD support; otherwise the runtime uses read barriers and CC. |
| [odrefresh/odr_config.h](https://android.googlesource.com/platform/art/+/bebbc3cc49f2d9d5420197df0a336fbc3fcbea40/odrefresh/odr_config.h), lines 66–78 | Lists the force-disable property with default false among settings that invalidate compiled artifacts when changed. |
| [odrefresh/odrefresh.cc](https://android.googlesource.com/platform/art/+/bebbc3cc49f2d9d5420197df0a336fbc3fcbea40/odrefresh/odrefresh.cc), lines 1089–1180 | A nondefault tracked flag rejects the precompiled system-artifact path. Changed cached values invalidate prior data artifacts. Normal boot recompilation supplies the compatible images. |
| [runtime/gc/space/image_space.cc](https://android.googlesource.com/platform/art/+/bebbc3cc49f2d9d5420197df0a336fbc3fcbea40/runtime/gc/space/image_space.cc), lines 3446–3453 | Rejects an OAT image whose concurrent-copying/read-barrier setting differs from the runtime. |
| [test/odsign/.../OdsignTestUtils.java](https://android.googlesource.com/platform/art/+/bebbc3cc49f2d9d5420197df0a336fbc3fcbea40/test/odsign/test-src/com/android/tests/odsign/OdsignTestUtils.java), lines 58–61, 143–155, 217–228 | Defines `/data/misc/apexdata/com.android.art/dalvik-cache/cache-info.xml`; tests verify zygote's actual mapped data-cache boot artifacts through `/proc/<pid>/maps`. |

The force-disable flag takes priority over both enable sources. No change to `enable_uffd_gc_2` or the read-only build property is needed. ART's static initialization and image compatibility checks are why overriding a later runtime option alone is not an adequate substitute for the supported boot procedure.

## Observed run 311 configurations

All three compared lanes used application revision `e2aa900b91298fe08eca8529d0345220faa9e2eb`, Actions run [40115177921](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/runs/40115177921). The original artifacts and all process logs remain authoritative; `collector-evidence.json` records their hashes and selected exact line references.

| Lane | Kernel observed in original log | Actual collector evidence | Outcome boundary |
| --- | --- | --- | --- |
| API 35 / 16 KB independent repeat, both boots | `6.6.50-android15-8-g8adecb593e9b-ab12525588`, built 2024-10-18 | CMC in zygote, system_server and ArtFlow | Both attempts crashed; final 213 passed, 1 empty failure, 36 of 250 missing. No minified launch. |
| API 36 / 16 KB | `6.6.66-android15-8-gd0c43a640eab-ab13812146`, built 2025-07-21 | CC in ArtFlow PID 2956; odrefresh reports build UFFD enabled but kernel UFFD support false | All 250 cases passed on first attempt. |
| API 36 / 4 KB | Same `6.6.66` kernel identity | CMC in ArtFlow PID 3027 | All 250 cases passed on first attempt. |

Artifacts: repeat35 [12569331702](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/runs/40115177921/artifacts/12569331702), 8,171,122 bytes, SHA-256 `290dded5d4c2d2a58a292a55b79bd27be428fc4d1e933cf9be3a67e17a4f2e3a`; API36/16 KB [12569311814](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/runs/40115177921/artifacts/12569311814), 8,274,063 bytes, SHA-256 `cbac69c1f4cce2adff68eb768807c450607cce89c6e94832566d135ded69139a`; standard36 [12569936243](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/runs/40115177921/artifacts/12569936243), 7,642,666 bytes, SHA-256 `a8f2ae27bffd6adc3843faef3b1ac719afff823530b0ab5762bb7932ff9f3dbb`.

The exact public kernel revisions strengthen this observation. [API35 fs/userfaultfd.c](https://android.googlesource.com/kernel/common/+/8adecb593e9b/fs/userfaultfd.c) lacks the emulated-page-size guard. [API36 fs/userfaultfd.c](https://android.googlesource.com/kernel/common/+/d0c43a640eab/fs/userfaultfd.c) adds `page_size_compat.h` and returns `-EOPNOTSUPP` at the userfaultfd syscall when `__PAGE_SIZE != PAGE_SIZE`. Those are the only differences between these two complete source files. The API36 file is byte-identical to the same file in [the December 2024 disabling commit](https://android.googlesource.com/kernel/common/+/38447e018c92f6ae182067a02a6954fa92b33a73). The old file SHA-256 is `c02d9907b2e46523c5f8ce874c9859b4dfa60c16fa489bfd2cd957ca3ae9265e`; the guarded file is `ad79f1c51b3d03a0473b8235ff125d99cb1afda9449647f715c35153eef814f6`.

This still is not causal proof. The [August 2026 re-enabling commit](https://android.googlesource.com/kernel/common/+/7aa7a88586d9f98dc3f97c55ec4e06961e2dabd0) explicitly disputes the earlier incompatibility rationale and explains how aligned UFFD operations can cover all four hardware subpages. Neither commit identifies our individual app crashes, and the compared API36 image has additional ART/framework/image differences. A same-image collector control can test an association while avoiding those larger substitutions.

## Proposed isolated procedure and acceptance criteria

Use a disposable, rootable API35/x86_64/16KB userdebug AVD. Preserve the app/test source, emulator binary, system image, kernel, page size, test identities and disabled-JIT configuration. Before switching, retain fingerprint, boot ID, kernel, all relevant flags, actual CMC startup logs and process maps. Keep continuous log capture during runtime restarts so collector lines are not lost to the ring buffer.

1. Obtain adb root and verify UID 0. Capture the initial state and apply the existing unchanged JIT-disabled configuration before capturing the CMC baseline.
2. Disable DeviceConfig sync only for this disposable diagnostic, then request `device_config put runtime_native_boot force_disable_uffd_gc true`. Read back both DeviceConfig and its persistent system property. Do not change unrelated enable flags.
3. Perform a full reboot, with bounded offline/online/boot-completion polling. Require a changed boot ID. Preserve boot and odrefresh logs on failure as well as success.
4. Verify the force-disable entry is true in the actual boot cache at `/data/misc/apexdata/com.android.art/dalvik-cache/cache-info.xml`. Require unchanged fingerprint/kernel/page size and actual CC for the new zygote/system_server processes. Require their rebuilt data-cache `boot.art` and `boot.oat` mappings. A missing compatible image or JIT-Zygote fallback invalidates the controlled setup.
5. Reapply the existing unchanged JIT-disabled script after reboot. Require JIT properties false, actual CC after that service restart, and the same valid boot-image mappings. Property requests alone are not acceptance.
6. For the actual instrumentation and minified app processes, retain PID, exact cmdline, maps and original logcat. Require the collector line belonging to that PID to say CC and require compatible data-cache boot mappings. Run all frozen identities once, with no automatic instrumentation retry, and preserve failures with their timing and original logs.

The control necessarily changes both the collector and the associated read-barrier compilation of boot artifacts; this is the supported coupled runtime configuration. Even two complete passes would support stability under this configuration, not prove a particular kernel bug, erase earlier failures, or establish complete Procreate parity.

## Reproduction and limits

The source links above identify the exact release and kernel files used for this read-only
comparison. Their recorded hashes distinguish source facts from a requested emulator setting.
The actual diagnostic is isolated on branch `codex/android15-cc-control-e2aa900`; its workflow
adds only control/verification files and rejects changes to the application or its 250 declared
tests. A setting is accepted only after the real boot-cache, process-collector and mapped-image
checks complete. The completed run, original artifacts and observed outcomes must be recorded
before this control can be called effective on this particular image.

## First diagnostic: failed before instrumentation

[Control run 40119181068](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/runs/40119181068)
uses workflow commit `a143fc15aa1f8b9ad3af4b0aa5357089f073e852`, tree
`c3ee55aa81026e630c228354f11ad34120c1e7d5`, on branch
`codex/android15-cc-control-e2aa900`. Its application/tests are byte-unchanged from `e2aa900`;
only five diagnostic files are added. The workflow verifies that exact allowlist and the 250-case
identity SHA-256 `308b1663e5f2711c2279997de1e4a5b34ace31bbf8dd5a440db1aae5342a54c2`.
The workflow configured two independent hosted VMs/AVDs for one instrumentation invocation each,
retaining all minified-launch gates.
Sixteen local parser/corruption tests and independent source review passed before publication.
Both jobs nevertheless failed during setup, before a reboot or instrumentation. Each retained
the original CMC baseline and reported that the requested DeviceConfig value had not yet
propagated to the persistent runtime property. The immediate readback was too early to establish
whether this image supported the change. Lane B's subsequent failure capture contained both
ordinary and `aconfig_flags` properties set to true; lane A did not capture a later successful
readback. No actual CC process, test result or minified launch was produced. All 250 identities
are missing from each lane, and this run remains a failed setup experiment.

| Lane | Original artifact | Bytes | SHA-256 |
| --- | --- | ---: | --- |
| A | [12570493408](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/runs/40119181068/artifacts/12570493408) | 6,533,240 | `03ce60b3be8aef9a0a908dfc8bf5ccea5bee9cebd26cdb665a1ad3763b83a2dc` |
| B | [12569319975](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/runs/40119181068/artifacts/12569319975) | 6,028,091 | `41829bcbe5be55e146d36ffb3e86d1bc66341d3ef74af5d383ca7e172b99d2ee` |

The source archives contain all 864 original `e2aa900` files byte-for-byte plus only the five
diagnostic additions. Both baselines had SDK 35, actual 16,384-byte pages, the expected kernel,
JIT disabled, CMC zygote/system-server logs and `libart.so` SHA-256
`6ac4d052d0cf6f7d69ff817444f610afa25e583bb032d2a0e310346c0f8b7133`.
Lane B also retained an earlier system-server SIGSEGV, before the flag write and before its
JIT-restarted baseline. Its unknown null-PC/nterp/Binder trace is neither an application test
failure nor evidence about CC. The later metadata step independently failed because plain
`emulator -version` selected a GUI binary requiring unavailable `libpulse.so.0`; the running
headless emulator had already reported version 37.2.12.0, build 16428233. That metadata failure
prevented the original image-file hash capture. Lane B's startup and environment runner-image
labels also differ, so neither label is silently substituted for the other in the retained audit.

## Corrected setup diagnostic: runtime failure before completed tests

[Control run 40120334470](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/runs/40120334470)
uses commit `409ef9981ee03902be97ed8ef84d16036d4da831`, tree
`b3e883b18bfd0c259fbd262b0c48f28fdfbfe8e4`. This is a new source revision, not a rerun of the
failed experiment. Application files and all 250 test identities remain unchanged. The corrected
setup waits within one monotonic 60-second budget for both DeviceConfig and the persistent
property to return exit status zero and literal `true`. Every observation, elapsed time, status,
value and stderr is retained in JSONL. A nonzero command that prints `true`, or a value that never
propagates before the deadline, still fails setup.

The version query now uses `emulator -no-window -noaudio -version`, retains its exit status and
continues collecting image metadata even if that query fails. The emulator's
[official launcher source](https://android.googlesource.com/platform/external/qemu/+/emu-master-dev/android/emulator/main-emulator.cpp)
selects the headless QEMU binary when `-no-window` is present. The unchanged-source guard also
fetches the exact application base if it is outside the shallow checkout before comparing files.
No test, crash predicate, collector check, retry policy or minified-launch gate was relaxed.

Independent inspection of the original artifacts confirms both 869-file source archives: all 864
application-base files remain byte-identical, with only the five diagnostic additions. Their source
tar SHA-256 is `21ddf363b79334d337d933612fd4759b3183018437d665bf487f803acd7ca8a4`.
The bounded propagation wait succeeded after 2.119095506 seconds on A and 1.090400259 seconds on B.
Each performed a full reboot, retained a changed boot ID, rebuilt/mapped compatible data-cache boot
images, and recorded CC in its configured zygote and system-server PIDs with JIT disabled.

Both selected system-server processes then aborted with Scudo allocation-header corruption, before
the verifier printed its configured-phase PASS. That marker describes captured boot/cache/collector
facts; it does not establish that the sampled process remained healthy while reporting completed.

| Lane | Selected configured system server | Own CC startup | Scudo/SIGABRT | Application outcome |
| --- | ---: | --- | --- | --- |
| A | 4345 | 18:26:48.868 | 18:26:56.571–56.572, FinalizerDaemon TID 4357 | APK installation failed because the package service was unavailable; no instrumentation app PID captured. |
| B | 3618 | 18:27:00.441 | 18:27:05.199, HeapTaskDaemon TID 3628 | App PID 5923 logged CC at 18:27:16.320, then `DeadSystemException` at 18:27:20.538; instrumentation reported that the system crashed. |

The configured PASS lines appeared at 18:26:57.858 and 18:27:07.595 respectively. A's selected
deallocation path reaches `android::NativeDestroy` / `AssetManager.finalize`; B's reaches
`RegionSpace::ClearFromSpace` / `ConcurrentCopying::ReclaimPhase`. Both tombstones include rebuilt
data-cache `boot.oat`, ART BuildId `e4a3c6c284a8b17d09fd84d661cf181b` and libc BuildId
`eb58b4d427279994f00c0e1818477e4f`. These are corruption-detection contexts, not the origin of a
corrupt write or pointer. Earlier system-process failures are also retained; these selected events
are not asserted to be each boot's first fault or to share the original CMC crashes' cause.

Each lane invoked the connected-test Gradle task once and retained one zero-case XML suite: **zero
reported test identities, 250 missing, no retry, and no minified/native/pixel/timing acceptance**.
Both jobs failed. A real collector switch therefore did not produce a usable validated lane.
The regular seven-lane matrix retains its existing runtime configuration.

### Observed flag recovery and its boundary

After the crashes, init invoked `/system/bin/flags_health_check UPDATABLE_CRASHING`. Its own logs
report flag resetting and a successful write to `/data/server_configurable_flags/reset_flags`.
Complete later app-phase property captures show the force-disable property explicitly empty,
with the same boot ID and JIT still false. The app-phase verifier correctly rejects the changed
configuration. This was a successful empty `getprop` response, not a missing line or failed adb read.

The official Android 15 release source at
[`platform/system/server_configurable_flags` commit 3b6d3e8](https://android.googlesource.com/platform/system/server_configurable_flags/+/3b6d3e8d7d20d2f1876c8882dcbdf347b16f80dc/)
defines the mechanism. [`flags_health_check.rc`](https://android.googlesource.com/platform/system/server_configurable_flags/+/3b6d3e8d7d20d2f1876c8882dcbdf347b16f80dc/disaster_recovery/flags_health_check.rc)
starts the native health check when `sys.init.updatable_crashing=1`.
[`server_configurable_flags.cc`](https://android.googlesource.com/platform/system/server_configurable_flags/+/3b6d3e8d7d20d2f1876c8882dcbdf347b16f80dc/libflags/server_configurable_flags.cc)
clears nonempty `persist.device_config.*` properties except `attempted_boot_count` in that reset mode,
recording successful keys in the reset file. The force-disable property is in that scope. Its
individual reset is **inferred** from this mechanism and the observed chronology: the per-key reset
file was not retained, and equality of the installed binary to the public release source was not
established. A separate Java RescueParty disabled message does not negate this observed native
program execution. None of this identifies the preceding heap corruption's cause.

### Original corrected-control artifacts

| Lane | Original artifact | Bytes | SHA-256 |
| --- | --- | ---: | --- |
| A | [12570907294](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/runs/40120334470/artifacts/12570907294) | 15,897,557 | `dbdbba10382caa023c41d4871d72982957008b7ef5c217ad282afdd2df6b542a` |
| B | [12571420524](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/runs/40120334470/artifacts/12571420524) | 15,523,566 | `824c880766d66bc6895c7fc022992de76adff2df2d296b10bed7ee630e31becb` |

The corrected headless version query and image metadata collection succeeded. The image's official
`source.properties` TagId is `google_apis,page_size_16kb`, while the SDK package alias remains
`google_apis_ps16k`; the independent artifact reader's initially incorrect TagId expectation was
fixed in a separate audit copy, preserving the frozen original helper. No workflow runtime or
test condition changed. Lane A retains differing host labels (environment `20261023.334.3`, startup
banner `20261012.334.1`); lane B reports `20261012.334.1` consistently. Both labels remain in the audit
rather than selecting one as authoritative.

The [combined machine-readable record](evidence/android15-cc-control.json) preserves both diagnostic
revisions and all four failed lanes, the shared 250-case identity manifest, original artifact/source
hashes, phase facts, reset-inference boundary and independent-reader correction. Its 108,808 bytes
have SHA-256 `f538ccdd92685f373486dce6d05a6a953a3701d8cc48e1e917af79694563cfaa`.
Across the four lanes, zero test identities were reported and 1,000 expected lane-identities are
missing. The 16 workflow-side verifier cases and 22 independent synthetic reader checks validate
their bounded parsing/corruption behavior; they do not replace any missing emulator case.
