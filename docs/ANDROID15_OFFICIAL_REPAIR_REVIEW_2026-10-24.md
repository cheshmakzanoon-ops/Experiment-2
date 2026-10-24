# Android 15 / x86_64 / 16 KB: bounded official repair review

Reviewed on 24 October 2026, with fresh SDK indexes fetched at 18:55:23 UTC. This is a read-only literature and package-metadata review. No new emulator run, flag, workflow, application change or reference-code execution was performed.

## Conclusion

No concrete, officially documented repair was found for the observed API 35 image and the separate CMC/interpreter and CC/Scudo failures. The current image package remains revision 5. The current stable emulator remains the already-tested 37.2.12. A newer development/canary emulator is available, but its published fixes do not identify these failures. Its existence is not evidence that another binary experiment would repair them.

This conclusion is bounded by the public sources and search access available. It does not establish that no private issue, unindexed commit or unannounced fix exists, and it does not prove a common cause for the observed crashes.

## Available official packages

The [Google APIs image index](https://dl.google.com/android/repository/sys-img/google_apis/sys-img2-3.xml) still contains one matching `system-images;android-35;google_apis_ps16k;x86_64` package: stable revision 5, archive `x86_64-ps16k-35_r05.zip`, 1,696,342,085 bytes, declared SHA-1 `d709cf26b375abecfbbf5803afd20749ddfa2865`. No newer matching revision is offered in this fetched index. The archive itself was not downloaded again during this review.

The [SDK tools index](https://dl.google.com/android/repository/repository2-3.xml) offers:

| Emulator | Channel in XML | Linux build | Relevance |
| --- | --- | --- | --- |
| 37.2.12 | `channel-0`, stable | 16428233 | Already exercised by the retained runs. |
| 37.3.5 | `channel-2`, dev | 16632760 | Official website labels this release Canary; no matching runtime repair is documented. |

The [official 37-series closed-issue page](https://developer.android.com/studio/releases/fixed-bugs/emulator/37), updated 20 October, lists two fixes for 37.3.5: keyboard input-device properties and a debug-build metrics crash. The preceding 37.3.4 entry concerns an unsupported snapshot-version failure. Neither entry names API 35's 16 KB image, the observed ART Build ID, concurrent compaction, or Scudo allocation-header corruption. The [main release notes](https://developer.android.com/studio/releases/emulator) describe a new preview engine for eligible API 37+ phone AVDs; that is not a documented API 35 repair.

## Relevant kernel evidence does not prove this crash's cause

The [19 December 2024 kernel change](https://android.googlesource.com/kernel/common/+/38447e018c92f6ae182067a02a6954fa92b33a73) disabled userfaultfd during x86_64 16 KB emulation. Its stated test result was no system restarts and CC rather than CMC. This is relevant to the observed older `6.6.50-android15-8-g8adecb593e9b-ab12525588` image and its actual CMC startup logs, but it is not an identification of our individual fault.

The [19 August 2026 re-enabling change](https://android.googlesource.com/kernel/common/+/7aa7a88586d9f98dc3f97c55ec4e06961e2dabd0) explicitly disputes the earlier compatibility rationale. It explains that aligned 16 KB UFFD operations handle all four underlying 4 KB subpages. These two commit descriptions cannot be reduced to an established claim that CMC is inherently broken on every emulated 16 KB configuration.

The four issues cited by the disabling commit—385144325, 379001861, 384985178 and 377361489—were not accessible through this review's public retrieval tool. Their current status and any fixed-in build could not be independently verified. Exact searches for ART Build ID `e4a3c6c284a8b17d09fd84d661cf181b`, the image build and observed interpreter/collector signatures did not yield an accessible official issue linking those identities to a supported replacement package.

## CC Scudo failures remain a separate unresolved observation

The retained CC2 control already verified an actual collector switch and then observed system-server failures before useful application testing. Lane A terminated through asset-manager finalization; lane B terminated during `RegionSpace::ClearFromSpace` / concurrent-copying reclamation. Both reported allocation-header corruption. Those stacks identify where invalid allocator state was detected, not where it originated.

The [official Scudo troubleshooting page](https://source.android.com/docs/security/test/scudo) defines this message as a failed allocation-header checksum, with possible overwritten metadata or a pointer that does not identify a valid chunk. It treats Scudo as a mitigation and distinguishes detection from root-cause diagnosis. The message therefore does not establish a Scudo defect, a collector defect, a kernel defect, an app defect, or a shared cause with the earlier CMC failures. No official matching repair was found for these CC2 signatures.

## Evidence retained

[SDK availability record](evidence/android15-sdk-availability-2026-10-24.json) records both retrieval URLs, UTC times, HTTP dates, selected package records and complete input hashes. Both official indexes were last modified 22 October 2026 at 20:34:36 GMT:

| Input | Bytes | SHA-256 |
| --- | ---: | --- |
| `google-apis-sys-img2-3.xml` | 237,654 | `858c1f39632fa98235f13ebfcf2f362d5a19eeaed6c5f9c991425f257809e518` |
| `sdk-repository2-3.xml` | 423,137 | `f2bc624c8e1dc14c320fdf17e49a5501c35934948e46a444df8b36a138a03e1e` |

Existing original artifacts remain authoritative for the observed image, kernel, ART identity, collector, fault chronology and failed controls. This research supplies no basis for changing required CI lanes, enlarging retry eligibility, repeating the already-failed emulator/JIT controls, or treating a later pass as erasing an earlier failure.
