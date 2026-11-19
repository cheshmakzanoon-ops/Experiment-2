# Production hardening — November 19, 2026

## Scope and source

The user requested 20 waves of three agents to repair the production-readiness findings and
continue through verification. The starting main revision is
`54fa5e0a9d8ef8502d637192c9dc9748c2000dfe`. Android CI run
[44500406601](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/runs/44500406601)
completed two successful full eight-job attempts at that revision. Each device matrix covered
283 declared tests, with two permitted API-specific skips on API 26, and no internal framework
recovery in the inspected device logs. Its verification artifact records 1,751 JVM tests in 292
suites with no failures, errors, or skips. These are baseline results, not acceptance of later code.

The work targets the active offline editor and release process. A production decision requires
verified source, artifact identity, optimized user workflows, upgrade preservation, physical
device/stylus/performance evidence, and publisher approval. Missing external evidence must stay
open; engineering checks cannot manufacture it or substitute an emulator for a physical device.

## Wave record

Each numbered wave has three distinct agent assignments. The integrator reviews their combined
changes, preserves existing tests and static-analysis baselines, and records actual execution.
Planned later assignments may be refined to address concrete review findings or CI failures.
Early row statuses describe their implementation checkpoints. The dated execution entries below
record subsequent integrated checks; final exact-revision acceptance remains a separate gate.

| Wave | Three assignments | Status |
| --- | --- | --- |
| 01 | PSD allocation budget; import resource ownership; transaction design review | Implemented/reviewed; Kotlin execution pending |
| 02 | Atomic import implementation; decoder adversarial review; ownership regression review | Implemented/reviewed; runtime verification pending |
| 03 | Resolved dependency inventory; optimized release workflow coverage; database migration coverage | Implemented; execution and independent review pending |
| 04 | Transaction adversarial review; dependency report verification; release test integration review | Implemented/reviewed |
| 05 | Document-bound picker/import failures; optimized runtime integration; upgrade preservation review | Implemented/reviewed; new runtime tests pending |
| 06 | Cancellation/concurrency review; memory-budget boundaries; dependency/artifact integrity | Implemented/reviewed; expanded runtime verification continuing |
| 07 | PSD channel/color semantics; device texture limits; optimized test R8 integration | Implemented/reviewed; optimized APKs build successfully |
| 08 | Physical-performance evidence tooling; signed artifact validation; upgrade execution tooling | Implemented/reviewed; host tests and Android compilation passed |
| 09 | Image allocation bounds; preview-failure input handling; PSD compatibility and color notices | Implemented/reviewed; compilation/analysis passed, fixture repaired and rerun |
| 10 | Remaining import lifetimes; optimized test linkage; independent save/recovery audit | Integrated verification exposed six ownership failures and a runtime mapping failure; follow-ups in waves 12–13 |
| 11 | Release/version claims; model/photo decoding; installed historical-app upgrade | Implemented/reviewed; historical application and test APKs built |
| 12 | Recovery ordering and export snapshots; publisher-binary workflow; import acceptance | Implemented; focused checks passed, integrated verification continuing |
| 13 | Physical-evidence review; publisher-evidence review; optimized ABI mapping review | Implemented/reviewed; focused checks passed, Android runtime acceptance pending |
| 14 | Hostile model/archive review; project-lifecycle review; installed-upgrade evidence review | Implemented/reviewed; focused checks passed |
| 15 | USD allocation review; capability/user-message review; committed-save metadata consistency | Implemented/reviewed; 102 core 3D and 38 save/recovery-focused cases passed |
| 16 | Independent data integrity review; independent optimized-build review; independent CI gate review | Implemented/reviewed; focused regressions passed, integrated execution pending |
| 17 | Final source safety review; final test adequacy review; release decision review | Implemented/reviewed; 105 import/library and 19 harness cases passed |
| 18 | Palette identity and legacy upgrade; Android 15 acceptance; Android 16/tablet and release acceptance | Repairs and independent evidence reviews complete; new-source Android execution pending |
| 19 | Regression follow-through; artifact/evidence cross-check; documentation cross-check | All three complete; active palette callback and source-receipt regressions repaired |
| 20 | Final data safety acceptance; final engineering acceptance; final release-status acceptance | All three complete; PSD source admission and ordinary-release driver repaired; exact-source runtime gates pending |

## Findings to close

1. PSD/PSB parsing allocates decoded layers before the repository capacity check.
2. Layered imports can leave partial edits on a late failure or cancellation.
3. Procreate temporary resources can be abandoned during gallery-to-editor handoff.
4. Critical user workflows are not exercised in the optimized release-equivalent application.
5. Database/application upgrade preservation needs executable evidence.
6. The dependency inventory substitutes the Compose BOM version for member artifact versions and
   does not resolve the transitive release graph.
7. Physical performance, stylus behavior, publisher signing, and store acceptance remain external
   release gates unless actual evidence is obtained.

## Initial verification log (historical)

- Starting audit: 191 CI-script Python tests and 76 tooling Python tests passed locally.
- Build environment: JDK available; Android SDK and Gradle distribution not configured at start.
- New code had not been verified at this initial checkpoint. See subsequent records; never infer
  a pass from implementation.

## Continuation rules

Read this record, README capability boundaries, and the latest exact-revision Actions evidence
before resuming. Preserve all existing seven emulator lanes, complete test identity accounting,
first-failure diagnostics, and static-analysis baselines. Keep new changes in the isolated
integration checkout, review parallel ownership boundaries, and publish only reviewed commits.
Update this record with completed wave assignments, fixes, test outcomes, and remaining gates.

