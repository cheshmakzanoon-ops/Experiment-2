package com.artflow.studio.data.repository.canvas

import android.content.Context
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.core.pixels.SelectionMask
import com.artflow.studio.core.pixels.SelectionMaskCodec
import com.artflow.studio.data.local.CanvasDocument
import com.artflow.studio.data.local.ProjectStorage
import com.artflow.studio.domain.model.layer.Layer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

/** Exercises retained repository buffers with a deliberately small, deterministic heap allowance. */
@OptIn(ExperimentalCoroutinesApi::class)
class CanvasSelectionMemoryTest {
    @get:Rule val temporary = TemporaryFolder()

    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var storage: ProjectStorage
    private lateinit var repository: CanvasRepositoryImpl

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        val context = mock(Context::class.java)
        `when`(context.filesDir).thenReturn(temporary.newFolder("files"))
        storage = ProjectStorage(context, dispatcher)
        repository = CanvasRepositoryImpl(storage, dispatcher, rasterBudget = CanvasRasterBudget(HEAP_BYTES))
    }

    @After
    fun tearDown() {
        repository.dispose()
        Dispatchers.resetMain()
    }

    @Test
    fun selectionChangesConsumeHistoryEvenWhenAllLayerRastersAreShared() =
        runTest {
            open()
            val layer = repository.getActiveLayerId()
            repeat(30) { index ->
                repository.setSelection(mask(index + 1))
                assertTrue(repository.setLayerName(layer, "Revision $index"))
            }

            // 3 KiB holds the shared 1 KiB raster and eight independent 256-byte selections.
            assertEquals(8, repository.undoDepth)
            assertEquals(0, repository.redoDepth)
            assertTrue(repository.undo())
            assertEquals("Revision 28", currentName())
            assertEquals(30, selectionValue())
            assertTrue(repository.redo())
            assertEquals("Revision 29", currentName())
            assertEquals(30, selectionValue())
        }

    @Test
    fun selectionChangesDuringUndoTrimCombinedStacksAndKeepTheNearestRedo() =
        runTest {
            openWithSharedSelectionHistory()
            val originalDepth = repository.undoDepth
            var traversed = 0
            var lastName = ""
            var lastSelection = 0
            while (repository.undoDepth > 0) {
                repository.setSelection(mask(50 + traversed))
                lastName = currentName()
                lastSelection = selectionValue()
                assertTrue(repository.undo())
                traversed++
                assertTrue(traversed <= originalDepth)
                assertTrue(repository.undoDepth + repository.redoDepth <= 30)
                assertNavigationBudget(repository.undoDepth, repository.redoDepth)
            }

            // Traversal is not retained memory: original entries share one selection, and earlier
            // inverse entries can be discarded while navigation continues. Some originals are
            // trimmed, but the final inverse stack may retain all eight independent masks.
            assertTrue(traversed in 1 until originalDepth)
            assertEquals(8, repository.redoDepth)
            assertTrue(repository.redo())
            assertEquals(lastName, currentName())
            assertEquals(lastSelection, selectionValue())
        }

    @Test
    fun selectionChangesDuringRedoTrimCombinedStacksAndKeepTheNearestUndo() =
        runTest {
            openWithSharedSelectionHistory()
            repeat(20) { assertTrue(repository.undo()) }
            assertEquals(20, repository.redoDepth)
            val originalDepth = repository.redoDepth
            var traversed = 0
            var lastName = ""
            var lastSelection = 0
            while (repository.redoDepth > 0) {
                repository.setSelection(mask(100 + traversed))
                lastName = currentName()
                lastSelection = selectionValue()
                assertTrue(repository.redo())
                traversed++
                assertTrue(traversed <= originalDepth)
                assertTrue(repository.undoDepth + repository.redoDepth <= 30)
                assertNavigationBudget(repository.redoDepth, repository.undoDepth)
            }

            assertTrue(traversed in 1 until originalDepth)
            assertEquals(8, repository.undoDepth)
            assertTrue(repository.undo())
            assertEquals(lastName, currentName())
            assertEquals(lastSelection, selectionValue())
        }

    @Test
    fun returnedSelectionsCannotMutateHistoricalCoverageAfterUndoAndRedo() =
        runTest {
            open()
            repository.setSelection(mask(11))
            val layer = repository.getActiveLayerId()
            assertTrue(repository.setLayerName(layer, "First"))
            assertTrue(repository.setLayerName(layer, "Second"))
            assertTrue(repository.setLayerName(layer, "Third"))

            assertTrue(repository.undo())
            assertEquals("Second", currentName())
            requireNotNull(repository.selection()).clear()
            assertEquals(11, selectionValue())
            assertTrue(repository.undo())
            assertEquals("First", currentName())
            assertEquals(11, selectionValue())

            assertTrue(repository.redo())
            assertEquals("Second", currentName())
            requireNotNull(repository.selection()).invert()
            assertEquals(11, selectionValue())
            assertTrue(repository.undo())
            assertEquals("First", currentName())
            assertEquals(11, selectionValue())
            assertTrue(repository.redo())
            assertEquals("Second", currentName())
            assertEquals(11, selectionValue())
        }

    @Test
    fun inputSelectionMutationsCannotChangeRevisitedHistoricalStates() =
        runTest {
            open()
            val first = mask(11)
            val second = mask(22)
            val third = mask(33)
            val layer = repository.getActiveLayerId()
            repository.setSelection(first)
            assertTrue(repository.setLayerName(layer, "First"))
            repository.setSelection(second)
            assertTrue(repository.setLayerName(layer, "Second"))
            repository.setSelection(third)
            assertTrue(repository.setLayerName(layer, "Third"))

            first.clear()
            second.invert()
            third.selectAll()
            assertEquals(33, selectionValue())
            assertTrue(repository.undo())
            assertEquals("Second", currentName())
            assertEquals(33, selectionValue())
            assertTrue(repository.undo())
            assertEquals("First", currentName())
            assertEquals(22, selectionValue())
            assertTrue(repository.redo())
            assertEquals("Second", currentName())
            assertEquals(33, selectionValue())
            assertTrue(repository.undo())
            assertTrue(repository.undo())
            assertEquals(11, selectionValue())
        }

    @Test
    fun aSingleOversizedSnapshotIsDroppedWithoutChangingTheEditedDocument() =
        runTest {
            open()
            repeat(3) { index ->
                val layer = repository.addLayer("Layer $index").id
                assertTrue(repository.setLayerPixels(layer, PixelBuffer.filled(SIZE, SIZE, RED), "Fixture"))
            }
            val active = repository.getActiveLayerId()
            assertTrue(repository.setLayerName(active, "Four retained raster planes"))

            assertEquals(0, repository.undoDepth)
            assertEquals(0, repository.redoDepth)
            assertFalse(repository.undo())
            assertFalse(repository.redo())
            assertEquals(4, repository.getAllLayers().size)
            assertEquals("Four retained raster planes", currentName())
            assertEquals(RED, requireNotNull(repository.layerPixels(active)).pixels.first())
        }

    @Test
    fun savedSelectionRejectionPreservesOwnedSlotsRevisionAndHistory() =
        runTest {
            open()
            val slots = List(3) { mask(it + 1) }
            repository.setSavedSelections(slots)
            slots.first().clear()
            assertEquals(1, repository.savedSelections().first().coverageAt(0, 0))
            assertTrue(repository.setLayerName(repository.getActiveLayerId(), "Current"))
            val revision = repository.contentRevision
            val history = repository.historyMark
            val undoDepth = repository.undoDepth

            val failure = runCatching { repository.setSavedSelections(List(4) { mask(it + 20) }) }.exceptionOrNull()

            assertTrue(failure is IllegalArgumentException)
            assertEquals(CanvasRasterBudget.SAVED_SELECTION_LIMIT_MESSAGE, failure?.message)
            assertEquals(listOf(1, 2, 3), repository.savedSelections().map { it.coverageAt(0, 0) })
            assertEquals(revision, repository.contentRevision)
            assertEquals(history, repository.historyMark)
            assertEquals(undoDepth, repository.undoDepth)
        }

    @Test
    fun overBudgetDocumentFailsBeforeRasterDecodingOrLiveStateReplacement() =
        runTest {
            open()
            repository.setSelection(mask(42))
            repository.setSavedSelections(listOf(mask(12)))
            assertTrue(repository.setLayerName(repository.getActiveLayerId(), "Current"))
            val revision = repository.contentRevision
            val history = repository.historyMark
            val undoDepth = repository.undoDepth
            val layers = repository.getAllLayers()
            storage.saveDocument(
                OTHER_PROJECT,
                CanvasDocument(
                    width = SIZE,
                    height = SIZE,
                    dpi = 72,
                    backgroundColor = -1,
                    activeLayerId = 1L,
                    nextLayerId = 2L,
                    // A missing raster must not be read before the slot budget rejects this file.
                    layers = listOf(Layer(1L, "Incoming", 0, rasterFile = "layers/1/v1-missing.png")),
                    savedSelections = List(4) { SelectionMaskCodec.encode(mask(it + 1)) },
                ),
            )

            val failure = runCatching { repository.loadCanvas(OTHER_PROJECT) }.exceptionOrNull()

            assertTrue(failure is IllegalArgumentException)
            assertEquals(CanvasRasterBudget.SAVED_SELECTION_LIMIT_MESSAGE, failure?.message)
            assertEquals(PROJECT, repository.projectId())
            assertEquals(layers, repository.getAllLayers())
            assertEquals(42, selectionValue())
            assertEquals(listOf(12), repository.savedSelections().map { it.coverageAt(0, 0) })
            assertEquals(revision, repository.contentRevision)
            assertEquals(history, repository.historyMark)
            assertEquals(undoDepth, repository.undoDepth)
        }

    private suspend fun open() {
        repository.loadOrCreate(PROJECT, SIZE, SIZE, 72)
    }

    private suspend fun openWithSharedSelectionHistory() {
        open()
        repository.setSelection(mask(1))
        val layer = repository.getActiveLayerId()
        repeat(20) { assertTrue(repository.setLayerName(layer, "Revision $it")) }
        assertEquals(20, repository.undoDepth)
    }

    private fun currentName(): String = repository.getAllLayers().first { it.id == repository.getActiveLayerId() }.name

    private fun selectionValue(): Int = requireNotNull(repository.selection()).coverageAt(0, 0)

    private fun assertNavigationBudget(
        originalDepth: Int,
        inverseDepth: Int,
    ) {
        // This fixture shares one raster and one mask across all remaining original entries.
        // Every inverse entry has its own mask because the loop changes selection before moving.
        val distinctMasks = inverseDepth + if (originalDepth > 0) 1 else 0
        val retainedBytes = SIZE.toLong() * SIZE * (Int.SIZE_BYTES + distinctMasks)
        assertTrue("Retained history uses $retainedBytes bytes", retainedBytes <= HEAP_BYTES / 4)
    }

    private fun mask(value: Int): SelectionMask = SelectionMask(SIZE, SIZE).apply { coverage.fill(value.toByte()) }

    private companion object {
        const val PROJECT = 42L
        const val OTHER_PROJECT = 43L
        const val SIZE = 16
        const val HEAP_BYTES = 12L * 1024
        val RED = 0xFFFF0000.toInt()
    }
}
