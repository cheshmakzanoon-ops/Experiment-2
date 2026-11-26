# Release dependency integrity review — 19 November 2026

## Scope and evidence

`writeReleaseDependencyInventory` inventories `releaseRuntimeClasspath` before R8 and
resource shrinking. Its artifact hashes identify the selected AAR/JAR inputs. They do
not identify the final APK's contents, certify publisher authenticity, or inventory
build plugins, KSP processors, debug tools, or test dependencies.

The first local inventory inspected in this wave contained **150 components, 107
artifacts, 155 POMs including parents, and 892 selection edges**. Of those edges, 331
were constraints. A separate read of the local Gradle cache matched the recorded
SHA-256 and size of **all 107 artifacts**; none were missing or mismatched. The existing
verifier accepted permitted POM license declarations for all 150 components, without
changing the license policy. The inventory file's SHA-256 was
`d44a8ebdc7bb3cd1ae392cdc9a5128f03f6d85ed6b80c62be928198b8eacb341`.

That run is **historical diagnostic evidence, not qualification of the current
checkout**. Build files changed while its long POM queries were running, and the old
task read build input hashes only at the end. Its hashes could therefore describe
different files from those Gradle had evaluated. The dependency constraints and
locking configuration added below also require a new resolved inventory.

The 44 components without an artifact are metadata/platform components and
multiplatform forwarding modules in this graph. They remain visible in the inventory;
their version is not guessed from the Compose BOM. For example, the observed graph
selected Compose UI/runtime 1.9.0, foundation 1.7.3, and Material3 1.3.0 despite the
2024.09.03 BOM declaration. This review does not change that tested dependency family.

## Concrete repairs

### Reject build input changes during inventory generation

The Gradle script now captures its tracked build input hashes when the script is
applied. It compares that snapshot at task entry and again before writing the report.
An edit, addition, or removal fails the task and leaves no old report at the requested
output path. The inventory records the original snapshot; it never substitutes hashes
read after dependency resolution.

The guarantee starts at the final `apply(...)` in `app/build.gradle.kts` and lasts
through report publication. It addresses the observed concurrent edit during POM
queries. It does not claim to detect edits that happened earlier in settings/project
evaluation, changes to files outside the tracked input set, or deliberate restoration
of identical bytes between checks. The Python verifier independently rejects a report
whose tracked input hashes no longer match the checkout.

`.github/scripts/test_release_dependency_inventory_gradle.py` exercises the real task
using one local Maven fixture, with no external dependency resolution. Its five cases
cover a stable graph, an edit before task execution, an edit inside dependency
resolution, a newly added optional version catalog, and a removed build input. Negative
cases also check deletion of a pre-existing success-looking report.

The ordinary Python discovery explicitly skips these cases without
`ARTFLOW_GRADLE_BINARY`; those skips are **not passes**. Main CI supplies the provisioned
wrapper in a dedicated step after the signing-negative check:

```bash
ARTFLOW_GRADLE_BINARY="$PWD/gradlew" python -m unittest discover \
  -s .github/scripts -p 'test_release_dependency_inventory_gradle.py' -v
```

At implementation time, the existing license/SBOM suite executed **27 tests with 27
passes**. The new Gradle fixture discovery executed **0 cases and reported 5 explicit
skips**; real execution and the new resolved graph must be recorded by the subsequent
serial build/CI run. Static review of the fixture is not execution evidence.

### Constrain two existing transitive dependencies

| Observed selected input | Narrow change | Evidence and applicability |
| --- | --- | --- |
| `com.google.guava:guava:33.3.1-android`, through Media3 common | Require `33.8.0-android` | Google's advisory documents deserialization allocation denial of service, CVE-2026-102554, fixed in 33.7.2. The later 33.8.0 release also repairs hash flooding in `HashBiMap` and `LinkedHashMultimap.ValueSet` (CVE-2026-118042). [Advisory](https://github.com/google/guava/security/advisories/GHSA-xxph-c9ww-hj94), [release notes](https://github.com/google/guava/releases). |
| `com.squareup.okhttp3:okhttp:4.12.0`, through Coil base | Require `4.12.1` | Upstream's 22 October 2026 patch blocks compromised eMudhra intermediates for JVM/AOSP environments that have not received the Play Services trust update. This stays within the 4.12 series. [Official 4.x changelog](https://lysine.dev/okhttp/changelogs/changelog_4x/). |

