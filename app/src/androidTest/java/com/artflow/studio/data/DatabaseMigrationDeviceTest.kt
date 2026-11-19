package com.artflow.studio.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.artflow.studio.data.local.database.ArtFlowDatabase
import com.artflow.studio.data.local.entity.BrushEntity
import com.artflow.studio.data.local.entity.ProjectEntity
import com.artflow.studio.data.local.entity.toDomain
import com.artflow.studio.data.renderer.BitmapPixelBridge
import com.artflow.studio.data.repository.BrushLibraryStore
import com.artflow.studio.data.repository.ProjectRepositoryImpl
import com.artflow.studio.data.repository.canvas.CanvasRepositoryImpl
import com.artflow.studio.data.repository.settings.SettingsRepositoryImpl
import com.artflow.studio.di.DatabaseModule
import com.artflow.studio.domain.model.settings.GallerySort
import com.artflow.studio.domain.model.settings.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Production Room opening, persisted legacy SQLite, real project files, and migration rollback. */
@RunWith(AndroidJUnit4::class)
class DatabaseMigrationDeviceTest {
    private lateinit var fixture: DatabaseUpgradeFixture
    private var database: ArtFlowDatabase? = null

    @Before
    fun setup() {
        fixture = DatabaseUpgradeFixture(ApplicationProvider.getApplicationContext<Context>())
    }

    @After
    fun cleanup() {
        closeDatabase()
        fixture.close()
    }

    @Test
    fun versionOneMigrationPreservesAllRowsArtworkAndRecoveryAcrossSecondOpen() =
        runBlocking {
            fixture.create()
            assertEquals(1, fixture.databaseSnapshot().version)
            val filesBefore = fixture.filesSnapshot()

            var opened = openProductionDatabase()
            assertDatabaseValid(opened)
            assertRows(opened, fixture.projects, fixture.brushes)
            assertLibraryAndSettings(opened)
            assertArtworkAndRecovery(opened)
            assertEquals("Migration and read/recovery must preserve every existing file", filesBefore, fixture.filesSnapshot())

            // New nullable stack values and both AUTOINCREMENT histories must remain writable.
            val stack = "Client's \"Ocean\" 🎨"
            opened.projectDao().setStack(fixture.projects.map { it.id }, stack)
            val newProject =
                fixture.projects.last().copy(
                    id = 0,
                    name = "After migration",
                    modifiedAt = 1_700_000_000_300L,
                    stack = "New work",
                )
            val projectId = opened.projectDao().insertProject(newProject)
            assertEquals(DatabaseUpgradeFixture.PROJECT_HIGH_WATER + 1, projectId)
            val newBrush = fixture.brushes.last().copy(id = 0, name = "After migration")
            val brushId = opened.brushDao().insertBrush(newBrush)
            assertEquals(DatabaseUpgradeFixture.BRUSH_HIGH_WATER + 1, brushId)

            closeDatabase()
            opened = openProductionDatabase()
            assertDatabaseValid(opened)
            assertRows(
                opened,
                fixture.projects.map { it.copy(stack = stack) } + newProject.copy(id = projectId),
                fixture.brushes + newBrush.copy(id = brushId),
            )
            assertLibraryAndSettings(opened)
            assertArtworkAndRecovery(opened)
            assertEquals("A second open must retain documents, PNGs, and recovery ordering", filesBefore, fixture.filesSnapshot())
        }

    @Test
    fun missingMigrationRejectsUpgradeWithoutDeletingDataAndProductionRegistrationCanRetry() =
        runBlocking {
            fixture.create()
            val before = fixture.databaseSnapshot()
            val filesBefore = fixture.filesSnapshot()
            database = Room.databaseBuilder(fixture.context, ArtFlowDatabase::class.java, ArtFlowDatabase.DATABASE_NAME).build()

            val failure = openingFailure(requireNotNull(database))
            assertTrue("Missing migration must fail explicitly: $failure", failure is IllegalStateException)
            assertTrue(failure?.message.orEmpty().contains("migration", ignoreCase = true))
            closeDatabase()
            assertEquals("A rejected open must leave version 1 and every row intact", before, fixture.databaseSnapshot())
            assertEquals(filesBefore, fixture.filesSnapshot())

            // No clearing/reseeding occurs: the exact failed database is opened through the app's provider.
            val opened = openProductionDatabase()
            assertDatabaseValid(opened)
            assertRows(opened, fixture.projects, fixture.brushes)
            assertArtworkAndRecovery(opened)
            assertEquals(filesBefore, fixture.filesSnapshot())
        }

    @Test
    fun schemaValidationFailureRollsBackTheMigrationAndPreservesEveryOriginalRow() =
        runBlocking {
            fixture.create { db -> db.execSQL("ALTER TABLE projects ADD COLUMN incompatible INTEGER NOT NULL DEFAULT 7") }
            val before = fixture.databaseSnapshot()
            val filesBefore = fixture.filesSnapshot()

            val failure = openingFailure(openProductionDatabase())
            assertTrue("An incompatible schema must fail validation: $failure", failure is IllegalStateException)
            assertTrue(failure?.message.orEmpty().contains("migration", ignoreCase = true))
            closeDatabase()
            assertEquals(
                "The failed ALTER/validation transaction must restore version, identity, schema, rows, and sequences",
                before,
                fixture.databaseSnapshot(),
            )
            assertEquals(filesBefore, fixture.filesSnapshot())
        }

