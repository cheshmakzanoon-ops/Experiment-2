# Colour input, palette recovery and artwork workflow

## Source changes

The continuation starts at `eb19f96a0bb9898bbae2f1c1484880d1204f912f` and keeps the
previous data-integrity repairs. `384ae8b` fixes a palette-test formatting error
and makes Gradle collect all independent static-analysis results with `--continue`.
A failed analysis task still fails CI; no rule, assertion or baseline was weakened.

`0ff64d6` fixes the colour wheel's gesture lifetime and geometry. Recomposition no
longer restarts a drag; one pointer owns its starting square/ring region through
release. Hit testing and explicit gradient bounds match the displayed controls.
Alpha is retained, and choosing grey or black no longer destroys latent hue and
saturation. Subsequent corrections include the stroke width when sizing the ring
on high-density displays and remove two style findings without suppressions.

`90201fb` isolates unreadable optional palette text instead of preventing the
whole gallery from loading. Other preferences and artwork remain usable. The exact
original palette row is retained through unrelated setting edits, and custom-palette
changes cannot bypass the recovery warning. Settings offers an explicit confirmation
and Android document-picker backup. Reset happens only after that writer finishes
and closes successfully. Failed/cancelled writes, failed closes, failed database
commits and stale recovery operations retain the original. A slow provider does not
hold the preference mutex; edits made during backup are kept when recovery commits.

This supersedes the earlier data-integrity note's fail-closed startup behavior for
malformed palettes. Actual database read failures still surface as errors; neither
artwork nor the database is destructively reset. A backup preserves the unreadable
text for later inspection; it does not claim to repair those palette contents.

## Tests and execution evidence

The wheel has ten JVM geometry/state checks and six real Compose gesture tests.
Palette recovery has repository concurrency/failure checks, production ViewModel
stream-write/close failure tests, real SQLite persistence tests and confirmation UI
tests. `ArtworkWorkflowTest` drives the unchanged app shell from gallery creation
through real canvas input, save-and-leave, reopening, activity recreation and discard.
It compares actual composite pixels and saved metadata, not only UI labels.

Run `35426320458` on `0ff64d6` executed 342 JVM tests with no failures/errors/skips.
Its API 26 report executed 138 tests: no failures/errors and two explicit API-specific
skips; all six wheel gesture tests passed. That run was not a complete release pass:
ktlint and Detekt found the two corrected style issues. The recovery candidate's
first JVM run caught a test importing an unavailable mocking library; the test was
converted to the already-installed Mockito dependency without adding a framework.

These historical observations are not execution evidence for later changes. Use
the completed exact-commit Android CI reports for the combined candidate, including
all five device lanes, minified launch, lint, static analysis, builds and artifact
preflight. Pending, failed, cancelled or skipped gates are not counted as passing.
Activity recreation is not a physical-device process-death or performance campaign.

## Remaining release scope

The complete 50-phase roadmap is not delivered by these repairs. The original
advanced transforms, groups, reference/linking workflows, imported textures, PSD
import, smart objects, timelapse and cloud/collaboration gaps remain listed in the
README and release-readiness record. Publisher signing, Play Console requirements,
real-device/stylus/low-memory testing and store assets still require their own evidence.
No zero-defect or unconditional Google Play production approval is asserted.