These are dependency constraints; they upgrade an existing transitive input without
introducing a new application feature or moving to another major library line.
Google publishes the Android Guava flavor explicitly and documents Android testing on
API 24, below this application's API 26 minimum. Its non-Beta API compatibility policy
supports the limited upgrade, but successful Android compilation, D8/R8 processing,
and regression runs remain required. See [Guava's official documentation](https://guava.dev/).

Source inspection found no application `ObjectInputStream`/`readObject` boundary and
no direct Guava API use. It also found no application `CertificatePinner` or custom
OkHttp client. All three Coil `AsyncImage` sites in `GalleryLayout`, `GalleryPreview`,
and `GalleryScreen` receive local `File` objects. The main manifest does not request
`INTERNET`; the post-change merged release manifest still needs to be checked during
release qualification. These observations limit demonstrated reachability; they do
not certify that every transitive or platform code path is unreachable.

The recent upstream [CertificatePinner trailing-dot report](https://github.com/lysine-dev/okhttp/issues/9724)
has different preconditions: an application must configure certificate pins and request
the affected hostname form. No such application path was observed here. The 4.12.1
certificate block must not be described as a fix for that separate pinning report.

The selected Okio 3.6.0 is newer than its 3.4.0 GZIP-extra-field repair documented in
the [official Okio changelog](https://lysine.dev/okio/changelog/). No additional Okio
upgrade was justified by that older issue. This targeted review is not an exhaustive
vulnerability scan, and absence of another verified match is not a clean bill of health.

### Freeze release versions and verify the Gradle distribution

Only `releaseRuntimeClasspath` activates dependency locking. Strict lock mode makes
missing lock state fail for that configuration. Generate the lock in a separate
invocation and review the selected versions before producing the inventory:

```bash
./gradlew :app:dependencies --configuration releaseRuntimeClasspath --write-locks
./gradlew :app:writeReleaseDependencyInventory --stacktrace
python .github/scripts/check_licenses_and_sbom.py . \
  --resolved-inventory app/build/reports/dependencies/release-runtime.json \
  --revision "$(git rev-parse HEAD)" --sbom-out app/build/reports/dependencies/release-sbom.cdx.json
```

The generated `app/gradle.lockfile` belongs in source control. Do not combine lock or
verification-metadata generation with evidence publication: Gradle may write those
files at build completion, after the inventory snapshot was taken. A later normal
invocation must validate the resulting stable inputs. See Gradle's
[locking documentation](https://docs.gradle.org/current/userguide/dependency_locking.html).

The wrapper now requires Gradle's published 8.14.3 binary distribution SHA-256:
`bd71102213493060956ec229d946beee57158dbd89d0e62b91bca0fa2c5f3531`.
The existing wrapper JAR hashes to
`a8451eeda314d0568b5340498b36edf147a8f0d692c5ff58082d477abe9146e4`,
which is an authentic older 8.2/8.2.1 bootstrap listed by Gradle. Its older version is
not evidence of modification. [Official distribution and wrapper checksums](https://gradle.org/release-checksums/).

Full dependency verification metadata is a separate remaining task. The current
inventory hashes are measurements, not a checked-in trusted checksum baseline that
Gradle enforces before consuming every artifact. A dedicated run should resolve the
normal build/test/release configurations with `--write-verification-metadata sha256`,
review the generated evidence and its source, then rerun with verification enabled.
Do not use blanket trusted-artifact exceptions to make incomplete metadata pass.
See Gradle's [dependency verification guidance](https://docs.gradle.org/current/userguide/dependency_verification.html).

License declarations, selected-version locks, artifact checksum enforcement,
vulnerability review, final-package analysis, and publisher signing remain distinct
checks. Passing one cannot substitute for another.

### Packaged notice verification (26 November 2026)

Inspection of the diagnostic APK and AAB from revision
`d129c5a611de0f70776fe5d5abe3db6c139a0b8e`, run `45788267910`, artifact
`15102721850` (`artflow-builds-unverified`), found the exact checked-in Unicode
license, AndroidX Apache license texts, Jakarta notice and OkHttp public-suffix
notice. The downloaded artifact ZIP matched its provider SHA-256
`0d70912a8db9f8f1902ac2093cf51c2a95e10fc9346229ccbfefa7575c86cbf5`.
The only native library was `libandroidx.graphics.path.so`, packaged for
`armeabi-v7a`, `arm64-v8a`, `x86` and `x86_64`. These observations describe those
specific binaries; they are not a fresh native-device qualification.

Neither binary included the project's own GPLv3 license. The application now
packages an exact copy of the root `LICENSE` as `META-INF/ARTFLOW-LICENSE.txt`.
The artifact verifier CLI requires that file and `META-INF/UNICODE-LICENSE.txt`
to match their repository source bytes. It checks APK root resources and AAB
`base/root/` resources separately and rejects missing, truncated, modified or
misplaced notices. Existing native alignment and archive checks remain in force.
Generic `verify_archive` callers retain their optional project-notice policy;
the repository CLI always supplies the two requirements.

The notice regression suite uses synthetic APK/AAB containers and exercises the
CLI from an unrelated working directory. These checks establish byte identity
and placement. A subsequent Android build and artifact preflight must confirm
that resource packaging retains the added GPL text in actual output; the older
diagnostic artifacts remain evidence of the original omission. This scoped
change does not establish satisfaction of GPL corresponding-source delivery,
all third-party attribution obligations, publisher signing or distribution
compliance. See the [GPLv3 text](https://www.gnu.org/licenses/gpl-3.0.html).

No dependency, SDK, Gradle plugin, workflow action or test baseline changed in
this notice fix. The existing strict release lock, dependency/POM license
inventory, workflow SHA pins and known-pattern secret scanner remain separate
gates. Trusted dependency checksum enforcement and a complete current
vulnerability review remain outside the evidence supplied here.
