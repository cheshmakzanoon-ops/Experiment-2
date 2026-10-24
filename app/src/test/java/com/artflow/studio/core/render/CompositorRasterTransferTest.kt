package com.artflow.studio.core.render

import com.artflow.studio.core.pixels.BlendModes
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.core.pixels.PixelBufferPool
import com.artflow.studio.domain.model.layer.Layer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompositorRasterTransferTest {
    @Test
    fun pooledRasterPlacementMatchesGeneralSourceOverForEveryAlphaAndChannelValue() {
        val source =
            PixelBuffer(
                256,
                256,
                IntArray(256 * 256) { index ->
                    val alpha = index ushr 8
                    val value = index and 255
                    (alpha shl 24) or (value shl 16) or (((value * 73 + 19) and 255) shl 8) or (255 - value)
                },
            )
        val original = source.pixels.copyOf()
        // The unchanged general blend is an independent arithmetic path, including byte rounding.
        val expected = source.pixels.map { BlendModes.sourceOver(0, it) }.toIntArray()
        val pool = PixelBufferPool(1)
        pool.release(pool.obtain(256, 256).apply { pixels.fill(0xFFCC4488.toInt()) })
        val compositor = Compositor()
        try {
            val input = Compositor.LayerInput(Layer(1, "Raster", 0), source)
            val rendered = requireNotNull(compositor.renderLayerContent(input, 256, 256, pool))
            assertArrayEquals(expected, rendered.pixels)
            assertArrayEquals(original, source.pixels)
            assertTrue(rendered.pixels.take(256).all { it == 0 })

            // A subsequent transparent raster must not expose pixels left by the previous owner.
            rendered.pixels.fill(0xFF123456.toInt())
            pool.release(rendered)
            val transparent = PixelBuffer.filled(256, 256, 0x00FFFFFF)
            val cleared = requireNotNull(compositor.renderLayerContent(input.copy(raster = transparent), 256, 256, pool))
            assertTrue(cleared.pixels.all { it == 0 })
            assertTrue(transparent.pixels.all { it == 0x00FFFFFF })
            pool.release(cleared)
        } finally {
            compositor.release()
            pool.clear()
        }
    }
}
