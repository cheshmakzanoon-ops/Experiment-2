package com.artflow.studio.core.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import javax.imageio.ImageIO

class ApngEncoderTest {
    private fun png(color: Int): ByteArray {
        val image = BufferedImage(4, 3, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until 3) for (x in 0 until 4) image.setRGB(x, y, color)
        return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    }

    private fun chunkTypes(bytes: ByteArray): List<String> {
        val buffer = ByteBuffer.wrap(bytes, 8, bytes.size - 8)
        val types = mutableListOf<String>()
        while (buffer.remaining() >= 12) {
            val length = buffer.int
            val type = ByteArray(4).also { buffer.get(it) }.decodeToString()
            buffer.position(buffer.position() + length + 4)
            types += type
        }
        return types
    }

    @Test fun framesBecomeAnAnimatedPngThatStillShowsTheFirstFrame() {
        val animated = ApngEncoder.encode(listOf(png(-0x10000), png(-0xff0100)), listOf(100, 250))
        val types = chunkTypes(animated)
        assertEquals(listOf("IHDR", "acTL", "fcTL", "IDAT", "fcTL", "fdAT", "IEND"), types)
        // Plain PNG readers ignore the animation and show the first frame.
        val first = ImageIO.read(animated.inputStream())
        assertEquals(4, first.width)
        assertEquals(-0x10000, first.getRGB(1, 1))
        assertTrue(animated.size > png(-0x10000).size)
    }
}
