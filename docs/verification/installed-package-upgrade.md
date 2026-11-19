# Installed-package replacement campaign

## Scope and current status

`.github/scripts/run_package_upgrade.py` executes a retained package-replacement campaign on
one explicitly designated **disposable** Android device or emulator. It installs a provided old
APK, pauses for data creation through that old application's real UI, freezes its actual private
storage, installs the candidate with `adb install -r`, and runs the candidate's real editor
recovery/edit/save workflow followed by reopen in a separate application process.

The host tests exercise rejection, snapshot and evidence contracts. They do **not** establish
that an Android package campaign passed. No device campaign was executed while adding this tool:
there is no attached designated test device or supplied compatible APK/key pair in this session.
Android compilation and real device execution remain required. The tool never substitutes host
tests, an empty result, the ordinary artwork workflow test, or an operator assertion for the
candidate's campaign-specific evidence.

This first campaign supports single-base, **debuggable test APKs**, using `run-as` for stopped-data
preservation. It requires identical current signer certificate SHA-256 values for the old,
candidate and instrumentation APKs. It deliberately does not handle signing-key rotation,
split APKs, non-primary Android users, non-debuggable publisher builds or store delivery.
`publisherQualified`, `storeReleaseQualified` and `installedV1MigrationQualified` remain false,
including after a successful campaign. Physical-device qualification is a separate gate.

