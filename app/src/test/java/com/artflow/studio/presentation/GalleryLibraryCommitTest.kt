package com.artflow.studio.presentation

import androidx.lifecycle.viewModelScope
import com.artflow.studio.core.color.Palette
import com.artflow.studio.data.local.ProjectStorage
import com.artflow.studio.data.local.dao.SettingsDao
import com.artflow.studio.data.local.entity.SettingsEntity
import com.artflow.studio.data.repository.BrushLibraryStore
import com.artflow.studio.data.repository.settings.SettingsRepositoryImpl
import com.artflow.studio.domain.model.brush.BrushParams
import com.artflow.studio.domain.model.brush.SavedBrush
import com.artflow.studio.domain.repository.ProjectRepository
import com.artflow.studio.presentation.ui.viewmodel.BrushLibraryViewModel
import com.artflow.studio.presentation.ui.viewmodel.MainViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.io.IOException

/** Gallery success must follow the actual library commit, including failure and retry. */
@OptIn(ExperimentalCoroutinesApi::class)
class GalleryLibraryCommitTest {
    @Test
    fun paletteImportWaitsForItsCommittedLibraryRow() = pausedWrite(brush = false)

    @Test
    fun brushImportWaitsForItsCommittedLibraryRow() = pausedWrite(brush = true)

    @Test
    fun paletteImportFailureReachesTheCallerAndPreservesExistingRows() = failedWrite(brush = false)

    @Test
    fun brushImportFailureReachesTheCallerAndPreservesExistingRows() = failedWrite(brush = true)

    private fun pausedWrite(brush: Boolean) =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val fixture = Fixture()
            try {
                fixture.seedAndLoad()
                val before = fixture.dao.rows.toMap()
                fixture.dao.pause = true
                val operation = async { runCatching { fixture.import(brush) } }
                fixture.awaitWrite()
                runCurrent()
                assertFalse("The import must not report success before the write finishes", operation.isCompleted)
                assertEquals(before, fixture.dao.rows)
                fixture.dao.release.complete(Unit)
                operation.await().getOrThrow()
                assertEquals(listOf("Existing", "Imported"), fixture.names(brush))
            } finally {
                fixture.close()
                Dispatchers.resetMain()
            }
        }

    private fun failedWrite(brush: Boolean) =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val fixture = Fixture()
            try {
                fixture.seedAndLoad()
                val before = fixture.dao.rows.toMap()
                fixture.dao.failure = IOException("Injected full storage")
                val failure = runCatching { fixture.import(brush) }.exceptionOrNull()
                assertTrue("The gallery caller must receive the failed write", failure is IOException)
                assertEquals(before, fixture.dao.rows)
                assertEquals(listOf("Existing"), fixture.names(brush))
                fixture.dao.failure = null
                fixture.import(brush)
                assertEquals(listOf("Existing", "Imported"), fixture.names(brush))
            } finally {
                fixture.close()
                Dispatchers.resetMain()
            }
        }

    private class Fixture {
        val dao = ControlledDao()
        val settings = SettingsRepositoryImpl(dao)
        val store = BrushLibraryStore(dao)
        private val projects = mock(ProjectRepository::class.java).also { `when`(it.getAllProjects()).thenReturn(flowOf(emptyList())) }
        val main = MainViewModel(projects, settings, mock(ProjectStorage::class.java))
        val brushes = BrushLibraryViewModel(store)

        suspend fun seedAndLoad() {
            settings.addPalette(Palette(id = 41, name = "Existing", colors = listOf(-1)))
            store.saveCopy("Existing", BrushParams())
            withContext(Dispatchers.Default) { withTimeout(5_000) { brushes.state.first { !it.loading } } }
        }

        suspend fun import(brush: Boolean) {
            if (brush) {
                brushes.importWhenReady(listOf(SavedBrush("incoming", "Imported", BrushParams(size = 7f))))
            } else {
                main.addPalette("Imported", listOf(0xFF123456.toInt()))
            }
        }

        suspend fun names(brush: Boolean): List<String> =
            if (brush) {
                BrushLibraryStore(dao).brushes.first().map { it.name }
            } else {
                SettingsRepositoryImpl(dao)
                    .settings
                    .first()
                    .customPalettes
                    .map { it.name }
            }

        suspend fun awaitWrite() = withContext(Dispatchers.Default) { withTimeout(5_000) { dao.entered.await() } }

        fun close() {
            dao.release.complete(Unit)
            main.viewModelScope.cancel()
            brushes.viewModelScope.cancel()
        }
    }

    private class ControlledDao : SettingsDao by mock(SettingsDao::class.java) {
        val rows = linkedMapOf<String, SettingsEntity>()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var pause = false
        var failure: IOException? = null

        override fun getAllSettings() = flowOf(rows.values.toList())

        override suspend fun getSettingByKey(key: String) = rows[key]

        override suspend fun insertSetting(setting: SettingsEntity) {
            beforeWrite()
            rows[setting.key] = setting
        }

        override suspend fun insertSettings(settings: List<SettingsEntity>) {
            beforeWrite()
            settings.forEach { rows[it.key] = it }
        }

        private suspend fun beforeWrite() {
            if (pause) {
                entered.complete(Unit)
                release.await()
            }
            failure?.let { throw it }
        }
    }
}
