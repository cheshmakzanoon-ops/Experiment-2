# Release sign-off

## Decision

**Public production rollout: NO-GO until publisher and physical-device gates are completed.**
Engineering changes may be published to main after their implementation and required verification;
source publication is not a Play release. The current candidate is tracked in the
[November 19 hardening record](PRODUCTION_HARDENING_2026-11-19.md) and
[PR #41](https://github.com/cheshmakzanoon-ops/Experiment-2/pull/41). Its acceptance requires its own
exact-revision evidence; historical results remain in [TEST_EVIDENCE.md](TEST_EVIDENCE.md).

## Engineering acceptance

- Preserve the earlier font/backup/codec hardening and all required CI checks.
- Verify the save/reopen budget, bounded decoding, atomic import destination/lifetime, consistent
  package snapshots, preview failure handling, and earlier persistent linking changes through
  their real callers and failure-path regressions.
- Pass Gradle tests, formatting, detekt without baseline expansion, Android Lint, APK/AAB checks,
  and every declared instrumentation identity in the seven required emulator configurations.
- Record every framework recovery. The retained qualification contract requires at least two
  consecutive all-green eight-job workflows and three consecutive green API35/16 KB lane
  executions on the final source SHA. Two successful workflow conclusions are not automatically
  two clean first attempts; identify internal recovery separately. One successful workflow alone
  is insufficient for this multi-run certification.
- Reconcile README/plan claims with the shipped runtime; keep advanced incomplete features visible.
- Run the selected optimized workflow inventory and retain generated test ABI rules, installed APK
  identities, R8/shrinker evidence and original results. The benchmark's additional test ABI keeps,
  test hosts and disposable signing differ from the publisher artifact; its success cannot qualify
  an untested publisher binary.
- Execute installed-package preservation with actual old-app data, matching test signers and
  retained before/after evidence. Room migration fixtures establish schema behavior; they do not
  prove that replacing an installed application preserves the full user workflow. Use the
  [package campaign](../verification/installed-package-upgrade.md) and report whether the baseline
  was an archived APK or a build of pinned historical source.

## Version and identity policy

The maintenance candidate is designated **1.1.1 / version code 4**, following the repository's
identified **1.1.0 / code 3** application builds. The build values in
[`app/build.gradle.kts`](../../app/build.gradle.kts) and the final packaged manifest must agree.
This records a source-release decision, not evidence that code 4 is unused in a publisher account.
Before distribution, the publisher must compare the candidate with every already distributed
version and the account's used codes, preserve `com.artflow.studio` and the approved signing identity,
and select an unused increasing code. The [Android versioning contract](https://developer.android.com/studio/publish/versioning)
explains code ordering and Play's prohibition on reusing an uploaded code. A same-code `adb install -r`
campaign remains engineering replacement evidence only.

Commit any required version change before freezing the final source and repeat that revision's
required qualification. Do not relabel an earlier APK or reuse an earlier SHA's passing record
after changing the packaged version, signing configuration, dependencies or application code.
Minimum API 26 and target API 36 remain the configured platform contract. Google's
[target API requirements](https://developer.android.com/google/play/requirements/target-sdk),
checked November 19, 2026, require API 36 for new phone/tablet apps and updates from August 31, 2026;
recheck the applicable publishing policy at submission.

## External release gates

1. Build the publisher-signed bundle using private, git-ignored signing configuration and
   `./gradlew bundleRelease -PrequireReleaseSigning=true`; verify its signature, application/version
   identity, and SHA-256 digest. CI unsigned bundles and debug-signed smoke APKs are not production.
   Use the [publisher artifact gate](../verification/publisher-artifact-qualification.md) with the
   explicitly approved upload certificate and retained exact-revision CI source/build evidence;
   APK app-signing approval is separate from AAB upload-signing approval.
2. Test representative physical low-memory Android8, Android15/16 phones, a tablet and active
   pressure/tilt stylus, and applicable ARM64 16 KB hardware. Use the
   [physical qualification campaign](../verification/physical-device-qualification.md) to bind
   observations and raw measurements to the actual installed artifact. Record drawing latency/performance,
   process death, storage exhaustion, revoked URIs, long sessions, and reinstall/upgrade preservation
   with real artwork, fonts, and grains.
3. Complete publisher/store requirements, including a public privacy-policy URL matching the app,
   truthful screenshots/listing, applicable declarations, prelaunch review, and staged rollout.

No private signing material or publisher identity should be invented or committed. The remaining
external evidence cannot be replaced with unit tests, screenshots of a mockup, or a renamed gate.
