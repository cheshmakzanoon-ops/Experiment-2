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
import com.artflow.studio.domain.repository.settings.SettingsRepository
import com.artflow.studio.presentation.ui.viewmodel.CanvasUiState
import com.artflow.studio.presentation.ui.viewmodel.CanvasViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.io.IOException

/** Controlled persistence callbacks expose stale completions through the actual editor ViewModel. */
@OptIn(ExperimentalCoroutinesApi::class)
class CanvasTimelapseControlTest {
    @Test
    fun failedPauseKeepsTheEditorOffAndPreventsLaterSettledEditsFromBeingCaptured() =
        withFixture { fixture ->
            fixture.beforeSet = { _, _ -> throw IOException("Storage is full") }

            fixture.viewModel.setTimelapseRecording(false)
            runCurrent()
            fixture.paint()
            runCurrent()
            advanceTimeBy(1_000)
            runCurrent()

            assertFalse(fixture.viewModel.timelapseRecording.value)
            assertTrue(fixture.captures.isEmpty())
            assertEquals(1, fixture.messages.size)
            assertTrue(fixture.messages.single().contains("paused for this app session"))
            assertTrue(fixture.messages.single().contains("could not be saved"))
        }

    @Test
    fun resumeRemainsOffUntilPersistenceSucceedsAndFailureNeverShowsOn() =
        withFixture(initialRecording = false) { fixture ->
            var observedPending = false
            fixture.beforeSet = { _, recording ->
                assertTrue(recording)
                assertFalse(fixture.viewModel.timelapseRecording.value)
                observedPending = true
            }
            fixture.viewModel.setTimelapseRecording(true)
            runCurrent()
            assertTrue(observedPending)
            assertTrue(fixture.viewModel.timelapseRecording.value)

            fixture.beforeSet = { _, _ -> throw IOException("The preference could not be saved") }
            fixture.viewModel.setTimelapseRecording(true)
            runCurrent()
            assertFalse(fixture.viewModel.timelapseRecording.value)
        }

    @Test
    fun anOlderResumeCompletionCannotOverwriteANewerPause() =
        withFixture(initialRecording = false) { fixture ->
            var interrupted = false
            fixture.beforeSet = { _, recording ->
                if (recording && !interrupted) {
                    interrupted = true
                    // Deliver a new user request before the older persistence completion returns.
                    fixture.viewModel.setTimelapseRecording(false)
                }
            }

            fixture.viewModel.setTimelapseRecording(true)
            runCurrent()

            assertTrue(interrupted)
            assertFalse(fixture.viewModel.timelapseRecording.value)
            assertTrue(fixture.messages.isEmpty())
        }

    @Test
    fun anOlderOpeningPreferenceReadCannotOverwriteANewlyAcceptedPause() =
        withFixture { fixture ->
            var delivered = false
            fixture.beforeRead = { id ->
                if (id == SECOND && !delivered) {
                    delivered = true
                    fixture.viewModel.setTimelapseRecording(false)
                }
            }

            fixture.viewModel.open(SECOND)
            runCurrent()

            assertTrue(delivered)
            assertEquals(SECOND, (fixture.viewModel.uiState.value as CanvasUiState.Ready).projectId)
            assertFalse(fixture.viewModel.timelapseRecording.value)
        }

    @Test
    fun oldArtworkControlFailuresAndClearSuccessDoNotNotifyTheNewArtwork() =
        withFixture { fixture ->
            fixture.beforeSet = { _, _ ->
                fixture.viewModel.open(SECOND)
                throw IOException("The previous artwork could not be updated")
            }
            fixture.viewModel.setTimelapseRecording(false)
            runCurrent()
            assertEquals(SECOND, (fixture.viewModel.uiState.value as CanvasUiState.Ready).projectId)
            assertTrue(fixture.viewModel.timelapseRecording.value)
            assertTrue(fixture.messages.isEmpty())

            fixture.beforeClear = {
                fixture.viewModel.open(FIRST)
                fixture.viewModel.open(SECOND)
            }
            fixture.viewModel.clearTimelapse()
            runCurrent()
            assertEquals(SECOND, (fixture.viewModel.uiState.value as CanvasUiState.Ready).projectId)
            assertTrue(fixture.messages.isEmpty())
        }

    @Test
    fun failedClearReportsIncompleteDeletionInsteadOfSuccess() =
        withFixture { fixture ->
            fixture.beforeClear = { throw IOException("A recording file could not be deleted") }

            fixture.viewModel.clearTimelapse()
            runCurrent()

            assertTrue(fixture.viewModel.timelapseRecording.value)
            assertEquals(listOf("Could not finish clearing the recording. Open Replay to check what remains."), fixture.messages)
        }

    private fun withFixture(
        initialRecording: Boolean = true,
        block: suspend TestScope.(Fixture) -> Unit,
    ) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val fixture = Fixture(initialRecording)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { fixture.viewModel.messageFlow.toList(fixture.messages) }
        try {
            fixture.viewModel.open(FIRST)
            runCurrent()
            assertTrue(fixture.viewModel.uiState.value is CanvasUiState.Ready)
            block(fixture)
        } finally {
            fixture.viewModel.viewModelScope.cancel()
            runCurrent()
            fixture.canvas.dispose()
            Dispatchers.resetMain()
        }
    }

    private class Fixture(
        initialRecording: Boolean,
    ) {
        private val storage =
            mock(ProjectStorage::class.java) { call ->
                when (call.method.name) {
                    "loadDocument", "loadModel" -> null
                    "hasUnsavedRecovery" -> false
                    else -> error("Unexpected storage call: ${call.method.name}")
                }
            }
        val canvas = CanvasRepositoryImpl(storage)
        private val projects =
            object : ProjectRepository by mock(ProjectRepository::class.java) {
                override suspend fun getProjectById(projectId: Long): Project =
                    Project(projectId, "Artwork $projectId", "", null, 8, 6, 72, 1L, 1L)
            }
        private val settings =
            mock(SettingsRepository::class.java).also {
                `when`(it.settings).thenReturn(flowOf(AppSettings(autosaveEnabled = false, recordTimelapse = true)))
            }
        private val stored = mutableMapOf(FIRST to initialRecording)
        var beforeSet: (Long, Boolean) -> Unit = { _, _ -> }
        var beforeRead: (Long) -> Unit = {}
        var beforeClear: (Long) -> Unit = {}
        val messages = mutableListOf<String>()
        val captures = mutableListOf<Pair<Long, PixelBuffer>>()
        private val recorder =
            mock(TimelapseRecorder::class.java) { call ->
                val id = if (call.arguments.isNotEmpty()) call.getArgument<Long>(0) else 0L
                when (call.method.name) {
                    "isEnabled" -> true
                    "isRecording" -> (stored[id] ?: true).also { beforeRead(id) }
                    "setRecording" -> {
                        val recording = call.getArgument<Boolean>(1)
                        beforeSet(id, recording)
                        stored[id] = recording
                        Unit
                    }
                    "clear" -> beforeClear(id)
                    "capture" -> {
                        captures += id to call.getArgument<PixelBuffer>(1)
                        Unit
                    }
                    else -> error("Unexpected recording call: ${call.method.name}")
                }
            }
        val viewModel = CanvasViewModel(canvas, projects, settings, mock(ArtworkExporter::class.java), recorder)

        suspend fun paint() {
            canvas.applyRasterEdit(canvas.getActiveLayerId(), "Paint") { it.pixels.fill(0xFF123456.toInt()) }
        }
    }

    private companion object {
        const val FIRST = 1L
        const val SECOND = 2L
    }
}
