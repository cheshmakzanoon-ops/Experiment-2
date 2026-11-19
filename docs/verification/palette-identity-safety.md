# Palette creation and edit safety

## Reproduced failures

Wave 18 reproduced four failures against the previous implementation using the real
`SettingsRepositoryImpl`, its normal serialization, and a controlled settings DAO:

- Two new palettes requesting the same timestamp identity left only the second palette.
- While the first write was paused, a second creation with the same requested identity waited
  for the settings mutex but then replaced the first palette after its write completed.
- The editor's create and duplicate actions used the editing upsert. A stale visible palette
  list could give both actions the same preferred identity, replacing the just-created palette.
- Two zero identities remained duplicated, so a later rename could select both palettes.

All four new regressions failed before the repair. Their original failures remain in
`tooling/w18-palette/before-tests.log` in the shared review workspace.

The adjacent stale-edit review reproduced four more lost-update cases: rapid appends dropped
the first swatch, selecting a default restored older names/colors, a queued edit recreated a
deleted palette, and indexed replacement restored previously removed colors. Those four
failures remain in `tooling/w18-palette/edit-before-tests.log`.

## Repair

`addPalette` now means creation of a separate palette. Inside the existing settings update
mutex, it compares the requested identity with the latest stored collection and chooses an
unused identity before persistence. Existing palettes, contents and order are preserved.
The collision search also handles zero and the largest `Long` without overflowing into a
shared identity. An unused supplied identity is retained for compatibility.

The existing one-argument creation method remains available. A second overload creates and
moves a new palette to the front in the same durable update. The editor's create and duplicate
actions use that creation operation; intentional `updatePalette` edits retain their existing
identity and ordering behavior. Gallery and canvas imports already call the one-argument
creation method, so repeated clock values are resolved at the repository boundary without
changing their awaited-write or notification behavior.

No schema migration or rewrite of existing stored identities is introduced. The fix prevents
new creation collisions; it cannot restore a palette that an older version already replaced.

Palette appends now transform the latest stored palette under the settings mutex. Selecting
a default only reorders that latest object. Neither operation recreates a deleted palette.
Indexed replacement/removal checks that the displayed swatch list still matches the stored
list; if it changed, the operation reports a retry message while retaining the latest colors.
This avoids applying a stale position to a different swatch or restoring an earlier list.
Explicit full-palette replacement through the existing repository update remains available.

## Validation

Standalone Kotlin compilation and **74 JVM tests passed**, including the eight new regressions
and the existing settings, palette editing and palette import/codec suites. Tests compare exact
names, swatch contents, identity uniqueness, ordering and serialization after reopening the
repository. The editor regression also verifies that an intentional edit affects only the
chosen palette after creation collisions have been resolved.

Changed-file ktlint, Detekt with the unchanged configuration and baseline, and `git diff --check`
passed. The original tests and assertions were retained. This is JVM repository evidence with
a controlled DAO, not Android or actual Room execution. An intermediate invocation stopped
before suite execution because the parent's concurrent integrated build refreshed cached test
classes. That runner setup log is retained; unchanged palette test dependencies were then read
from the frozen verification checkout. Root's integrated build and exact-source
device qualification remain separate gates. No Gradle, commits or pushes were performed by
this assignment.
