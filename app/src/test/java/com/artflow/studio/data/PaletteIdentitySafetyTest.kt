package com.artflow.studio.data

import com.artflow.studio.core.color.Palette
import com.artflow.studio.data.local.dao.SettingsDao
import com.artflow.studio.data.local.entity.SettingsEntity
import com.artflow.studio.data.repository.settings.SettingsRepositoryImpl
import com.artflow.studio.presentation.ui.viewmodel.PaletteEditor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PaletteIdentitySafetyTest {
    @Test
    fun repeatedCreationIdentityPreservesBothPalettesAndTheirExactContentsAfterReopen() =
        runTest {
            val dao = MemorySettingsDao()
            val repository = SettingsRepositoryImpl(dao)
            val first = Palette(id = 1700000000000L, name = "First", colors = listOf(-1, 0xFF123456.toInt()))
            val second = first.copy(name = "Second", colors = listOf(0xFFABCDEF.toInt(), 0xFF654321.toInt()))
            repository.addPalette(first)
            repository.addPalette(second)

            val reopened = SettingsRepositoryImpl(dao).settings.first().customPalettes
            assertEquals(listOf("First", "Second"), reopened.map { it.name })
            assertEquals(first, reopened[0])
            assertEquals(second.copy(id = reopened[1].id), reopened[1])
            assertEquals(2, reopened.map { it.id }.toSet().size)
            assertEquals(repository.current().customPalettes, reopened)
        }

    @Test
    fun concurrentCreationsAllocateFromTheCommittedCollectionAfterWaitingForTheWrite() =
        runTest {
            val dao = MemorySettingsDao()
            val repository = SettingsRepositoryImpl(dao)
            val existing = Palette(id = 70, name = "Existing", colors = listOf(7, 70))
            repository.addPalette(existing)
            dao.writeGate = CompletableDeferred()
            val first = async { repository.addPalette(Palette(id = 71, name = "First", colors = listOf(1, 11))) }
            runCurrent()
            val second = async { repository.addPalette(Palette(id = 71, name = "Second", colors = listOf(2, 22))) }
            runCurrent()
            assertFalse(first.isCompleted)
            assertFalse(second.isCompleted)
            assertEquals(listOf(existing), repository.current().customPalettes)
            dao.writeGate!!.complete(Unit)
            first.await()
            second.await()

            val reopened = SettingsRepositoryImpl(dao).settings.first().customPalettes
            assertEquals(listOf("Existing", "First", "Second"), reopened.map { it.name })
            assertEquals(listOf(listOf(7, 70), listOf(1, 11), listOf(2, 22)), reopened.map { it.colors })
            assertEquals(3, reopened.map { it.id }.toSet().size)
            assertEquals(existing, reopened.first())
        }

    @Test
    fun editorCreateAndDuplicateCannotReplaceEachOtherWhenTheVisiblePaletteListIsStale() =
        runTest {
            val dao = MemorySettingsDao()
            val repository = SettingsRepositoryImpl(dao)
            // Both old editor calls deterministically prefer Long.MAX_VALUE, regardless of the clock.
            val original = Palette(id = Long.MAX_VALUE - 1, name = "Original", colors = listOf(3, 33))
            repository.addPalette(original)
            val failures = mutableListOf<Throwable>()
            val editor = PaletteEditor(repository, this, CoroutineExceptionHandler { _, error -> failures += error }) { listOf(original) }
            dao.writeGate = CompletableDeferred()
            editor.create("Created")
            runCurrent()
            editor.duplicate(original)
            runCurrent()
            assertEquals(listOf(original), repository.current().customPalettes)
            dao.writeGate!!.complete(Unit)
            runCurrent()

            val reopened = SettingsRepositoryImpl(dao).settings.first().customPalettes
            assertTrue(failures.toString(), failures.isEmpty())
            assertEquals(listOf("Original copy", "Created", "Original"), reopened.map { it.name })
            assertEquals(listOf(listOf(3, 33), emptyList<Int>(), listOf(3, 33)), reopened.map { it.colors })
            assertEquals(3, reopened.map { it.id }.toSet().size)
            assertEquals(original, reopened.last())

            val created = reopened.single { it.name == "Created" }
            repository.updatePalette(created.copy(colors = listOf(4, 44)))
            val edited = SettingsRepositoryImpl(dao).settings.first().customPalettes
            assertEquals(reopened.map { it.id }, edited.map { it.id })
            assertEquals(listOf(listOf(3, 33), listOf(4, 44), listOf(3, 33)), edited.map { it.colors })
        }

    @Test
    fun zeroAndLargestStoredIdentitiesCannotAliasNewPaletteEdits() =
        runTest {
            for (id in listOf(0L, Long.MAX_VALUE)) {
                val dao = MemorySettingsDao()
                val repository = SettingsRepositoryImpl(dao)
                repository.addPalette(Palette(id = id, name = "Old", colors = listOf(1)))
                repository.addPalette(Palette(id = id, name = "New", colors = listOf(2)))
                val stored = repository.current().customPalettes
                assertEquals(listOf("Old", "New"), stored.map { it.name })
                assertEquals(2, stored.map { it.id }.toSet().size)
                repository.renamePalette(stored[1].id, "Renamed")
                val reopened = SettingsRepositoryImpl(dao).settings.first().customPalettes
                assertEquals(listOf("Old", "Renamed"), reopened.map { it.name })
                assertEquals(listOf(listOf(1), listOf(2)), reopened.map { it.colors })
            }
        }

    @Test
    fun rapidAppendsUseTheLatestStoredSwatchesWhileTheVisiblePaletteIsStale() =
        runTest {
            val dao = MemorySettingsDao()
            val repository = SettingsRepositoryImpl(dao)
            val original = Palette(id = 81, name = "Working", colors = listOf(1))
            repository.addPalette(original)
            val failures = mutableListOf<Throwable>()
            val editor = PaletteEditor(repository, this, CoroutineExceptionHandler { _, error -> failures += error }) { listOf(original) }
            dao.writeGate = CompletableDeferred()
            editor.addSwatch(original.id, 2)
            runCurrent()
            editor.addSwatch(original.id, 3)
            runCurrent()
            assertEquals(original, repository.current().customPalettes.single())
            dao.writeGate!!.complete(Unit)
            runCurrent()

            assertTrue(failures.toString(), failures.isEmpty())
            assertEquals(
                original.copy(colors = listOf(1, 2, 3)),
                SettingsRepositoryImpl(dao)
                    .settings
                    .first()
                    .customPalettes
                    .single(),
            )
        }

    @Test
    fun makingAPaletteDefaultReordersItsLatestContentsWithoutRestoringAStaleCopy() =
        runTest {
            val dao = MemorySettingsDao()
            val repository = SettingsRepositoryImpl(dao)
            val first = Palette(id = 91, name = "First", colors = listOf(1))
            val second = Palette(id = 92, name = "Second", colors = listOf(2))
            repository.addPalette(first)
            repository.addPalette(second)
            val failures = mutableListOf<Throwable>()
            val editor =
                PaletteEditor(
                    repository,
                    this,
                    CoroutineExceptionHandler {
                            _,
                            error,
                        ->
                        failures += error
                    },
                ) { listOf(first, second) }
            val edited = second.copy(name = "Renamed", colors = listOf(2, 22))
            repository.updatePalette(edited)
            editor.setDefault(second.id)
            runCurrent()

            assertTrue(failures.toString(), failures.isEmpty())
            val reopened = SettingsRepositoryImpl(dao).settings.first()
            assertEquals(listOf(edited, first), reopened.customPalettes)
            assertEquals("Renamed", reopened.defaultPaletteName)
        }

    @Test
    fun aQueuedEditCannotRecreateAPaletteThatWasDeletedAfterTheUiSnapshot() =
        runTest {
            val dao = MemorySettingsDao()
            val repository = SettingsRepositoryImpl(dao)
            val original = Palette(id = 101, name = "Deleted", colors = listOf(1))
            repository.addPalette(original)
            val failures = mutableListOf<Throwable>()
            val editor = PaletteEditor(repository, this, CoroutineExceptionHandler { _, error -> failures += error }) { listOf(original) }
            repository.removePalette(original.id)
            editor.addSwatch(original.id, 2)
            editor.setDefault(original.id)
            runCurrent()

            assertTrue(failures.toString(), failures.isEmpty())
            assertTrue(
                SettingsRepositoryImpl(dao)
                    .settings
                    .first()
                    .customPalettes
                    .isEmpty(),
            )
        }

    @Test
    fun anIndexedEditRefusesAChangedSwatchListInsteadOfRestoringOldColors() =
        runTest {
            val dao = MemorySettingsDao()
            val repository = SettingsRepositoryImpl(dao)
            val original = Palette(id = 111, name = "Edited", colors = listOf(1, 2, 3))
            repository.addPalette(original)
            val failures = mutableListOf<Throwable>()
            val editorScope = CoroutineScope(coroutineContext + SupervisorJob())
            var displayed = original
            try {
                val editor =
                    PaletteEditor(
                        repository,
                        editorScope,
                        CoroutineExceptionHandler {
                                _,
                                error,
                            ->
                            failures += error
                        },
                    ) { listOf(displayed) }
                val latest = original.copy(colors = listOf(2, 3))
                repository.updatePalette(latest)
                editor.replaceSwatch(original.id, 1, 9)
                runCurrent()

                assertEquals(
                    latest,
                    SettingsRepositoryImpl(dao)
                        .settings
                        .first()
                        .customPalettes
                        .single(),
                )
                assertEquals(1, failures.size)
                assertTrue(
                    failures
                        .single()
                        .message
                        .orEmpty()
                        .contains("palette changed", ignoreCase = true),
                )
                displayed = latest
                editor.replaceSwatch(original.id, 1, 9)
                runCurrent()
                displayed = repository.current().customPalettes.single()
                assertEquals(latest.copy(colors = listOf(2, 9)), displayed)
                editor.removeSwatch(original.id, 0)
                runCurrent()
                assertEquals(
                    latest.copy(colors = listOf(9)),
                    SettingsRepositoryImpl(dao)
                        .settings
                        .first()
                        .customPalettes
                        .single(),
                )
                assertEquals(1, failures.size)
            } finally {
                editorScope.cancel()
            }
        }

    private class MemorySettingsDao : SettingsDao {
        private val rows = mutableMapOf<String, SettingsEntity>()
        var writeGate: CompletableDeferred<Unit>? = null

        override fun getAllSettings(): Flow<List<SettingsEntity>> = flow { emit(rows.values.toList()) }

        override suspend fun insertSettings(settings: List<SettingsEntity>) {
            writeGate?.await()
            settings.forEach { rows[it.key] = it }
        }

        override suspend fun getSettingByKey(key: String): SettingsEntity? = rows[key]

        override fun getSettingsByCategory(category: String): Flow<List<SettingsEntity>> =
            flow { emit(rows.values.filter { it.category == category }) }

        override suspend fun insertSetting(setting: SettingsEntity): Unit = error("Only atomic writes are permitted")

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
