package com.artflow.studio.data

import android.content.Context
import com.artflow.studio.core.canvas.CanvasOperations
import com.artflow.studio.data.local.ProjectStorage
import com.artflow.studio.data.repository.canvas.CanvasRepositoryImpl
import com.artflow.studio.domain.model.animation.AnimationFrame
import com.artflow.studio.domain.model.animation.AnimationSettings
import com.artflow.studio.domain.repository.canvas.CanvasInvalidationEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock

/** Metadata must participate in document invalidation, validated commits and history like pixels. */
@OptIn(ExperimentalCoroutinesApi::class)
class DocumentMetadataEditTest {
    private lateinit var repository: CanvasRepositoryImpl

    @Before
    fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        repository = CanvasRepositoryImpl(ProjectStorage(mock(Context::class.java)))
    }

    @After
    fun cleanup() {
        repository.dispose()
        Dispatchers.resetMain()
    }

    @Test
    fun dpiOnlyEditNotifiesObserversAndHasIndependentUndoRedo() =
        runTest {
            repository.createCanvas(8, 6, 72)
            val events = mutableListOf<CanvasInvalidationEvent>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                repository.observeCanvasInvalidation().collect { events += it }
            }
            val before = events.size
            assertTrue(repository.setCanvasDpi(300))
            assertEquals(before + 1, events.size)
            assertSame(CanvasInvalidationEvent.Full, events.last())
            assertTrue(repository.hasUnsavedChanges())
            assertEquals(1, repository.undoDepth)
            assertEquals(300, repository.getCanvasSize().dpi)
            assertTrue(repository.undo())
            assertEquals(72, repository.getCanvasSize().dpi)
            assertTrue(repository.redo())
            assertEquals(300, repository.getCanvasSize().dpi)
        }

    @Test
    fun durationOnlyEditNotifiesObserversAndRestoresItsOwnHold() =
        runTest {
            repository.createCanvas(8, 6, 72)
            val original = repository.frames().single().durationMs
            val events = mutableListOf<CanvasInvalidationEvent>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                repository.observeCanvasInvalidation().collect { events += it }
            }
            val before = events.size
            assertTrue(repository.setFrameDuration(0, 500))
            assertEquals(before + 1, events.size)
            assertSame(CanvasInvalidationEvent.Full, events.last())
            assertTrue(repository.hasUnsavedChanges())
            assertEquals(1, repository.undoDepth)
            val changedFrames = repository.timeline.value.frames
            val changedFrame = changedFrames.single()
            assertEquals(500, changedFrame.durationMs)
            assertTrue(repository.undo())
            assertEquals(original, repository.frames().single().durationMs)
            assertTrue(repository.redo())
            assertEquals(500, repository.frames().single().durationMs)
        }

    @Test
    fun editingAnInactiveFrameDoesNotSelectIt() =
        runTest {
            repository.createCanvas(8, 6, 72)
            repository.addFrame(false)
            assertEquals(1, repository.activeFrameIndex())
            assertTrue(repository.setFrameDuration(0, 350))
            assertEquals(1, repository.activeFrameIndex())
            val editedFrame = repository.timeline.value.frames[0]
            assertEquals(350, editedFrame.durationMs)
        }

    @Test
    fun identicalMetadataDoesNotDirtyACleanDocumentOrPublishAnEdit() =
        runTest {
            repository.createCanvas(8, 6, 72)
            val events = mutableListOf<CanvasInvalidationEvent>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                repository.observeCanvasInvalidation().collect { events += it }
            }
            val revision = repository.contentRevision
            val before = events.size
            assertTrue(repository.setCanvasDpi(72))
            assertTrue(repository.setCanvasBackgroundColor(repository.getBackgroundColor()))
            assertTrue(repository.setFrameDuration(0, repository.frames().single().durationMs))
            repository.updateAnimationSettings(repository.timeline.value.settings)
            assertEquals(before, events.size)
            assertEquals(revision, repository.contentRevision)
            assertEquals(0, repository.undoDepth)
            assertFalse(repository.hasUnsavedChanges())
        }

    @Test
    fun identicalMetadataPreservesRedo() =
        runTest {
            repository.createCanvas(8, 6, 72)
            repository.setCanvasDpi(300)
            repository.undo()
            val revision = repository.contentRevision
            assertTrue(repository.setCanvasDpi(72))
            assertTrue(repository.setCanvasBackgroundColor(repository.getBackgroundColor()))
            assertTrue(repository.setFrameDuration(0, repository.frames().single().durationMs))
            repository.updateAnimationSettings(repository.timeline.value.settings)
            assertEquals(revision, repository.contentRevision)
            assertEquals(0, repository.undoDepth)
            assertEquals(1, repository.redoDepth)
            assertTrue(repository.redo())
            assertEquals(300, repository.getCanvasSize().dpi)
        }

    @Test
    fun invalidFrameIndexDoesNotCreateHistoryOrDirtyState() =
        runTest {
            repository.createCanvas(8, 6, 72)
            val revision = repository.contentRevision
            assertFalse(repository.setFrameDuration(-1, 500))
            assertFalse(repository.setFrameDuration(1, 500))
            assertFalse(repository.hasUnsavedChanges())
            assertEquals(0, repository.undoDepth)
            assertEquals(revision, repository.contentRevision)
        }

    @Test
    fun clampedMetadataNoOpsDoNotCreateAnotherHistoryEntry() =
        runTest {
            repository.createCanvas(8, 6, 72)
            repository.setCanvasDpi(CanvasOperations.MAX_DPI)
            repository.setFrameDuration(0, AnimationFrame.MAX_DURATION_MS)
            val depth = repository.undoDepth
            val revision = repository.contentRevision
            repository.setCanvasDpi(Int.MAX_VALUE)
            repository.setFrameDuration(0, Int.MAX_VALUE)
            assertEquals(depth, repository.undoDepth)
            assertEquals(revision, repository.contentRevision)
            assertEquals(CanvasOperations.MAX_DPI, repository.getCanvasSize().dpi)
            assertEquals(AnimationFrame.MAX_DURATION_MS, repository.frames().single().durationMs)
        }

    @Test
    fun nonFiniteOnionOpacityRejectsTheEntireSettingsChange() =
        runTest {
            repository.createCanvas(8, 6, 72)
            val before = repository.timeline.value
            val revision = repository.contentRevision
            for (invalid in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
                val result =
                    runCatching {
                        repository.updateAnimationSettings(before.settings.copy(fps = 60, onionSkinOpacity = invalid))
                    }
                assertTrue(result.exceptionOrNull() is IllegalArgumentException)
                assertEquals(before, repository.timeline.value)
                assertEquals(0, repository.undoDepth)
                assertEquals(revision, repository.contentRevision)
                assertFalse(repository.hasUnsavedChanges())
            }
        }

    @Test
    fun equivalentNormalizedAnimationSettingsAreANoOp() =
        runTest {
            repository.createCanvas(8, 6, 72)
            val normalized = AnimationSettings(fps = 60, onionSkinFrames = 5, onionSkinOpacity = 1f)
            repository.updateAnimationSettings(normalized)
            val depth = repository.undoDepth
            val revision = repository.contentRevision
            repository.updateAnimationSettings(normalized.copy(fps = Int.MAX_VALUE, onionSkinFrames = 100, onionSkinOpacity = 50f))
            assertEquals(normalized, repository.timeline.value.settings)
            assertEquals(depth, repository.undoDepth)
            assertEquals(revision, repository.contentRevision)
        }

    @Test
    fun fpsEditPreservesCustomHoldsAndUndoRestoresAllDefaults() =
        runTest {
            repository.createCanvas(8, 6, 72)
            repository.addFrame(false)
            repository.setFrameDuration(0, 500)
            val before = repository.timeline.value
            val settings = before.settings.copy(fps = 24)
            val depth = repository.undoDepth
            repository.updateAnimationSettings(settings)
            assertEquals(depth + 1, repository.undoDepth)
            assertEquals(500, repository.frames()[0].durationMs)
            assertEquals(settings.frameDurationMs, repository.frames()[1].durationMs)
            assertTrue(repository.undo())
            assertEquals(before, repository.timeline.value)
        }
}
