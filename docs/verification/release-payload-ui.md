# Normal release payload UI qualification

The `Normal release payload UI qualification` workflow exercises the ordinary
`:app:assembleRelease` APK through a separate `:release-ui` APK. The app keeps its
production `Application`, production manifest and normal R8/resource-shrinker rules.
The UI driver has its own package, process and instrumentation target. It does not
link to application classes, inject a repository, read the app's private files, or
replace Hilt's application graph. This closes a different gap from the benchmark
instrumentation lane, whose generated test ABI deliberately retains application code.

## What a passing attempt establishes

The job records the clean source revision/tree, raw Git commit object, and compressed
source archive before building. Preparation and final verification rehash the commit
and reconstruct the complete Git tree from every archived path, executable mode, and
file's contents. Updating an archive's own SHA-256 cannot make different source bytes
claim the recorded commit. The prepared build is bound to the exact source receipt.
It retains the ordinary unsigned release, then signs a copy using a newly generated
two-day disposable certificate. The private key is destroyed after signing and is
excluded from uploaded evidence. Official `apksigner` verifies the copy, and the host
compares every uncompressed ZIP payload entry before and after signing. Only JAR
signature metadata and the APK signing block are outside that comparison.

Official `apkanalyzer` output must show a non-debuggable, non-`testOnly` production
app without instrumentation/test hosts and a separate driver that instruments itself.
The driver APK must contain no app package classes, and the app must contain no driver
package classes. The retained release mapping must show real app-class obfuscation,
with no benchmark test ABI or reflection keeps. Mapping, configuration, removed-code
and resource-shrinker reports are retained together.

The host accepts only an explicitly selected API 36 emulator with 4 KB pages and
neither app nor test package registered, including retained uninstalled data. It never
uninstalls or clears an existing installation. It installs the release copy without
`-t`, installs the separate driver, pulls both installed APKs, and compares their hashes
with the retained build inputs. Package-manager flags and the driver's runtime package
checks both require the app to be non-debuggable and non-`testOnly`.

The only test uses the visible UI to:

1. Complete onboarding, select the light theme, and disable stylus-only input/autosave.
2. Create a 64 × 64 artwork, observe its rendered paper, and inject a pointer stroke.
3. Leave the canvas using **Save and leave**, then reopen the saved artwork.
4. Export a flattened PNG and select the driver's **Qualification PNG receiver** in
   the real Android share sheet. The receiver opens the actual FileProvider URI with
   its read grant and verifies the matching ClipData and filename.
5. Force-stop the production package, observe its process exit, launch its real entry
   point in a different process, reopen the saved project, and export/share again.
6. Require all 4,096 decoded ARGB pixels to be opaque, nonblank, partly white and equal
   before/after process death. The host independently decodes both retained PNGs and
   compares them to every recorded pixel, not merely a screenshot or thumbnail.

Each attempt has a fresh token and immutable host/device receipts. Only one exact
started/passed test is accepted. Skips, extra tests, timeouts, missing screenshots,
missing share grants, mismatched APKs, stale receipts and repeat attempts fail. The
first failed test captures its visible hierarchy/screenshot; command stdout/stderr,
logcat, final screenshot, installed binaries and any partial device evidence survive
failure. CI performs no hidden retry. Artifact names include the GitHub run attempt
so a manually rerun job does not replace an earlier failure's evidence.

## Scope and use

The workflow runs for pull requests, pushes to `main`, and manual dispatch. Its
artifact is the evidence; adding the workflow or compiling the driver is not a
successful runtime qualification. A successful report contains `success: true`, the
exact revision/token, installed APK hashes, two distinct app PIDs, both PNG receipts,
all-pixel comparison, and the visible UI captures.

This is **normal release payload qualification with disposable signing**. The report
always leaves `publisherQualified`, `storeInstallQualified` and
`physicalDeviceQualified` false. It does not establish publisher certificate lineage,
store delivery/splits, hardware stylus behavior, performance, all devices, or overall
production readiness. Source/build receipts are workflow observations, not remote
attestation or proof of a reproducible build. Publisher-artifact, installed-upgrade,
physical-device and other release gates remain separate.

