package com.artflow.studio.data.renderer.gles3

import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.data.renderer.gpu.VirtualTileCanvas
import com.artflow.studio.domain.model.layer.BlendMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Milestone4Gles3PipelineTest {
    @Test
    fun `glsl es 300 shader library covers all 26 blend modes and core pipeline stages`() {
        val shaders =
            listOf(
                Gles3Shaders.QUAD_VERTEX,
                Gles3Shaders.BRUSH_DAB_FRAGMENT,
                Gles3Shaders.COMPOSITE_26_MODES_FRAGMENT,
                Gles3Shaders.IMPASTO_RAKING_LIGHT_FRAGMENT,
                Gles3Shaders.LIQUIFY_WARP_FRAGMENT,
            )
        shaders.forEach { src ->
            assertTrue("Expected #version 300 es header", src.startsWith("#version 300 es"))
            assertTrue("Expected main() entry point", "void main()" in src)
        }
        val indices = BlendMode.getLayerBlendModes().map(Gles3Shaders::blendModeIndex).toSet()
        assertEquals("All 26 blend modes must map to distinct shader branches", 26, indices.size)
    }

    @Test
    fun `float16 accumulation tile eliminates low-opacity airbrush quantization stall`() {
        val tile = Float16AccumulationTile(16, 16)
        // 30 passes at 0.015 alpha (which would stall at 0 or band heavily in naive 8-bit accumulation)
        repeat(30) {
            tile.accumulatePixel(8, 8, 1f, 0.5f, 0.25f, 0.015f)
        }
        val out = PixelBuffer(16, 16)
        tile.resolveTo8Bit(out, dither = false)
        val alpha = (out.get(8, 8) ushr 24) and 0xFF
        // Expected analytical alpha: 1 - (1 - 0.015)^30 ≈ 0.3646 -> ~93/255
        assertTrue("Expected smooth float buildup (~93), got $alpha", alpha in 88..98)

        // Verify IEEE-754 binary16 round-trip precision
        for (v in listOf(0f, 0.125f, 0.5f, 0.75f, 1.0f, -0.5f)) {
            val back = Float16AccumulationTile.halfToFloat(Float16AccumulationTile.floatToHalf(v))
            assertEquals(v, back, 0.002f)
        }
    }

    @Test
    fun `gles3 render backend activates float16 pipeline when supported and falls back cleanly on gles2`() {
        val gles2Backend = Gles3RenderBackend(Gles3Capabilities(glMajorVersion = 2, supportsHalfFloatColorBuffer = false))
        gles2Backend.initialize(32, 32)
        assertTrue(gles2Backend.isContextInitialized)
        assertFalse(gles2Backend.isUsingFloat16Pipeline)
        gles2Backend.release()

        val gles3Backend =
            Gles3RenderBackend(
                Gles3Capabilities(glMajorVersion = 3, glMinorVersion = 1, supportsHalfFloatColorBuffer = true, maxTextureSize = 4096),
            )
        gles3Backend.initialize(32, 32)
        assertTrue(gles3Backend.isUsingFloat16Pipeline)
        val layer = VirtualTileCanvas(32, 32)
        val src = PixelBuffer(32, 32).apply { fill(0x80CC4422.toInt()) }
        layer.loadFromBuffer(src)
        gles3Backend.uploadDirtyTiles(layer, listOf(0))
        val target = PixelBuffer(32, 32)
        gles3Backend.compositeLayers(listOf(layer), target)
        assertTrue((target.get(16, 16) ushr 24) in 120..136)
        gles3Backend.release()
        assertFalse(gles3Backend.isContextInitialized)
    }
}
