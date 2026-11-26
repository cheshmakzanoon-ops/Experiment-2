package com.artflow.studio.data.repository.canvas

import com.artflow.studio.core.pixels.IntBounds
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.core.render.StrokeRasterizer
import com.artflow.studio.core.render.StrokeReach
import com.artflow.studio.domain.model.brush.BrushParams
import com.artflow.studio.domain.model.brush.DualBrush
import com.artflow.studio.domain.model.brush.PressureMapping
import com.artflow.studio.domain.model.brush.Stroke
import com.artflow.studio.domain.model.brush.StrokePoint
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ceil
import kotlin.math.floor

class PreviewCacheTest {
    private val params = BrushParams(size = 4f)
    private val requests = mutableListOf<IntBounds?>()

    private fun stroke(vararg xs: Float): Stroke =
        Stroke(id = 7, points = xs.map { StrokePoint(it, 50f, 1f, timestamp = 0L) }, brushParams = params, layerId = 1, color = -1)

    private fun key(
        name: String?,
        raster: Int = 0,
    ) = name?.let { PreviewCache.Key(listOf(it), mapOf(1L to listOf(raster))) }

    private fun frame(
        cache: PreviewCache,
        name: String?,
        strokes: List<Stroke>,
        damage: PreviewCache.Damage? = PreviewCache.Damage.NONE,
        raster: Int = 0,
    ) = runBlocking {
        cache.frame(key(name, raster), damage, strokes, 200, 100) { region ->
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
        val reach = StrokeReach.of(params) + StrokeReach.PADDING
        assertTrue(dirty.left in floor(20 - reach).toInt() - 2..floor(20 - reach).toInt())
        assertTrue(dirty.right in ceil(30 + reach).toInt()..ceil(30 + reach).toInt() + 2)
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

    @Test fun committedStrokeOnlyRedrawsItsDamage() {
        val cache = PreviewCache()
        frame(cache, "doc", listOf(stroke(10f, 20f)))
        // The stroke was committed: its layer's pixels changed, inside the reported area only.
        val area = IntBounds(2, 42, 28, 58)
        val committed = frame(cache, "doc", emptyList(), PreviewCache.Damage.NONE.plus(area, 1L), raster = 1)
        assertEquals(area, committed.dirty)
        // A pixel change on a layer the damage does not name forces a full composite.
        assertNull(frame(cache, "doc", emptyList(), PreviewCache.Damage.NONE, raster = 2).dirty)
        assertNull("Unknown damage forces a full composite", frame(cache, "doc", emptyList(), null, raster = 2).dirty)
    }

    @Test
    fun pressureGrainOnEitherBrushRecompositesEarlierPixelsWhenAStrokeGrows() =
        runBlocking {
            val pressure = PressureMapping(listOf(PressureMapping.Point(0f, 0.1f), PressureMapping.Point(1f, 0.9f)))
            val textured =
                params.copy(
                    size = 14f,
                    pressureToSize = 0f,
                    pressureToOpacity = 0f,
                    textureId = "canvas",
                    blendTexture = true,
                    grainDepth = 0.7f,
                    importedAuxiliaryCurves =
                        mapOf(
                            "dynamicsPressureGrainDepthCurve" to pressure,
                            "dynamicsPressureGrainDepthJitterCurve" to pressure,
                        ),
                )
            val primary = params.copy(size = 20f, pressureToSize = 0f, pressureToOpacity = 0f)
            for (brush in listOf(textured, primary.copy(dual = DualBrush(textured)))) {
                for (erase in listOf(false, true)) {
                    val cache = PreviewCache()
                    val whole =
                        stroke(20.5f, 50.5f, 140.5f).copy(
                            brushParams = brush,
                            isEraser = erase,
                            points =
                                listOf(
                                    StrokePoint(20.5f, 50.5f, pressure = 0.2f, timestamp = 0L),
                                    StrokePoint(50.5f, 50.5f, pressure = 0.3f, timestamp = 20L),
                                    StrokePoint(140.5f, 50.5f, pressure = 0.9f, timestamp = 50L),
                                ),
                        )
                    for (count in listOf(2, 3)) {
                        val partial = whole.copy(points = whole.points.take(count))
                        val expected = PixelBuffer.filled(200, 100, if (erase) 0xFF7799BB.toInt() else 0)
                        StrokeRasterizer().draw(expected, partial)
                        val actual =
                            cache.frame(key("grain"), PreviewCache.Damage.NONE, listOf(partial), 200, 100) { region ->
                                // The region renderer supplies the correct current pixels; the cache
                                // must request all changed pixels, including the old stroke beginning.
                                if (region == null) expected.copy() else expected.crop(region)
                            }
                        assertArrayEquals(
                            "dual=${brush.dual != null} / erase=$erase / count=$count",
                            expected.pixels,
                            actual.buffer.pixels,
                        )
                    }
                }
            }
        }
}
