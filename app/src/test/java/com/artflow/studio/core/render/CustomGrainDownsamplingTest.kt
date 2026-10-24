package com.artflow.studio.core.render

import com.artflow.studio.core.pixels.PixelBuffer
import org.junit.Assert.assertEquals
import org.junit.Test

class CustomGrainDownsamplingTest {
    @Test fun nonDivisibleReductionIncludesTheLastSourceCorner() {
        val image = PixelBuffer.filled(3, 3, BLACK)
        image.pixels[8] = WHITE

        // The final white source pixel occupies 1 / 2.25 of the last destination box.
        assertEquals(listOf(0, 0, 0, 113), values(image, 2))
    }

    @Test fun aSourcePixelCrossingTheBoundaryContributesToBothBoxes() {
        val image = PixelBuffer.filled(3, 3, BLACK)
        for (y in 0 until 3) image.pixels[y * 3 + 1] = WHITE

        // Each 1.5-pixel-wide box contains half of the white middle column: coverage 1/3.
        assertEquals(List(4) { 85 }, values(image, 2))
    }

    @Test fun nonBinaryRatioIncludesTheEntireLastColumn() {
        val image = PixelBuffer.filled(5, 5, BLACK)
        for (y in 0 until 5) image.pixels[y * 5 + 4] = WHITE

        // The right-hand box is 5/3 pixels wide and contains one white pixel: coverage 3/5.
        assertEquals(listOf(0, 0, 153, 0, 0, 153, 0, 0, 153), values(image, 3))
    }

    @Test fun constantCoverageRetainsLuminanceAndAlpha() {
        val cases =
            listOf(
                BLACK to 0,
                WHITE to 255,
                0xFF808080.toInt() to 128,
                0x80FFFFFF.toInt() to 128,
                0x00FFFFFF to 0,
                0xFFFF0000.toInt() to 54,
            )
        for ((color, expected) in cases) {
            assertEquals("color ${color.toUInt()}", List(9) { expected }, values(PixelBuffer.filled(5, 5, color), 3))
        }
    }

    @Test fun centeredRectangleCropIgnoresOutsidePixelsAndWeightsAlpha() {
        val image = PixelBuffer.filled(7, 5, BLACK)
        for (y in 0 until 5) {
            image.pixels[y * 7] = WHITE
            image.pixels[y * 7 + 6] = WHITE
            image.pixels[y * 7 + 5] = 0x80FFFFFF.toInt()
        }
        image.pixels[1 * 7 + 2] = 0x00FFFFFF

        // Crop columns 1..5. Its last column covers 3/5 of the right box at alpha 128.
        assertEquals(listOf(0, 0, 77, 0, 0, 77, 0, 0, 77), values(image, 3))
    }

    @Test fun divisibleReductionAndNoResizeKeepTheirExactValues() {
        val levels = listOf(0, 32, 64, 96, 128, 160, 192, 224, 16, 48, 80, 112, 144, 176, 208, 240)
        val image = PixelBuffer(4, 4, levels.map(::grey).toIntArray())

        assertEquals(levels, values(image, 4))
        assertEquals(listOf(80, 144, 96, 160), values(image, 2))
    }

    @Test fun upsamplingPreservesTheExistingNearestPixelMapping() {
        val image = PixelBuffer(2, 2, intArrayOf(grey(0), grey(64), grey(128), grey(255)))
        val top = listOf(0, 0, 0, 64, 64)
        val bottom = listOf(128, 128, 128, 255, 255)

        assertEquals(top + top + top + bottom + bottom, values(image, 5))
    }

    @Test fun photoContrastNormalizationStillFollowsResampling() {
        val image = PixelBuffer.filled(5, 5, grey(100))
        for (y in 0 until 5) image.pixels[y * 5 + 4] = grey(120)

        assertEquals(listOf(100, 100, 112, 100, 100, 112, 100, 100, 112), values(image, 3))
        val photo = CustomGrains.tileFrom(image, size = 3)
        assertEquals(listOf(0, 0, 255, 0, 0, 255, 0, 0, 255), photo.values.map { it.toInt() and 255 })
    }

    private fun values(
        image: PixelBuffer,
        size: Int,
    ): List<Int> = CustomGrains.tileFrom(image, size = size, normalizeContrast = false).values.map { it.toInt() and 255 }

    private fun grey(value: Int): Int = BLACK or (value shl 16) or (value shl 8) or value

    companion object {
        private const val BLACK = 0xFF000000.toInt()
        private const val WHITE = -1
    }
}
