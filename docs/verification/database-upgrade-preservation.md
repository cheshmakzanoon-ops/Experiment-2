# Database upgrade preservation

## Executable scope

`DatabaseMigrationDeviceTest` exercises a real persisted SQLite database, the registered
`ArtFlowDatabase.MIGRATION_1_2`, and `DatabaseModule.provideArtFlowDatabase`. Its context redirects
the production database name to an isolated on-disk test database; the provider's migration and
fallback configuration are unchanged. Tables are created from the committed version-1 Room
schema, not from current Room entities or an in-memory imitation.
Every successful open also checks SQLite's actual file path against the isolated fixture path.

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
The [installed-package campaign tool](installed-package-upgrade.md) now executes that separate
debug-APK path with a bounded manual old-app seed, exact stopped-data snapshots, `adb install -r`,
and candidate recovery/edit/save/fresh-process-reopen assertions. Its implementation and host
tests are not a device campaign pass; the required APK/key/device inputs remain outstanding.

The rejection snapshots preserve SQLite storage classes and exact numeric values: NULL, 64-bit
integers, doubles, text, and blob bytes remain distinct. String conversion alone could hide a
changed numeric type or rounded REAL value. Failure assertions identify the absent 1→2 or 3→2
migration path, or Room's project-schema validation error naming the incompatible column. A
missing migration therefore cannot satisfy the validation-rollback case. The positive fixture's
project modification times are distinct, including the inserted row, so its gallery-order check
does not depend on unspecified ordering among equal timestamps.

## Historical package baseline for follow-up

The committed [attempt-one artifact record](../evidence/ci-run-44463021020-attempt-1.json)
identifies an older build suitable as a **package-update** baseline:

| Provenance field | Recorded value |
| --- | --- |
| Historical branch head | `384095da6b0feec12736fa54fe7d245777e1c37e` |
| Source actually built by CI | PR merge `2b857405a05dcefa19cc8f6c43102ae55668f5a9` |
| Workflow | [44463021020, attempt 1](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/runs/44463021020/attempts/1) |
| Build archive | [artflow-builds-unverified, artifact 14502360127](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/runs/44463021020/artifacts/14502360127) |
| Archive SHA-256 | `efa26c50f0d664b053400fd5b74f073d3923283797c47f078bbeed4f37d9a107` |
| Archive size | 34,715,768 bytes |
| Historical head's application configuration | `com.artflow.studio`, version code 3, version name `1.1.0`, Room database version 2 |

This review checked the historical source and committed artifact record. It did not download or
install that archive again, verify the APK's signing certificate, or establish publisher approval.
The record reports a successful build and six completed device lanes; the full matrix failed on
the primary API35/16 KB lane, so the archive's `unverified` status remains meaningful.

The later upgrade harness can first verify and inspect the retained archive, including its
individual APK digest, application ID, version code, and signing-certificate digest. A compatible
candidate must retain the application ID, have a nondecreasing version code, and satisfy Android's
signature-update requirements. CI debug keys must not be assumed to match between jobs. If the
archived APK cannot be updated with an available test signing identity, rebuild the identified
historical source and candidate with the same dedicated test key in isolated checkouts, and label
the result as an upgrade between rebuilt sources. Seed the old app, retain the pre-upgrade files
and settings, use `adb install -r` without uninstalling or clearing application data, and verify
launch, gallery/settings/library reads, newer recovery, edit/save, and reopen after replacement.

This identified baseline already uses Room v2. It can qualify package replacement and persisted
artwork continuity, but it cannot supply an installed v1→v2 database migration. The available
shallow history's oldest visible database snapshot, `3b0eedf99334f5ba88ba0639c9882a4c7248df4f`,
also uses Room v2. A real v1 application build and its provenance remain unidentified; the pinned
v1 SQLite fixture must not be presented as that missing APK evidence.

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

The initial instrumentation sources compiled locally during wave 04, as recorded in the
[production-hardening log](../release/PRODUCTION_HARDENING_2026-11-19.md). The wave-05 assertion
changes passed focused standalone ktlint and detekt checks with the existing configuration and
baseline. Those revised sources still require the root Android compilation run, and device
execution remains pending CI. No device migration or installed-package upgrade pass is claimed.
