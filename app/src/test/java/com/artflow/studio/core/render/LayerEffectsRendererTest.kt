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
    fun aTransparentOutlineDoesNotExpandTheShadow() {
        val shadow = LayerEffects.Shadow(opacity = 1f, angleDegrees = 0f, distance = 12f, blur = 2f)
        val original = dot(40, 30, 10, 15, 2)
        val withoutOutline = original.copy()
        val withTransparentOutline = original.copy()

        LayerEffectsRenderer.apply(withoutOutline, LayerEffects(shadow = shadow))
        LayerEffectsRenderer.apply(
            withTransparentOutline,
            LayerEffects(outline = LayerEffects.Outline(color = 0x00FF0000, width = 4f), shadow = shadow),
        )

        assertArrayEquals(withoutOutline.pixels, withTransparentOutline.pixels)
    }

    @Test
    fun theOutlineAlphaScalesItsShadowCoverage() {
        val layer = dot(40, 30, 10, 15, 2)
        val outline = LayerEffects.Outline(color = 0x80FF0000.toInt(), width = 4f)
        val shadow = LayerEffects.Shadow(color = 0x80000000.toInt(), opacity = 0.5f, angleDegrees = 0f, distance = 12f, blur = 0f)

        LayerEffectsRenderer.apply(layer, LayerEffects(outline = outline, shadow = shadow))

        // This point is cast by the outline alone; its own alpha and both shadow alpha factors apply.
        assertEquals(32, alpha(layer.getSafe(26, 15)))
        assertEquals(0, layer.getSafe(26, 15) and 0x00FFFFFF)
        assertEquals(128, alpha(layer.getSafe(14, 15)))
    }

    @Test
    fun anOffCanvasShadowBlursBackIntoTheCanvasAndKeepsThePaintOnTop() {
        val layer = dot(9, 9, 0, 4, 0)
        val paint = layer.getSafe(0, 4)
        val shadow = LayerEffects.Shadow(opacity = 1f, angleDegrees = 180f, distance = 1f, blur = 2f)

        LayerEffectsRenderer.apply(layer, LayerEffects(shadow = shadow))

        assertEquals(7, alpha(layer.getSafe(1, 4)))
        assertEquals(paint, layer.getSafe(0, 4))
        assertEquals(0, layer.getSafe(8, 4))
    }

    @Test
    fun edgeAndCornerShadowsMatchTheCropOfAPaddedCanvas() {
        val source = PixelBuffer(11, 9)
        source.setUnchecked(0, 0, 0xFF3366CC.toInt())
        source.setUnchecked(10, 0, 0x803366CC.toInt())
        source.setUnchecked(0, 8, 0x403366CC)
        source.setUnchecked(10, 8, 0xFF663311.toInt())
        source.setUnchecked(5, 0, 0xFF110022.toInt())
        source.setUnchecked(0, 4, 0xFF003311.toInt())
        source.setUnchecked(5, 8, 0x00332211)
        source.setUnchecked(5, 4, 0x55443322)
        val before = source.pixels.copyOf()
        // Enough room for even the maximum three-pass support and the outward offset.
        val padding = 104
        for (blur in listOf(2f, 8f, LayerEffects.MAX_SHADOW_BLUR)) {
            for (angle in 0 until 360 step 45) {
                val shadow = LayerEffects.Shadow(opacity = 0.8f, angleDegrees = angle.toFloat(), distance = 7f, blur = blur)
                val actual = source.copy()
                val padded = PixelBuffer(source.width + 2 * padding, source.height + 2 * padding)
                for (y in 0 until source.height) {
                    System.arraycopy(source.pixels, y * source.width, padded.pixels, (y + padding) * padded.width + padding, source.width)
                }

                LayerEffectsRenderer.apply(actual, LayerEffects(shadow = shadow))
                LayerEffectsRenderer.apply(padded, LayerEffects(shadow = shadow))

                for (index in source.pixels.indices) {
                    val x = index % source.width
                    val y = index / source.width
                    assertEquals("blur=$blur, angle=$angle at ($x, $y)", padded.getSafe(x + padding, y + padding), actual.pixels[index])
                }
            }
        }
        assertArrayEquals(before, source.pixels)
    }

    @Test
    fun zeroOffsetCornerCoverageRetainsAllThreeBlurPasses() {
        val caster = FloatArray(81).also { it[0] = 1f }
        val before = caster.copyOf()
        val shadow = LayerEffects.Shadow(distance = 0f, blur = 2f)

        val cast = LayerEffectsRenderer.shadowCoverage(caster, 9, 9, shadow)

        // Three radius-one box kernels have weights [1, 3, 6, 7, 6, 3, 1] / 27.
        assertEquals(49f / 729f, cast[0], 1e-6f)
        assertEquals(36f / 729f, cast[10], 1e-6f)
        assertEquals(0f, cast[4], 1e-6f)
        assertArrayEquals(before, caster, 0f)
    }

    @Test
    fun maximumBlurKeepsTheFullCornerKernel() {
        val caster = FloatArray(81).also { it[0] = 1f }
        val shadow = LayerEffects.Shadow(distance = 0f, blur = LayerEffects.MAX_SHADOW_BLUR)

        val cast = LayerEffectsRenderer.shadowCoverage(caster, 9, 9, shadow)

        // At radius 32, the central coefficient of three uniform kernels is 3169 / 65^3.
        val centralWeight = 3169f / 274625f
        assertEquals(centralWeight * centralWeight, cast[0], 1e-8f)
    }

    @Test
    fun anInteriorShadowKeepsItsExistingKernel() {
        val caster = FloatArray(81).also { it[4 * 9 + 4] = 1f }
        val shadow = LayerEffects.Shadow(angleDegrees = 0f, distance = 1f, blur = 2f)
        val weights = intArrayOf(1, 3, 6, 7, 6, 3, 1)
        val expected = FloatArray(81)
        for (y in weights.indices) {
            for (x in weights.indices) expected[(y + 1) * 9 + x + 2] = weights[x] * weights[y] / 729f
        }

        val cast = LayerEffectsRenderer.shadowCoverage(caster, 9, 9, shadow)

        for (i in expected.indices) assertEquals("Pixel $i", expected[i], cast[i], 1e-6f)
    }

    @Test
    fun shadowScratchCanExtendPastTheDocumentEdgeLimit() {
        val shadow = LayerEffects.Shadow(distance = 0f, blur = 2f)
        for ((width, height) in listOf(8192 to 1, 1 to 8192)) {
            val caster = FloatArray(width * height) { 1f }

            val cast = LayerEffectsRenderer.shadowCoverage(caster, width, height, shadow)

            assertEquals((width + 6) * (height + 6), cast.size)
            assertEquals(7f / 27f, cast[caster.size / 2], 1e-6f)
            assertTrue(caster.all { it == 1f })
        }
    }

    @Test
    fun shadowPaddingAdmissionIncludesTheHaloAndOneReusableLine() {
        val maximum = LayerEffects.Shadow(blur = LayerEffects.MAX_SHADOW_BLUR)
        assertEquals(10_221_824L, LayerEffectsRenderer.shadowPaddingBytes(8192, 4882, maximum))
        assertEquals(6_473_216L, LayerEffectsRenderer.shadowPaddingBytes(8192, 1, maximum))
        assertEquals(6_473_216L, LayerEffectsRenderer.shadowPaddingBytes(1, 8192, maximum))
        assertEquals(0L, LayerEffectsRenderer.shadowPaddingBytes(8192, 1, maximum.copy(blur = 0f)))
    }

    @Test
    fun anUnblurredShadowKeepsTheExactOffsetAndNeedsNoPadding() {
        val caster = FloatArray(12).also { it[4] = 0.5f }
        val shadow = LayerEffects.Shadow(angleDegrees = 0f, distance = 1f, blur = 0f)
        val expected = FloatArray(12).also { it[5] = 0.5f }

        assertArrayEquals(expected, LayerEffectsRenderer.shadowCoverage(caster, 4, 3, shadow), 0f)
        assertEquals(0.5f, caster[4], 0f)
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
