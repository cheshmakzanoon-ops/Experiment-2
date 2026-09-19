package com.artflow.studio.presentation

import androidx.lifecycle.viewModelScope
import com.artflow.studio.data.export.ArtworkExporter
import com.artflow.studio.data.local.ProjectStorage
import com.artflow.studio.data.repository.canvas.CanvasRepositoryImpl
import com.artflow.studio.domain.model.Project
import com.artflow.studio.domain.model.settings.AppSettings
import com.artflow.studio.domain.repository.ProjectRepository
import com.artflow.studio.domain.repository.settings.SettingsRepository
import com.artflow.studio.presentation.ui.viewmodel.CanvasViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

/** Virtual time drives the real ViewModel and repository, including cancellation boundaries. */
@OptIn(ExperimentalCoroutinesApi::class)
class CanvasPlaybackViewModelTest {
    @Test
    fun playPauseStateSurvivesFrameEmissionsAndPauseStopsAdvancement() =
        withFixture { fixture ->
            fixture.prepare(100, 200)
            runCurrent()
            fixture.viewModel.togglePlayback()
            assertTrue(fixture.viewModel.timeline.value.isPlaying)
            runCurrent()
            advanceTimeBy(100)
            runCurrent()
            assertEquals(1, fixture.repository.activeFrameIndex())
            assertTrue(fixture.viewModel.timeline.value.isPlaying)
            fixture.viewModel.togglePlayback()
            assertFalse(fixture.viewModel.timeline.value.isPlaying)
            advanceTimeBy(1000)
            runCurrent()
            assertEquals(1, fixture.repository.activeFrameIndex())
        }

    @Test
    fun nonLoopingPlaybackHonorsEveryHoldAndStopsAfterTheLastOne() =
        withFixture { fixture ->
            fixture.prepare(100, 200)
            fixture.repository.updateAnimationSettings(fixture.animationSettings().copy(loop = false))
            fixture.viewModel.togglePlayback()
            runCurrent()
            advanceTimeBy(99)
            runCurrent()
            assertEquals(0, fixture.repository.activeFrameIndex())
            advanceTimeBy(1)
            runCurrent()
            assertEquals(1, fixture.repository.activeFrameIndex())
            advanceTimeBy(199)
            runCurrent()
            assertTrue(fixture.viewModel.timeline.value.isPlaying)
            advanceTimeBy(1)
            runCurrent()
            assertFalse(fixture.viewModel.timeline.value.isPlaying)
            assertEquals(1, fixture.repository.activeFrameIndex())
            fixture.viewModel.togglePlayback()
            runCurrent()
            assertEquals(0, fixture.repository.activeFrameIndex())
        }

    @Test
    fun nonLoopingPingPongMakesOneForwardAndReturnPass() =
        withFixture { fixture ->
            fixture.prepare(100, 200, 300)
            fixture.repository.updateAnimationSettings(fixture.animationSettings().copy(loop = false, pingPong = true))
            fixture.viewModel.togglePlayback()
            runCurrent()
            for ((hold, next) in listOf(100L to 1, 200L to 2, 300L to 1, 200L to 0)) {
                advanceTimeBy(hold)
                runCurrent()
                assertEquals(next, fixture.repository.activeFrameIndex())
                assertTrue(fixture.viewModel.timeline.value.isPlaying)
            }
            advanceTimeBy(100)
            runCurrent()
            assertFalse(fixture.viewModel.timeline.value.isPlaying)
            assertEquals(0, fixture.repository.activeFrameIndex())
        }

    @Test
    fun loopingPingPongDoesNotHoldEitherEndpointTwice() =
        withFixture { fixture ->
            fixture.prepare(100, 100, 100)
            fixture.repository.updateAnimationSettings(fixture.animationSettings().copy(loop = true, pingPong = true))
            fixture.viewModel.togglePlayback()
            runCurrent()
            for (next in listOf(1, 2, 1, 0, 1, 2)) {
                advanceTimeBy(100)
                runCurrent()
                assertEquals(next, fixture.repository.activeFrameIndex())
                assertTrue(fixture.viewModel.timeline.value.isPlaying)
            }
        }

    @Test
    fun playbackRangeContainsEveryDisplayedFrame() =
        withFixture { fixture ->
            fixture.prepare(100, 100, 100, 100)
            fixture.repository.updateAnimationSettings(
                fixture.animationSettings().copy(playbackRangeStart = 1, playbackRangeEnd = 2),
            )
            fixture.viewModel.togglePlayback()
            runCurrent()
            assertEquals(1, fixture.repository.activeFrameIndex())
            for (next in listOf(2, 1, 2, 1)) {
                advanceTimeBy(100)
                runCurrent()
                assertEquals(next, fixture.repository.activeFrameIndex())
            }
        }

