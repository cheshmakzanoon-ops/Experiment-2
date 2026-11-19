package com.artflow.studio.presentation

import android.content.Context
import androidx.lifecycle.viewModelScope
import com.artflow.studio.core.pixels.SelectionMask
import com.artflow.studio.data.export.ArtworkExporter
import com.artflow.studio.data.export.TimelapseRecorder
import com.artflow.studio.data.local.ProjectStorage
import com.artflow.studio.data.repository.canvas.CanvasRepositoryImpl
import com.artflow.studio.domain.model.Project
import com.artflow.studio.domain.model.settings.AppSettings
import com.artflow.studio.domain.repository.ProjectRepository
import com.artflow.studio.domain.repository.settings.SettingsRepository
import com.artflow.studio.presentation.ui.viewmodel.CanvasUiState
import com.artflow.studio.presentation.ui.viewmodel.CanvasViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

@OptIn(ExperimentalCoroutinesApi::class)
class CanvasRecoverySelectionsTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun recoveredSlotsReachTheUiBeforeTheNextSlotIsSaved() = recoveryPreservesSlots(listOf(mask(80)))

    @Test
    fun recoveringAnEmptySlotListDoesNotResurrectTheSavedDocumentsSlots() = recoveryPreservesSlots(emptyList())

    private fun recoveryPreservesSlots(recoveredSlots: List<SelectionMask>) =
        runTest {
            Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
            val context = mock(Context::class.java).also { `when`(it.filesDir).thenReturn(temporary.newFolder("files")) }
            val storage = ProjectStorage(context)
            val writer = CanvasRepositoryImpl(storage)
            try {
                writer.loadOrCreate(PROJECT, WIDTH, HEIGHT, 72)
                writer.setSavedSelections(listOf(mask(40)))
                writer.saveCanvas(PROJECT)
                writer.setSavedSelections(recoveredSlots)
                writer.autosave(PROJECT)
                assertTrue(storage.hasUnsavedRecovery(PROJECT))
            } finally {
                writer.dispose()
            }

            val canvas = CanvasRepositoryImpl(storage)
            var row = Project(PROJECT, "Recover selections", "", null, WIDTH, HEIGHT, 72, 1L, 1L)
            val projects =
                object : ProjectRepository by mock(ProjectRepository::class.java) {
                    override suspend fun getProjectById(projectId: Long): Project? = row.takeIf { it.id == projectId }

                    override suspend fun updateSavedArtwork(project: Project): Project = project.also { row = it }
                }
            val settings =
                mock(SettingsRepository::class.java).also {
                    `when`(it.settings).thenReturn(flowOf(AppSettings(autosaveEnabled = false)))
                }
            val viewModel =
                CanvasViewModel(canvas, projects, settings, ArtworkExporter(context, storage), mock(TimelapseRecorder::class.java))
            try {
                viewModel.open(PROJECT)
                await { viewModel.savedSelections.saved.first { coverage(it) == listOf(40) } }
                assertTrue((viewModel.uiState.value as CanvasUiState.Ready).recoveryAvailable)

                viewModel.recoverAutosave()
                await { viewModel.messageFlow.first { it == "Recovered the autosaved version" } }
                assertEquals(coverage(recoveredSlots), coverage(canvas.savedSelections()))
                assertEquals(coverage(recoveredSlots), coverage(viewModel.savedSelections.saved.value))
                assertFalse((viewModel.uiState.value as CanvasUiState.Ready).recoveryAvailable)

                canvas.setSelection(mask(120))
                assertTrue(viewModel.savedSelections.save())
                val expected = coverage(recoveredSlots) + 120
                assertEquals(expected, coverage(canvas.savedSelections()))
                assertEquals(expected, coverage(viewModel.savedSelections.saved.value))
                viewModel.save()
                await { viewModel.saving.first { !it } }
                assertFalse(storage.hasUnsavedRecovery(PROJECT))

                val reopened = CanvasRepositoryImpl(storage)
                try {
                    reopened.loadOrCreate(PROJECT, WIDTH, HEIGHT, 72)
                    assertEquals(expected, coverage(reopened.savedSelections()))
                } finally {
                    reopened.dispose()
                }
            } finally {
                viewModel.viewModelScope.coroutineContext[Job]?.cancelAndJoin()
                canvas.dispose()
                Dispatchers.resetMain()
            }
        }

    private companion object {
        const val PROJECT = 17L
        const val WIDTH = 8
        const val HEIGHT = 6

        fun mask(value: Int): SelectionMask = SelectionMask(WIDTH, HEIGHT, ByteArray(WIDTH * HEIGHT) { value.toByte() })

        fun coverage(masks: List<SelectionMask>): List<Int> = masks.map { it.coverageAt(0, 0) }

        suspend fun <T> await(block: suspend () -> T): T =
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) { block() }
            }
    }
}
