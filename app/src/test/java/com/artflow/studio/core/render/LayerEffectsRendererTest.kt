package com.artflow.studio.core.render

import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.domain.model.layer.LayerEffects
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.sqrt

class LayerEffectsRendererTest {
    private fun alpha(pixel: Int) = pixel ushr 24

    private fun dot(
        width: Int,
        height: Int,
        cx: Int,
        cy: Int,
        r: Int,
        color: Int = 0xFF3366CC.toInt(),
    ): PixelBuffer =
        PixelBuffer(width, height).also { buffer ->
            for (y in 0 until height) {
                for (x in 0 until width) {
                    if ((x - cx) * (x - cx) + (y - cy) * (y - cy) <= r * r) buffer.pixels[y * width + x] = color
                }
            }
        }

    @Test
    fun outlineMatchesTheBruteForceDistanceToThePaint() {
        val random = Random(5)
        val width = 47
        val height = 31
        val coverage = FloatArray(width * height) { if (random.nextFloat() < 0.04f) 1f else 0f }
        val ring = LayerEffectsRenderer.outlineCoverage(coverage, width, height, 3f)
        val painted = coverage.indices.filter { coverage[it] >= 0.5f }
        for (index in coverage.indices) {
            val x = index % width
            val y = index / width
            val nearest =
                painted.minOfOrNull { p ->
                    val dx = (x - p % width).toFloat()
                    val dy = (y - p / width).toFloat()
                    sqrt(dx * dx + dy * dy)
                } ?: Float.MAX_VALUE
            assertEquals("($x, $y)", (3.5f - nearest).coerceIn(0f, 1f), ring[index], 1e-4f)
        }
    }

    @Test
    fun anOutlineSurroundsThePaintInItsColourAndLeavesThePaintOnTop() {
        val layer = dot(40, 40, 20, 20, 6)
        val paint = layer.getSafe(20, 20)
        LayerEffectsRenderer.apply(layer, LayerEffects(outline = LayerEffects.Outline(0xFFFF0000.toInt(), 4f)))
        assertEquals(paint, layer.getSafe(20, 20))
        assertEquals(0xFFFF0000.toInt(), layer.getSafe(20 + 6 + 2, 20))
        assertEquals(0, alpha(layer.getSafe(20 + 6 + 6, 20)))
    }

    @Test
    fun theShadowFallsAwayFromThePaintAndSoftens() {
        val layer = dot(80, 80, 30, 30, 8)
        val shadow = LayerEffects.Shadow(color = 0xFF000000.toInt(), opacity = 1f, angleDegrees = 0f, distance = 20f, blur = 6f)
        LayerEffectsRenderer.apply(layer, LayerEffects(shadow = shadow))
        // Straight to the right of the dot, where nothing was painted, the shadow shows.
        assertTrue(alpha(layer.getSafe(50, 30)) > 200)
        assertEquals(0, layer.getSafe(50, 30) and 0x00FFFFFF)
        // Its edge is soft, and nothing is cast up or to the left.
        val edge = alpha(layer.getSafe(58, 30))
        assertTrue(edge in 1..254)
        assertEquals(0, alpha(layer.getSafe(30, 15)))
    }

    @Test
    fun noEffectsOrAnEmptyLayerChangeNothing() {
        val layer = dot(20, 20, 10, 10, 4)
        val before = layer.pixels.copyOf()
        LayerEffectsRenderer.apply(layer, LayerEffects())
        assertArrayEquals(before, layer.pixels)
        val empty = PixelBuffer(20, 20)
        LayerEffectsRenderer.apply(empty, LayerEffects(outline = LayerEffects.Outline(), shadow = LayerEffects.Shadow()))
        assertArrayEquals(IntArray(400), empty.pixels)
    }
}
