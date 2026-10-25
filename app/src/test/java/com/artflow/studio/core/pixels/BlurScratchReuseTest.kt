package com.artflow.studio.core.pixels

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Pixel contracts preserved while reusing full-canvas blur scratch planes. */
class BlurScratchReuseTest {
    @Test
    fun transparentHiddenRgbNeverBleedsIntoABoxBlur() {
        val source = PixelBuffer(3, 1)
        source.pixels[0] = 0x0000FF00
        source.pixels[1] = 0x80AA4411.toInt()
        source.pixels[2] = 0x00FF00FF
        val blurred = ImageFilters.boxBlur(source, 1)

        blurred.pixels.forEach { pixel ->
            assertTrue("alpha: ${pixel ushr 24}", (pixel ushr 24) in 42..44)
            assertTrue(abs(((pixel ushr 16) and 255) - 170) <= 1)
            assertTrue(abs(((pixel ushr 8) and 255) - 68) <= 1)
            assertTrue(abs((pixel and 255) - 17) <= 1)
        }
    }

    @Test
    fun halfOpaquePigmentsMixByPremultipliedCoverage() {
        val source = PixelBuffer(2, 1)
        source.pixels[0] = 0x80FF0000.toInt()
        source.pixels[1] = 0x800000FF.toInt()
        val blurred = ImageFilters.boxBlur(source, 1)

        assertColourNear(blurred.pixels[0], alpha = 128, red = 170, green = 0, blue = 85)
        assertColourNear(blurred.pixels[1], alpha = 128, red = 85, green = 0, blue = 170)
    }

    @Test
    fun uniformColourRemainsUniformUnderGaussianBlur() {
        val source = PixelBuffer.filled(9, 7, 0x79D06020)
        val blurred = ImageFilters.gaussianBlur(source, 1.2f)
        blurred.pixels.forEach { pixel ->
            assertColourNear(pixel, alpha = 0x79, red = 0xD0, green = 0x60, blue = 0x20)
        }
    }

    private fun assertColourNear(
        pixel: Int,
        alpha: Int,
        red: Int,
        green: Int,
        blue: Int,
    ) {
        assertTrue("alpha: ${pixel ushr 24}", abs((pixel ushr 24) - alpha) <= 1)
        assertTrue("red", abs(((pixel ushr 16) and 255) - red) <= 1)
        assertTrue("green", abs(((pixel ushr 8) and 255) - green) <= 1)
        assertTrue("blue", abs((pixel and 255) - blue) <= 1)
    }
}
