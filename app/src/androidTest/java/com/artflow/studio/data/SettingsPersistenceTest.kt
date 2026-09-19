package com.artflow.studio.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.artflow.studio.core.color.Palette
import com.artflow.studio.data.local.database.ArtFlowDatabase
import com.artflow.studio.data.local.entity.SettingsEntity
import com.artflow.studio.data.repository.settings.SettingsRepositoryImpl
import com.artflow.studio.domain.model.settings.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real SQLite transactions and disk reopen, rather than an in-memory DAO imitation. */
@RunWith(AndroidJUnit4::class)
class SettingsPersistenceTest {
    private lateinit var context: Context
    private lateinit var databaseName: String
    private lateinit var database: ArtFlowDatabase

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        databaseName = "settings-test-${System.nanoTime()}"
        database = openDatabase()
    }

    @After
    fun cleanup() {
        database.close()
        context.deleteDatabase(databaseName)
    }

    private fun openDatabase(): ArtFlowDatabase = Room.databaseBuilder(context, ArtFlowDatabase::class.java, databaseName).build()

    @Test
    fun completePreferencesAndPaletteNamesSurviveDatabaseReopen() =
        runBlocking {
            val repository = SettingsRepositoryImpl(database.settingsDao())
            val palette = Palette(id = 7, name = "Ocean;; \"ink\" 🎨", colors = listOf(-1, -16777216))
            repository.update {
                it.copy(
                    themeMode = ThemeMode.DARK,
                    seenOnboarding = true,
                    highContrast = true,
                    stylusOnly = true,
                    uiScale = 1.2f,
                    customPalettes = listOf(palette),
                )
            }
            val expected = repository.current()
            database.close()
            database = openDatabase()
            assertEquals(expected, SettingsRepositoryImpl(database.settingsDao()).settings.first())
        }

    @Test
    fun unreadablePaletteRowSurvivesUnrelatedEditsAndOnlyResetsAfterBackup() =
        runBlocking {
            val original = "  {damaged;; 🎨\n"
            database.settingsDao().insertSetting(SettingsEntity(key = "color.palettes", value = original))
            val repository = SettingsRepositoryImpl(database.settingsDao())
            assertTrue(repository.settings.first().paletteRecoveryRequired)
            repository.setThemeMode(ThemeMode.DARK)
            assertEquals(original, database.settingsDao().getSettingByKey("color.palettes")!!.value)
            var backup: String? = null
            repository.backupAndResetUnreadablePalettes { backup = it }
            assertEquals(original, backup)
            database.close()
            database = openDatabase()
            val reopened = SettingsRepositoryImpl(database.settingsDao()).settings.first()
            assertFalse(reopened.paletteRecoveryRequired)
            assertTrue(reopened.customPalettes.isEmpty())
            assertEquals(ThemeMode.DARK, reopened.themeMode)
        }

    @Test
    fun failurePartwayThroughBatchRollsBackEveryRowAndLeavesCacheUnchanged() =
        runBlocking {
            val repository = SettingsRepositoryImpl(database.settingsDao())
            repository.setThemeMode(ThemeMode.DARK)
            val before = repository.current()
            val rowsBefore = database.settingsDao().getAllSettings().first()
            withContext(Dispatchers.IO) {
                database.openHelper.writableDatabase.execSQL(
                    "CREATE TRIGGER fail_palette BEFORE INSERT ON settings WHEN NEW.key = 'color.palettes' " +
                        "BEGIN SELECT RAISE(ABORT, 'injected storage failure'); END",
                )
            }
            assertTrue(runCatching { repository.update { it.copy(themeMode = ThemeMode.LIGHT, highContrast = true) } }.isFailure)
            assertEquals(before, repository.current())
            assertEquals(rowsBefore, database.settingsDao().getAllSettings().first())
            withContext(Dispatchers.IO) {
                database.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_palette")
            }
            repository.setHighContrast(true)
            assertTrue(repository.current().highContrast)
            assertEquals(ThemeMode.DARK, repository.current().themeMode)
        }
}