    @Test
    fun backgroundStopsPlaybackEvenWhenAutosaveIsDisabled() =
        withFixture { fixture ->
            fixture.prepare(100, 100)
            fixture.viewModel.togglePlayback()
            runCurrent()
            fixture.viewModel.saveRecoveryOnBackground()
            assertFalse(fixture.viewModel.timeline.value.isPlaying)
            advanceTimeBy(1000)
            runCurrent()
            assertEquals(0, fixture.repository.activeFrameIndex())
        }

    @Test
    fun selectingAFrameStopsThePlaybackOwner() =
        withFixture { fixture ->
            fixture.prepare(100, 100)
            fixture.viewModel.togglePlayback()
            runCurrent()
            fixture.viewModel.selectFrame(1)
            runCurrent()
            assertFalse(fixture.viewModel.timeline.value.isPlaying)
            advanceTimeBy(1000)
            runCurrent()
            assertEquals(1, fixture.repository.activeFrameIndex())
        }

    @Test
    fun durationEditStopsPlaybackBeforeChangingItsTiming() =
        withFixture { fixture ->
            fixture.prepare(100, 100)
            fixture.viewModel.togglePlayback()
            runCurrent()
            fixture.viewModel.setFrameDuration(0, 400)
            runCurrent()
            assertFalse(fixture.viewModel.timeline.value.isPlaying)
            advanceTimeBy(1000)
            runCurrent()
            assertEquals(0, fixture.repository.activeFrameIndex())
            val changedFrame = fixture.repository.frames()[0]
            assertEquals(400, changedFrame.durationMs)
        }

    @Test
    fun oldCancelledLoopCannotClearARapidRestart() =
        withFixture { fixture ->
            fixture.prepare(100, 100)
            fixture.viewModel.togglePlayback()
            runCurrent()
            fixture.viewModel.togglePlayback()
            fixture.viewModel.togglePlayback()
            runCurrent()
            assertTrue(fixture.viewModel.timeline.value.isPlaying)
            advanceTimeBy(100)
            runCurrent()
            assertTrue(fixture.viewModel.timeline.value.isPlaying)
            assertEquals(1, fixture.repository.activeFrameIndex())
        }

    @Test
    fun aSingleFrameNeverEntersPlayingState() =
        withFixture { fixture ->
            fixture.viewModel.togglePlayback()
            runCurrent()
            assertFalse(fixture.viewModel.timeline.value.isPlaying)
            advanceTimeBy(1000)
            runCurrent()
            assertEquals(0, fixture.repository.activeFrameIndex())
        }

    @Test
    fun animationSettingsDoNotRewriteTheOnionSkinPreference() =
        withFixture { fixture ->
            fixture.prepare(100, 100)
            fixture.viewModel.togglePlayback()
            runCurrent()
            fixture.viewModel.updateAnimationSettings(fixture.animationSettings().copy(fps = 24))
            runCurrent()
            assertFalse(fixture.viewModel.timeline.value.isPlaying)
            assertEquals(24, fixture.animationSettings().fps)
            verify(fixture.settings, never()).setOnionSkin(true)
            verify(fixture.settings, never()).setOnionSkin(false)
        }

    @Test
    fun playbackDoesNotCreateDocumentEditsOrHistoryEntries() =
        withFixture { fixture ->
            fixture.prepare(100, 100)
            val revision = fixture.repository.contentRevision
            val depth = fixture.repository.undoDepth
            fixture.viewModel.togglePlayback()
            runCurrent()
            advanceTimeBy(750)
            runCurrent()
            fixture.viewModel.togglePlayback()
            assertEquals(revision, fixture.repository.contentRevision)
            assertEquals(depth, fixture.repository.undoDepth)
        }

    private fun withFixture(block: suspend TestScope.(Fixture) -> Unit) =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val fixture = Fixture()
            try {
                fixture.viewModel.open(1L)
                runCurrent()
                block(fixture)
            } finally {
                fixture.viewModel.viewModelScope.cancel()
                fixture.repository.dispose()
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
        private val project = Project(1L, "Playback", "", null, 8, 6, 72, 1L, 1L)
        private val projects =
            mock(ProjectRepository::class.java) { call ->
                check(call.method.name == "getProjectById") { "Unexpected project call: ${call.method.name}" }
                project
            }
        val settings =
            mock(SettingsRepository::class.java).also {
                `when`(it.settings).thenReturn(flowOf(AppSettings(autosaveEnabled = false, onionSkin = false)))
            }
        val viewModel = CanvasViewModel(repository, projects, settings, mock(ArtworkExporter::class.java))

        fun animationSettings() = repository.timeline.value.settings

        suspend fun prepare(vararg holds: Int) {
            repeat(holds.size - 1) { repository.addFrame(false) }
            holds.forEachIndexed { index, hold -> repository.setFrameDuration(index, hold) }
            repository.selectFrame(0)
        }
    }
}
