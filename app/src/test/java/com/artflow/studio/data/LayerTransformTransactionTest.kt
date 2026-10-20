package com.artflow.studio.data

import android.content.Context
import com.artflow.studio.core.canvas.LayerTransform
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
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock

/** Real repository transactions; no replacement transformation, history or compositing code. */
@OptIn(ExperimentalCoroutinesApi::class)
class LayerTransformTransactionTest {
    private lateinit var repository: CanvasRepositoryImpl
    private val red = 0xFFFF0000.toInt()
    private val move = LayerTransform.Parameters(translationX = 1f)

    @Before fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        repository = CanvasRepositoryImpl(ProjectStorage(mock(Context::class.java)))
    }

    @After fun cleanup() {
        repository.dispose()
        Dispatchers.resetMain()
    }

    private suspend fun fixture(): Long {
        repository.createCanvas(7, 5, 72)
        val id = repository.getActiveLayerId()
        val pixels = PixelBuffer(7, 5).apply { setUnchecked(2, 2, red) }
        assertTrue(repository.setLayerPixels(id, pixels, "Transform fixture"))
        return id
    }

    private suspend fun raw(id: Long): IntArray = requireNotNull(repository.layerPixels(id)).pixels

    private suspend fun visible(): IntArray = requireNotNull(repository.compositeFrame(0, transparentBackground = true)).pixels

    @Test fun layerAndEditableMaskMoveTogetherWithOneUndo() =
        runTest {
            val id = fixture()
            val coverage = SelectionMask(7, 5).apply { coverage[2 * 7 + 2] = 255.toByte() }
            assertTrue(repository.addLayerMask(id, coverage))
            val before = visible()
            val rawBefore = raw(id)
            val depth = repository.undoDepth
            assertTrue(repository.transformLayer(id, move, repository.contentRevision))
            assertEquals(depth + 1, repository.undoDepth)
            val after = visible()
            assertEquals(red, after[2 * 7 + 3])
            assertEquals(0, after[2 * 7 + 2])
            assertTrue(requireNotNull(repository.getActiveLayer()).hasMask())
            assertTrue(repository.undo())
            assertArrayEquals(before, visible())
            assertArrayEquals(rawBefore, raw(id))
            assertTrue(repository.redo())
            assertArrayEquals(after, visible())
            assertTrue(repository.invertLayerMask(id))
            assertEquals(0, visible()[2 * 7 + 3])
            assertEquals(red, raw(id)[2 * 7 + 3])
        }

    @Test fun identityAndBlankLayerDoNotCreateUndoSteps() =
        runTest {
            val id = fixture()
            val depth = repository.undoDepth
            assertTrue(repository.transformLayer(id, LayerTransform.Parameters(rotationDegrees = 360f), repository.contentRevision))
            assertEquals(depth, repository.undoDepth)
            repository.createCanvas(7, 5, 72)
            val emptyDepth = repository.undoDepth
            assertTrue(repository.transformLayer(repository.getActiveLayerId(), move, repository.contentRevision))
            assertEquals(emptyDepth, repository.undoDepth)
        }

    @Test fun staleRevisionAndWrongTargetCannotOverwriteArtwork() =
        runTest {
            val id = fixture()
            val revision = repository.contentRevision
            repository.setLayerName(id, "Edited after panel opened")
            val depth = repository.undoDepth
            val before = raw(id)
            assertFalse(repository.transformLayer(id, move, revision))
            assertFalse(repository.transformLayer(id + 999, move, repository.contentRevision))
            assertArrayEquals(before, raw(id))
            assertEquals(depth, repository.undoDepth)
        }

    @Test fun selectedPixelsAndEmptySelectionsAreExplicitlyRejected() =
        runTest {
            val id = fixture()
            val before = raw(id)
            val depth = repository.undoDepth
            for (mask in listOf(SelectionMask(7, 5), SelectionMask(7, 5).apply { coverage.fill(255.toByte()) })) {
                repository.setSelection(mask)
                assertFalse(repository.transformLayer(id, move, repository.contentRevision))
                assertArrayEquals(before, raw(id))
                assertEquals(depth, repository.undoDepth)
                assertNotNull(repository.selection())
            }
            repository.clearSelection()
            assertTrue(repository.transformLayer(id, move, repository.contentRevision))
        }

    @Test fun lockedLinkedAndInFlightLayersCannotBeTransformed() =
        runTest {
            val id = fixture()
            repository.setLayerLock(id, true)
            assertFalse(repository.transformLayer(id, move, repository.contentRevision))
            repository.setLayerLock(id, false)
            val provisional = requireNotNull(repository.beginRasterEdit(id))
            val depth = repository.undoDepth
            assertFalse(repository.transformLayer(id, move, repository.contentRevision))
            assertEquals(depth, repository.undoDepth)
            repository.cancelRasterEdit(provisional)
            val other = repository.addLayer("Linked").id
            assertTrue(repository.linkLayers(listOf(id, other)))
            repository.setActiveLayer(id)
            assertFalse(repository.transformLayer(id, move, repository.contentRevision))
        }

    @Test fun otherLayersAndFramesAreNotTransformed() =
        runTest {
            val id = fixture()
            val original = raw(id)
            val originalName = requireNotNull(repository.getActiveLayer()).name
            val other = repository.addLayer("Untouched").id
            repository.setLayerPixels(other, PixelBuffer.filled(7, 5, 0xFF0000FF.toInt()), "Other layer")
            val otherBefore = raw(other)
            repository.addFrame(true)
            repository.selectFrame(0)
            repository.setActiveLayer(id)
            assertTrue(repository.transformLayer(id, move, repository.contentRevision))
            assertArrayEquals(otherBefore, raw(other))
            repository.selectFrame(1)
            // Duplicated frames have independent layer IDs; compare their retained pixel plane.
            val duplicate = repository.getAllLayers().first { it.name == originalName }
            assertArrayEquals(original, raw(duplicate.id))
        }
}
