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
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock

/** Persistent membership and real multi-layer transactions, including protected peers and masks. */
@OptIn(ExperimentalCoroutinesApi::class)
class LinkedLayerTransactionTest {
    private lateinit var repository: CanvasRepositoryImpl
    private val red = 0xFFFF0000.toInt()
    private val blue = 0xFF0000FF.toInt()

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

    private suspend fun fixture(): Pair<Long, Long> {
        repository.createCanvas(7, 7, 72)
        val first = repository.getActiveLayerId()
        repository.setLayerPixels(first, PixelBuffer(7, 7).apply { setUnchecked(2, 2, red) }, "First")
        val second = repository.addLayer("Shade").id
        repository.setLayerPixels(second, PixelBuffer(7, 7).apply { setUnchecked(4, 4, blue) }, "Second")
        return first to second
    }

    private fun link(id: Long): Long? = repository.getAllLayers().first { it.id == id }.linkGroupId

    private suspend fun pixels(id: Long): PixelBuffer = requireNotNull(repository.layerPixels(id))

    @Test
    fun linkingUnlinkingAndNoOpsHaveExactUndoBoundaries() =
        runTest {
            val (first, second) = fixture()
            val depth = repository.undoDepth
            assertFalse(repository.linkLayers(listOf(first, first)))
            assertFalse(repository.linkLayers(listOf(first, Long.MAX_VALUE)))
            assertEquals(depth, repository.undoDepth)
            assertTrue(repository.linkLayers(listOf(first, second)))
            val group = link(first)
            assertNotNull(group)
            assertEquals(group, link(second))
            assertEquals(depth + 1, repository.undoDepth)
            assertTrue(repository.linkLayers(listOf(first, second)))
            assertEquals(depth + 1, repository.undoDepth)
            assertTrue(repository.unlinkLayer(first))
            assertNull(link(first))
            assertNull(link(second))
            assertTrue(repository.undo())
            assertEquals(group, link(first))
            assertEquals(group, link(second))
            assertTrue(repository.undo())
            assertNull(link(first))
            assertTrue(repository.redo())
            assertEquals(group, link(second))
        }

    @Test
    fun linkingAnExistingMemberPreservesItsUnselectedPeers() =
        runTest {
            val (first, second) = fixture()
            val third = repository.addLayer("Third").id
            val fourth = repository.addLayer("Unrelated").id
            assertTrue(repository.linkLayers(listOf(first, second)))
            assertTrue(repository.linkLayers(listOf(second, third)))
            assertEquals(link(first), link(second))
            assertEquals(link(first), link(third))
            assertNull(link(fourth))
        }

    @Test
    fun translationMovesLinkedPixelsAndMaskAsOneUndoableTransaction() =
        runTest {
            val (first, second) = fixture()
            val mask = SelectionMask(7, 7).apply { coverage[2 * 7 + 2] = 255.toByte() }
            assertTrue(repository.addLayerMask(first, mask))
            assertTrue(repository.linkLayers(listOf(first, second)))
            repository.setActiveLayer(first)
            val before = requireNotNull(repository.compositeFrame(0, transparentBackground = true)).pixels
            val depth = repository.undoDepth
            assertTrue(repository.transformLayer(first, LayerTransform.Parameters(translationX = 1f), repository.contentRevision))
            assertEquals(red, pixels(first).getSafe(3, 2))
            assertEquals(blue, pixels(second).getSafe(5, 4))
            val after = requireNotNull(repository.compositeFrame(0, transparentBackground = true)).pixels
            assertEquals(red, after[2 * 7 + 3])
            assertEquals(0, after[2 * 7 + 2])
            assertEquals(depth + 1, repository.undoDepth)
            assertTrue(repository.undo())
            assertArrayEquals(before, requireNotNull(repository.compositeFrame(0, transparentBackground = true)).pixels)
            assertTrue(repository.redo())
            assertArrayEquals(after, requireNotNull(repository.compositeFrame(0, transparentBackground = true)).pixels)
        }

