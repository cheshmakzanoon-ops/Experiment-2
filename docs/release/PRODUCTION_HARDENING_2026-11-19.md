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

| Wave | Three assignments | Status |
| --- | --- | --- |
| 01 | PSD allocation budget; import resource ownership; transaction design review | Implemented/reviewed; Kotlin execution pending |
| 02 | Atomic import implementation; decoder adversarial review; ownership regression review | Implemented/reviewed; runtime verification pending |
| 03 | Resolved dependency inventory; optimized release workflow coverage; database migration coverage | Implemented; execution and independent review pending |
| 04 | Transaction adversarial review; dependency report verification; release test integration review | In progress |
| 05 | Real import failure-path tests; optimized artwork workflow tests; upgrade preservation tests | Planned |
| 06 | Cancellation/concurrency review; memory-budget boundaries; dependency/artifact integrity | Planned |
| 07 | Editor error/notice behavior; large-canvas scope checks; input acceptance coverage | Planned |
| 08 | Physical-performance evidence tooling; signed artifact validation; upgrade execution tooling | Planned |
| 09 | Save/recovery failure paths; export failure paths; lifecycle/resource review | Planned |
| 10 | Integrated compile/analysis findings; regression completeness; release documentation reconciliation | Planned |
| 11 | CI findings: storage/import; UI/release; build/tooling | Planned |
| 12 | Import transaction acceptance; recovery/upgrade acceptance; optimized workflow acceptance | Planned |
| 13 | Release evidence validation; performance evidence validation; dependency evidence validation | Planned |
| 14 | Hostile archive review; project-lifecycle review; release artifact review | Planned |
| 15 | Capability claim review; user-facing failure review; support/workload contract review | Planned |
| 16 | Independent data integrity review; independent optimized-build review; independent CI gate review | Planned |
| 17 | Final source safety review; final test adequacy review; release decision review | Planned |
| 18 | Exact-revision CI review: legacy; Android 15; Android 16/tablet | Planned |
| 19 | Regression follow-through; artifact/evidence cross-check; documentation cross-check | Planned |
| 20 | Final data safety acceptance; final engineering acceptance; final release-status acceptance | Planned |

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

## Verification log

- Starting audit: 191 CI-script Python tests and 76 tooling Python tests passed locally.
- Build environment: JDK available; Android SDK and Gradle distribution not configured at start.
- New code verification: pending. See subsequent records; never infer a pass from implementation.

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
