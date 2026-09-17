# Release readiness and evidence

This is a verification record, not a certification that an app has no bugs. `agent.md` preserves
all 50 original development phases. The currently implemented offline editor is narrower than
that roadmap; the missing features listed below have **not** been silently removed from the goals.

## Reliability repairs

The repair candidate includes atomic project writes and recovery, immutable edit snapshots,
rejection of stale/cancelled edits, correct GL lifecycle restoration, deterministic brush output,
protected fills, layer clipping/adjustments, unsaved mask compositing, independent project
copies with rollback, and coherent export snapshots.

Export repairs cover PNG alpha and DPI, JPEG density, WebP, PSD layer/merged alpha and names,
PDF page cleanup/aspect ratio, GIF, complete PNG sequences and MP4 frame submission/timestamps/
end-of-stream. MP4 uses AndroidX Media3 1.10.1 portable muxing for consistent final-frame
duration on older Android devices. Gallery publishing separates images from video, removes partial outputs, and
uses Android storage rules. Share/view/document-save actions operate on exported copies;
FileProvider excludes editable project files. Export controls apply whole presets and display
format-specific settings. Settings includes the offline privacy policy and explains Android backup.

Scaling now extends edge pixels rather than introducing transparent resize borders. Hilt 2.54
is used with KSP2 to generate Hilt wiring and Room schemas without the old processor classpath
conflict.

The compiler's memory budget was corrected, uncalled native code was removed from the application
build (source retained), destructive Room migration fallback was removed, and production builds
can require real signing inputs with `-PrequireReleaseSigning=true`.

## Evidence discipline

A passing workflow must build the exact source revision under review. A successful build of the
old source does not validate a reconstructed repair candidate. The saved candidate patch was checksum verified and exercised in CI before being committed
as ordinary source at the repository owner's explicit request. Committing that work does not
mean that every release check passed. Temporary patch-transport files have been removed; permanent
CI tests the source directly without relying on expiring repair artifacts.

The workflow reports and artifacts are the authoritative execution evidence. A benchmark-variant
launch uses release minification with a disposable debug key; it does not certify publisher signing.
Record the final run, commit, test counts, artifacts and SHA-256 values here when verified. Do not
substitute a scheduled job, a job that was cancelled, a skipped job, or a source-code assertion for
executed evidence. `detekt` passes against the existing baseline; that is not zero historical
findings. API-specific assumptions must remain visible in the test reports.

## Source publication checkpoint — September 17, 2026

All saved application, test, release-check and documentation changes from candidate run
[35216159218](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/runs/35216159218)
are committed as ordinary repository files, rather than only as compressed patch payloads.
Generated Python bytecode is excluded; it is not application source. The original candidate
patch SHA-256 is `b6269fd00f8cb911ec3a7db748e944c22e0ec045bbd31fd69938f3749c32f299`.

Observed results of that candidate's full verification run:

| Check | Result |
| --- | --- |
| Candidate formatting and static analysis | Passed |
| Missing-production-signing rejection | Passed |
| JVM tests, debug/release lint, debug APK and release AAB build step | Passed |
| Packaged artifact integrity/native alignment check | Failed |
| API 26 device test job | Failed |
| API 36 device tests and minified launch job | Passed |
| API 35, 16 KB device tests and minified launch job | Passed |
| Automatic verified-source promotion | Skipped because not all gates passed |

The repository owner subsequently requested that all work be committed and pushed despite the
incomplete release verification. Source publication is not a Google Play release or a production
sign-off. Keep the failing checks enabled and resolve their reports before release. Subsequent CI
runs on the committed source supersede this checkpoint only where they actually complete.

## Required publisher and device gates

The following items require separate evidence before a production rollout:

- Build the final signed AAB with the publisher's private upload key. Verify its signature,
  application ID and increasing version code; preserve the existing key/app identity when updating
  an already published app. CI's unsigned bundle is **not** upload-ready.
- Run the final minified, signed app on representative physical phones/tablets, a pressure-sensitive
  stylus, a low-memory device and a 16 KB page-size environment. Exercise process death/relaunch,
  upgrades with existing artwork, storage exhaustion, denied/revoked URI access, rotation,
  background/foreground transitions, many frames/layers and large-canvas editing. Archive the
  exact device/OS/app version, steps, expected result and observed result. Binary alignment is not
  equivalent to runtime coverage.
- Complete Play Console's applicable verification/testing requirements, content rating, target
  audience, ads declaration, Data safety form and app access answers. Publish an active public
  privacy-policy URL, check the publisher identity/contact against Console, and provide authentic
  screenshots and owned store assets. Review the pre-launch report on the uploaded release.

No signing key, Play Console approval, physical-device campaign or unconditional crash-free
operation is claimed by this repository. An internal testing candidate and a public production
release are different deliverables.

## Original roadmap gaps

| Goal | Current limitation |
| --- | --- |
| Advanced transform tools | MOVE/TRANSFORM translate pixels; general scale/skew/perspective/distortion is not implemented. Canvas-wide operations are separate. |
| Layer groups, linking and references | Grouping UI and operations are absent; linking/reference model support is not a complete user workflow. |
| Texture brushes and custom pressure curves | Texture fields are not a texture implementation; CUSTOM pressure still uses the documented linear fallback. |
| Native engine and benchmarks | C++ is an unintegrated prototype. No benchmark module or measured device-performance acceptance report exists. |
| PSD import and smart objects | Import/smart objects are absent. Export has explicit fidelity limits for effects and does not promise Photoshop round-trip equivalence. |
| Timelapse recording | Animation export is not recording the drawing process. Timelapse recording is absent. |
| Cloud synchronization and collaboration | Not implemented. The app has no network permission; Android backup is not an app-operated synchronization service. |
| Exhaustive quality assurance | Regression coverage is substantially expanded but is not coverage of every screen, device, gesture or failure scenario. |

Do not advertise these missing functions as delivered or mark all phase tasks complete. The store
listing is deliberately constrained to implemented functions, not the complete future roadmap.

## Reproduction commands

```bash
./gradlew testDebugUnitTest ktlintCheck detekt lintDebug lintRelease assembleDebug bundleRelease
./gradlew connectedDebugAndroidTest
python -m unittest discover -s .github/scripts -p 'test_*.py'
python .github/scripts/verify_android_artifacts.py app/build/outputs/apk/debug/app-debug.apk app/build/outputs/bundle/release/app-release.aab
./gradlew bundleRelease -PrequireReleaseSigning=true
```

Use `gradlew.bat` on Windows. The device command requires a connected emulator/device; the final
signing command requires the git-ignored root `keystore.properties` and real private keystore.
Room schemas are exported to `app/schemas`; future schema changes need explicit migrations and
upgrade tests, not destructive recreation.

## Primary release references

Reviewed September 17, 2026. Recheck these pages and the app's own Play Console before submission.

- Target API policy: https://support.google.com/googleplay/android-developer/answer/11926878?hl=en
- Data safety, including apps collecting no data: https://support.google.com/googleplay/android-developer/answer/10787469?hl=en
- Privacy policy and user data: https://support.google.com/googleplay/android-developer/answer/10144311?hl=en
- Android backup behavior: https://developer.android.com/identity/data/autobackup
- 16 KB alignment and runtime validation: https://developer.android.com/guide/practices/page-sizes