    @Test
    fun rotationAndScalingShareOnePivotAndKeepUnlinkedArtworkUntouched() =
        runTest {
            val (first, second) = fixture()
            val other = repository.addLayer("Unlinked").id
            val untouched = PixelBuffer(7, 7).apply { setUnchecked(0, 0, -1) }
            repository.setLayerPixels(other, untouched, "Other")
            assertTrue(repository.linkLayers(listOf(first, second)))
            repository.setActiveLayer(first)
            val depth = repository.undoDepth
            assertTrue(repository.transformLayer(first, LayerTransform.Parameters(rotationDegrees = 90f), repository.contentRevision))
            assertEquals(red, pixels(first).getSafe(4, 2))
            assertEquals(blue, pixels(second).getSafe(2, 4))
            assertEquals(depth + 1, repository.undoDepth)
            assertTrue(repository.undo())
            val scale = LayerTransform.Parameters(scaleX = 2f, scaleY = 2f, interpolation = LayerTransform.Interpolation.NEAREST)
            assertTrue(repository.transformLayer(first, scale, repository.contentRevision))
            assertEquals(red, pixels(first).getSafe(1, 1))
            assertEquals(blue, pixels(second).getSafe(5, 5))
            assertArrayEquals(untouched.pixels, pixels(other).pixels)
            assertEquals(depth + 1, repository.undoDepth)
        }

    @Test
    fun protectedPeersRejectTheWholeTransformWithoutChangingHistory() =
        runTest {
            val (first, second) = fixture()
            assertTrue(repository.linkLayers(listOf(first, second)))
            repository.setActiveLayer(first)
            val before = pixels(first).pixels
            repository.setLayerLock(second, true)
            var depth = repository.undoDepth
            val move = LayerTransform.Parameters(translationX = 1f)
            assertFalse(repository.transformLayer(first, move, repository.contentRevision))
            assertEquals(depth, repository.undoDepth)
            repository.setLayerLock(second, false)
            val group = requireNotNull(repository.groupLayers(listOf(second)))
            repository.setLayerVisibility(group, false)
            repository.setActiveLayer(first)
            depth = repository.undoDepth
            assertFalse(repository.transformLayer(first, move, repository.contentRevision))
            assertEquals(depth, repository.undoDepth)
            assertArrayEquals(before, pixels(first).pixels)
        }

    @Test
    fun aLinkChangeCannotCommitAnAlreadyOpenedBatch() =
        runTest {
            val (first, second) = fixture()
            assertTrue(repository.linkLayers(listOf(first, second)))
            val edits = listOf(first, second).map { requireNotNull(repository.beginRasterEdit(it)) }
            assertFalse(repository.unlinkLayer(first))
            // A different document mutation invalidates the entire provisional transaction.
            repository.setLayerName(second, "Changed while transforming")
            val depth = repository.undoDepth
            edits.forEach { it.buffer.clear() }
            assertFalse(repository.commitRasterEdits(edits, "Stale linked transform"))
            assertEquals(depth, repository.undoDepth)
            assertEquals(red, pixels(first).getSafe(2, 2))
            assertEquals(blue, pixels(second).getSafe(4, 4))
        }

    @Test
    fun duplicatedLayersDoNotStayLinkedToTheirOriginals() =
        runTest {
            val (first, second) = fixture()
            assertTrue(repository.linkLayers(listOf(first, second)))
            val copy = requireNotNull(repository.duplicateLayer(first))
            assertNull(link(copy))
            assertEquals(link(first), link(second))
            val group = requireNotNull(repository.groupLayers(listOf(first, second)))
            val groupCopy = requireNotNull(repository.duplicateLayer(group))
            val children = repository.getAllLayers().filter { it.parentGroupId == groupCopy }
            assertEquals(2, children.size)
            assertNotNull(children.first().linkGroupId)
            assertEquals(children.first().linkGroupId, children.last().linkGroupId)
            assertNotEquals(link(first), children.first().linkGroupId)
        }
}
