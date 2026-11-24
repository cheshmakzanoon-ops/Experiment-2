package com.artflow.studio.core.render

import com.artflow.studio.core.pixels.BlendModes
import com.artflow.studio.core.pixels.IntBounds
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.core.pixels.PixelBufferPool
import com.artflow.studio.domain.model.brush.BrushParams
import com.artflow.studio.domain.model.brush.Stroke
import com.artflow.studio.domain.model.brush.StrokePoint
import com.artflow.studio.domain.model.layer.BlendMode
import com.artflow.studio.domain.model.layer.Layer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineMemoryOptimizationTest {
    private fun patternTile(area: IntBounds): PixelBuffer =
        PixelBuffer(area.width, area.height).also { buffer ->
            for (y in 0 until area.height) {
                for (x in 0 until area.width) {
                    val cx = area.left + x
                    val cy = area.top + y
                    val r = (cx and 0xFF) shl 16
                    val g = (cy and 0xFF) shl 8
                    val b = (cx + cy) and 0xFF
                    buffer.pixels[y * area.width + x] = (0xFF shl 24) or r or g or b
                }
            }
        }

    @Test
    fun sparseTileCacheAllocatesOnlyTouchedTilesOnLargeCanvas() {
        val cache = TileCache(tileSize = 128)
        val area = IntBounds(10, 10, 80, 80)
        val result = cache.read("doc", 4096, 4096, area, ::patternTile)

        assertArrayEquals(patternTile(area).pixels, result.pixels)
        assertEquals(1, cache.cachedTileCount)
        assertEquals(128L * 128L * Int.SIZE_BYTES, cache.allocatedBytes)
        // Full 4096x4096 would be 64 MiB; sparse cache uses 64 KiB.
        assertTrue(cache.allocatedBytes < 256L * 1024L)

        cache.release()
        assertEquals(0, cache.cachedTileCount)
        assertEquals(0L, cache.allocatedBytes)
    }

    @Test
    fun sparseTileCacheEvictsLeastRecentlyUsedTilesOverBudget() {
        // Budget for exactly 2 tiles of 16x16 (1024 bytes each = 2048 bytes).
        val tileBytes = 16L * 16L * Int.SIZE_BYTES
        val cache = TileCache(tileSize = 16, maxBytes = tileBytes * 2)
        var renderCalls = 0
        val render: (IntBounds) -> PixelBuffer = { area ->
            renderCalls++
            patternTile(area)
        }

        // Fill tile (0,0) and tile (1,0).
        cache.read("k", 64, 64, IntBounds(0, 0, 10, 10), render)
        cache.read("k", 64, 64, IntBounds(16, 0, 26, 10), render)
        assertEquals(2, renderCalls)
        assertEquals(2, cache.cachedTileCount)
        assertEquals(tileBytes * 2, cache.allocatedBytes)

        // Touch tile (0,0) so tile (1,0) becomes the least recently used.
        cache.read("k", 64, 64, IntBounds(2, 2, 8, 8), render)
        assertEquals(2, renderCalls)

        // Fill tile (2,0); tile (1,0) must be evicted to stay within maxBytes.
        val tile2 = cache.read("k", 64, 64, IntBounds(32, 0, 42, 10), render)
        assertArrayEquals(patternTile(IntBounds(32, 0, 42, 10)).pixels, tile2.pixels)
        assertEquals(3, renderCalls)
        assertEquals(2, cache.cachedTileCount)
        assertEquals(tileBytes * 2, cache.allocatedBytes)

        // Tile (0,0) is still cached (no new render); tile (1,0) must re-render.
        cache.read("k", 64, 64, IntBounds(1, 1, 5, 5), render)
        assertEquals(3, renderCalls)
        val reloaded = cache.read("k", 64, 64, IntBounds(16, 0, 26, 10), render)
        assertEquals(4, renderCalls)
        assertArrayEquals(patternTile(IntBounds(16, 0, 26, 10)).pixels, reloaded.pixels)
    }

    @Test
    fun pixelBufferPoolEnforcesByteBudgetAndSupportsUnclearObtain() {
        val oneBufferBytes = 64L * 64L * Int.SIZE_BYTES
        val pool = PixelBufferPool(maxCapacity = 8, maxBytes = oneBufferBytes * 2)

        val b1 = PixelBuffer(64, 64).apply { fill(0xFF112233.toInt()) }
        val b2 = PixelBuffer(64, 64).apply { fill(0xFF445566.toInt()) }
        val b3 = PixelBuffer(64, 64)

        assertTrue(pool.release(b1))
        assertTrue(pool.release(b2))
        // Third release exceeds maxBytes even though maxCapacity is 8.
        assertFalse(pool.release(b3))
        assertEquals(oneBufferBytes * 2, pool.pooledBytes)

        val reusedUnclear = pool.obtain(64, 64, clear = false)
        assertEquals(0xFF112233.toInt(), reusedUnclear.pixels[0])
        assertEquals(oneBufferBytes, pool.pooledBytes)

        val reusedClear = pool.obtain(64, 64, clear = true)
        assertEquals(0, reusedClear.pixels[0])
        assertEquals(0L, pool.pooledBytes)
    }

    @Test
    fun blendModesFastPathsPreserveTransparentAndOpaqueSemantics() {
        val dst = PixelBuffer(4, 1)
        val src = PixelBuffer(4, 1)
        dst.pixels[0] = 0xFF102030.toInt()
        dst.pixels[1] = 0x00000000
        dst.pixels[2] = 0xFF405060.toInt()
        dst.pixels[3] = 0x80204060.toInt()

        src.pixels[0] = 0x00AABBCC // transparent source -> backdrop unchanged
        src.pixels[1] = 0x80FF0080.toInt() // transparent backdrop -> source verbatim
        src.pixels[2] = 0xFFABCDEF.toInt() // opaque source -> source verbatim
        src.pixels[3] = 0x80C08040.toInt() // semi-transparent over semi-transparent

        val expectedSemi = BlendModes.sourceOver(dst.pixels[3], src.pixels[3])
        BlendModes.composite(dst, src, BlendMode.NORMAL, 1f)

        assertEquals(0xFF102030.toInt(), dst.pixels[0])
        assertEquals(0x80FF0080.toInt(), dst.pixels[1])
        assertEquals(0xFFABCDEF.toInt(), dst.pixels[2])
        assertEquals(expectedSemi, dst.pixels[3])
    }

    @Test
    fun strokeRasterizerLiveDrawMatchesFullDrawWithOriginOffset() {
        val points =
            listOf(
                StrokePoint(x = 12f, y = 14f, pressure = 0.7f, timestamp = 0L),
                StrokePoint(x = 24f, y = 20f, pressure = 0.9f, timestamp = 16L),
                StrokePoint(x = 36f, y = 30f, pressure = 0.5f, timestamp = 32L),
            )
        val stroke =
            Stroke(
                id = 42L,
                layerId = 1L,
                color = 0xFF2266CC.toInt(),
                points = points,
                brushParams = BrushParams(size = 8f, opacity = 0.65f, spacing = 0.25f),
            )

        val fullCanvas = PixelBuffer(64, 64)
        StrokeRasterizer().draw(fullCanvas, stroke)

        val cropped = PixelBuffer(32, 32)
        val liveRasterizer = StrokeRasterizer(originX = 10, originY = 10)
        val live = liveRasterizer.startLive(stroke, 64, 64)
        liveRasterizer.drawLive(cropped, live, stroke, canvas = null)
        liveRasterizer.release()

        val expectedCrop = fullCanvas.crop(IntBounds(10, 10, 41, 41))
        assertArrayEquals(expectedCrop.pixels, cropped.pixels)
    }

    @Test
    fun compositorDirectRasterThumbnailMatchesScaledRaster() {
        val raster = patternTile(IntBounds(0, 0, 256, 128))
        val layer = Layer(id = 1L, name = "Paint", index = 0)
        val compositor = Compositor()
        try {
            val thumb = compositor.layerThumbnail(Compositor.LayerInput(layer = layer, raster = raster), size = 64)
            assertNotNull(thumb)
            val expected = raster.scaled(64, 32)
            assertArrayEquals(expected.pixels, thumb!!.pixels)
        } finally {
            compositor.release()
        }
    }
}