    @Test
    fun productionProviderRejectsAnUnknownNewerDatabaseWithoutDestructiveFallback() =
        runBlocking {
            // A simulated future file uses the full v2 shape but advertises an unsupported version.
            fixture.create(schemaVersion = 2, databaseVersion = 3)
            val before = fixture.databaseSnapshot()
            val filesBefore = fixture.filesSnapshot()

            val failure = openingFailure(openProductionDatabase())
            assertTrue("The production provider must refuse an unsupported downgrade: $failure", failure is IllegalStateException)
            assertTrue(failure?.message.orEmpty().contains("migration", ignoreCase = true))
            closeDatabase()
            assertEquals(
                "The production provider must never replace an unknown database with empty tables",
                before,
                fixture.databaseSnapshot(),
            )
            assertEquals(filesBefore, fixture.filesSnapshot())
        }

    private fun openProductionDatabase(): ArtFlowDatabase = DatabaseModule.provideArtFlowDatabase(fixture.context).also { database = it }

    private fun closeDatabase() {
        database?.close()
        database = null
    }

    private suspend fun openingFailure(opened: ArtFlowDatabase): Throwable? =
        withContext(Dispatchers.IO) { runCatching { opened.openHelper.writableDatabase }.exceptionOrNull() }

    private suspend fun assertDatabaseValid(opened: ArtFlowDatabase) =
        withContext(Dispatchers.IO) {
            val sqlite = opened.openHelper.writableDatabase
            assertEquals(2, sqlite.version)
            sqlite.query("PRAGMA quick_check").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("ok", cursor.getString(0))
                assertFalse(cursor.moveToNext())
            }
        }

    private suspend fun assertRows(
        opened: ArtFlowDatabase,
        projects: List<ProjectEntity>,
        brushes: List<BrushEntity>,
    ) {
        assertEquals(
            projects.sortedBy { it.id },
            opened
                .projectDao()
                .getAllProjects()
                .first()
                .sortedBy { it.id },
        )
        assertEquals(
            brushes.sortedBy { it.id },
            opened
                .brushDao()
                .getAllBrushes()
                .first()
                .sortedBy { it.id },
        )
        assertEquals(
            fixture.settings.sortedBy { it.key },
            opened
                .settingsDao()
                .getAllSettings()
                .first()
                .sortedBy { it.key },
        )
        val repository = ProjectRepositoryImpl(opened.projectDao(), fixture.storage, opened)
        assertEquals(projects.sortedByDescending { it.modifiedAt }.map { it.toDomain() }, repository.getAllProjects().first())
        assertEquals(projects.filter { it.isFavorite }.map { it.toDomain() }, repository.getFavoriteProjects().first())
        projects.forEach { assertEquals(it.toDomain(), repository.getProjectById(it.id)) }
    }

    private suspend fun assertLibraryAndSettings(opened: ArtFlowDatabase) {
        val preferences = SettingsRepositoryImpl(opened.settingsDao()).settings.first()
        assertEquals(ThemeMode.DARK, preferences.themeMode)
        assertTrue(preferences.highContrast)
        assertTrue(preferences.stylusOnly)
        assertTrue(preferences.seenOnboarding)
        assertEquals(45_000L, preferences.autosaveIntervalMs)
        assertEquals(GallerySort.MANUAL, preferences.gallerySort)
        assertEquals(listOf(DatabaseUpgradeFixture.DRAFT_ID, DatabaseUpgradeFixture.ARTWORK_ID), preferences.galleryOrder)
        assertEquals(fixture.palette.name, preferences.defaultPaletteName)
        assertEquals(listOf(fixture.palette), preferences.customPalettes)
        assertFalse(preferences.paletteRecoveryRequired)
        val library = BrushLibraryStore(opened.settingsDao())
        assertEquals(fixture.savedBrushes, library.brushes.first())
        assertEquals(fixture.favouriteBrushes, library.favourites.first())
        assertEquals(fixture.recentBrushes, library.recent.first())
        assertEquals(fixture.brushSets, library.sets.first())
    }

    private suspend fun assertArtworkAndRecovery(opened: ArtFlowDatabase) {
        val project = requireNotNull(opened.projectDao().getProjectById(DatabaseUpgradeFixture.ARTWORK_ID))
        val thumbnailPath = requireNotNull(project.thumbnailPath)
        assertEquals(fixture.storage.documentFile(project.id), File(project.filePath))
        assertEquals(fixture.storage.thumbnailFile(project.id), File(thumbnailPath))
        assertArrayEquals(
            DatabaseUpgradeFixture.savedPixels(),
            requireNotNull(BitmapPixelBridge.decodeFile(thumbnailPath)).pixels,
        )
        assertTrue(fixture.storage.hasUnsavedRecovery(project.id))
        withContext(Dispatchers.Main) {
            val canvas = CanvasRepositoryImpl(fixture.storage)
            try {
                requireNotNull(canvas.loadCanvas(project.id))
                assertEquals("Legacy ink 🎨", canvas.getAllLayers().single().name)
                assertArrayEquals(DatabaseUpgradeFixture.savedPixels(), requireNotNull(canvas.layerPixels(7)).pixels)
                assertArrayEquals(DatabaseUpgradeFixture.savedPixels(), requireNotNull(canvas.compositeBuffer()).pixels)
                assertTrue(canvas.hasUnresolvedRecovery(project.id))
                requireNotNull(canvas.recoverAutosave(project.id))
                assertEquals("Recovered ink 🎨", canvas.getAllLayers().single().name)
                assertArrayEquals(DatabaseUpgradeFixture.recoveredPixels(), requireNotNull(canvas.layerPixels(7)).pixels)
                assertArrayEquals(DatabaseUpgradeFixture.recoveredPixels(), requireNotNull(canvas.compositeBuffer()).pixels)
                assertFalse(canvas.hasUnresolvedRecovery(project.id))
            } finally {
                canvas.dispose()
            }
        }
    }
}