### Wave 01

- `w01_psd_budget`: added metadata-only PSD inspection and conservative preallocation budgeting,
  plus eight deterministic decoder regressions including a compressed large-layer fixture.
- `w01_import_ownership`: added awaited project creation, cancellation-safe archive ownership,
  identity-safe pending handoff cleanup and eight lifecycle regressions.
- `w01_transaction_design`: traced synchronization and rejected unsafe live rollback/mutex designs;
  specified off-main preparation and a single project/frame-bound publication with one undo.
- `git diff --check` passed. Kotlin tests were not yet executed at this checkpoint.

### Wave 02

- `w02_atomic_import`: converted image/PSD/Procreate placement to prepared, project/frame-bound
  publication with one undo boundary. Added late failure, cancellation, stale destination, profile,
  transparent-layer preservation and undo/redo regressions; retained capacity-failure assertions.
- `w02_decoder_review`: rejects incomplete/oversized/unknown compressed PSD channels, closes native
  inflater resources on all exits, and resolves new complexity/class-size findings. Assigned-file
  ktlint and detekt checks passed without changing baselines.
- `w02_ownership_review`: added cancellation polling, original-failure preservation, owned-prefix
  orphan cleanup, replacement/claim regressions, and a completion guardian for never-started editor
  jobs. Gallery PSD preflight now inspects metadata without decoding pixels.
- Build Tools 36.0.0 is now explicit, matching the documented build contract. Local tooling setup
  exposed an installed JRE without javac; a verified full JDK 17 and Android SDK 36 are now
  installed. Targeted tests are running after configuring the managed proxy trust store.

### Wave 03

- `w03_resolved_dependencies`: replaced the declaration-derived SBOM with the resolved release
  runtime graph, artifact hashes, hash-verified raw Maven POMs, inherited license evidence, and
  build-input fingerprints. Twenty-three focused Python tests passed.
- `w03_optimized_qualification`: added a separately selected benchmark instrumentation variant,
  installed-build assertions, a real paint/save/fresh-Activity reopen/PNG export workflow, and a
  fixed API 36 qualification lane. The verifier checks the exact selected class inventory,
  installed APK bytes, R8/resource-shrinker evidence, and one attempt without skips or retries.
- `w03_room_upgrade`: added four real SQLite/production Room regressions for v1-to-v2 preservation,
  absent-migration rejection/retry, validation rollback, and unknown-future-version rejection.
  Fixtures include referenced artwork/recovery and settings; physical old-APK upgrade remains open.
