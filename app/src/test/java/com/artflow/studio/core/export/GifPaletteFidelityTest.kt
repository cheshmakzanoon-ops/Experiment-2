package com.artflow.studio.core.export

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

/** Independent decoding verifies colour fidelity and the reserved transparency slot. */
class GifPaletteFidelityTest {
    private fun decoded(
        pixels: IntArray,
        transparency: Boolean = false,
        maxColors: Int = 256,
    ): IntArray {
        val bytes =
            GifEncoder.encode(
                listOf(pixels),
                pixels.size,
                1,
                listOf(100),
                keepTransparency = transparency,
                maxColors = maxColors,
            )
        val image = requireNotNull(ImageIO.read(ByteArrayInputStream(bytes)))
        return image.getRGB(0, 0, pixels.size, 1, null, 0, pixels.size)
    }

    @Test
    fun flatColoursKeepAllEightChannelBitsIncludingNeighboursInTheSameHistogramCell() {
        val pixels =
            intArrayOf(
                0xFFFF0000.toInt(),
                0xFF00FF00.toInt(),
                0xFF0000FF.toInt(),
                -1,
                0xFF010203.toInt(),
                0xFF060507.toInt(),
                0xFF000000.toInt(),
                0xFFFEFDFC.toInt(),
            )
        assertArrayEquals(pixels, decoded(pixels))
    }

    @Test
    fun exactPaletteUsesEveryAvailableSlotWithAndWithoutTransparency() {
        val opaque = IntArray(256) { 0xFF330000.toInt() or it }
        assertArrayEquals(opaque, decoded(opaque))
        val transparent = opaque.copyOf().also { it[it.lastIndex] = 0 }
        assertArrayEquals(transparent, decoded(transparent, transparency = true))
    }

    @Test
    fun aReducedPaletteReservesItsTransparentSlotBeforeQuantizingOpaqueColours() {
        val decoded = decoded(intArrayOf(0, 0xFFFF0000.toInt(), 0xFF0000FF.toInt()), transparency = true, maxColors = 2)
        assertEquals(0, decoded[0] ushr 24)
        assertEquals(255, decoded[1] ushr 24)
        assertEquals(255, decoded[2] ushr 24)
        assertEquals(decoded[1], decoded[2])
    }
}
