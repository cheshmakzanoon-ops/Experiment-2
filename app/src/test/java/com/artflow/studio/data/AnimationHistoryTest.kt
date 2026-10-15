package com.artflow.studio.data

import android.content.Context
import com.artflow.studio.data.local.ProjectStorage
import com.artflow.studio.data.repository.canvas.CanvasRepositoryImpl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock

/** Animation settings and frame timing as undo history: viewing preferences stay out, slider drags merge. */
@OptIn(ExperimentalCoroutinesApi::class)
class AnimationHistoryTest {
    private lateinit var repository: CanvasRepositoryImpl

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        repository = CanvasRepositoryImpl(ProjectStorage(mock(Context::class.java)))
    }

    @After
    fun tearDown() {
        repository.dispose()
        Dispatchers.resetMain()
    }

    @Test fun onionSkinLookChangesAreNotUndoStepsAndKeepTheRedoStack() =
        runTest {
            repository.createCanvas(8, 6, 72)
            repository.addFrame(false)
            repository.undo()
            assertEquals(1, repository.redoDepth)
            val depth = repository.undoDepth
            val settings = repository.timeline.value.settings
            repository.updateAnimationSettings(settings.copy(onionSkinOpacity = 0.6f))
            repository.updateAnimationSettings(settings.copy(onionSkinOpacity = 0.6f, onionSkinFrames = 3))
            assertEquals(depth, repository.undoDepth)
            assertEquals(1, repository.redoDepth)
            assertEquals(0.6f, repository.timeline.value.settings.onionSkinOpacity, 0f)
        }

    @Test fun undoKeepsTheCurrentOnionLookAndRestoresTiming() =
        runTest {
            repository.createCanvas(8, 6, 72)
            repository.addFrame(false)
            val defaults = repository.timeline.value.settings
            repository.updateAnimationSettings(defaults.copy(fps = 24))
            repository.updateAnimationSettings(repository.timeline.value.settings.copy(onionSkinOpacity = 0.7f))
            repository.undo()
            val restored = repository.timeline.value.settings
            assertEquals(defaults.fps, restored.fps)
            assertEquals(0.7f, restored.onionSkinOpacity, 0f)
            assertEquals(2, repository.frames().size)
        }

    @Test fun aFrameDurationDragIsOneUndoStep() =
        runTest {
            repository.createCanvas(8, 6, 72)
            repository.addFrame(false)
            val original = repository.frames()[0].durationMs
            val depth = repository.undoDepth
            repository.setFrameDuration(0, 200)
            repository.setFrameDuration(0, 300)
            repository.setFrameDuration(0, 400)
            assertEquals(depth + 1, repository.undoDepth)
            assertEquals(400, repository.frames()[0].durationMs)
            repository.undo()
            assertEquals(original, repository.frames()[0].durationMs)
            assertEquals(2, repository.frames().size)
        }
}