- Root integrated resolved dependency verification into the existing main workflow and published
  waves 01–02 in draft [PR 41](https://github.com/cheshmakzanoon-ops/Experiment-2/pull/41).
- Local production and JVM test sources compiled. The first targeted run executed 103 cases:
  81 passed and 22 failed during Mockito initialization because the local runtime could not attach
  its instrumentation agent. The same Byte Buddy agent is now preloaded in a local-only Gradle
  init script; no application/test assertion was changed. Regression execution is being repeated.

- With the agent preloaded, 102 of 103 targeted cases passed. The remaining late-mask regression
  used an all-white mask that the writer correctly omitted, so the intended I/O failure never
  occurred. Wave 04 is correcting the fixture to require an actual stored mask tile.

### Wave 04

- `w04_atomic_adversarial`: corrected the native-mask fixture so it contains a real tile and checks
  the injected storage exception; the original artwork/history equality assertion is unchanged.
  Independent source review found no additional confirmed atomic publication defect.
- `w04_dependency_review`: rejected UTF-16 XML DTD/entity evidence, separated version constraints
  from actual CycloneDX dependency edges, and rejected contradictory GPL/permissive declarations
  without exporting a fabricated permissive license. Twenty-seven dependency tests passed.
- `w04_release_integration`: preserved first-attempt diagnostics on refused/concurrent reruns and
  made malformed installed-APK JSON produce failed reports instead of escaping report generation.
  Twenty-one verifier and eight harness tests passed; focused Kotlin formatting passed.
- Root compiled all debug instrumentation sources successfully, including the new migration and
  optimized workflow tests. Real dependency inventory generation and device execution are pending.

### Wave 05

- `w05_import_destination`: starts the existing atomic import before a picker/camera launches,
  binds each late result to its own request, and runs image/PSD reads inside preparation. Added
  twelve controller JVM regressions and two Android URI/private-photo/persistence regressions.
  Navigation, cancellation, replacement requests, failed launches and bitmap cleanup are covered.
- `w05_optimized_runtime`: traced the first optimized CI failure to a debug-only instrumentation
  host and shared that host between debug and benchmark while keeping publisher release separate.
  Added all four migration cases to the optimized inventory (43 cases across ten classes).
- `w05_upgrade_review`: retained actual SQLite storage types in rollback snapshots and strengthened
  error-path and isolated-database assertions. Documented an identified historical APK candidate,
  including that it already uses Room v2 and cannot prove an installed v1-to-v2 upgrade.
- Root verification: all 245 CI-script Python tests passed at the wave 04 checkpoint. CI run
  44532670270 executed 1,790 JVM tests with the single already-corrected mask-fixture failure;
  its resolved dependency gate recorded 150 components and 107 artifacts with permitted POM
  declarations. Local debug instrumentation and the resolved inventory task also completed.
- Optimized run 44532670562 failed before instrumentation on its missing test host. The host fix
  and wave 04 repairs are published at 1402c7e752a50861a35d7f502534cd023846f59a; execution is
  being repeated. None of these incomplete runs is counted as release acceptance.

### Wave 06

- `w06_decoder_boundaries`: reproduced fabricated pixels from truncated raw and RLE composites,
  then enforced exact merged sample/row boundaries, malformed-composite rejection and row-table
  memory accounting. Twelve new bounded decoder cases passed in the root's targeted run.
- `w06_dependency_integrity`: independently checked all 107 artifact hashes in the diagnostic
  inventory; added evaluation-time input snapshots and fail-closed concurrent-change checks,
  official wrapper distribution checksum, targeted Guava/OkHttp security maintenance constraints,
  and strict release-runtime version locking. The first inventory remains diagnostic because its
  tracked inputs changed during generation. No broad vulnerability-free claim is made.
- `w06_import_concurrency`: isolated camera launch files across canvas/palette callers, added
  completion cleanup and four real ActivityResult/FileProvider regressions; bounded unclaimed PSD
  payloads by owner lifetime/deadline with four additional ownership regressions.
- Root executed 147 targeted JVM cases across 24 suites: zero failures, errors or skips. These
  include all twelve picker-controller, twelve composite-boundary, and eleven atomic-import
  cases. Debug instrumentation compilation also passed. This predates wave 06's final camera/PSD
  ownership edits; their expanded runtime checks remain pending.
- Root generated the release lock in a separate invocation (Guava 33.8.0-android and OkHttp 4.12.1)
  and executed all five real offline Gradle provenance fixtures: five passed, zero skipped.
- Follow-up review is addressing explicit PSD transparency/CMYK semantics, device texture limits,
  and the next optimized CI failure: missing compile-time annotation types while shrinking the
  instrumentation APK. Application optimization remains required.

### Wave 07

- `w07_psd_channel_semantics`: separated auxiliary channels from explicitly marked transparency,
  corrected inverted CMYK ink conversion, and bounded global tagged blocks. Added eleven
  independently constructed RGB/grayscale/CMYK PSD/PSB cases across depth/compression paths.
  Embedded ICC/spot-color reproduction remains unsupported and documented.
- `w07_canvas_limits`: honors the device's actual positive GL texture limit, retries smaller
  previews after allocation failure, and preserves full-resolution document/save/export pixels.
  Added five passing pure-JVM sizing/retry cases and two real GLES device regressions; device
  execution remains pending. Corrected unsupported large-canvas/virtual-tiling claims.
- `w07_optimized_r8`: inspected dependency bytecode and scoped two absent compiler-annotation
  warnings to benchmark instrumentation. No production optimization or runtime type was disabled.
- Root built `verifyReleaseQualificationBuildType`, `assembleBenchmark`, and
  `assembleBenchmarkAndroidTest` successfully with the benchmark test selector. Both application
  and instrumentation R8 tasks completed. The new rule file appears only in the test APK's R8
  configuration; the application's preexisting Media3 consumer rule independently mentions
  `javax.lang.model.element.Modifier`. Runtime optimized qualification is still pending.
- Read-only ADB discovery found no connected devices. Physical-device and installed-upgrade
  evidence cannot be inferred from local compilation or the emulator matrix.
- The subsequent full local JVM run executed 1,834 cases across 299 suites: 1,832 passed, zero
  failures/errors and two explicit external-corpus skips (Procreate documents and reference IPA).
  CI downloads and requires both corpora. The stable-input release inventory recorded 150
  components/107 artifacts, and all 150 components passed the POM-license evidence gate.

### Wave 08

- `w08_physical_evidence`: added read-only ADB identity/artifact capture and a campaign verifier
  requiring approved workloads, raw measurement hashes, hardware/stylus observations and calibrated
  optical latency evidence. Forty-one host tests passed. An empty real CLI campaign fails as
  `UNQUALIFIED`; no hardware/performance result is invented.
- `w08_signed_artifact_gate`: added package/version/SDK, archive, official signature, certificate
  role/validity and retained CI-payload checks. Twenty-two tests passed with real JDK/Android tools
  and disposable synthetic keys. Missing official tools fail the new required CI step; these
  fixtures do not qualify any publisher artifact.
- `w08_package_upgrade`: added a disposable-device prepare/freeze/install-r campaign and an explicit
  nonce-bound candidate recovery/edit/save/fresh-process reopen probe. Seventeen host tests passed.
  The known old package is Room v2/code 3; same-code replacement remains engineering-only, and
  neither installed v1 migration nor publisher/store acceptance is asserted.
- Root compiled all debug instrumentation successfully, including the campaign probe. The selected
  optimized inventory remains 43 cases. No device is attached and no package campaign was run.
- Integrated CI-script discovery ran 330 cases: 325 passed and the five opt-in Gradle provenance
  fixtures were explicitly skipped in that invocation. Those five passed separately in wave 06
  and remain a required dedicated CI step. Official signing-tool fixtures ran without skips.
- CI run 44534277777 exposed the new artwork workflow's PNG transparency mismatch (white editor
  composite versus default transparent export). The test now explicitly operates the visible
  flatten-white switch and retains the full pixel comparison. Failed attempts remain failed;
  subsequent complete device runs must validate the repair.

### Wave 09

- `w09_image_decode_budget`: fixed just-under-double-edge sampling, pre-array dimension checks,
  simultaneous decoder/copy allocations and changing-provider reads. Photo URI/file decoding uses
  one bounded immutable source and a heap/headroom-aware allocation plan; native artwork remains
  exact-size. Eleven pure JVM cases passed; nine real-platform cases compiled but need execution.
