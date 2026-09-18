package com.artflow.studio.data

import android.content.Context
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.core.pixels.SelectionMask
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock

@OptIn(ExperimentalCoroutinesApi::class)
class SelectionTransactionTest {
    private lateinit var repository: CanvasRepositoryImpl

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        repository = CanvasRepositoryImpl(ProjectStorage(mock(Context::class.java)))
    }

    @After fun tearDown() {
        repository.dispose()
        Dispatchers.resetMain()
    }

    private suspend fun open() = repository.createCanvas(8, 6, 72)

    private fun full() = SelectionMask(8, 6).apply { selectAll() }

    @Test fun successfulSelectionIsSingleUseAndDoesNotDirtyArtwork() =
        runTest {
            open()
            val revision = repository.contentRevision
            val depth = repository.undoDepth
            val session = repository.beginSelectionEdit()
            val mask = full()
            assertTrue(repository.commitSelectionEdit(session, mask))
            mask.clear()
            assertTrue(requireNotNull(repository.selection()).isFull())
            assertEquals(revision, repository.contentRevision)
            assertEquals(depth, repository.undoDepth)
            assertFalse(repository.hasUnsavedChanges())
            assertFalse(repository.commitSelectionEdit(session, SelectionMask(8, 6)))
        }

    @Test fun newerRequestWinsAndOldCancellationCannotCancelIt() =
        runTest {
            open()
            val old = repository.beginSelectionEdit()
            val latest = repository.beginSelectionEdit()
            repository.cancelSelectionEdit(old)
            assertFalse(repository.commitSelectionEdit(old, full()))
            assertTrue(repository.commitSelectionEdit(latest, SelectionMask(8, 6)))
            assertEquals(0, requireNotNull(repository.selection()).selectedPixelCount())
        }

    @Test fun clearFromAnotherOwnerSupersedesPendingComputation() =
        runTest {
            open()
            val session = repository.beginSelectionEdit()
            repository.clearSelection()
            assertFalse(repository.commitSelectionEdit(session, full()))
            assertNull(repository.selection())
        }

    @Test fun synchronousSelectionCannotBeOverwrittenByAnOldWorker() =
        runTest {
            open()
            val session = repository.beginSelectionEdit()
            repository.setSelection(SelectionMask(8, 6))
            assertFalse(repository.commitSelectionEdit(session, full()))
            assertEquals(0, requireNotNull(repository.selection()).selectedPixelCount())
        }

    @Test fun originalMaskIsAnIsolatedSnapshot() =
        runTest {
            open()
            repository.setSelection(full())
            val session = repository.beginSelectionEdit()
            requireNotNull(session.original).clear()
            assertTrue(requireNotNull(repository.selection()).isFull())
            assertEquals(8, session.width)
            assertEquals(6, session.height)
            repository.cancelSelectionEdit(session)
            assertFalse(repository.commitSelectionEdit(session, full()))
        }

    @Test fun pixelChangesInvalidateColourSampling() =
        runTest {
            open()
            val session = repository.beginSelectionEdit()
            repository.setLayerPixels(repository.getActiveLayerId(), PixelBuffer.filled(8, 6, -1), "Paint")
            assertFalse(repository.commitSelectionEdit(session, full()))
            assertNull(repository.selection())
        }

    @Test fun switchingLayersAndBackDoesNotReviveARequest() =
        runTest {
            open()
            val first = repository.getActiveLayerId()
            val second = repository.addLayer().id
            repository.setActiveLayer(first)
            val session = repository.beginSelectionEdit()
            repository.setActiveLayer(second)
            repository.setActiveLayer(first)
            assertFalse(repository.commitSelectionEdit(session, full()))
        }

    @Test fun switchingFramesAndBackDoesNotReviveARequest() =
        runTest {
            open()
            repository.addFrame(false)
            repository.selectFrame(0)
            val session = repository.beginSelectionEdit()
            repository.selectFrame(1)
            repository.selectFrame(0)
            assertFalse(repository.commitSelectionEdit(session, full()))
        }

    @Test fun sameSizedReplacementDocumentRejectsOldResult() =
        runTest {
            open()
            val session = repository.beginSelectionEdit()
            open()
            assertFalse(repository.commitSelectionEdit(session, full()))
        }

    @Test fun canvasResizeRejectsOldDimensionsWithoutThrowing() =
        runTest {
            open()
            val session = repository.beginSelectionEdit()
            assertTrue(repository.resizeCanvas(4, 4, false))
            assertFalse(repository.commitSelectionEdit(session, full()))
            assertNull(repository.selection())
        }

    @Test fun wrongSizedResultIsNotPublished() =
        runTest {
            open()
            val session = repository.beginSelectionEdit()
            assertFalse(repository.commitSelectionEdit(session, SelectionMask(1, 1)))
            assertNull(repository.selection())
        }

    @Test fun disposalRejectsPendingSelection() =
        runTest {
            open()
            val session = repository.beginSelectionEdit()
            repository.dispose()
            assertFalse(repository.commitSelectionEdit(session, full()))
        }

    @Test fun undoInvalidatesPendingSelection() =
        runTest {
            open()
            repository.setLayerPixels(repository.getActiveLayerId(), PixelBuffer.filled(8, 6, -1), "Fixture")
            val session = repository.beginSelectionEdit()
            assertTrue(repository.undo())
            assertFalse(repository.commitSelectionEdit(session, full()))
        }
}
