package com.artflow.studio.data.renderer

import com.artflow.studio.core.pixels.PixelBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ImageOrientationTest {
    @Test
    fun allEightOrientationsMatchIndependentRectangularPixelLayouts() {
        val source = PixelBuffer(3, 2, intArrayOf(1, 2, 3, 4, 5, 6))
        val expected =
            listOf(
                intArrayOf(1, 2, 3, 4, 5, 6),
                intArrayOf(3, 2, 1, 6, 5, 4),
                intArrayOf(6, 5, 4, 3, 2, 1),
                intArrayOf(4, 5, 6, 1, 2, 3),
                intArrayOf(1, 4, 2, 5, 3, 6),
                intArrayOf(4, 1, 5, 2, 6, 3),
                intArrayOf(6, 3, 5, 2, 4, 1),
                intArrayOf(3, 6, 2, 5, 1, 4),
            )
        expected.forEachIndexed { index, pixels ->
            val orientation = index + 1
            val result = ImageOrientation.apply(source, orientation)
            assertEquals(if (orientation >= 5) 2 else 3, result.width)
            assertEquals(if (orientation >= 5) 3 else 2, result.height)
            assertEquals(result.width to result.height, ImageOrientation.dimensions(3, 2, orientation))
            assertArrayEquals("EXIF orientation $orientation", pixels, result.pixels)
        }
        assertArrayEquals(intArrayOf(1, 2, 3, 4, 5, 6), source.pixels)
    }

    @Test
    fun absentOrUnknownOrientationDoesNotAllocateOrChangePixels() {
        val source = PixelBuffer(1, 2, intArrayOf(7, 9))
        listOf(-1, 0, 1, 9, Int.MAX_VALUE).forEach { orientation ->
            assertSame(source, ImageOrientation.apply(source, orientation))
            assertEquals(1 to 2, ImageOrientation.dimensions(1, 2, orientation))
        }
    }

    @Test
    fun onePixelWideImagesKeepEveryTransparentSampleWhenMirroredAndRotated() {
        val source = PixelBuffer(1, 3, intArrayOf(0x00112233, 0x80112233.toInt(), -1))
        val result = ImageOrientation.apply(source, 6)
        assertEquals(3, result.width)
        assertEquals(1, result.height)
        assertArrayEquals(intArrayOf(-1, 0x80112233.toInt(), 0x00112233), result.pixels)
    }
}