- `w09_preview_availability`: added typed primary-preview availability, generation/interruption
  guards, upload recovery and attachment-bound callbacks. Failed previews cancel/block canvas
  pointer edits while preserving save/navigation. Provisional shape rollback is guarded by exact
  destination/revision/history identity. Ten affected pure cases passed; six new GLES/view cases
  need device execution. Existing helpers now wait for an actual displayed preview.
- `w09_psd_compatibility`: bounded all image-resource records and padding; added conservative
  layerless Alpha Identifiers handling and post-commit notices for approximate CMYK, unapplied ICC,
  unused extra channels and flattened alternate-layer fallback. Ninety-six PSD/PSB cases passed.
  A pinned independent producer-file probe inspected seven supported files and exactly matched
  143,600 pixels in an explicitly derived layerless fixture. Four controller cases await Gradle.

- Root traced the Android 15/4 KB failure in run 44534277777 to concurrent export-directory
  initialization: `mkdirs()` can return false because the other caller just created the directory.
  Project/export/atomic-write directory setup now accepts a verified existing directory after that
  race and rechecks linked-path guards. Eighteen storage-tree tests passed, including deterministic
  competing creation, existing-file preservation and a late symbolic-link rejection. Assigned-file
  ktlint and detekt passed without baseline changes. Complete Android validation remains pending.
- Wave 08's optimized run 44540625809 built both APKs, then the actual runner failed before
  executing any of 43 cases with `NoClassDefFoundError: androidx.tracing.Trace`. This is recorded
  as failed qualification. Wave 10 is inspecting the app/test dependency boundary and R8 output;
  application optimization and full case accounting remain required.
- Frozen wave 09 production and all instrumentation sources compiled. Full ktlint and detekt
  passed with unchanged baselines. The full JVM run executed 1,864 cases: 1,861 passed, two
  external-corpus skips and one new-test failure because both retained/imported layers were named
  `Background`. The fixture now identifies the added layer by ID, asserts original IDs survive,
  and retains every pixel/history assertion. All four controller cases passed on the focused rerun.


### Wave 10

- `w10_import_lifecycle`: added shared retained photo/model admission and reserve-before-create
  handoffs, owner/deadline expiry, explicit model claims that preserve retryable unsaved work,
  deletion cleanup after successful row deletion, and guarded camera orphan cleanup. All 32 new
  tests and 19 existing Procreate ownership cases passed standalone. Changed Android callers and
  instrumentation compiled; existing model suites still need the normal Gradle test runtime.
- `w10_save_recovery_audit`: reproduced mixed-revision native exports and overlapping pin loss.
  Scoped package snapshots now capture exact committed manifest bytes and retain referenced raster
  generations until archive completion. Counted leases and acquisition/pruning synchronization
  protect overlapping exports; cancellation/error cleanup releases ownership. Thirty-four storage
  cases passed, including five new snapshot regressions and the 18 directory-tree cases.
- `w10_optimized_test_linkage`: uses AGP's public pre-R8 class artifacts and bundled R8
  TraceReferences to retain the exact application ABI called by separately packaged instrumentation.
  Publisher rules are unchanged; benchmark-specific constraints are explicitly recorded. Five real
  R8 execution fixtures, 33 qualification tests, 28 dependency-evidence tests and eight harness
  tests passed. Root's real-project generation compiled both source sets and passed in 3m34s,
  tracing 893 classes, 241 fields and 1,993 methods from 2,592 test classes. This is build evidence,
  not a successful optimized Android execution.
- API26 in run 44540625922 completed 297 declared identities: 294 passed, one failed and two
  permitted API-specific skips. The new flatten-white test matcher saw both unlabeled switches.
  The switches now have accessible names and the test selects the exact visible control, retaining
  off/on and complete exported-pixel assertions. Other lanes and a new revision must be evaluated.
- The first root Python discovery lacked ANDROID_HOME for its required real-APK fixture and failed
  that environment precondition. With the configured SDK, the intermediate discovery ran 340 cases:
  335 passed and the five separately required Gradle provenance fixtures were explicitly skipped.
  The final verifier added three more adversarial cases afterward; frozen-checkout discovery is due.

### Wave 11

- `w11_release_contract`: reconciled eight current/historical documents, preserving earlier failure
  records while removing absolute data-loss/determinism and every-edit timelapse promises. Checked
  107 local Markdown links. Root designated the maintenance candidate 1.1.1/code4, following known
  code3 builds; publisher-used codes and signing identity still require actual account evidence.
- `w11_model_decode`: preflights every retained texture and the actual atlas geometry before pixel
  allocation, budgets simultaneous decode/copy/atlas/install buffers, and refuses invalid or oversized
  referenced textures instead of silently dropping them. File and Android 8 URI photo decoding honor
  all eight EXIF orientations from the same bounded source; model UV images retain raw orientation.
  Forty-four focused pure tests passed and four new platform tests await Android execution. Exif
  1.4.2 is explicitly resolved and locked; native artwork decoding keeps exact dimensions.
- `w11_installed_upgrade`: added automatic real-old-app UI seeding and increasing-version installed
  replacement with source/APK/overlay hashes, frozen stopped storage, and recovery/edit/save/reopen
  checks. Thirty host cases passed. The real Room-v1
  source ca9ce535c09d35ceca6aba9f686c60f58f93bc09 has the same committed schema1 SHA-256
  afbdb33dcf9e2e6f2610560ca1adf4cd876c5241a3f56b62a162361c5c493dde. Root built its unchanged
  production source and the test-only seed overlay with the authentic SDK Build Tools 35.0.0.
  Both APKs compiled successfully. No installed-v1 execution is claimed yet; this is rebuilt pinned
  source with disposable signing, not proof about an originally distributed publisher APK.

