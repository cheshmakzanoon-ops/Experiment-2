package com.artflow.studio.data.repository.canvas

import com.artflow.studio.core.pixels.IntBounds
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.domain.model.brush.BrushParams
import com.artflow.studio.domain.model.brush.Stroke
import com.artflow.studio.domain.model.brush.StrokePoint
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewCacheTest {
    private val params = BrushParams(size = 4f)
    private val requests = mutableListOf<IntBounds?>()

    private fun stroke(vararg xs: Float): Stroke =
        Stroke(id = 7, points = xs.map { StrokePoint(it, 50f, 1f, timestamp = 0L) }, brushParams = params, layerId = 1, color = -1)

    private fun frame(
        cache: PreviewCache,
        key: Any?,
        strokes: List<Stroke>,
    ) = runBlocking {
        cache.frame(key, strokes, 200, 100) { region ->
            requests += region
            PixelBuffer(region?.width ?: 200, region?.height ?: 100)
        }
    }

    @Test fun growingStrokeOnlyRecompositesItsNewTail() {
        val cache = PreviewCache()
        assertNull(frame(cache, "doc", listOf(stroke(10f, 20f))).dirty)
        val next = frame(cache, "doc", listOf(stroke(10f, 20f, 30f)))
        val dirty = requireNotNull(next.dirty)
        // From the previous last point (20) to the new one (30), grown by the brush reach.
        assertTrue(dirty.left in 8..12 && dirty.right in 38..42)
        assertEquals(dirty, requests.last())
    }

    @Test fun unchangedFrameDoesNoWork() {
        val cache = PreviewCache()
        frame(cache, "doc", listOf(stroke(10f, 20f)))
        val count = requests.size
        assertTrue(requireNotNull(frame(cache, "doc", listOf(stroke(10f, 20f))).dirty).isEmpty)
        assertEquals(count, requests.size)
    }

    @Test fun documentChangesAndRemovedStrokesAreCovered() {
        val cache = PreviewCache()
        frame(cache, "doc", listOf(stroke(10f, 20f)))
        val removed = requireNotNull(frame(cache, "doc", emptyList()).dirty)
        assertTrue(removed.left <= 10 && removed.right >= 20)
        assertNull(frame(cache, "edited", emptyList()).dirty)
        assertNull("No key means the preview cannot be reused", frame(cache, null, emptyList()).dirty)
        assertNull(frame(cache, null, emptyList()).dirty)
    }

    @Test fun reshapedStrokeRedrawsOldAndNewExtent() {
        val cache = PreviewCache()
        frame(cache, "doc", listOf(stroke(10f, 20f, 30f)))
        // QuickShape replaced the points: the old extent must be cleared too.
        val dirty = requireNotNull(frame(cache, "doc", listOf(stroke(60f, 70f))).dirty)
        assertTrue(dirty.left <= 10 && dirty.right >= 70)
    }
}
