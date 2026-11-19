package com.artflow.studio.data.repository.canvas

import android.content.Context
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.data.local.ProjectStorage
import com.artflow.studio.domain.model.brush.BrushParams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

/** Uses the actual editor, PNG codecs and storage with a small deterministic heap policy. */
@OptIn(ExperimentalCoroutinesApi::class)
class CanvasReopenBudgetTest {
    @get:Rule val temporary = TemporaryFolder()

    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var storage: ProjectStorage
    private lateinit var repository: CanvasRepositoryImpl
    private var heapBytes = 512L * 1024

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        val context = mock(Context::class.java)
        `when`(context.filesDir).thenReturn(temporary.newFolder("files"))
        storage = ProjectStorage(context, dispatcher)
        repository = newRepository()
    }

    @After
    fun tearDown() {
        repository.dispose()
        Dispatchers.resetMain()
    }

    @Test
    fun duplicatedFramesRejectGrowthBeforeHistoryAndReopenAfterIndependentEdits() =
        runTest {
            openPainted()
            repeat(9) { repository.addFrame(true) }
            val before = repository.frames()
            val revision = repository.contentRevision
            val history = repository.historyMark
            expectCapacity { repository.addFrame(true) }
            assertEquals(before, repository.frames())
            assertEquals(revision, repository.contentRevision)
            assertEquals(history, repository.historyMark)
            saveAndReopen()
            assertEquals(10, repository.frames().size)
            assertEquals(RED, firstPixel(0))
            assertEquals(RED, firstPixel(9))
            assertTrue(repository.setLayerPixels(repository.getActiveLayerId(), PixelBuffer.filled(SIZE, SIZE, BLUE), "Edit duplicate"))
            saveAndReopen()
            assertEquals(RED, firstPixel(0))
            assertEquals(BLUE, firstPixel(9))
        }

    @Test
    fun duplicatedLayersCountTheirFutureDecodedPlanesEvenWhilePixelsAreShared() =
        runTest {
            openPainted()
            repeat(9) { assertNotNull(repository.duplicateLayer(repository.getActiveLayerId())) }
            val history = repository.historyMark
            val layers = repository.getAllLayers()
            expectCapacity { repository.duplicateLayer(repository.getActiveLayerId()) }
            assertEquals(history, repository.historyMark)
            assertEquals(layers, repository.getAllLayers())
            saveAndReopen()
            assertEquals(10, repository.getAllLayers().size)
            assertEquals(RED, firstPixel(0))
        }

    @Test
    fun firstPaintReservationsPreventOvercommitAndCancellationReturnsCapacity() =
        runTest {
            useFourPlaneBudget()
            openPainted()
            repeat(2) { repository.addFrame(true) }
            val painted = repository.getActiveLayerId()
            val empty = repository.addLayer("Empty").id
            val stroke = repository.beginStroke(20f, 20f, 1f, BrushParams(size = 4f), empty)
            assertTrue(stroke > 0L)
            expectCapacity { repository.addLayerMask(painted) }
            repository.cancelStroke(stroke)
            assertTrue(repository.addLayerMask(painted))
            val revision = repository.contentRevision
            val history = repository.historyMark
            assertEquals(0L, repository.beginStroke(20f, 20f, 1f, BrushParams(size = 4f), empty))
            assertEquals(CanvasRasterBudget.EDIT_LIMIT_MESSAGE, repository.editRejectionReason)
            assertEquals(revision, repository.contentRevision)
            assertEquals(history, repository.historyMark)
            expectCapacity { repository.setLayerPixels(empty, PixelBuffer.filled(SIZE, SIZE, BLUE), "Too much") }
            assertEquals(history, repository.historyMark)
            assertTrue(repository.removeLayerMask(painted))
            val accepted = repository.beginStroke(20f, 20f, 1f, BrushParams(size = 4f), empty)
            assertTrue(accepted > 0L)
            repository.endStroke(accepted)
            saveAndReopen()
            assertEquals(3, repository.frames().size)
            assertTrue(requireNotNull(repository.layerPixels(empty)).pixels.any { it ushr 24 != 0 })
        }

    @Test
    fun pixelSessionsClipboardAndResizeCannotProduceAnUnopenableDocument() =
        runTest {
            useFourPlaneBudget()
            openPainted()
            repeat(2) { repository.addFrame(true) }
            val painted = repository.getActiveLayerId()
            val first = repository.addLayer("First").id
            val second = repository.addLayer("Second").id
            val session = requireNotNull(repository.beginRasterEdit(first))
            assertEquals(null, repository.beginRasterEdit(second))
            assertEquals(CanvasRasterBudget.EDIT_LIMIT_MESSAGE, repository.editRejectionReason)
            assertFalse(repository.canImportLayers(1, 1))
            repository.cancelRasterEdit(session)
            assertTrue(repository.canImportLayers(1, 1))
            assertTrue(repository.addLayerMask(painted))
            repository.setActiveLayer(painted)
            val history = repository.historyMark
            expectCapacity { repository.copySelectionToLayer(cut = true) }
            expectCapacity { repository.resizeCanvas(SIZE + 8, SIZE, true) }
            assertEquals(history, repository.historyMark)
            assertEquals(SIZE, repository.getCanvasSize().width)
            assertEquals(RED, requireNotNull(repository.layerPixels(painted)).pixels.first())
            saveAndReopen()
            assertEquals(3, repository.frames().size)
        }

    @Test
    fun duplicatedMasksAndNewBlankFramesUseTheSameReopenBudget() =
        runTest {
            useFourPlaneBudget()
            openPainted()
            assertTrue(repository.addLayerMask(repository.getActiveLayerId()))
            assertNotNull(repository.duplicateLayer(repository.getActiveLayerId()))
            val history = repository.historyMark
            expectCapacity { repository.addFrame(false) }
            expectCapacity { repository.duplicateLayer(repository.getActiveLayerId()) }
            assertEquals(history, repository.historyMark)
            saveAndReopen()
            assertEquals(2, repository.getAllLayers().size)
            assertTrue(repository.getAllLayers().all { it.maskFile != null })
        }

    @Test
    fun pastedFramesAreRejectedBeforeChangingTheTimeline() =
        runTest {
            useFourPlaneBudget()
            openPainted()
            assertTrue(repository.copyFrame(0))
            repeat(3) { assertTrue(repository.pasteFrame(it + 1)) }
            val before = repository.frames()
            val history = repository.historyMark
            expectCapacity { repository.pasteFrame(4) }
            assertEquals(before, repository.frames())
            assertEquals(history, repository.historyMark)
            saveAndReopen()
            assertEquals(4, repository.frames().size)
        }

    private fun newRepository(): CanvasRepositoryImpl =
        CanvasRepositoryImpl(storage, dispatcher, rasterBudget = CanvasRasterBudget(heapBytes))

    private fun useFourPlaneBudget() {
        repository.dispose()
        heapBytes = SIZE.toLong() * SIZE * 4 * 3 * 4
        repository = newRepository()
    }

    private suspend fun openPainted() {
        repository.loadOrCreate(PROJECT, SIZE, SIZE, 72)
        assertTrue(repository.setLayerPixels(repository.getActiveLayerId(), PixelBuffer.filled(SIZE, SIZE, RED), "Fixture"))
    }

    private suspend fun saveAndReopen() {
        assertNotNull(repository.saveCanvas(PROJECT))
        repository.dispose()
        repository = newRepository()
        assertNotNull(repository.loadCanvas(PROJECT))
    }

    private suspend fun firstPixel(frame: Int): Int = requireNotNull(repository.compositeFrame(frame)).pixels.first()

    private suspend fun expectCapacity(block: suspend () -> Unit) {
        val failure = runCatching { block() }.exceptionOrNull()
        assertTrue("Expected a capacity rejection, got $failure", failure is IllegalArgumentException)
        assertTrue(failure?.message.orEmpty().contains("memory"))
        assertFalse(failure?.message.orEmpty().contains("Maximum project layer count"))
    }

    private companion object {
        const val PROJECT = 83L
        const val SIZE = 64
        val RED = 0xFFFF0000.toInt()
        val BLUE = 0xFF0000FF.toInt()
    }
}
