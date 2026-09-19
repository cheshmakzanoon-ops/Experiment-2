package com.artflow.studio.data

import com.artflow.studio.core.color.Palette
import com.artflow.studio.core.color.PaletteCodec
import com.artflow.studio.data.local.dao.SettingsDao
import com.artflow.studio.data.local.entity.SettingsEntity
import com.artflow.studio.data.repository.settings.SettingsRepositoryImpl
import com.artflow.studio.domain.model.settings.ThemeMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsRepositoryTest {
    @Test
    fun firstCollectionLoadsPreferencesWithoutAWrite() =
        runTest {
            val dao = MemorySettingsDao(mapOf("theme.mode" to "DARK", "general.onboardingSeen" to "true"))
            val repository = SettingsRepositoryImpl(dao)
            val stored = repository.settings.first()
            assertEquals(ThemeMode.DARK, stored.themeMode)
            assertTrue(stored.seenOnboarding)
            assertEquals(1, dao.reads)
            assertEquals(0, dao.writes)
        }

    @Test
    fun updateWaitsForTheInitialReadWithoutOverwritingOtherPreferences() =
        runTest {
            val dao = MemorySettingsDao(mapOf("theme.mode" to "DARK"))
            dao.readGate = CompletableDeferred()
            val repository = SettingsRepositoryImpl(dao)
            val first = async { repository.settings.first() }
            runCurrent()
            val edit = launch { repository.setHighContrast(true) }
            runCurrent()
            assertFalse(first.isCompleted)
            assertFalse(edit.isCompleted)
            assertEquals(0, dao.writes)
            dao.readGate!!.complete(Unit)
            first.await()
            edit.join()
            assertEquals(ThemeMode.DARK, repository.current().themeMode)
            assertTrue(repository.current().highContrast)
            assertEquals(1, dao.reads)
        }

    @Test
    fun overlappingEditsAreSerializedAndPublishOnlyAfterPersistence() =
        runTest {
            val dao = MemorySettingsDao()
            dao.writeGate = CompletableDeferred()
            val repository = SettingsRepositoryImpl(dao)
            repository.settings.first()
            val first = launch { repository.setThemeMode(ThemeMode.DARK) }
            runCurrent()
            val second = launch { repository.setHighContrast(true) }
            runCurrent()
            assertEquals(1, dao.writes)
            assertEquals(ThemeMode.SYSTEM, repository.current().themeMode)
            dao.writeGate!!.complete(Unit)
            first.join()
            second.join()
            assertEquals(ThemeMode.DARK, repository.current().themeMode)
            assertTrue(repository.current().highContrast)
            val reopened = SettingsRepositoryImpl(dao).settings.first()
            assertEquals(repository.current(), reopened)
        }

    @Test
    fun failedWriteDoesNotPublishAndCanBeRetried() =
        runTest {
            val dao = MemorySettingsDao(mapOf("theme.mode" to "DARK"))
            val repository = SettingsRepositoryImpl(dao)
            val before = repository.settings.first()
            dao.writeFailure = IOException("Disk full")
            assertTrue(runCatching { repository.setHighContrast(true) }.exceptionOrNull() is IOException)
            assertEquals(before, repository.current())
            assertEquals("DARK", dao.rows["theme.mode"]!!.value)
            assertNull(dao.rows["access.highContrast"])
            dao.writeFailure = null
            repository.setHighContrast(true)
            assertTrue(repository.current().highContrast)
        }

    @Test
    fun failedReadDoesNotCacheDefaultsOrAllowDestructiveWrites() =
        runTest {
            val dao = MemorySettingsDao(mapOf("theme.mode" to "DARK"))
            dao.readFailure = IOException("Temporarily unavailable")
            val repository = SettingsRepositoryImpl(dao)
            assertTrue(runCatching { repository.setHighContrast(true) }.isFailure)
            assertEquals(0, dao.writes)
            dao.readFailure = null
            assertEquals(ThemeMode.DARK, repository.settings.first().themeMode)
            assertEquals(2, dao.reads)
        }

    @Test
    fun cancelledReadIsRetriedInsteadOfRememberedAsLoaded() =
        runTest {
            val dao = MemorySettingsDao(mapOf("theme.mode" to "DARK"))
            dao.readGate = CompletableDeferred()
            val repository = SettingsRepositoryImpl(dao)
            val first = launch { repository.settings.first() }
            runCurrent()
            first.cancel()
            first.join()
            dao.readGate!!.complete(Unit)
            assertEquals(ThemeMode.DARK, repository.settings.first().themeMode)
            assertEquals(2, dao.reads)
            assertEquals(0, dao.writes)
        }

    @Test
    fun cancellationDuringCommitStillPublishesTheCommittedValue() =
        runTest {
            val dao = MemorySettingsDao()
            dao.writeGate = CompletableDeferred()
            val repository = SettingsRepositoryImpl(dao)
            val edit = launch { repository.setHighContrast(true) }
            runCurrent()
            assertEquals(1, dao.writes)
            edit.cancel()
            dao.writeGate!!.complete(Unit)
            edit.join()
            assertTrue(edit.isCancelled)
            assertTrue(repository.current().highContrast)
            assertTrue(SettingsRepositoryImpl(dao).settings.first().highContrast)
        }

    @Test
    fun noOpDoesNotRewriteStorage() =
        runTest {
            val dao = MemorySettingsDao(mapOf("theme.mode" to "DARK"))
            val repository = SettingsRepositoryImpl(dao)
            repository.setThemeMode(ThemeMode.DARK)
            repository.update { it }
            assertEquals(0, dao.writes)
        }

    @Test
    fun paletteNamesWithSeparatorsQuotesAndUnicodeSurviveRestart() =
        runTest {
            val dao = MemorySettingsDao()
            val repository = SettingsRepositoryImpl(dao)
            val palette = Palette(id = 4, name = "Ink;; \"blue\\green\" 🎨", colors = listOf(-1, -16777216))
            repository.addPalette(palette)
            assertTrue(dao.rows["color.palettes"]!!.value.startsWith("["))
            assertEquals(listOf(palette), SettingsRepositoryImpl(dao).settings.first().customPalettes)
        }

    @Test
    fun legacyPaletteDelimitersAreSplitOnlyBetweenCompleteObjects() =
        runTest {
            val first = Palette(id = 1, name = "A;;B\\\";;C", colors = listOf(1, 2))
            val second = Palette(id = 2, name = "};[;;]", colors = listOf(3, 4))
            val legacy = PaletteCodec.exportJson(first) + ";;" + PaletteCodec.exportJson(second)
            val dao = MemorySettingsDao(mapOf("color.palettes" to legacy))
            val repository = SettingsRepositoryImpl(dao)
            assertEquals(listOf(first, second), repository.settings.first().customPalettes)
            repository.setHighContrast(true)
            assertTrue(dao.rows["color.palettes"]!!.value.startsWith("["))
            assertEquals(listOf(first, second), SettingsRepositoryImpl(dao).settings.first().customPalettes)
        }

    @Test
    fun malformedPaletteDataIsNotSilentlyErasedByAnUnrelatedEdit() =
        runTest {
            val damaged = "{\"name\":\"unclosed"
            val dao = MemorySettingsDao(mapOf("color.palettes" to damaged))
            val repository = SettingsRepositoryImpl(dao)
            assertTrue(runCatching { repository.setHighContrast(true) }.isFailure)
            assertEquals(damaged, dao.rows["color.palettes"]!!.value)
            assertEquals(0, dao.writes)
        }

    @Test
    fun invalidScaleIsRejectedAndCorruptStoredScaleUsesSafeDefault() =
        runTest {
            val dao = MemorySettingsDao(mapOf("access.uiScale" to "NaN"))
            val repository = SettingsRepositoryImpl(dao)
            assertEquals(1f, repository.settings.first().uiScale, 0f)
            for (value in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
                assertTrue(runCatching { repository.setUiScale(value) }.exceptionOrNull() is IllegalArgumentException)
            }
            assertEquals(0, dao.writes)
            repository.setUiScale(99f)
            assertEquals(1.6f, repository.current().uiScale, 0f)
        }

    @Test
    fun callerOwnedCollectionsAndPublishedSnapshotsCannotMutateTheCache() =
        runTest {
            val dao = MemorySettingsDao()
            val repository = SettingsRepositoryImpl(dao)
            val colors = mutableListOf(1, 2)
            val recent = mutableListOf(3, 4)
            repository.update { it.copy(customPalettes = listOf(Palette(name = "Owned", colors = colors)), recentColors = recent) }
            colors.clear()
            recent.clear()
            val snapshot = repository.settings.first()
            (snapshot.customPalettes[0].colors as MutableList<Int>).clear()
            (snapshot.recentColors as MutableList<Int>).clear()
            assertEquals(listOf(1, 2), repository.current().customPalettes[0].colors)
            assertEquals(listOf(3, 4), repository.current().recentColors)
            assertEquals(repository.current(), SettingsRepositoryImpl(dao).settings.first())
        }

    @Test
    fun aThrowingTransformCannotMutateCachedCollections() =
        runTest {
            val repository = SettingsRepositoryImpl(MemorySettingsDao())
            repository.update { it.copy(recentColors = listOf(1, 2)) }
            val before = repository.current()
            assertTrue(
                runCatching {
                    repository.update {
                        (it.recentColors as MutableList<Int>).clear()
                        error("Aborted transform")
                    }
                }.isFailure,
            )
            assertEquals(before, repository.current())
        }

    private class MemorySettingsDao(
        initial: Map<String, String> = emptyMap(),
    ) : SettingsDao {
        val rows = initial.mapValues { (key, value) -> SettingsEntity(key = key, value = value) }.toMutableMap()
        var reads = 0
        var writes = 0
        var readGate: CompletableDeferred<Unit>? = null
        var writeGate: CompletableDeferred<Unit>? = null
        var readFailure: IOException? = null
        var writeFailure: IOException? = null

        override fun getAllSettings(): Flow<List<SettingsEntity>> =
            flow {
                reads++
                readGate?.await()
                readFailure?.let { throw it }
                emit(rows.values.toList())
            }

        override suspend fun insertSettings(settings: List<SettingsEntity>) {
            writes++
            writeGate?.await()
            writeFailure?.let { throw it }
            settings.forEach { rows[it.key] = it }
        }

        override suspend fun getSettingByKey(key: String): SettingsEntity? = rows[key]

        override fun getSettingsByCategory(category: String): Flow<List<SettingsEntity>> =
            flow { emit(rows.values.filter { it.category == category }) }

        override suspend fun insertSetting(setting: SettingsEntity): Unit = error("Single-row writes are not atomic")

        override suspend fun updateSetting(setting: SettingsEntity): Unit = error("Unused")

        override suspend fun deleteSetting(setting: SettingsEntity): Unit = error("Unused")

        override suspend fun deleteSettingByKey(key: String): Unit = error("Unused")

        override suspend fun updateSettingValue(
            key: String,
            value: String,
            timestamp: Long,
        ): Unit = error("Unused")
    }
}
