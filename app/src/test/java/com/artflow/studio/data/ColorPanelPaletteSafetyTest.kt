package com.artflow.studio.data

import com.artflow.studio.core.color.Palette
import com.artflow.studio.data.local.dao.SettingsDao
import com.artflow.studio.data.local.entity.SettingsEntity
import com.artflow.studio.data.repository.settings.SettingsRepositoryImpl
import com.artflow.studio.presentation.ui.components.color.PaletteEdits
import com.artflow.studio.presentation.ui.viewmodel.PaletteActions
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ColorPanelPaletteSafetyTest {
    @Test
    fun queuedPanelAdditionsKeepBothColorsWhenTheDisplayedPaletteIsStale() =
        runTest {
            val dao = MemorySettingsDao()
            val repository = SettingsRepositoryImpl(dao)
            val displayed = Palette(id = 7, name = "Working", colors = listOf(RED))
            repository.addPalette(displayed)
            val failures = mutableListOf<Throwable>()
            val editorScope = CoroutineScope(coroutineContext + SupervisorJob())
            try {
                val actions = PaletteActions(repository, editorScope, CoroutineExceptionHandler { _, error -> failures += error }, {})
                // CanvasScreen uses this binding; the separate UI regression exercises ColorPanel gestures.
                val edits = PaletteEdits.from(actions)
                dao.writeGate = CompletableDeferred()
                edits.onAddColor(displayed.id, BLUE)
                runCurrent()
                edits.onAddColor(displayed.id, GREEN)
                runCurrent()
                assertEquals(displayed, repository.current().customPalettes.single())
                dao.writeGate!!.complete(Unit)
                runCurrent()

                assertTrue(failures.toString(), failures.isEmpty())
                assertEquals(displayed.copy(colors = listOf(RED, BLUE, GREEN)), reopened(dao))
            } finally {
                dao.writeGate?.complete(Unit)
                editorScope.cancel()
            }
        }

    @Test
    fun aStalePanelRemovalKeepsNewerSwatchesAndReportsAVisibleRetry() =
        runTest {
            val dao = MemorySettingsDao()
            val repository = SettingsRepositoryImpl(dao)
            val displayed = Palette(id = 8, name = "Working", colors = listOf(RED, BLUE, GREEN))
            repository.addPalette(displayed)
            val latest = displayed.copy(name = "Renamed", colors = listOf(BLUE, GREEN))
            repository.updatePalette(latest)
            val failures = mutableListOf<Throwable>()
            val editorScope = CoroutineScope(coroutineContext + SupervisorJob())
            try {
                val actions = PaletteActions(repository, editorScope, CoroutineExceptionHandler { _, error -> failures += error }, {})
                val edits = PaletteEdits.from(actions)
                edits.onRemoveColor(displayed, 1)
                runCurrent()

                assertEquals(latest, reopened(dao))
                assertEquals(1, failures.size)
                assertTrue(
                    failures
                        .single()
                        .message
                        .orEmpty()
                        .contains("palette changed", ignoreCase = true),
                )
                // Refresh the selection: the intended swatch can now be removed without undoing the rename.
                edits.onRemoveColor(latest, 1)
                runCurrent()
                assertEquals(latest.copy(colors = listOf(BLUE)), reopened(dao))
                assertEquals(1, failures.size)
            } finally {
                editorScope.cancel()
            }
        }

    @Test
    fun aDeletedPanelPaletteCannotBeEditedAndReportsTheFailure() =
        runTest {
            val dao = MemorySettingsDao()
            val repository = SettingsRepositoryImpl(dao)
            val displayed = Palette(id = 9, name = "Working", colors = listOf(RED))
            repository.addPalette(displayed)
            repository.removePalette(displayed.id)
            val failures = mutableListOf<Throwable>()
            val editorScope = CoroutineScope(coroutineContext + SupervisorJob())
            try {
                val actions = PaletteActions(repository, editorScope, CoroutineExceptionHandler { _, error -> failures += error }, {})
                val edits = PaletteEdits.from(actions)
                edits.onAddColor(displayed.id, BLUE)
                edits.onRemoveColor(displayed, 0)
                runCurrent()

                assertTrue(
                    SettingsRepositoryImpl(dao)
                        .settings
                        .first()
                        .customPalettes
                        .isEmpty(),
                )
                assertEquals(2, failures.size)
                assertTrue(failures.all { it.message.orEmpty().contains("no longer available", ignoreCase = true) })
            } finally {
                editorScope.cancel()
            }
        }

    @Test
    fun aCurrentSwatchSelectionKeepsNewerMetadataAndRemovesOnlyTheSelectedDuplicate() =
        runTest {
            val dao = MemorySettingsDao()
            val repository = SettingsRepositoryImpl(dao)
            val displayed = Palette(id = 10, name = "Working", colors = listOf(RED, BLUE, RED))
            repository.addPalette(displayed)
            val renamed = displayed.copy(name = "Renamed", isFavorite = true, source = "Imported")
            repository.updatePalette(renamed)
            val failures = mutableListOf<Throwable>()
            val editorScope = CoroutineScope(coroutineContext + SupervisorJob())
            try {
                val actions = PaletteActions(repository, editorScope, CoroutineExceptionHandler { _, error -> failures += error }, {})
                val edits = PaletteEdits.from(actions)
                edits.onRemoveColor(displayed, 2)
                runCurrent()
                // The panel's existing perceptual duplicate suppression still applies to additions.
                edits.onAddColor(displayed.id, RED)
                runCurrent()

                assertEquals(renamed.copy(colors = listOf(RED, BLUE)), reopened(dao))
                assertTrue(failures.toString(), failures.isEmpty())
            } finally {
                editorScope.cancel()
            }
        }

    @Test
    fun aSwatchReplacementRequiresTheDisplayedColorsAndKeepsNewerMetadata() =
        runTest {
            val dao = MemorySettingsDao()
            val repository = SettingsRepositoryImpl(dao)
            val displayed = Palette(id = 11, name = "Working", colors = listOf(RED, BLUE, RED))
            repository.addPalette(displayed)
            val latest = displayed.copy(name = "Renamed", colors = listOf(BLUE, RED), isFavorite = true)
            repository.updatePalette(latest)
            val failures = mutableListOf<Throwable>()
            val editorScope = CoroutineScope(coroutineContext + SupervisorJob())
            try {
                val actions = PaletteActions(repository, editorScope, CoroutineExceptionHandler { _, error -> failures += error }, {})
                val edits = PaletteEdits.from(actions)
                edits.onReplaceColor(displayed, 1, GREEN)
                runCurrent()
                assertEquals(latest, reopened(dao))
                assertEquals(1, failures.size)
                assertTrue(
                    failures
                        .single()
                        .message
                        .orEmpty()
                        .contains("palette changed", ignoreCase = true),
                )

                edits.onReplaceColor(latest, 1, GREEN)
                runCurrent()
                assertEquals(latest.copy(colors = listOf(BLUE, GREEN)), reopened(dao))
                assertEquals(1, failures.size)
            } finally {
                editorScope.cancel()
            }
        }

    @Test
    fun panelCreationDuplicationAndRenameKeepTheDefaultPaletteThroughReopen() =
        runTest {
            val dao = MemorySettingsDao()
            val repository = SettingsRepositoryImpl(dao)
            val actions = PaletteActions(repository, this, CoroutineExceptionHandler { _, error -> throw error }, {})
            val edits = PaletteEdits.from(actions)
            edits.onCreate("Study")
            runCurrent()
            val created = reopened(dao)
            assertEquals("Study", created.name)
            assertEquals(emptyList<Int>(), created.colors)
            assertEquals("Study", repository.current().defaultPaletteName)

            edits.onAddColor(created.id, BLUE)
            runCurrent()
            edits.onRename(created.id, "Landscape")
            runCurrent()
            val original = reopened(dao)
            assertEquals("Landscape", SettingsRepositoryImpl(dao).settings.first().defaultPaletteName)
            edits.onDuplicate(original)
            runCurrent()
            val duplicated = SettingsRepositoryImpl(dao).settings.first()
            assertEquals("Landscape copy", duplicated.defaultPaletteName)
            assertEquals(listOf("Landscape copy", "Landscape"), duplicated.customPalettes.map { it.name })
            assertEquals(listOf(listOf(BLUE), listOf(BLUE)), duplicated.customPalettes.map { it.colors })
            assertEquals(
                2,
                duplicated.customPalettes
                    .map { it.id }
                    .toSet()
                    .size,
            )

            edits.onRemove(duplicated.customPalettes.first().id)
            runCurrent()
            assertEquals("", SettingsRepositoryImpl(dao).settings.first().defaultPaletteName)
            assertEquals(original, reopened(dao))
        }

    private suspend fun reopened(dao: SettingsDao): Palette =
        SettingsRepositoryImpl(dao)
            .settings
            .first()
            .customPalettes
            .single()

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

    private companion object {
        val RED = 0xFFFF0000.toInt()
        val BLUE = 0xFF0000FF.toInt()
        val GREEN = 0xFF00FF00.toInt()
    }
}
