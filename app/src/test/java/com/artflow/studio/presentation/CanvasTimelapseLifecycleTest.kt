package com.artflow.studio.presentation

import androidx.lifecycle.viewModelScope
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.data.export.ArtworkExporter
import com.artflow.studio.data.export.TimelapseRecorder
import com.artflow.studio.data.local.ProjectStorage
import com.artflow.studio.data.repository.canvas.CanvasRepositoryImpl
import com.artflow.studio.domain.model.Project
import com.artflow.studio.domain.model.settings.AppSettings
import com.artflow.studio.domain.repository.ProjectRepository
import com.artflow.studio.domain.repository.canvas.CanvasRepository
import com.artflow.studio.domain.repository.settings.SettingsRepository
import com.artflow.studio.presentation.ui.viewmodel.CanvasUiState
import com.artflow.studio.presentation.ui.viewmodel.CanvasViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

/** Delayed edits and suspended composition must never cross artwork ownership. */
@OptIn(ExperimentalCoroutinesApi::class)
class CanvasTimelapseLifecycleTest {
    @Test
    fun pendingCaptureCannotWriteOldPixelsIntoAnArtworkThatIsStillLoading() =
        withFixture { fixture ->
            fixture.paint(OLD_INK)
            runCurrent()
            fixture.pauseSecondOpen = true
            fixture.viewModel.open(SECOND_PROJECT)
            runCurrent()
            assertTrue(fixture.viewModel.uiState.value is CanvasUiState.Loading)
            advanceTimeBy(1_000)
            runCurrent()
            assertTrue(fixture.captures.isEmpty())

            fixture.openGate.complete(Unit)
            runCurrent()
            assertEquals(SECOND_PROJECT, (fixture.viewModel.uiState.value as CanvasUiState.Ready).projectId)
            fixture.paint(NEW_INK)
            runCurrent()
            advanceTimeBy(1_000)
            runCurrent()
            assertEquals(listOf(SECOND_PROJECT to NEW_INK), fixture.captures)
        }

    @Test
    fun suspendedCompositionCannotRecordTheNextArtworkUnderThePreviousIdentity() =
        withFixture { fixture ->
            fixture.pauseComposite = true
            fixture.paint(OLD_INK)
            runCurrent()
            advanceTimeBy(1_000)
            runCurrent()
            assertTrue(fixture.compositeEntered.isCompleted)

            fixture.viewModel.open(SECOND_PROJECT)
            runCurrent()
            assertEquals(SECOND_PROJECT, (fixture.viewModel.uiState.value as CanvasUiState.Ready).projectId)
            fixture.paint(NEW_INK)
            fixture.compositeGate.complete(Unit)
            runCurrent()
            advanceTimeBy(1_000)
            runCurrent()
            assertEquals(listOf(SECOND_PROJECT to NEW_INK), fixture.captures)
        }

    @Test
    fun failedOpenCancelsPendingCaptureAndRetryCanRecordAgain() =
        withFixture { fixture ->
            fixture.paint(OLD_INK)
            runCurrent()
            fixture.missingSecondProject = true
            fixture.viewModel.open(SECOND_PROJECT)
            runCurrent()
            assertTrue(fixture.viewModel.uiState.value is CanvasUiState.Error)
            advanceTimeBy(1_000)
            runCurrent()
            assertTrue(fixture.captures.isEmpty())

            fixture.missingSecondProject = false
            fixture.viewModel.open(SECOND_PROJECT)
            runCurrent()
            fixture.paint(NEW_INK)
            runCurrent()
            advanceTimeBy(1_000)
            runCurrent()
            assertEquals(listOf(SECOND_PROJECT to NEW_INK), fixture.captures)
        }

    private fun withFixture(block: suspend TestScope.(Fixture) -> Unit) =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val fixture = Fixture()
            try {
                fixture.viewModel.open(FIRST_PROJECT)
                runCurrent()
                assertTrue(fixture.viewModel.uiState.value is CanvasUiState.Ready)
                block(fixture)
            } finally {
                fixture.viewModel.viewModelScope.cancel()
                fixture.compositeGate.complete(Unit)
                fixture.openGate.complete(Unit)
                runCurrent()
                fixture.canvas.dispose()
                Dispatchers.resetMain()
            }
        }

    private class Fixture {
        val canvas = CanvasRepositoryImpl(mock(ProjectStorage::class.java))
        var pauseSecondOpen = false
        var missingSecondProject = false
        var pauseComposite = false
        val openGate = CompletableDeferred<Unit>()
        val compositeEntered = CompletableDeferred<Unit>()
        val compositeGate = CompletableDeferred<Unit>()
        val captures = mutableListOf<Pair<Long, Int>>()
        private val repository =
            object : CanvasRepository by canvas {
                override suspend fun compositeWithoutPrivateLayers(): PixelBuffer {
                    if (pauseComposite) {
                        compositeEntered.complete(Unit)
                        // A compositor may finish native/CPU work after its caller is cancelled.
                        withContext(NonCancellable) { compositeGate.await() }
                    }
                    // Read the active artwork after suspension, as a compositor awaiting the
                    // repository lock may do. A stale collector must be cancelled before this.
                    return requireNotNull(canvas.layerPixels(canvas.getActiveLayerId())).copy()
                }
            }
        private val projects =
            object : ProjectRepository by mock(ProjectRepository::class.java) {
                override suspend fun getProjectById(projectId: Long): Project? {
                    if (projectId == SECOND_PROJECT) {
                        if (missingSecondProject) return null
                        if (pauseSecondOpen) openGate.await()
                    }
                    return Project(projectId, "Artwork $projectId", "", null, 8, 6, 72, 1L, 1L)
                }
            }
        private val settings =
            mock(SettingsRepository::class.java).also {
                `when`(it.settings).thenReturn(flowOf(AppSettings(autosaveEnabled = false, recordTimelapse = true)))
            }
        private val recorder =
            mock(TimelapseRecorder::class.java) { call ->
                when (call.method.name) {
                    "isEnabled", "isRecording" -> true
                    "capture" -> {
                        captures += call.getArgument<Long>(0) to call.getArgument<PixelBuffer>(1).pixels.first()
                        Unit
                    }
                    else -> error("Unexpected recording call: ${call.method.name}")
                }
            }
        val viewModel = CanvasViewModel(repository, projects, settings, mock(ArtworkExporter::class.java), recorder)

        suspend fun paint(ink: Int) {
            canvas.applyRasterEdit(canvas.getActiveLayerId(), "Paint") { it.pixels.fill(ink) }
        }
    }

    private companion object {
        const val FIRST_PROJECT = 1L
        const val SECOND_PROJECT = 2L
        const val OLD_INK = 0xFF112233.toInt()
        const val NEW_INK = 0xFF445566.toInt()
    }
}
