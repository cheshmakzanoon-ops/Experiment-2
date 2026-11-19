# Publisher artifact qualification

The [publisher verifier](../../.github/scripts/qualify_publisher_artifact.py) checks an actual
APK or AAB, explicit certificate approval, and retained source/build evidence. It reuses
`verify_android_artifacts.verify_archive` for ZIP/native alignment checks. The existing
archive-only command remains suitable for **unsigned CI candidates**; its `PASS` has never
been publisher approval.

This gate has no signing-key discovery and never opens a private keystore. The publisher
still builds the release with `-PrequireReleaseSigning=true` using the existing private,
git-ignored configuration. Do not weaken that build guard or use the benchmark variant.

## What is checked

| Check | Required evidence and behavior |
| --- | --- |
| Actual package identity | `apkanalyzer manifest print` for APK; official `bundletool validate` and `dump manifest` for every AAB module. Production package `com.artflow.studio`, explicit expected version code/name, minimum API 26 and target API 36 must match the packaged manifest. A missing SDK declaration, unexpected major version, or maximum-SDK restriction fails. |
| Production manifest/code | Non-debuggable, no `testOnly`, no instrumentation runner, and no registered test host. Known ArtFlow/Hilt/AndroidX test hosts and runners are also rejected in DEX descriptors. The benchmark variant's shared device-test host and debug certificate are disqualifying. |
| Existing binary preflight | ZIP integrity, duplicate-path rejection, packaged native ABI/ELF/16 KB checks from the existing verifier. Additional bounded archive/path checks run before parsing. Binary alignment is not a physical-device result. |
| APK signature | Official `apksigner verify --verbose --print-certs-pem -Werr` must succeed across the APK's declared supported Android range. The certificate DER SHA-256 must equal the caller's approved **app-signing** fingerprint. |
| AAB signature | Extract the public signing certificate with `keytool -printcert -rfc -jarfile`; match its DER SHA-256 to the approved **upload** fingerprint, check its X.509 validity, and require `jarsigner -verify -strict` against a temporary store containing only that public certificate. Signature tampering, unsigned payload entries, weak/disabled-algorithm warnings, and unknown signers fail. |
| Approval | Retain an explicit approval JSON for this package, certificate role and SHA-256, including owner, timestamp, certificate source, and approval reference. Neither the tool nor the examples invent these facts. |
| Provenance | Retain CI source/build ZIPs, their original provider artifact metadata, and the exact successful run/attempt metadata. Match downloaded bytes to provider SHA-256/size, repository/revision/run, and artifact creation times within that attempt. Check the source `REVISION.txt` and git archive revision. Compare every candidate payload entry with the retained CI package, excluding only top-level JAR signature metadata. |