### Wave 12

- `w12_recovery_ordering`: established checked saved/autosave timestamp ordering on the temporary
  inode before fsync/rename; retained captured model/preview inodes for deferred export reads;
  and blocked blank-fallback save/export when a first autosave is the only surviving document.
  Fifty-eight focused JVM cases passed, including fifteen new cases. Snapshot protection assumes
  cooperative atomic writers and does not claim a transaction across independent external writes.
- `w12_import_acceptance`: reproduced the six integrated failures, then repaired model ownership
  before the first asynchronous lookup/preparation. The same seventeen original tests passed with
  production-only repairs. Added identity-safe stale-claim and cancellation/deletion regressions.
  A controlled three-decoder probe also demonstrated overlapping headroom spending without
  allocating texture arrays. Photo/model preparation now has a synchronous, reentrant, fail-fast
  admission boundary before source reads; a second operation receives a retry message. All 104
  focused cases passed. Geometry/archive expansion and other import families remain separate.
- `w12_release_payload_ui`: added a separate Java/UIAutomator test module which observes the normal
  release APK through visible UI and a real FileProvider share recipient. The workflow compares
  all 4,096 exported pixels before/after a force-stop and new app process, verifies unchanged release
  payload when applying a disposable signature, and retains the exact source/artifact identity.
  Sixteen host regressions, 28 dependency checks and three workflow-pin checks passed. Root built
  both APKs, including normal release R8/resource shrinking and release lint. The actual packaged
  manifest targets only the separate test package. APK inspection confirms code4/1.1.1, API26–36,
  non-debuggable/non-testOnly application flags, 1,751 renamed application classes and no benchmark
  retention rules in normal release. These are local build facts, not Android runtime acceptance.
- Frozen checkpoint `af7ff79` built both optimized APKs and passed ktlint/detekt with unchanged
  baselines. Its complete JVM run had 1,901 cases: 1,893 passed, six ownership failures and two
  explicit external-corpus skips. The same six failed in Actions 44549867769 and are repaired above.
  Frozen Python discovery ran 343 cases: 338 passed and five separately required Gradle provenance
  fixtures were explicit opt-in skips. Failed runs remain failed; newer source needs full execution.
- Optimized Actions 44549867524 progressed to two completed cases, then failed on a Kotlin
  `ContinuationInterceptor.DefaultImpls` call whose class was remapped but method name was not.
  It does not qualify any of the required 43-case optimized inventory; wave 13 owns the repair.

### Wave 13

- `w13_physical_evidence_review`: rejected reviews preceding required post-capture completion,
  exclusive-output collisions/symlinks, and nonfinite derived uncertainty. All 44 host cases passed,
  with three new failures reproduced against the previous implementation. Reports explicitly
  identify caller-supplied evidence as unauthenticated. No actual hardware campaign is claimed.
- `w13_publisher_evidence_review`: rejected linked evidence outputs, concurrent first-attempt
  overwrite, preexisting logs/reports and nonfinite JSON evidence. All 27 cases passed with required
  official JDK/Android tools and disposable test certificates. The offline verifier does not
  authenticate caller-supplied workflow/artifact-role metadata; publisher review remains required.
- `w13_optimized_abi_review`: confirmed the mismatched method call in both actual APK DEX files
  and reproduced it with actual Kotlin 2.1.20/bundled R8 bytecode. Exact traced external ABI names
  now stay stable in benchmark; publisher rules and all global application optimization remain
  intact. Six real-R8 fixtures passed, requiring internal helper obfuscation and unused-code
  removal, plus 37 qualification, 28 dependency and eight harness cases. Required fixture evidence
  is bound to the actual application input bytes and R8 tool hash. The 43-case Android run remains
  required; a passing host fixture cannot replace it.
- Frozen wave-12 host discovery executed 380 cases: 375 passed and five opt-in Gradle fixtures
  were explicitly skipped for their separate required invocation. An earlier local invocation
  incorrectly replaced PATH and hid the primary Python executable; its 19 harness failures are
  retained. Restoring inherited PATH made the unchanged suite pass. Normal release APK inspection
  also required local `-XX:-UsePerfData` to avoid a shared-/tmp PID-namespace JVM warning on stdout;
  the XML parser was not weakened or changed. Frozen `66752d0` subsequently passed full Gradle
  JVM/debug-instrumentation compilation/APK assembly and ktlint/detekt: 1,938 tests in 313 suites,
  1,936 passed, no failures/errors, and two explicit external-corpus skips.
- Frozen `253466d` built both optimized APKs and passed all six mandatory real-R8 fixtures plus
  ktlint. The generated boundary retained 894 exact external classes, 241 fields and 1,997 methods;
  the ordinary publisher rules stayed separate. Its resolved release SBOM contains 150 components
  with the required POM license evidence. This is dependency provenance, not a vulnerability or
  legal certification.
- Actions 44555384195 reached all 43 optimized identities: 42 passed, no skips, and one artwork
  workflow timed out. The prior Kotlin linkage failure did not recur. The failed original run is
  retained and wave 16 investigates the remaining timeout. Actions 44555384282 reached the
  normal-release gallery but failed before painting because the New artwork FAB lacked an
  accessible label; wave 16 owns its production semantics repair and a new execution is required.
