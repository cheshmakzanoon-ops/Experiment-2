package com.artflow.studio.core.export

import com.artflow.studio.core.pixels.PixelBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import kotlin.random.Random

class PngCodecTest {
    @Test
    fun pngPreservesEveryAlphaAndRawColorChannel() {
        val pixels = IntArray(256 * 16) { index -> (index % 256 shl 24) or (Random(index).nextInt() and 0xFFFFFF) }
        val original = PixelBuffer(256, 16, pixels)
        val encoded = PngCodec.encode(original)
        // Android's compile-time boot classpath omits java.desktop, but the host JVM has it.
        // Keep the independent JDK decoder rather than verifying our encoder with itself.
        val decoded =
            Class
                .forName("javax.imageio.ImageIO")
                .getMethod("read", java.io.InputStream::class.java)
                .invoke(null, ByteArrayInputStream(encoded))
        val imageClass = Class.forName("java.awt.image.BufferedImage")
        assertEquals(original.width, imageClass.getMethod("getWidth").invoke(decoded))
        assertEquals(original.height, imageClass.getMethod("getHeight").invoke(decoded))
        val integer = Int::class.javaPrimitiveType!!
        val restored =
            imageClass
                .getMethod("getRGB", integer, integer, integer, integer, IntArray::class.java, integer, integer)
                .invoke(decoded, 0, 0, original.width, original.height, null, 0, original.width) as IntArray
        assertArrayEquals(original.pixels, restored)
        assertArrayEquals(encoded, PngCodec.encode(original))
    }

    @Test(expected = IllegalArgumentException::class)
    fun oversizedDimensionsFailBeforeIntegerOverflowAllocation() {
        PixelBuffer(65536, 65536)
    }

    @Test(expected = IllegalArgumentException::class)
    fun negativeDimensionsFailBeforeAllocation() {
        PixelBuffer(-1, 100)
    }
}
