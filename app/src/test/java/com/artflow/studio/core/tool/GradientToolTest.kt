package com.artflow.studio.core.tool

import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.core.pixels.SelectionMask
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GradientToolTest {
    private val red = 0xFFFF0000.toInt()

    private fun solid(type: GradientTool.GradientType = GradientTool.GradientType.LINEAR) =
        GradientTool.Gradient("Solid", type, listOf(GradientTool.Stop(0f, red), GradientTool.Stop(1f, red)))

    @Test
    fun everyGeometryHonorsAlphaLockWithoutDisablingColourChanges() {
        for (type in GradientTool.GradientType.entries) {
            val target = PixelBuffer(3, 1, intArrayOf(0x400000FF, 0x800000FF.toInt(), 0x0000FF00))
            val result = GradientTool.draw(target, 0f, 0f, 3f, 0f, GradientTool.Settings(solid(type), alphaLock = true))
            assertTrue("$type must still paint", result.changed)
            assertArrayEquals(intArrayOf(0x40FF0000, 0x80FF0000.toInt(), 0x0000FF00), target.pixels)
        }
    }

    @Test
    fun opacityAndSelectionAreMultipliedExactlyOnce() {
        for (type in GradientTool.GradientType.entries) {
            val target = PixelBuffer(3, 1)
            val mask = SelectionMask(3, 1, byteArrayOf(255.toByte(), 128.toByte(), 0))
            GradientTool.draw(target, 0f, 0f, 3f, 0f, GradientTool.Settings(solid(type), opacity = 0.5f, mask = mask))
            assertArrayEquals(intArrayOf(0x80FF0000.toInt(), 0x40FF0000, 0), target.pixels)
        }
    }

    @Test
    fun featheredLockedGradientBlendsColourWithoutSquaringDestinationCoverage() {
        val target = PixelBuffer(1, 1, intArrayOf(0x400000FF))
        val mask = SelectionMask(1, 1, byteArrayOf(128.toByte()))
        GradientTool.draw(target, 0f, 0f, 3f, 0f, GradientTool.Settings(solid(), mask = mask, alphaLock = true, opacity = 0.5f))
        assertEquals(0x404000BF, target.pixels[0])
    }

    @Test
    fun invalidOrZeroOpacityDoesNotTouchPixels() {
        for (opacity in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            val target = PixelBuffer(1, 1, intArrayOf(0x400000FF))
            val result = GradientTool.draw(target, 0f, 0f, 3f, 0f, GradientTool.Settings(solid(), opacity = opacity))
            assertFalse(result.changed)
            assertEquals(0x400000FF, target.pixels[0])
        }
    }

    @Test
    fun transparentStopsDoNotEraseArtwork() {
        val gradient =
            GradientTool.Gradient(
                "Clear",
                GradientTool.GradientType.LINEAR,
                listOf(GradientTool.Stop(0f, 0x00FF0000), GradientTool.Stop(1f, 0x0000FF00)),
            )
        for (locked in listOf(false, true)) {
            val target = PixelBuffer(1, 1, intArrayOf(0x400000FF))
            val result = GradientTool.draw(target, 0f, 0f, 3f, 0f, GradientTool.Settings(gradient, alphaLock = locked))
            assertFalse(result.changed)
            assertEquals(0x400000FF, target.pixels[0])
        }
    }

    @Test
    fun smallerMaskUsesCoordinatesRatherThanReusingCoverageInTheNextRow() {
        val target = PixelBuffer(3, 2)
        val mask = SelectionMask(1, 2).apply { selectAll() }
        GradientTool.draw(target, 0f, 0f, 3f, 0f, GradientTool.Settings(solid(), mask = mask))
        assertArrayEquals(intArrayOf(red, 0, 0, red, 0, 0), target.pixels)
    }

    @Test
    fun invalidOrDegenerateGeometryDoesNotPaint() {
        for (end in listOf(0f, Float.NaN, Float.POSITIVE_INFINITY, Float.MAX_VALUE)) {
            val target = PixelBuffer(1, 1)
            val result = GradientTool.draw(target, 0f, 0f, end, 0f)
            assertFalse(result.changed)
            assertEquals(0, target.pixels[0])
        }
    }

    @Test
    fun preparedUnsortedRampMatchesPublicSamplerWithReversalAndPartialAlpha() {
        val gradient =
            GradientTool.Gradient(
                "Unsorted",
                GradientTool.GradientType.LINEAR,
                listOf(
                    GradientTool.Stop(1f, 0x800000FF.toInt()),
                    GradientTool.Stop(0f, red),
                    GradientTool.Stop(0.35f, 0x4000FF00),
                ),
            )
        for (reverse in listOf(false, true)) {
            val target = PixelBuffer(8, 1)
            GradientTool.draw(
                target,
                0f,
                0f,
                8f,
                0f,
                GradientTool.Settings(gradient, dither = false, reverse = reverse),
            )
            for (x in 0 until target.width) {
                val t = (x + 0.5f) / target.width
                assertEquals(gradient.colorAt(if (reverse) 1f - t else t), target.pixels[x])
            }
        }
    }

    @Test
    fun nonFiniteStartAndEndCoordinatesLeaveBothPixelsAndBoundsUntouched() {
        val invalid = listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)
        for (coordinate in 0..3) {
            for (value in invalid) {
                val axis = floatArrayOf(0f, 0f, 3f, 3f)
                axis[coordinate] = value
                val target = PixelBuffer(2, 1, intArrayOf(red, 0x400000FF))
                val before = target.pixels.copyOf()
                val result = GradientTool.draw(target, axis[0], axis[1], axis[2], axis[3])
                assertFalse(result.changed)
                assertEquals(null, result.bounds)
                assertArrayEquals(before, target.pixels)
            }
        }
    }
}
