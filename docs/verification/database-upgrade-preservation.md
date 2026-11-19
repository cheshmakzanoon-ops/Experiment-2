# Database upgrade preservation

## Executable scope

`DatabaseMigrationDeviceTest` exercises a real persisted SQLite database, the registered
`ArtFlowDatabase.MIGRATION_1_2`, and `DatabaseModule.provideArtFlowDatabase`. Its context redirects
the production database name to an isolated on-disk test database; the provider's migration and
fallback configuration are unchanged. Tables are created from the committed version-1 Room
schema, not from current Room entities or an in-memory imitation.

The historical schema is `app/schemas/com.artflow.studio.data.local.database.ArtFlowDatabase/1.json`.
Its SHA-256 is `afbdb33dcf9e2e6f2610560ca1adf4cd876c5241a3f56b62a162361c5c493dde` and is
asserted by the fixture, so changing that old schema cannot silently change the upgrade under test.
The Android test APK packages the existing `app/schemas` directory as assets. The fixture uses
the existing SQLite framework helper dependency; it does not require `room-testing`.

| Device test | Required observation |
| --- | --- |
| `versionOneMigrationPreservesAllRowsArtworkAndRecoveryAcrossSecondOpen` | The production provider opens a seeded v1 file as v2. Every project, legacy brush, and settings field is preserved; old stacks are null. Gallery/domain reads, palette and saved-brush library decoding, favorites, recents, and named brush sets remain usable. New stack writes and inserts survive a second production open, and deleted project/brush IDs are not reused. |
| `missingMigrationRejectsUpgradeWithoutDeletingDataAndProductionRegistrationCanRetry` | A Room builder without the migration rejects the same v1 file. Its version, Room identity, table definitions, all rows, sequences, and artwork bytes remain intact. The production provider then successfully upgrades that same file without clearing or reseeding it. |
| `schemaValidationFailureRollsBackTheMigrationAndPreservesEveryOriginalRow` | A v1 project table with an incompatible extra column fails Room's post-migration schema validation. The complete migration transaction, including the new stack column, rolls back. The original schema, version, Room identity, data, and artwork remain intact. |
| `productionProviderRejectsAnUnknownNewerDatabaseWithoutDestructiveFallback` | A simulated future-version file is refused by the production provider. Its tables, data, Room identity, sequences, version, and artwork remain intact. This guards against adding a destructive downgrade fallback. |

The positive case compares full entities, including nullable fields, Unicode and quoted names,
timestamps, dimensions, DPI, favorites, paths, brush dynamics, and an unknown settings key. IDs
above 32 bits and deleted-ID high-water marks exercise preservation of SQLite's integer identity
and `AUTOINCREMENT` state. The real current brush library lives in settings; it is checked in
addition to the older `brushes` table. SQLite `PRAGMA quick_check` must return exactly `ok` after
each successful production open.

## Artwork fixtures and boundaries

`database-v1-canvas.artflow` and `database-v1-autosave.artflow` are deliberately small **synthetic**
version-1 document fixtures. They are not extracted from a historical APK or a user's artwork.
The test creates separate deterministic saved/recovery PNGs, a flattened preview, and a thumbnail,
then seeds the actual project row with their private-storage references. It resolves those paths
after migration, decodes the thumbnail, and loads the saved and recovery images through
`CanvasRepositoryImpl`. Exact layer and composite pixels must match. File SHA-256 values and
modification times must remain unchanged through migration, normal reading, recovery loading,
and the second database open. This also preserves the newer-autosave ordering used by recovery.

These checks cover the Room v1→v2 upgrade and referenced local artwork continuity. They do not
claim installation of an old APK followed by `adb install -r`, application process death,
publisher-signature compatibility, Android backup/restore, or physical-device upgrade acceptance.
Those remain separate release gates. In particular, a real package-upgrade campaign still needs
an independently identified old APK and a compatible signed candidate, seeded old-app data,
post-install launch/edit/save/reopen checks, and retained APK/device/test evidence.

## Running and recording

The tests use APIs available on Android 8/API26 and are part of the normal full instrumentation
inventory, including API35 and API36 lanes. No platform skip or reduced assertion is introduced.

```bash
./gradlew :app:compileDebugAndroidTestKotlin
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.artflow.studio.data.DatabaseMigrationDeviceTest
```

Retain the checked-out revision, emulator/device API and page size, test XML, and original failures
alongside the existing complete-inventory evidence. A targeted pass is useful regression evidence;
it does not replace the required full matrix or package-upgrade qualification.

At implementation time, the new Kotlin files passed the repository's standalone ktlint formatter
and check. Android compilation and device execution are pending the root build/CI run; this record
does not report them as passed.
