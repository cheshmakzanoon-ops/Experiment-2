package com.artflow.studio.presentation

import androidx.lifecycle.viewModelScope
import com.artflow.studio.core.canvas.CanvasOperations
import com.artflow.studio.core.pixels.IntBounds
import com.artflow.studio.data.export.ArtworkExporter
import com.artflow.studio.data.local.ProjectStorage
import com.artflow.studio.data.repository.canvas.CanvasRepositoryImpl
import com.artflow.studio.domain.model.Project
import com.artflow.studio.domain.model.layer.AdjustmentType
import com.artflow.studio.domain.model.settings.AppSettings
import com.artflow.studio.domain.repository.ProjectRepository
import com.artflow.studio.domain.repository.settings.SettingsRepository
import com.artflow.studio.presentation.ui.viewmodel.CanvasUiState
import com.artflow.studio.presentation.ui.viewmodel.CanvasViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

/** The real repository's metadata notifications must reach the state used by the exit dialog. */
@OptIn(ExperimentalCoroutinesApi::class)
class CanvasMetadataViewModelTest {
    @Test
    fun dpiOnlyEditEnablesSaveWarningAndUndoWithoutAPixelEdit() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val fixture = Fixture()
            try {
                fixture.viewModel.open(1L)
                runCurrent()
                assertTrue(fixture.viewModel.uiState.value is CanvasUiState.Ready)
                assertFalse(fixture.viewModel.dirty.value)
                fixture.viewModel.setCanvasDpi(300)
                runCurrent()
                assertTrue(fixture.viewModel.dirty.value)
                assertEquals(1, fixture.viewModel.history.value.undoDepth)
                assertEquals(300, (fixture.viewModel.uiState.value as CanvasUiState.Ready).dpi)
            } finally {
                fixture.close()
                Dispatchers.resetMain()
            }
        }

    @Test
    fun durationOnlyEditEnablesSaveWarningAndUndoWithoutAPixelEdit() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val fixture = Fixture()
            try {
                fixture.viewModel.open(1L)
                runCurrent()
                fixture.viewModel.setFrameDuration(0, 500)
                runCurrent()
                assertTrue(fixture.viewModel.dirty.value)
                assertEquals(1, fixture.viewModel.history.value.undoDepth)
                assertEquals(500, fixture.viewModel.timeline.value.frames.single().durationMs)
            } finally {
                fixture.close()
                Dispatchers.resetMain()
            }
        }

    @Test
    fun displayedDpiUsesTheRepositorysClampedValue() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val fixture = Fixture()
            try {
                fixture.viewModel.open(1L)
                runCurrent()
                fixture.viewModel.setCanvasDpi(Int.MAX_VALUE)
                runCurrent()
                assertEquals(CanvasOperations.MAX_DPI, (fixture.viewModel.uiState.value as CanvasUiState.Ready).dpi)
                fixture.viewModel.setCanvasDpi(Int.MIN_VALUE)
                runCurrent()
                assertEquals(CanvasOperations.MIN_DPI, (fixture.viewModel.uiState.value as CanvasUiState.Ready).dpi)
            } finally {
                fixture.close()
                Dispatchers.resetMain()
            }
        }

    @Test
    fun acceptingUnchangedMetadataDoesNotCreateASaveWarning() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val fixture = Fixture()
            try {
                fixture.viewModel.open(1L)
                runCurrent()
                fixture.viewModel.setCanvasDpi(72)
                fixture.viewModel.setFrameDuration(0, fixture.repository.frames().single().durationMs)
                fixture.viewModel.setCanvasBackgroundColor(fixture.repository.getBackgroundColor())
                runCurrent()
                assertFalse(fixture.viewModel.dirty.value)
                assertEquals(0, fixture.viewModel.history.value.undoDepth)
            } finally {
                fixture.close()
                Dispatchers.resetMain()
            }
        }

    @Test
    fun rejectedCropDoesNotPublishImaginaryCanvasDimensions() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val fixture = Fixture()
            try {
                fixture.viewModel.open(1L)
                runCurrent()
                val before = fixture.viewModel.uiState.value
                fixture.viewModel.cropCanvas(IntBounds(50, 50, 60, 60))
                runCurrent()
                assertEquals(before, fixture.viewModel.uiState.value)
                assertFalse(fixture.viewModel.dirty.value)
                assertEquals(0, fixture.viewModel.history.value.undoDepth)
            } finally {
                fixture.close()
                Dispatchers.resetMain()
            }
        }

    @Test
    fun rejectedRotationPreservesDimensionsAndReportsFailure() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val fixture = Fixture()
            try {
                fixture.viewModel.open(1L)
                runCurrent()
                val before = fixture.viewModel.uiState.value
                val message = async(UnconfinedTestDispatcher(testScheduler)) { fixture.viewModel.messageFlow.first() }
                fixture.viewModel.rotateCanvas(45)
                runCurrent()
                assertEquals(before, fixture.viewModel.uiState.value)
                assertTrue(message.await().startsWith("Rotation not applied."))
                assertFalse(fixture.viewModel.dirty.value)
            } finally {
                fixture.close()
                Dispatchers.resetMain()
            }
        }

    @Test
    fun rejectedFlipDuringAnActiveEditDoesNotReportSuccess() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val fixture = Fixture()
            try {
                fixture.viewModel.open(1L)
                runCurrent()
                val session = requireNotNull(fixture.repository.beginRasterEdit(fixture.repository.getActiveLayerId()))
                val message = async(UnconfinedTestDispatcher(testScheduler)) { fixture.viewModel.messageFlow.first() }
                fixture.viewModel.flipCanvas(true)
                runCurrent()
                assertTrue(message.await().startsWith("Flip not applied."))
                assertFalse(fixture.viewModel.dirty.value)
                assertEquals(0, fixture.viewModel.history.value.undoDepth)
                fixture.repository.cancelRasterEdit(session)
            } finally {
                fixture.close()
                Dispatchers.resetMain()
            }
        }

    @Test
    fun rejectedAdjustmentDuringAnActiveEditDoesNotReportSuccess() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val fixture = Fixture()
            try {
                fixture.viewModel.open(1L)
                runCurrent()
                val session = requireNotNull(fixture.repository.beginRasterEdit(fixture.repository.getActiveLayerId()))
                val message = async(UnconfinedTestDispatcher(testScheduler)) { fixture.viewModel.messageFlow.first() }
                fixture.viewModel.applyAdjustmentToCanvas(AdjustmentType.INVERT, emptyMap(), true)
                runCurrent()
                assertTrue(message.await().startsWith("Adjustment not applied."))
                assertFalse(fixture.viewModel.dirty.value)
                assertEquals(0, fixture.viewModel.history.value.undoDepth)
                fixture.repository.cancelRasterEdit(session)
            } finally {
                fixture.close()
                Dispatchers.resetMain()
            }
        }

    private class Fixture {
        private val storage =
            mock(ProjectStorage::class.java) { call ->
                when (call.method.name) {
                    "loadDocument" -> null
                    "hasUnsavedRecovery" -> false
                    else -> error("Unexpected storage call: ${call.method.name}")
                }
            }
        val repository = CanvasRepositoryImpl(storage)
        private val project = Project(1L, "Metadata", "", null, 8, 6, 72, 1L, 1L)
        private val projects =
            mock(ProjectRepository::class.java) { call ->
                check(call.method.name == "getProjectById") { "Unexpected project call: ${call.method.name}" }
                project
            }
        private val settings =
            mock(SettingsRepository::class.java).also {
                `when`(it.settings).thenReturn(flowOf(AppSettings(autosaveEnabled = false)))
            }
        val viewModel = CanvasViewModel(repository, projects, settings, mock(ArtworkExporter::class.java))

        fun close() {
            viewModel.viewModelScope.cancel()
            repository.dispose()
        }
    }
}
