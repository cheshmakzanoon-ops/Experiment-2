# Release decision review — wave 17

## Decision and scope

The source remains an engineering candidate. Public rollout remains **NO-GO** until all existing
engineering, publisher, physical-device and store gates in [release sign-off](../release/RELEASE_SIGNOFF.md)
are satisfied. This review did not execute a final Android campaign, access a publisher account,
approve a signing identity, submit to a store or measure a physical stylus. It introduces no new
release gate and does not treat a configured workflow or passing helper test as executed acceptance.

The current maintenance version is 1.1.1 / code 4, package `com.artflow.studio`, minimum API 26,
target API 36 in [the application build](../../app/build.gradle.kts). These source values agree
with the documented candidate; only the publisher can establish whether code 4 is unused and
compatible with every distributed version and the approved signing identity. Android's
[version contract](https://developer.android.com/studio/publish/versioning) requires increasing
release codes, and Play does not permit reusing an uploaded code. The current phone/tablet
[target API requirement](https://developer.android.com/google/play/requirements/target-sdk),
rechecked November 19, 2026, requires API 36 for new apps and updates from August 31, 2026.

## Keep artifact conclusions separate

| Artifact or campaign | What a complete pass can establish | What it does not establish |
| --- | --- | --- |
| Debug app and full seven-lane matrix | The complete declared instrumentation inventory and build/static checks on the retained source/runtime, with original invocation and installed-APK evidence | Normal release optimization, publisher identity or physical performance |
| Benchmark app and 43-case optimized lane | Selected real workflows under R8/resource shrinking with the explicitly retained test ABI, test hosts and Hilt wiring | Identical optimization constraints or application wiring to the publisher release |
| Ordinary release payload and separate UI driver | The production Application and ordinary release rules, unchanged app payload after disposable signing, paint/save/share and exact pixels after a fresh process | Approved publisher signing, store-delivered splits or hardware qualification |
| Installed historical Room-v1 campaign | An increasing-version update of the pinned old source build, real old-app data, schema migration and recovery/edit/save/reopen under the same disposable signer | Reproduction of an originally distributed APK, publisher key rotation, split delivery or store upgrade acceptance |
| Publisher artifact verifier | The supplied APK/AAB's checked signature role, approved public certificate fingerprint, package/version and retained payload provenance within the verifier's declared scope | Physical acceptance, account ownership, remote attestation or overall public release approval |

The existing final-revision contract requires two consecutive complete eight-job workflows and
three consecutive primary API35/16 KB executions, plus optimized, normal-release UI and installed
upgrade acceptance. A PR run's metadata head and its checked-out merge commit must not be conflated.
The final merged main SHA needs its own evidence; a changed source revision cannot inherit an
earlier checkpoint's pass. [The CI review](ci-acceptance-review.md) describes the supported rerun
procedure, exact attempt accounting and explicit framework-recovery disclosure.

## Privacy claim repair

The Help introduction said artwork and exports stayed on the device and that there was no upload.
That unconditional statement contradicted the enabled Android cloud-backup rules and user-selected
export recipients. The [manifest](../../app/src/main/AndroidManifest.xml) enables backup; the
[legacy rules](../../app/src/main/res/xml/backup_rules.xml) and
[Android 12+ rules](../../app/src/main/res/xml/data_extraction_rules.xml) include project files,
fonts, grains and the Room database. Android's [Auto Backup documentation](https://developer.android.com/identity/data/autobackup)
describes cloud storage subject to the user's settings and a 25 MB per-app quota; it does not
guarantee that an artwork library is backed up.

The Help resource now states that there is no account or developer upload, that artwork is kept in
private app storage, and that Android backup and selected export recipients follow the user's
choices and device settings. The gallery reviewer also owns the corresponding empty-state wording
correction. Neither change alters backup settings or export behavior.

The [public policy text](../privacy-policy.md) and
[in-app policy resource](../../app/src/main/res/values/privacy.xml) already make these distinctions:
private project/export deletion, separately retained gallery/provider copies, possible Android
backup restoration, and no developer collection. The source manifest requests no Internet
permission; the observed cached release merged manifest likewise has no Internet permission.
The latter is supporting build evidence from an earlier checkpoint, not inspection of a final
publisher artifact. FileProvider is non-exported with explicit URI grants and exposes only the
export and camera staging roots; the full artwork store is not a provider root.

The existing store gate remains open. Google Play's [User Data policy](https://support.google.com/googleplay/android-developer/answer/10144311)
requires a publicly accessible privacy-policy page and an in-app policy or link even for an app
without developer data collection. The publisher still must confirm the actual store identity,
contact, policy URL and Data safety declaration against the final artifact. No declaration or
publisher approval is inferred from a Markdown file in this repository.

## Review outcome

The reviewed README boundaries accurately distinguish the active CPU/GLES display pipeline from
unused GPU/sparse/CMYK prototypes, bounded format subsets from full interchange parity, and
retained timelapse snapshots from a complete edit history. Unfinished professional ambitions are
separate product scope; they are not silently represented as shipped functionality.

Physical low-memory and long-session behavior, real pressure/tilt input, stock ARM64 16 KB devices,
approved signing/version identity, publisher-delivered upgrade behavior and store acceptance
remain the explicit external work. Emulators and synthetic certificate fixtures cannot complete
those records. Source integration can proceed after its required verification without claiming
that public distribution is approved.

This assignment changed one Android string and release documentation only. XML parsing and
resource-name uniqueness, local documentation links and scoped diff checks are the appropriate
verification. No behavior-mirroring tests, Gradle invocation, device execution, baseline changes,
commits or pushes were performed by this reviewer.
