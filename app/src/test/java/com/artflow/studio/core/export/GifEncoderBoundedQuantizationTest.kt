package com.artflow.studio.core.export

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

class GifEncoderBoundedQuantizationTest {
    @Test
    fun aLargeDetailedFrameKeepsItsDominantColorAfterPaletteReduction() {
        // Nine megapixels fit the app's canvas ceiling. More than 255 opaque colors force
        // median-cut quantization even when the transparent slot is reserved, as in app exports.
        val width = 3000
        val pixels = IntArray(width * width) { -1 }
        repeat(256) { index ->
            pixels[index] = 0xFF000000.toInt() or ((index and 31) shl 3) or ((index shr 5) shl 11)
        }
        val bytes =
            GifEncoder.encode(
                frames = listOf(pixels),
                width = width,
                height = width,
                delaysMs = listOf(100),
                keepTransparency = true,
            )
        val image = requireNotNull(ImageIO.read(ByteArrayInputStream(bytes)))
        assertEquals(width, image.width)
        assertEquals(width, image.height)
        // Histogram reduction drops the bottom three channel bits, but the bright region
        // must not turn black when its per-channel weighted sum exceeds Int.MAX_VALUE.
        assertEquals(0xFFF8F8F8.toInt(), image.getRGB(width / 2, width / 2))
    }

    @Test
    fun boundedLookupRetainsTheExactNearestColorWhenCacheSlotsCollide() {
        // Black and this green share a cache slot but must retain different palette entries.
        // The near-black pixel exceeds the two-color limit and forces histogram reduction.
        val black = 0xFF000000.toInt()
        val green = 0xFF008001.toInt()
        val pixels = intArrayOf(black, green, black or 1, green, black)
        val bytes = GifEncoder.encode(listOf(pixels), pixels.size, 1, listOf(100), maxColors = 2)
        val image = requireNotNull(ImageIO.read(ByteArrayInputStream(bytes)))
        val reducedGreen = 0xFF008000.toInt()
        assertArrayEquals(
            intArrayOf(black, reducedGreen, black, reducedGreen, black),
            image.getRGB(0, 0, pixels.size, 1, null, 0, pixels.size),
        )
    }
}
