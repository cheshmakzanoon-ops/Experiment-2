package com.artflow.studio.core.export

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.Random
import javax.imageio.ImageIO
import kotlin.math.abs

class GifEncoderTest {
    @Test
    fun aDecoderReadsBackEveryPixel() {
        val random = Random(3)
        val colours = IntArray(16) { 0xFF000000.toInt() or random.nextInt(0x1000000) }
        val width = 400
        val height = 400
        // Random pixels fill the LZW dictionary and force several resets.
        val pixels = IntArray(width * height) { colours[random.nextInt(colours.size)] }
        val gif = GifEncoder.encode(listOf(pixels), width, height, listOf(100))
        val decoded = ImageIO.read(ByteArrayInputStream(gif))
        var wrong = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (!close(decoded.getRGB(x, y), pixels[y * width + x])) wrong++
            }
        }
        assertEquals(0, wrong)
    }

    /** Palette quantisation may move a colour by a few levels; a misread code lands far away. */
    private fun close(
        a: Int,
        b: Int,
    ): Boolean = (0..16 step 8).all { shift -> abs(((a shr shift) and 0xFF) - ((b shr shift) and 0xFF)) <= TOLERANCE }

    private companion object {
        const val TOLERANCE = 12
    }

    @Test
    fun framesAreClearedToTheBackgroundBeforeTheNext() {
        val gif = GifEncoder.encode(listOf(IntArray(4), IntArray(4)), 2, 2, listOf(100, 100), keepTransparency = true)
        // Graphics control extension: 21 F9 04 <packed>; disposal method sits in bits 2-4.
        val packed =
            (0 until gif.size - 3)
                .first { gif[it] == 0x21.toByte() && gif[it + 1] == 0xF9.toByte() && gif[it + 2] == 0x04.toByte() }
                .let { gif[it + 3].toInt() and 0xFF }
        assertEquals(2, (packed shr 2) and 0x07)
    }
}