## Retained Settings-driver regression

The first attempt in [run 44563685734](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/runs/44563685734)
failed before artwork creation while finding **Stylus only**. The branch head was
`628466ec0727c56317c8e4094a493f68dcf13e96`; the actual PR checkout recorded by the
driver was `10b5a7b5ccb164dba841a630af261c1e09467425`. That attempt remains a failure.
Its 30,005,120-byte artifact, `release-payload-ui-api-36-44563685734-attempt-1`
(artifact ID `14550459363`), was downloaded and its SHA-256 independently matched
the provider digest `c5e7aff108b012ca3e7e3677de2580bcc75c575ed327cb7fe0f215ac9cff6424`.

The driver required label text and `checkable=true` on the same accessibility node.
The retained Settings hierarchy exposes each visible switch as a checkable parent
row with its label in a separate child TextView. The final capture is back at the
top of Settings after the unsuccessful bounded scroll search. The common Settings
`SwitchRow` also renders **Stylus only** and **Autosave** using this structure.

The repair scrolls to the exact visible label, resolves its nearest checkable
ancestor inside the visible application tree, and requires a unique enabled,
clickable control. Multiple distinct matching controls fail; duplicate label nodes
in one control resolve to the same control. After at most one click, the driver
resolves the current control again and verifies its checked state within the
unchanged deadline. Directly labeled export switches are supported by the same
resolver. No coordinates, hidden app state, additional test attempts, or increased
deadlines are introduced.

The original hierarchy SHA-256 is
`d310523ee74e6303d0123c154ca66adc54b41b16d8593aa643a3c867889f1d6d`;
the original failure PNG SHA-256 is
`54fccf3542b3aa4843ac655a33e527e05db0b683701f3662e972966ccd0d8f34`.
The regression fixture at
`.github/scripts/fixtures/release-ui/settings-checkable-rows.xml` retains the three
visible switch-row subtrees without changing their attributes or descendants.
Its wrapper is reduced to the scroll container. The host regression compiles and
executes the same Java resolver used by the Android driver against those rows and
tests direct descriptions, duplicate labels, ambiguous controls, unrelated siblings,
package boundaries, hidden labels, and missing labels. These host checks establish
the selection repair; the full ordinary-release workflow still requires a fresh
passing attempt on the final source revision.

For a clean checkout and a fresh local disposable emulator:

```bash
python .github/scripts/release_payload_ui.py record-source --output /tmp/artflow-release-ui-evidence
./gradlew :app:assembleRelease :release-ui:assembleRelease --stacktrace
python .github/scripts/release_payload_ui.py prepare \
  --output /tmp/artflow-release-ui-evidence \
  --apkanalyzer "$ANDROID_HOME/cmdline-tools/latest/bin/apkanalyzer" \
  --apksigner "$ANDROID_HOME/build-tools/36.0.0/apksigner"
python .github/scripts/release_payload_ui.py run \
  --output /tmp/artflow-release-ui-evidence --serial emulator-5554 --disposable-emulator
python .github/scripts/release_payload_ui.py verify --output /tmp/artflow-release-ui-evidence
```

The driver uses AGP's separate test-module/self-instrumenting configuration as in
[AndroidX's official pinned macrobenchmark sample](https://android.googlesource.com/platform/frameworks/support/+/87c1d4f6ab00fc194ac003a04b7a64cfdc1b8e5f/activity/integration-tests/macrobenchmark/build.gradle).
It uses AndroidX Test runner 1.7.0/ext-junit 1.3.0, matching the app's current test
dependencies, and [UiAutomator 2.4.1](https://developer.android.com/jetpack/androidx/releases/test-uiautomator).
These dependencies are packaged only in the driver; the app's release SBOM remains
scoped to `:app:releaseRuntimeClasspath`. The driver build script is included in the
build-input fingerprint so adding or changing the module invalidates stale evidence.
