# Release sign-off

## Decision

**Public production rollout: NO-GO until publisher and physical-device gates are completed.**
Engineering changes may be published to main after their implementation and required verification;
source publication is not a Play release. The November 18 integration milestone needs its own
exact-revision evidence in [TEST_EVIDENCE.md](TEST_EVIDENCE.md).

## Engineering acceptance

- Preserve the earlier font/backup/codec hardening and all required CI checks.
- Close save/reopen budget, streaming gallery import, and persistent linking integration defects
  with actual caller-path and failure-path regressions.
- Pass Gradle tests, formatting, detekt without baseline expansion, Android Lint, APK/AAB checks,
  and every declared instrumentation identity in the seven required emulator configurations.
- Record every framework recovery. The retained qualification contract requires at least two
  consecutive all-green eight-job workflows and three consecutive green API35/16 KB lane
  executions on the final source SHA. Two successful workflow conclusions are not automatically
  two clean first attempts; identify internal recovery separately. One successful workflow alone
  is insufficient for this multi-run certification.
- Reconcile README/plan claims with the shipped runtime; keep advanced incomplete features visible.

## External release gates

1. Build the publisher-signed bundle using private, git-ignored signing configuration and
   `./gradlew bundleRelease -PrequireReleaseSigning=true`; verify its signature, application/version
   identity, and SHA-256 digest. CI unsigned bundles and debug-signed smoke APKs are not production.
2. Test representative physical low-memory Android8, Android15/16 phones, a tablet and active
   pressure/tilt stylus, and applicable ARM64 16 KB hardware. Record drawing latency/performance,
   process death, storage exhaustion, revoked URIs, long sessions, and reinstall/upgrade preservation
   with real artwork, fonts, and grains.
3. Complete publisher/store requirements, including a public privacy-policy URL matching the app,
   truthful screenshots/listing, applicable declarations, prelaunch review, and staged rollout.

No private signing material or publisher identity should be invented or committed. The remaining
external evidence cannot be replaced with unit tests, screenshots of a mockup, or a renamed gate.