Android distinguishes the upload key used for a bundle from the app-signing key used for
installed APKs. Passing the bundle check does **not** establish the certificate of an APK
that Google Play later delivers. See Android's [signing guide](https://developer.android.com/studio/publish/app-signing),
[APK signature verifier](https://developer.android.com/tools/apksigner),
[APK manifest tool](https://developer.android.com/tools/apkanalyzer),
[bundletool](https://developer.android.com/tools/bundletool), and the JDK's
[strict JAR verification reference](https://docs.oracle.com/en/java/javase/17/docs/specs/man/jarsigner.html).

Android publisher certificates are normally self-signed. Silently accepting JDK exit
code 4 would also hide expiry and disabled-algorithm failures. This verifier does not
ignore that status: it checks validity separately and uses an exact approved public
certificate trust store for strict verification. The store's fixed password protects
no secret; it contains no private key and is deleted after verification. Multiple
certificate chains or signers are conservatively rejected. Existing signing-key
rotations and Play-generated split APK provenance need separate reviewed support;
this tool does not certify upgrade compatibility or an old signer on every OS version.

## Publisher-supplied approval

Create a retained approval record with real values from the publisher's approved
certificate records. This template is intentionally incomplete and will not pass:

```json
{
  "schemaVersion": 1,
  "approvedForPublisherRelease": true,
  "packageName": "com.artflow.studio",
  "certificateRole": "upload",
  "certificateSha256": "<approved upload certificate DER SHA-256>",
  "approvedBy": "<actual release owner>",
  "approvedAt": "<actual ISO-8601 timestamp with timezone>",
  "certificateSource": "<publisher record or Play Console certificate reference>",
  "approvalReference": "<durable approval reference>"
}
```

For a standalone production APK, use `app-signing` and its approved app-signing
certificate. An upload-certificate approval cannot be relabelled for APK acceptance.
Even an approval listing the normal Android debug certificate is rejected. The
verifier checks the approval's consistency; it does not authenticate the person who
wrote it. The release owner must obtain and review that approval through the real
publisher process.

## Retained CI provenance

Preserve the original downloaded ZIPs and the JSON responses for the CI run/attempt
and each provider artifact. GitHub's [artifact API](https://docs.github.com/en/rest/actions/artifacts)
defines the artifact identity, digest, byte size and workflow link. Do not substitute
a hand-written SHA string for the actual source archive or build artifact. Use a
provenance JSON alongside those files:

```json
{
  "schemaVersion": 1,
  "buildType": "release",
  "revision": "<exact final 40-character source revision>",
  "runAttempt": 1,
  "runMetadata": "run-attempt.json",
  "sourceArtifact": {
    "archive": "artflow-source.zip",
    "metadata": "source-artifact-metadata.json"
  },
  "buildArtifact": {
    "archive": "artflow-builds-unverified.zip",
    "metadata": "build-artifact-metadata.json",
    "packageEntry": "<exact path to app-release.aab inside the retained ZIP>"
  }
}
```

All paths are relative to this provenance file, remain within its directory, and
must refer to regular retained files. The source ZIP must contain unique
`REVISION.txt` and `artflow-source.tar.gz` entries from the existing CI source archive
step. The gzip tar's git revision and build-file presence are checked without
extracting source onto the filesystem. The CI run's `head_sha` must equal the supplied
final revision: a PR head paired with a different tested merge revision cannot pass
as final-revision provenance. Artifacts from a carried-forward earlier attempt are
rejected by creation time instead of being silently attributed to the latest rerun.

Signing a retained unsigned CI bundle changes its signature metadata. Comparing the
remaining entry names, sizes and hashes binds the signed candidate to that retained
payload. Rebuilding different payload bytes locally fails this comparison; retain
the actual corresponding publisher build in a reviewed CI workflow instead of
relaxing the comparison. Current CI's debug APK cannot supply a production APK: a
standalone APK requires retained production APK evidence of the same type. AAB-to-APK
generation and Play's transformations are not treated as byte-equivalent operations.

This is an **evidence linkage**, not independent reproducible-build proof. The tool
does not authenticate retained metadata online, validate a signed build attestation,
or rebuild source to compare outputs. Its report always says
`sourceToBinaryReproduced: false`. Original provider metadata and the identity of its
trusted retrieval process must remain reviewable with the signed candidate. The
release reviewer must also verify that the workflow/job and retained build artifact
are approved for production release. A caller's `buildType: release` declaration and
matching payload do not independently establish that workflow or artifact role.

## Run the gate

Use the official Android SDK command-line/build tools, JDK 17, and a reviewed
standalone official bundletool JAR. The JAR hash and raw command logs are retained.
Supply the actual final revision, approval fingerprint, and publisher files; the
version below reflects this checkout and must be updated intentionally for a new
release version.

```bash
python .github/scripts/qualify_publisher_artifact.py /publisher/app-release.aab \
  --output-dir /publisher/evidence/artifact-qualification \
  --revision "${FINAL_SOURCE_SHA:?required}" \
  --expected-package com.artflow.studio \
  --expected-version-code 4 --expected-version-name 1.1.1 \
  --expected-min-sdk 26 --expected-target-sdk 36 \
  --certificate-role upload \
  --approved-cert-sha256 "${APPROVED_UPLOAD_CERT_SHA256:?required}" \
  --approval-record /publisher/evidence/certificate-approval.json \
  --provenance /publisher/evidence/provenance.json \
  --bundletool /publisher/tools/bundletool-all.jar
```

The output directory must be empty and must not itself be a symbolic link. Each
attempt exclusively creates `qualification-attempt.json` before checking evidence,
so concurrent verifier attempts cannot share an initially empty directory. Command
logs and `verification.json` also use exclusive creation: an existing file or
symbolic link is never overwritten or followed. Keep the claim and any logs if an
attempt fails or is interrupted; use a new empty directory for the next attempt.

Missing inputs, tools, approval facts, evidence, or failed checks produce
`UNQUALIFIED`, exit status 1, and `verification.json` with the reasons when output
creation succeeds. An output collision also exits 1 and preserves the existing
entry instead of replacing it with a new report. JSON evidence rejects duplicate
keys and non-finite numbers, including numeric overflow. No tool absence becomes a
passing skip. The parser also offers explicit tool paths (`--apksigner`, `--apkanalyzer`,
`--java`, `--jarsigner`, `--keytool`). For APK use `--certificate-role app-signing`
and its approval; bundletool is then unnecessary.

`qualified` and `publisherArtifactQualified` describe only this artifact gate.
`publicReleaseQualified` is always false because this command does not perform the
other acceptance gates. Preserve the [release sign-off](../release/RELEASE_SIGNOFF.md):
two consecutive complete green eight-job workflows and three green API35/16 KB lane
executions on the exact final source SHA, with recovery disclosed, remain required.
Physical hardware, stylus/performance, real package upgrade preservation, store
identity/declarations, prelaunch review, and staged rollout remain external work.

## Verification of this tooling

The follow-up verification executed **27 tests with 27 passes and no skips** using JDK 17
and Android SDK 36 tools. Tests generated disposable fixture certificates in temporary
directories; no existing signing material was searched or opened. Actual JDK checks
rejected unsigned/tampered bundles, unsigned payload and non-signing `META-INF`
entries, wrong fingerprints, expired certificates, and Android debug certificates.
An actual `aapt2`/D8 fixture APK passed the complete gate with synthetic approval and
provider metadata, and real `apksigner` rejected subsequent tampering. Bundle module
manifest routing uses a controlled fixture; no real publisher AAB was qualified.
The additional cases cover non-finite JSON, linked output directories, report and
command-log collisions (including broken symbolic links), and concurrent attempts.
These regressions failed against the original implementation before the fixes.
The initial implementation also ran the reused native/ZIP preflight suite with
**15/15** passes; the follow-up did not modify that verifier.

```bash
python -m unittest discover -s .github/scripts -p 'test_qualify_publisher_artifact.py' -v
python -m unittest discover -s .github/scripts -p 'test_verify_android_artifacts.py' -v
```

Set `JAVA_HOME` to JDK 17 and add its `bin`, SDK `build-tools/36.0.0`, and SDK
`cmdline-tools/latest/bin` to `PATH`; set `ANDROID_SDK_ROOT` for the API 36 fixture.
Without the official tools, ordinary discovery reports explicit integration-test
skips. Those skips are not signature execution evidence. A required CI step after
SDK build-tools 36 are provisioned must set `ARTFLOW_REQUIRE_PUBLISHER_TOOL_TESTS=1`:

```bash
ARTFLOW_REQUIRE_PUBLISHER_TOOL_TESTS=1 python -m unittest discover \
  -s .github/scripts -p 'test_qualify_publisher_artifact.py' -v
```

That mode fails if the JDK or actual APK fixture tools are unavailable. The
production verifier itself never skips a missing official tool. No Gradle build,
publisher-key operation, store upload, or real release approval was performed by
these fixture tests.
