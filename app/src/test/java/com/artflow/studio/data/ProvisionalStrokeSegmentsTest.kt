package com.artflow.studio.data

import android.content.Context
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.data.local.ProjectStorage
import com.artflow.studio.data.repository.canvas.CanvasRepositoryImpl
import com.artflow.studio.domain.model.brush.BrushParams
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock

/** A model gesture may cross many UV seams before it is committed or cancelled. */
@OptIn(ExperimentalCoroutinesApi::class)
class ProvisionalStrokeSegmentsTest {
    private lateinit var repository: CanvasRepositoryImpl
    private val brush = BrushParams(size = 7f, spacing = 0.1f, opacity = 0.65f)

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        repository = newRepository()
    }

    @After
    fun tearDown() {
        repository.dispose()
        Dispatchers.resetMain()
    }

    @Test
    fun manySealedSegmentsRemainVisibleAndCancellableWithoutRetainingTheirCanvasBuffers() =
        runTest {
            val original = prepare(repository)
            val layer = repository.getActiveLayerId()
            val baseline = requireNotNull(repository.compositePreview()).pixels.copyOf()
            val undo = repository.undoDepth
            val history = repository.historyMark
            val segments = mutableListOf<Long>()
            repeat(18) { index ->
                val id = startSegment(repository, index)
                segments += id
                requireNotNull(repository.compositePreview())
                assertEquals("Only the currently growing segment keeps its live raster", 1, cacheEntries("liveStrokes"))
                assertEquals("Only the growing segment keeps a prepared layer copy", 1, cacheEntries("strokeCopies"))

                repository.sealStrokeSegment(id)
                assertNotNull(repository.activeStroke(id))
                val preview = requireNotNull(repository.compositePreview())
                assertFalse("Sealing cannot hide the provisional artwork", baseline.contentEquals(preview.pixels))
                assertEquals("A later preview cannot recreate sealed live buffers", 0, cacheEntries("liveStrokes"))
                assertEquals(0, cacheEntries("strokeCopies"))
                assertArrayEquals(original, requireNotNull(repository.layerPixels(layer)).pixels)
                assertEquals(undo, repository.undoDepth)
                assertEquals(history, repository.historyMark)
            }

            segments.forEach(repository::cancelStroke)
            segments.forEach { assertNull(repository.activeStroke(it)) }
            assertArrayEquals(original, requireNotNull(repository.layerPixels(layer)).pixels)
            assertArrayEquals(baseline, requireNotNull(repository.compositePreview()).pixels)
            assertEquals(undo, repository.undoDepth)
            assertEquals(history, repository.historyMark)
            assertEquals(0, cacheEntries("liveStrokes"))
            assertEquals(0, cacheEntries("strokeCopies"))
        }

    @Test
    fun sealedSegmentsCommitInOrderWithTheSamePixelsAndIndependentUndoSnapshots() =
        runTest {
            val reference = newRepository()
            try {
                val original = prepare(repository)
                prepare(reference)
                val layer = repository.getActiveLayerId()
                val undo = repository.undoDepth
                val segments =
                    (0..2).map { index ->
                        val id = startSegment(repository, index)
                        requireNotNull(repository.compositePreview())
                        repository.sealStrokeSegment(id)
                        reference.endStroke(startSegment(reference, index))
                        id
                    }
                assertArrayEquals(original, requireNotNull(repository.layerPixels(layer)).pixels)
                assertEquals(undo, repository.undoDepth)
                segments.forEach(repository::endStroke)
                val committed = requireNotNull(repository.layerPixels(layer)).pixels.copyOf()
                assertArrayEquals(requireNotNull(reference.layerPixels(reference.getActiveLayerId())).pixels, committed)
                assertFalse(original.contentEquals(committed))
                assertEquals(undo + segments.size, repository.undoDepth)
                assertEquals(0, cacheEntries("liveStrokes"))
                assertEquals(0, cacheEntries("strokeCopies"))

                repeat(segments.size) { assertTrue(repository.undo()) }
                assertArrayEquals(original, requireNotNull(repository.layerPixels(layer)).pixels)
                repeat(segments.size) { assertTrue(repository.redo()) }
                assertArrayEquals(committed, requireNotNull(repository.layerPixels(layer)).pixels)
            } finally {
                reference.dispose()
            }
        }

    @Test
    fun openingANewCanvasDropsSealedStateAndAReusedIdCanDrawNormally() =
        runTest {
            prepare(repository)
            val old = startSegment(repository, 0)
            val oldPoint = requireNotNull(repository.activeStroke(old)).points.first()
            requireNotNull(repository.compositePreview())
            repository.sealStrokeSegment(old)
            prepare(repository)
            assertNull(repository.activeStroke(old))
            assertEquals(0, cacheEntries("liveStrokes"))
            assertEquals(0, cacheEntries("strokeCopies"))
            val fresh = startSegment(repository, 0)
            assertEquals("This fixture exercises ID reuse after a document reset", old, fresh)
            assertTrue(oldPoint !== requireNotNull(repository.activeStroke(fresh)).points.first())
            requireNotNull(repository.compositePreview())
            assertEquals(1, cacheEntries("liveStrokes"))
            repository.cancelStroke(fresh)
            assertEquals(0, cacheEntries("liveStrokes"))
            assertEquals(0, cacheEntries("strokeCopies"))
        }

    private fun newRepository(): CanvasRepositoryImpl = CanvasRepositoryImpl(ProjectStorage(mock(Context::class.java)))

    private suspend fun prepare(target: CanvasRepositoryImpl): IntArray {
        target.createCanvas(96, 64, 72)
        val original = IntArray(96 * 64) { index -> ((64 + index % 160) shl 24) or 0x335577 }
        assertTrue(target.setLayerPixels(target.getActiveLayerId(), PixelBuffer(96, 64, original.copyOf()), "Fixture"))
        target.setStrokeColor(0xFFD03090.toInt())
        return original
    }

    private fun startSegment(
        target: CanvasRepositoryImpl,
        index: Int,
    ): Long {
        val x = 8f + (index % 6) * 14f
        val y = 10f + (index / 6) * 20f
        val id = target.beginStroke(x, y, 1f, brush, target.getActiveLayerId(), timestamp = 0L)
        assertTrue(id != 0L)
        target.continueStroke(id, x + 7f, y + 3f, 1f, timestamp = 16L)
        return id
    }

    /** Observe retained buffers directly; GC timing or a passing pixel assertion cannot prove this bound. */
    private fun cacheEntries(name: String): Int {
        val field = CanvasRepositoryImpl::class.java.getDeclaredField(name)
        field.isAccessible = true
        return (field.get(repository) as Map<*, *>).size
    }
}