The [identified historical package](database-upgrade-preservation.md#historical-package-baseline-for-follow-up)
already has Room v2 and version code 3. Replacing it with another code-3 APK is recorded as
`same-version-engineering-replacement`. A greater candidate code is recorded as
`increasing-version-test-update`; this still does not approve a store release. Lower codes and
identical old/candidate APKs are rejected. A genuine installed v1→v2 campaign requires an
independently identified real v1 application, which remains unavailable.

## Required artifacts and device

Supply all of the following before `prepare` can install anything:

- One disposable device's exact ADB serial, with the primary Android user active. Neither
  `com.artflow.studio` nor `com.artflow.studio.test` may already be registered, including an
  uninstalled package whose data was retained. The tool will not remove them to make a run pass.
- A verified old debug APK, the candidate debug APK and that candidate's debug instrumentation
  APK, each with an independently recorded SHA-256. The instrumentation APK must include
  `PackageUpgradeWorkflow` and target `com.artflow.studio.HiltTestRunner`.
- Exact 40-character historical and candidate source revisions and an explicit baseline origin:
  `archived-apk` or `rebuilt-pinned-source`. Source-to-artifact provenance is supplied by the
  operator; the tool measures and pins the actual APK bytes and installed copies.
- Android SDK `adb`, `apkanalyzer` and `apksigner`, plus Python 3.11 or newer. Tool paths may be
  supplied explicitly. The candidate and old app must support the measured device API.

The historical archive record is CI run `44463021020`, attempt 1, artifact `14502360127`,
`artflow-builds-unverified`, 34,715,768 bytes, archive SHA-256
`efa26c50f0d664b053400fd5b74f073d3923283797c47f078bbeed4f37d9a107`.
The source actually built was merge commit `2b857405a05dcefa19cc8f6c43102ae55668f5a9`, from
branch head `384095da6b0feec12736fa54fe7d245777e1c37e`. Before using a retrieved archive,
check its availability/expiry, compare its archive size and SHA-256 with that record, inspect
its entries, and separately hash the selected APK. An archive digest is not an individual APK
digest. The archive was not downloaded again during this implementation.

This tool has no expiring Actions-download dependency. A retained verified APK can be supplied
directly. If its CI debug certificate cannot be matched, build the pinned historical source
and candidate in separate checkouts with the same dedicated disposable test key, retain their
build logs and APK hashes, and select `rebuilt-pinned-source`. Do not present those rebuilt
packages as the original archived APKs or use a publisher's private key for this fixture.

## Execute one campaign

The paths, revisions and hashes below are explicit operator inputs. Use a fresh output directory;
each phase is exclusively claimed once. A failed or completed campaign cannot be retried in place.

```bash
python3 .github/scripts/run_package_upgrade.py prepare \
  --campaign /absolute/evidence/package-upgrade-001 \
  --serial emulator-5554 --disposable-device emulator-5554 \
  --allow-package com.artflow.studio \
  --old-apk /absolute/apks/old-debug.apk --old-sha256 OLD_APK_SHA256 \
  --candidate-apk /absolute/apks/candidate-debug.apk --candidate-sha256 CANDIDATE_APK_SHA256 \
  --test-apk /absolute/apks/candidate-debug-androidTest.apk --test-sha256 TEST_APK_SHA256 \
  --old-source 2b857405a05dcefa19cc8f6c43102ae55668f5a9 \
  --candidate-source EXACT_CANDIDATE_SOURCE_REVISION \
  --baseline-origin archived-apk \
  --apkanalyzer /absolute/android-sdk/cmdline-tools/latest/bin/apkanalyzer \
  --apksigner /absolute/android-sdk/build-tools/36.0.0/apksigner
```

Compatibility, signatures, device identity and package absence are checked before the first
install. `prepare` retains all three APKs, installs the old app without replacement flags,
pulls its installed APK and checks its bytes, then launches its actual `MainActivity`.
It prints a unique artwork name, also stored as `projectName` in `campaign.json`.

### Bounded manual old-app seed

The archived app does not contain today's test components. Do these steps in the **old app's
normal UI**, without injecting SQL, writing documents into its private storage or running the
candidate's instrumentation against it:

1. Complete onboarding. In Settings select **Light** theme, allow finger drawing, and enable
   autosave. Record the selected interval.
2. Create a custom palette and save a brush copy through the old app's palette and brush UI.
   These rows must be nonempty, survive replacement, and decode through the candidate's real
   settings and brush-library repositories.
3. Create exactly one artwork with the printed name, **64 × 64 pixels at 72 dpi**. Use a simple
   default single-layer canvas. Paint visible marks in the upper part and leave the lower part
   blank for the later automated stroke. Return to Gallery and choose **Save and leave**.
4. Reopen that saved artwork in the old app and inspect the saved picture. Add a visibly
   different mark in the upper half, wait at least the configured autosave interval and one
   further second, and leave the editor open without explicitly saving that second edit.
   The freeze step rejects a missing, identical or non-newer recovery document.
5. Save an operator seed record with this shape. Enter the real operator identity and useful
   observations; these statements describe the manual boundary and are not automated UI proof.

```json
{
  "schemaVersion": 1,
  "operator": "Operator name",
  "createdThroughOldAppUi": true,
  "savedAndReopenedThroughOldAppUi": true,
  "customPaletteCreatedThroughOldAppUi": true,
  "savedBrushCreatedThroughOldAppUi": true,
  "newerRecoveryCreatedThroughOldAppUi": true,
  "notes": "Record palette/brush names, saved and recovery marks, and autosave interval."
}
```

```bash
python3 .github/scripts/run_package_upgrade.py freeze \
  --campaign /absolute/evidence/package-upgrade-001 \
  --serial emulator-5554 --disposable-device emulator-5554 \
  --allow-package com.artflow.studio \
  --seed-record /absolute/evidence/old-app-seed-record.json

python3 .github/scripts/run_package_upgrade.py upgrade \
  --campaign /absolute/evidence/package-upgrade-001 \
  --serial emulator-5554 --disposable-device emulator-5554 \
  --allow-package com.artflow.studio
```

`freeze` confirms the installed old APK, retains the operator record, screenshot and activity
state, force-stops only the designated package, and captures `files` and `databases`. The host
reads a separate copy of the actual production SQLite database, including its WAL when present.
It requires `PRAGMA quick_check = ok`, preserves SQL storage classes and 64-bit identities, and
records the files' exact bytes, SHA-256 values and second-resolution tar timestamps. The original
tar bytes are retained. Linked, duplicate, escaping and oversized archive entries are rejected.

### Automated replacement and candidate checks

The upgrade phase rechecks the device fingerprint/API/page size and exact installed old APK.
It compares a second stopped-data capture with the frozen seed before replacing the package.
The only candidate install command is `adb -s SERIAL install -r CANDIDATE`; no downgrade,
uninstall or data-clear flag is used. Immediately after replacement, before candidate launch,
another snapshot must preserve every original file and logical database row.

The real candidate is launched normally, then stopped. Its matching instrumentation APK is
installed, and a nonce-bound baseline is copied into a separate private campaign directory.
Two bounded invocations select the existing
`OptimizedArtworkWorkflowTest#realPointerPaintSurvivesSaveFreshActivityReopenAndUiPngExport`
method with explicit campaign arguments. Its ordinary full-matrix assertions remain the default;
the helper declares no skipped or extra tests.

| Candidate phase | Required observations |
| --- | --- |
| `edit` | Exact old project, legacy-brush, settings and sequence rows; unchanged old artwork bytes and timestamps before editing; decoded palette/brush library; saved canvas pixels equal the old app's flattened PNG; distinct newer recovery decoded and selected through the real recovery dialog; real pointer input changes pixels; **Save and leave** persists those edited pixels. |
| `reopen` | A different process ID, linked to the edit checkpoint; gallery reopen through `MainActivity`; exact edited-pixel hash and saved PNG equality; no unsaved edits or pending recovery; preserved settings and every other project row. |

The old saved and recovery images are read through an independent candidate repository before
the UI phase. This verifies usability of the preserved original files; it does not independently
reimplement the old renderer. The old saved composite PNG supplies the independent saved-picture
comparison. The operator's old-app save/reopen observation and the retained final seed screenshot
make the manual evidence boundary explicit.

The script requires exactly one passing test per invocation **and** the complete phase report,
matching token, baseline hash, old/candidate APK hashes, project ID and assertion set. A normal
workflow pass, stale JSON, omitted assertion or instrumentation timeout cannot qualify the run.
It then preserves the stopped post-edit data. The complete campaign directory, including APKs,
private test data, command logs, screenshots and reports, is the review artifact.

## Failure retention and validation

Every subprocess has a timeout and preserves argv, stdout, stderr, exit status and timing.
Each phase additionally captures logcat, package state, the screen and any probe evidence without
clearing logs. There are no automatic reruns, uninstall commands, database deletion, package data
clearing, or cleanup of the original evidence. A refused repeat leaves the first outcome intact.
Use a new disposable device and output directory for another campaign; retain the failed one.

Host regression checks:

```bash
python3 -m unittest discover -s .github/scripts -p 'test_run_package_upgrade.py' -v
```

The initial host checks passed 17 cases. The two touched instrumentation files passed standalone
ktlint and detekt with the existing configuration and baseline. Those checks establish host-tool
and source hygiene only; they do not establish APK installation, hardware execution, a publisher
signature, or an installed v1 database migration.
