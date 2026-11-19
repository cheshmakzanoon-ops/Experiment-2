# Installed-package replacement campaign

## Scope and current status

`.github/scripts/run_package_upgrade.py` executes a retained package-replacement campaign on
one explicitly designated **disposable** Android device or emulator. It installs a provided old
APK, creates actual user data through that old application's UI (automatically or manually), freezes
its private storage, installs the candidate with `adb install -r`, and runs real editor recovery,
editing, saving and reopen in a separate application process.

The automatic path is wired into [Installed application upgrade](../../.github/workflows/installed-package-upgrade.yml)
on pull requests, main pushes and manual dispatch. Host regression tests and source checks validate
the harness only. **A complete passing campaign bound to the final source revision is required.**
The [hardening record](../release/PRODUCTION_HARDENING_2026-11-19.md) and
[verification index](https://github.com/cheshmakzanoon-ops/Experiment-2/pull/41) retain actual
attempt outcomes; an earlier partial or failed attempt does not qualify a later candidate.
A green build or another artwork test cannot substitute for this campaign's exact nonce,
source, artifact, original-data and process evidence.

The campaign supports single-base, **debuggable test APKs** and uses `run-as` for stopped-data
preservation. All old, candidate and instrumentation APKs must have the same measured current
signer certificate. Key rotation, split APKs, non-primary Android users, non-debuggable publisher
builds and store delivery remain outside this campaign. `publisherQualified` and
`storeReleaseQualified` always remain false. Physical-device qualification is separate.

A successful genuine Room-v1 baseline campaign sets `installedV1MigrationQualified` only after the
installed candidate reports schema v2 and verifies every original typed row, the exact migration
addition, preserved artwork and the complete recovery/edit/save/reopen workflow. A v2 continuity
campaign leaves that v1 flag false. Increasing application version codes are labeled
`increasing-version-test-update`; same codes are labeled `same-version-engineering-replacement`.
Neither label approves a store release. Lower codes and identical old/candidate APKs are rejected.

## Automatic installed Room-v1 campaign

The authentic historical baseline is source
[`ca9ce535c09d35ceca6aba9f686c60f58f93bc09`](https://github.com/cheshmakzanoon-ops/Experiment-2/tree/ca9ce535c09d35ceca6aba9f686c60f58f93bc09),
the parent of the gallery-stacks migration. Its application uses Room v1, version code 3 and version
name 1.1.0. Its committed schema is byte-identical to the current migration fixture:
`afbdb33dcf9e2e6f2610560ca1adf4cd876c5241a3f56b62a162361c5c493dde` (SHA-256).
The maintenance candidate's code 4 / version 1.1.1 provides an increasing-version test update.
The publisher must independently check account/distributed version codes before any release.

The workflow uses separate clean checkouts and sequential builds on the same fresh runner, sharing
only that runner's disposable Android debug key. It never uses a publisher key. The old production
sources and build files stay unchanged; the only allowed old-source overlay is the new
`app/src/androidTest/java/com/artflow/studio/InstalledUpgradeSeedTest.kt`, copied from
[the reviewed driver](../../tools/package-upgrade/src/com/artflow/studio/InstalledUpgradeSeedTest.kt). This is explicitly
**rebuilt pinned source**, not a claim to reproduce an originally distributed historical binary.

[The build collector](../../.github/scripts/package_upgrade_build.py) checks both source revisions,
rejects tracked edits and unexpected untracked inputs, verifies the schema digest, archives both
exact source trees, and retains the driver plus all four APK hashes. It checks again after builds.
Each source archive is bound to its retained raw Git commit object: the commit object's Git SHA
must equal the declared revision, its tree must equal the declared tree, and a streamed hash of
every archived path, executable mode and file must reconstruct that tree. The archive's commit
header, actual schema bytes, and candidate's committed seed driver must also agree. A fresh
SHA-256 receipt for substituted archive bytes cannot satisfy this chain. Source archive links,
conflicting paths, excessive entry counts and excessive decoded bytes are rejected without
extracting archive paths. The seed driver receipt identifies the retained copy, not a mutable
working-tree path.

The campaign rechecks these source/input/output receipts and uses official `apkanalyzer` and
`apksigner` before any install. These are measured workflow records, not an independent remote
attestation or a reproducible-build claim. The Git-object checks establish internal source identity;
they do not authenticate who ran the workflow or prove that an APK was produced from those sources.
Build/tool versions, logs, source archives, raw commit objects, overlay,
APK bytes/certificates and every campaign command remain in the job artifact.

On one fresh API 36 emulator, the workflow then runs these phases once:

1. **Prepare:** verify both application packages and both instrumentation packages; require no
   registered application/test package or retained uninstalled data; install and normally launch
   the actual old APK.
2. **Seed through old UI:** install its matching test-only driver, acknowledge onboarding, choose
   Light theme, toggle stylus-only on and off, choose the five-second autosave interval, create a
   64 × 64 artwork, create a custom palette and saved brush, paint through real pointer input,
   use **Save and leave**, reopen and compare pixels, then paint again and wait for the old app's
   actual autosave timer. There are no repository/SQL writes or injected artwork files in this
   driver. Repository reads only observe the results. The historical unlabeled colour chip is
   selected by its unique actual 34 dp semantic bounds; the retained tree and the asserted opened
   Colours panel expose that baseline-specific interaction.
3. **Freeze:** require the driver's one passing test and complete token/APK/source-bound proof;
   force-stop the old application and retain its real database, WAL, artwork and timestamps.
4. **Upgrade:** verify unchanged stopped data, install the candidate with `-r`, verify unchanged
   data before first launch, launch the real candidate, then replace the test APK with `-r -t`.
   No uninstall or data-clear command is used. Execute recovery/edit/save and fresh-process reopen
   through the candidate's existing campaign mode.

The old UI driver only appears in the separate historical test APK. It does not enlarge, skip or
replace the candidate's ordinary full-matrix test inventory. Its report proves automated UI
observations; the automatic path never substitutes a named operator's attestation. The candidate
reports the actual old and current database versions. For v1, the only accepted new project column
is nullable `stack`, with NULL values in all legacy rows; all original columns, SQL storage classes,
values and row counts remain exact before editing. A future or unrecognized schema is rejected.

The automation entry point is [run_installed_upgrade_ci.py](../../.github/scripts/run_installed_upgrade_ci.py).
It requires a completed build receipt and executes prepare → seed → freeze → upgrade without retries.
The required workflow conclusion checks both the installed-v1 flag and increasing version scope.
Failed initial attempts and all partial evidence are retained; a new attempt requires a new output
and fresh disposable emulator.

Historical seed selectors use the pinned application's actual semantics. The retained settings
tree showed both the interface-scale and autosave sliders sharing the autosave label's semantic
siblings, so sibling text alone was ambiguous. The driver selects the autosave slider's exact
5,000–300,000 ms range, performs its real `SetProgress` action, and still waits for the persisted
5,000 ms setting. The saved-brush button is in a fixed toolbar; the driver waits for its enabled,
visible state and clicks it without requesting a nonexistent scrollable ancestor. The pinned view
has no preview-ready signal, so before either stroke the driver observes its real coordinate
transform and valid surface: the document center must be (32, 32), and both intended stroke paths
must fall within the 64 × 64 artwork. The remaining selectors match the pinned UI, including the
initially empty artwork and saved-brush name fields. These are test-overlay repairs only. They do
not modify historical production source or turn a retained failed campaign into a passing one;
a new complete runtime campaign remains required.

## Required artifacts and device

Supply all of the following before `prepare` can install anything:

- One disposable device's exact ADB serial, with the primary Android user active. Neither
  `com.artflow.studio` nor `com.artflow.studio.test` may already be registered, including an
  uninstalled package whose data was retained. The tool will not remove them to make a run pass.
- A verified old debug APK, the candidate debug APK and that candidate's debug instrumentation
  APK, each with an independently recorded SHA-256. The instrumentation APK must include
  `PackageUpgradeWorkflow` and target `com.artflow.studio.HiltTestRunner`.
- Exact 40-character historical and candidate source revisions and an explicit baseline origin:
  `archived-apk` or `rebuilt-pinned-source`. For manual archived-package campaigns, source-to-artifact provenance is supplied by the
  operator. Automatic campaigns require the measured clean-source/test-overlay build receipt.
  Both paths pin the actual APK bytes and installed copies.
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

## Execute a manual campaign

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
The candidate application install is `adb -s SERIAL install -r CANDIDATE`; no downgrade,
uninstall or data-clear flag is used. Immediately after replacement, before candidate launch,
another snapshot must preserve every original file and logical database row.

The real candidate is launched normally, then stopped. Its matching instrumentation APK is
installed or replaces the old UI driver with `-r -t`, and a nonce-bound baseline is copied into a separate private campaign directory.
Two bounded invocations select the existing
`OptimizedArtworkWorkflowTest#realPointerPaintSurvivesSaveFreshActivityReopenAndUiPngExport`
method with explicit campaign arguments. Its ordinary full-matrix assertions remain the default;
the helper declares no skipped or extra tests.

| Candidate phase | Required observations |
| --- | --- |
| `edit` | Exact old project, legacy-brush, settings and sequence rows (plus only the nullable v1→v2 `stack` addition when applicable); unchanged old artwork bytes and timestamps before editing; decoded palette/brush library; saved canvas pixels equal the old app's flattened PNG; distinct newer recovery decoded and selected through the real recovery dialog; real pointer input changes pixels; **Save and leave** persists those edited pixels. |
| `reopen` | A different process ID, linked to the edit checkpoint; gallery reopen through `MainActivity`; exact edited-pixel hash and saved PNG equality; no unsaved edits or pending recovery; preserved settings and every other project row. |

The old saved and recovery images are read through an independent candidate repository before
the UI phase. This verifies usability of the preserved original files; it does not independently
reimplement the old renderer. The old saved composite PNG supplies the independent saved-picture
comparison. The automatic old-app driver independently asserts its own saved/reopened pixels and records
the seed screenshots; manual campaigns retain the operator's explicit save/reopen observation.
These two evidence boundaries are labeled separately.

The script requires exactly one matching test-start/test-pass pair, the selected class/method,
one declared/current test, and one successful instrumentation termination **and** the complete phase report,
matching token, baseline hash, old/candidate APK hashes, project ID and assertion set. A normal
workflow pass, summary-only success, duplicated/extra/skipped test, stale JSON, omitted assertion
or instrumentation timeout cannot qualify the run.
It then preserves the stopped post-edit data. The complete campaign directory, including APKs,
private test data, command logs, screenshots and reports, is the review artifact.

## Failure retention and validation

Every subprocess has a timeout and preserves argv, stdout, stderr, exit status and timing.
Each phase additionally captures logcat, package state, the screen and any probe evidence without
clearing logs. There are no automatic reruns, uninstall commands, database deletion, package data
clearing, or cleanup of the original evidence. A refused repeat leaves the first outcome intact.
Use a new disposable device and output directory for another campaign; retain the failed one.
An exclusive campaign-wide active-phase claim also prevents different phases from overlapping.
An interrupted process retains this claim, so another phase cannot continue a half-completed
attempt. It is released only after an orderly phase finishes and its diagnostics are captured.
Immutable command/manifest/build reports are created exclusively. Mutable campaign state is
replaced atomically through an unpredictable private staging file, without following linked
outputs or reusing a predictable partial file. Nonfinite JSON evidence is rejected. Workflow
artifact names include the run and attempt, retaining earlier attempts rather than colliding
with or replacing their artifact.

Host regression checks:

```bash
python3 -m unittest discover -s .github/scripts -p 'test_run_package_upgrade.py' -v
```

The extended host checks pass 26 campaign cases and 14 build-provenance cases. Run the latter
with `python3 -m unittest discover -s .github/scripts -p 'test_package_upgrade_build.py' -v`.
These checks establish host-tool behavior only; Android compilation and the actual installed-v1
workflow must pass separately. No host fixture establishes hardware execution, a publisher
signature or original-distribution provenance.