- Installed-upgrade Actions 44555383994 failed during old-app evidence-folder setup before UI
  seeding. Android's app-specific external root had not been initialized on the fresh install.
  The test-only historical overlay now initializes it through `Context.getExternalFilesDir`;
  compilation against unchanged pinned historical production source passed. The failed original
  campaign remains failed, and no installed-upgrade runtime acceptance is claimed.

### Wave 14

- `w14_project_lifecycle`: reproduced stale gallery rename/save writes overwriting newer project
  metadata. Rename now updates only name/time, and saved artwork updates read the latest Room row
  and patch only committed artwork fields. Missing rows are refused without recreating deleted
  projects. Thirty-three focused JVM cases passed; four new real-Room preservation/order/deletion
  cases compiled and await Android execution. The changes are committed in `a83940f`.
- `w14_hostile_model_inputs`: reproduced glTF buffer-view overread, silently ignored sparse edits
  and incomplete triangle acceptance. Added a cumulative preallocation budget for model archives,
  JSON/embedded buffers, OBJ workspaces and repeated geometry; enforced complete geometry, declared
  texture availability and finite normalization. All 95 core 3D cases passed, including 14 new
  regressions. Existing static-analysis baselines remained unchanged. See
  [model allocation evidence](../verification/model-input-allocation.md) for bounds and limitations.
- `w14_upgrade_evidence_review`: bound retained archives to raw Git commit objects and reconstructed
  trees, file contents and modes; validated the historical schema and exact test overlay. Added
  finite immutable evidence, exclusive campaign phases and exact single-test instrumentation
  accounting. Forty host cases and three workflow-pin checks passed. Actual source archives for
  historical `ca9ce53` and local `a83940f` reconstructed their declared trees. This establishes
  internal evidence consistency, not remote source authenticity or publisher binary identity.

### Wave 15

- `w15_usd_input_budget`: extended cumulative admission through USD crate/USDA expansion, checked
  decompression lengths and finite integral topology, and refused missing indexed UV data. Five
  previous false acceptances were reproduced; seven new regressions were added. An existing
  oversized-token fixture needed its overlapping binary layout repaired while retaining its
  original refusal assertion. After that repair, root compiled and ran all 102 core 3D tests
  successfully, including independent producer fixtures. Changed-file ktlint/detekt passed.
- `w15_capability_messages`: corrected unconditional timelapse/autosave/crash-recovery claims,
  described the supported model subset and memory limits, and made normal-release payload UI
  execution an explicit engineering gate. Resource XML/names, 60 local documentation links,
  HelpScreen ktlint and scoped diff checks passed. No runtime qualification is inferred from copy.
- `w15_committed_save_metadata`: reproduced a race that committed an 8x6 document while a later
  live resize supplied 12x10 gallery metadata. Immutable `SavedCanvas` metadata now governs
  manual/model saves and native export. Gallery writes stay ordered under the existing save
  mutex, while Main-thread UI completion and navigation occur after release. Six new races and
  32 existing model/metadata/recovery cases passed, including complete thumbnail pixel agreement,
  active-frame/revision accounting, newer edit preservation, two-save ordering and postcommit
  callback failure/cancellation. Changed production/JVM/Android sources compiled; ktlint/detekt
  passed without baseline changes. See [save evidence](../verification/committed-save-metadata.md).
- Frozen `3e77fde` passed full integrated Gradle validation in 6m9s: 1,968 JVM cases in 317 suites,
  1,966 passed, no failures/errors, and two explicit external-corpus skips. The debug instrumentation
  APK and full ktlint/detekt checks also passed. These results precede the wave-16 recovery/UI fixes.
- A local full host invocation failed one publisher fixture because the integrator's
  `JAVA_TOOL_OPTIONS` setting printed a startup banner into combined manifest output. A direct
  probe retained the failure and showed tool-specific `APKANALYZER_OPTS` produces valid XML with
  identical APK bytes; the parser was unchanged. The corrected 394-case discovery passed that
  fixture but encountered a 10-second child-shell timeout in one settings-readiness harness
  subcase. That failure is retained and assigned to wave 17; no full-host pass is claimed here.

### Wave 16

- `w16_data_integrity_review`: reproduced delayed recovery replacing a different open artwork or
  erasing edits made after recovery started. Recovery now checks project/revision and pending
  edits immediately before publication; opening invalidates the old recovery operation before
  lookup suspends. Session-bound callbacks cannot clear a different project's guard. Four new
  regressions and 41 existing save/recovery/model cases passed. Background recovery scheduling
  moved into its controller without changing the class-size threshold. Existing ktlint/detekt
  passed. See [recovery session evidence](../verification/recovery-session-safety.md).
- `w16_optimized_acceptance_review`: diagnosed the retained normal-release accessibility failure
  and optimized first-stroke timeout. Added the localized New artwork FAB description and waited
  for actual preview availability before injected input, retaining all pixel/dirty assertions
  and deadlines. Added first-failure diagnostics before cleanup. Normal-release source archives
  now reconstruct their raw commit/tree and bind to the prepared receipt. Twenty-three normal
  payload and 37 optimized verifier cases passed, Java driver compilation/ktlint passed, and a
  real `3e77fde` source archive reconstructed correctly. New Android execution is still required.
