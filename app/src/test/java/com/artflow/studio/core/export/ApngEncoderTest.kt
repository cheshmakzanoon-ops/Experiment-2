package com.artflow.studio.core.export

import com.artflow.studio.core.pixels.PixelBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.util.zip.Inflater

class ApngEncoderTest {
    private fun png(color: Int): ByteArray = PngCodec.encode(PixelBuffer(4, 3).also { it.fill(color) })

    private fun chunks(bytes: ByteArray): List<Pair<String, ByteArray>> {
        val buffer = ByteBuffer.wrap(bytes, 8, bytes.size - 8)
        val found = mutableListOf<Pair<String, ByteArray>>()
        while (buffer.remaining() >= 12) {
            val length = buffer.int
            val type = ByteArray(4).also { buffer.get(it) }.decodeToString()
            val data = ByteArray(length).also { buffer.get(it) }
            buffer.int // CRC
            found += type to data
        }
        return found
    }

    @Test fun framesBecomeAnAnimatedPngThatStillShowsTheFirstFrame() {
        val animated = ApngEncoder.encode(listOf(png(-0x10000), png(-0xff0100)), listOf(100, 250))
        val found = chunks(animated)
        assertEquals(listOf("IHDR", "acTL", "fcTL", "IDAT", "fcTL", "fdAT", "IEND"), found.map { it.first })
        // Plain PNG readers ignore the animation and show the first frame from IDAT.
        val inflater = Inflater().apply { setInput(found.first { it.first == "IDAT" }.second) }
        val firstRow = ByteArray(1 + 4 * 4).also { inflater.inflate(it) }
        inflater.end()
        assertEquals(listOf(-1, 0, 0, -1), firstRow.slice(1..4).map { it.toInt() })
        assertTrue(animated.size > png(-0x10000).size)
    }
}
