# Settings, project identity and palette integrity — September 19, 2026

## Scope and provenance

This work starts at published `b0b05cd374de83e26e0835fac3d7c1d1e3052822`, using the
exact source archive from successful Android CI run `35384319999`. The source ZIP
SHA-256 is `33460e95d86e33229028bb6fd591cfab21d381670bdc5694297e341e8da0fd7f`.
Changes are ordinary source files on `main`; no force-push, replacement repository,
patch transport, disabled tests or expanded static-analysis baseline was used.

## Repairs

### Durable settings and startup

`448e513351fd26e2003a0cb16fad123eea79fe4c` loads Room settings before the first
settings-flow emission. A mutex serializes loading and edits, preventing an edit
from overwriting unread stored preferences or another concurrent edit. A Room
collection insert saves the whole preference set transactionally. The in-memory
snapshot changes only after persistence succeeds. Failed reads remain retryable;
cancellation while waiting/loading is not swallowed. Once the small commit begins,
durability and publication finish together. Collection ownership is isolated and
non-finite UI scales are rejected. Onboarding and gallery publication wait for
stored settings rather than treating startup defaults as persisted user choices.

Stored palettes now use JSON arrays. Legacy concatenated JSON objects remain
readable even when a quoted palette name contains `;;`, escapes or Unicode.
Unparseable palette data is not silently converted to an empty list and overwritten.
An unreadable settings store is surfaced as an error; it is not automatically reset.

### Project IDs cannot claim orphaned artwork

`3f2f5b2b0c4d4a0dd99009fc1eae351e2ff8dbfa` shares transactional allocation between
creation and duplication. SQLite AUTOINCREMENT preserves deleted-ID high-water
marks, and allocation advances beyond orphaned project directories. Provisional
rows are removed only inside the transaction; existing artwork files are untouched.
Insert conflicts abort instead of replacing another project. Invalid dimensions,
DPI, identifiers, unknown explicit IDs and exhausted IDs fail before destructive work.
Known project IDs can still update their own metadata.

### Palette interchange

ASE export now writes the complete 12-byte header before any block. Dynamic payload
buffers support legal long UTF-16 names instead of overflowing a fixed 64 KiB buffer.
Each imported block has its own read boundary. Incomplete headers, impossible counts
or lengths, short names/channels, invalid terminators/types, unsupported colour models,
non-finite values, incomplete groups and trailing file data are rejected as a whole.
Valid block-local metadata and unknown block types are skipped at their declared
boundaries. RGB conversion rounds rather than truncates byte channels. ASE L* is
scaled from its normalized wire representation before the existing Lab conversion.
GPL numeric output uses a fixed locale. These codecs do not add file-picker UI.

ASE intentionally drops alpha and ArtFlow-specific metadata. Import is limited to
16 MiB and 65,536 colours; names allow 65,534 UTF-16 units plus the terminator.
The existing Lab-to-sRGB conversion is not an ICC/profile-managed Adobe colour-fidelity
certification. JSON remains the lossless ArtFlow palette backup format.

The wire-format checks were cross-checked against SwatchBooker's original ASE codec:
https://github.com/olivierberten/SwatchBooker/blob/master/src/swatchbook/codecs/adobe_ase.py
Only format facts were used; no third-party implementation was copied into the app.

## Regression coverage and observed evidence

- 14 settings JVM tests cover cold reads, concurrent reads/edits, failed reads/writes,
  cancellation, no-op writes, ownership, legacy palette migration and invalid UI scale.
- Two real-SQLite device tests cover disk reopen and rollback of the complete settings
  batch after an injected trigger aborts its final insert.
- Eight additional project device tests cover empty/existing database orphan collisions,
  concurrent creates, deleted-ID non-reuse, explicit IDs, metadata edits, invalid inputs
  and exhaustion. The four prior duplication/recovery tests remain enabled.
- 18 palette JVM tests cover independent binary fixtures, all 256 byte-channel values,
  alpha semantics, long names, every truncated prefix, malformed records, metadata,
  locale changes and 10,000 seeded random/mutated inputs.

Observed before final combined CI: all 315 JVM tests in run `35421717543` passed with
zero failures, errors or skips. That run was not green overall: two new Detekt findings
were corrected in the next commit. Run `35422046066` then passed Detekt but caught one
settings-chain formatting issue, corrected with the palette changes. Neither run is
represented here as a successful full release gate.

Locally, the existing artifact-verifier suite passed 28 tests and the standalone
selection verifier passed 32,120 checks. A standalone ASE/GPL kernel probe failed 15
of the new 18 groups against the old source and passed all 18 against the repair,
under a 96 MiB JVM heap. That probe copied the relevant production methods verbatim
and used the actual colour maths with an unused Compose type bridge; it was not a
full Gradle/JUnit/Android run. The same checks are registered as real JUnit tests.

For the final combined candidate, use the completed Android CI run for its exact
commit: JVM, ktlint, Detekt, debug/release lint, APK/AAB assembly, packaged-artifact
preflight, all five emulator lanes and release-minified launch. Pending, cancelled
or skipped steps are not passing evidence. The final workflow artifacts supersede
intermediate failures only where those checks actually execute successfully.

## Release decision

These repairs do not complete all 50 `agent.md` phases or certify a zero-defect app.
The README's missing groups, advanced layer transforms, reference/linking UI, PSD
import, custom textures and other roadmap work remain explicit. Publisher signing,
Play Console declarations/listing, closed-testing eligibility and results, physical
stylus/GPU/codec testing and low-memory performance evidence remain separate gates.
Do not publish a production release solely because a source commit or emulator job exists.