- `w16_ci_gate_review`: reproduced a green coverage summary with absent installed-APK identity.
  A separate required gate now binds original invocation token/status receipts, raw runner and
  collector manifests, and freshly hashed app/test build outputs. Failed-attempt APK bytes remain
  required; framework recovery stays explicit and classifier-limited. All 126 focused host cases
  passed, including 12 new acceptance regressions. The seven device configurations and 320 debug /
  43 optimized declared identities remain intact. See [CI review](../verification/ci-acceptance-review.md).
- The earlier checkpoint's Android Actions 44555384021 build job 135669942396 passed all required
  host suites, five Gradle provenance fixtures, JVM/instrumentation compilation, static checks,
  release/debug builds and official publisher-tool fixtures. Its device matrix is separate and
  does not qualify the newer source.

### Wave 17

- `w17_source_safety_review`: reproduced detached incoming-file collection and four premature
  library-completion/error cases. Gallery imports now await classification, preparation and
  persistence; artwork navigation ends the current incoming collection while later files stay
  queued for the next visit. Palette and brush imports await durable writes before announcing
  success. Picker entry points and ordinary brush-edit wrappers retain their UI contracts.
  Eight new cases and 97 existing safety cases passed; the existing gallery package device caller
  compiled. Ktlint/detekt passed unchanged. See [incoming lifecycle](../verification/incoming-import-lifecycle.md).
  A repeated-clock palette identity collision was identified separately and assigned to wave 18.
- `w17_test_adequacy_review`: traced the retained settings-readiness timeout to 180 Python stub
  launches inside a 10-second fixture. A controlled 40ms interpreter-startup delay reproduced
  the timeout; shell replies for those simple probes completed in 2.347s with all 45 readiness
  attempts and 198 logged adb calls. The production script, 90-second readiness deadline,
  10-second test deadline and refusal assertions are unchanged. All 19 harness cases passed.
  No original Kotlin test method/file was removed; substantive capacity/fixture adaptations
  retain real state/pixel/refusal assertions. See [test adequacy](../verification/test-adequacy-review.md).
- `w17_release_decision_review`: corrected the Help/gallery promise that all data remains on-device
  to reflect enabled Android backup and selected export recipients. Existing privacy policies
  already described those behaviors. Resource XML/unique names and 47 documentation links passed.
  The existing engineering/publisher/physical/store gates remain explicit; no store/account or
  physical evidence was fabricated. See [release decision](../verification/release-decision-review.md).
- Frozen `d481b23` passed full Gradle validation in 6m20s: 1,980 JVM cases in 320 suites,
  1,978 passed, no failures/errors, and two external-corpus skips. The debug instrumentation and
  ordinary-release UI driver APKs built; full ktlint/detekt passed. Full host discovery then passed
  489 cases: 484 passed and five separately required Gradle fixtures were explicit opt-in skips.
  Official publisher-tool fixtures were required and executed. The prior fixture/environment
  failures remain retained; this pass belongs to the repaired source and corrected local setup.

### Wave 18

- `w18_palette_identity_upgrade`: reproduced four creation and four stale-edit failures. New
  palettes now receive unused identities under the settings mutex; create/duplicate remain
  distinct from intentional replacement. Appends/default selection use current stored values,
  deleted palettes cannot be revived, and positional edits refuse a changed displayed swatch list.
  All 74 focused cases passed, including successful edits after refreshing stale state. Ktlint
  and detekt passed unchanged. MainViewModel's awaited import contract is preserved. See
  [palette identity evidence](../verification/palette-identity-safety.md). This cannot restore
  palettes overwritten by an older application. Installed-upgrade/API26 follow-through remains
  required on the newly published source.
- `w18_android15_acceptance`: independently verified the old checkpoint's API35/4KB artifact,
  measured runtime and original APK captures: 314 passed, two failed, no skips or recovery out of
  316 identities. Both failures matched the owned readiness/preview-observation repairs. Reviewed
  the primary and independent 16KB kernel pins, per-invocation runtime checks and current 320-case
  inventory without finding another gate defect. The old build's debug APK/release AAB native
  preflight passed across four ABIs; this does not qualify new source or physical ARM64 hardware.
- `w18_android16_acceptance`: independently verified the same two failures on old API36/16KB and
  standard lanes. A real-repository probe showed an unfinished stroke changes 260 preview pixels
  while committed pixels correctly stay unchanged. The preview-failure device test now observes
  the provisional preview and separately protects committed pixels before/after GL cancellation,
  retaining all input/save/recovery assertions and deadlines. Four focused cases, Android test
  compilation and ktlint passed. See [Android16/release review](../verification/android16-release-review.md).
  The ordinary release driver retains its complete UI/process/share/pixel checks, which still
  require actual execution on the next source.

### Wave 19

- `w19_documentation_crosscheck`: checked 29 Markdown files, 106 local links/anchors, 20 CLI help
  invocations and 16 documented option sets. Added the omitted ordinary-release UI scope to the
  evidence introduction and replaced an unnamed pending-upgrade checkpoint with the final-source
  acceptance requirement. No unexecuted pass or additional release gate was introduced.
- `w19_artifact_crosscheck`: reproduced acceptance of unchanged optimized runtime evidence under
  a different caller-supplied revision. Verification now requires the workflow's retained prebuild
  `REVISION.txt` and hashes that receipt in the report. Two new missing/mismatched-receipt cases
  retain the existing positive acceptance case. This checks receipt consistency; it does not
  authenticate externally supplied evidence or establish a reproducible publisher build.
- `w19_regression_followthrough`: traced the actual shipped `ColorPanel` callback, which still
  sent stale whole-palette replacements after the wave-18 helper/store repair. Three before-fix
  failures reproduced lost rapid additions, stale removal overwrites and absent deleted-palette
  feedback. The panel now sends operations through a shared `PaletteEdits.from` binding; the
  settings mutex reads the latest palette and rejects a stale positional selection. All 78
  focused JVM cases passed, including four new cases. An actual Compose/Room gesture and snackbar
  test compiled with the complete CanvasScreen and final PSD changes; device execution remains
  required. The debug inventory is now 321 identities; the optimized inventory remains 43.

### Wave 20

- `w20_final_data_safety`: reproduced source-buffer exhaustion before PSD parser admission in
  both actual import entry points. With a generated 96 MiB source and a 64 MiB heap, the original
  helper failed with `OutOfMemoryError` after 16,842,752 bytes. The new admitted reader reserved
  workspace and source-copy capacity, then refused after 5,330,262 bytes with a readable error,
  closed stream and no OOM cause; a 1,024-byte control remained byte-exact. An actual gallery
  regression also proves occupied admission prevents the full provider open and project creation.
  All 42 focused cases passed; the repeated eight-case low-heap subset is not an additional count.
  Caller compilation and unchanged-baseline ktlint/detekt passed. The remaining inspected save,
  recovery, storage, handoff and deletion paths produced no further demonstrated blocker. See
  [PSD source admission](../verification/psd-source-admission.md) for the concrete limits.
- `w20_final_engineering`: independently investigated the optimized build's unresolved Guava
  33.8.0-android and OkHttp 4.12.1 coordinates. All 12 POM/module/JAR requests from the two
  official Maven hosts succeeded, and fresh artifact bytes matched the existing local cache.
  The parallel Android build also resolved the same dependencies. The original CI log lacks an
  HTTP status, so no definite network/cache root cause is claimed. One explicit unchanged-source
  job rerun was requested; the failed first attempt stays retained. No version downgrade, source
  workaround, baseline expansion or test-gate removal was made.
- `w20_final_release_disposition`: diagnosed the actual ordinary-release Settings failure from
  its retained accessibility hierarchy. The driver incorrectly required label text and checkable
  state on one node; Compose exposes the label below its checkable row. It now resolves the
  nearest visible application ancestor, requires one enabled/clickable control, clicks at most
  once and verifies the current checked state within the unchanged 15-second deadline. Eight
  retained-hierarchy regressions and all 23 release-evidence verifier cases passed; all four
  driver Java sources compiled against the actual Android dependencies. All pixel, process-death,
  URI-grant, identity and single-attempt checks remain. See
  [ordinary-release evidence](../verification/release-payload-ui.md).

### Integration and release disposition after the 60 assignments

All 20 waves and their three assignments are complete. Completion records implementation and
scoped review, not unconditional release certification. Existing assignments continue any actual
runtime failure follow-through; this does not add a twenty-first wave or replace required gates.

- Frozen `3ceb1af` passed integrated Gradle validation in 6m09s: 2,001 JVM cases in 323 suites,
  1,999 passed, no failures/errors and two external-corpus skips. Debug instrumentation and the
  ordinary-release driver APKs built, and full ktlint/detekt passed without baseline changes.
  Host discovery executed 499 cases: 494 passed and the five separately required Gradle fixtures
  were explicit opt-in skips. Official publisher-tool fixtures were required and executed.
- The wave-18 remote checkpoint's build passed 1,988 JVM cases with no failures/errors/skips,
  including the fetched external corpora. Its retained source archive reconstructed the declared
  `10b5a7b5ccb164dba841a630af261c1e09467425` checkout tree. This does not qualify newer changes.
- Optimized Actions 44563685630 attempt 2 built successfully and executed all 43 identities:
  42 passed, one failed and none skipped. The failed Drawing pad interaction left Library selected;
  the retained trace overlaps keyboard dismissal. The test now observes the actual dialog's IME
  and layout readiness, checks preset/tab selection, and requires a practice stroke before its
  unchanged artwork-isolation assertions. This is a targeted readiness repair, not proof of the
  hypothesized platform race, and its next actual execution remains required.
- That same screenshot exposed a separate narrow-screen defect: the saved-brush count was
  squeezed beside three actions and wrapped into a thin vertical column. The count now has its
  own line and whole action buttons can wrap. A new actual-toolbar regression covers the 296 dp
  dialog interior at three font scales, minimum/contained touch targets and Save/Share dialogs.
  Labels, fonts and callbacks remain unchanged. The resulting debug inventory is 322 cases;
  the selected optimized inventory remains 43. Device screenshots must establish the result.
- Installed-upgrade Actions 44563685594 progressed past the corrected external-directory setup
  and failed when its historical autosave selector also matched the font-size slider. The retained
  tree proves the original two matches and the corrected exact-range match. The test-only overlay
  also removes an invalid scroll request on a fixed toolbar and observes the historical canvas's
  actual transform before input. All UI writes and persisted artwork/recovery checks remain;
  the pinned historical production source is unchanged. The failed campaign stays failed.

The final frozen main revision still requires the repeated Android workflow and primary API35 /
16KB executions specified in [release signoff](RELEASE_SIGNOFF.md), plus all 43 optimized cases,
the ordinary-release UI workflow and an actual installed historical-app upgrade. Their final
run identities and outcomes belong in the PR/release acceptance record so recording a result
does not silently change the source revision being qualified. Physical stylus/performance,
approved publisher signing and store delivery remain external gates. Public rollout remains
**NO-GO** until those required records exist; emulator and disposable signing fixtures cannot
stand in for them.
