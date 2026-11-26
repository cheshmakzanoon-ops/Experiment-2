package com.artflow.studio.core.export

import com.artflow.studio.core.color.ColorProfile
import com.artflow.studio.core.pixels.PixelBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.util.zip.CRC32
import kotlin.random.Random

class ApngMetadataRegressionTest {
    @Test
    fun pngCodecResolutionSurvivesAnimationWrapping() {
        val frames =
            listOf(-0x10000, -0xffff01).map { color ->
                PngCodec.encode(PixelBuffer.filled(4, 3, color), dpi = 300)
            }
        val output = chunks(ApngEncoder.encode(frames, listOf(16, 10_000), loops = 1))

        assertEquals(1, output.count { it.first == "pHYs" })
        val density = output.single { it.first == "pHYs" }.second
        assertArrayEquals(chunks(frames.first()).single { it.first == "pHYs" }.second, density)
        val values = ByteBuffer.wrap(density)
        assertEquals(11_811, values.int)
        assertEquals(11_811, values.int)
        assertEquals(1, values.get().toInt())
        assertTrue(output.indexOfFirst { it.first == "pHYs" } < output.indexOfFirst { it.first == "IDAT" })
    }

    @Test
    fun pngCodecColorProfileAndResolutionAreInheritedOnceFromTheFirstFrame() {
        val frames =
            listOf(-0x10000, -0xff0100).map { color ->
                PngCodec.encode(PixelBuffer.filled(4, 3, color), dpi = 144, profile = ColorProfile.DISPLAY_P3)
            }
        val source = chunks(frames.first())
        val output = chunks(ApngEncoder.encode(frames, listOf(80, 270)))

        assertEquals(
            listOf("IHDR", "iCCP", "pHYs", "acTL", "fcTL", "IDAT", "fcTL", "fdAT", "IEND"),
            output.map { it.first },
        )
        for (type in listOf("iCCP", "pHYs")) {
            assertArrayEquals(source.single { it.first == type }.second, output.single { it.first == type }.second)
        }
        val animation = ByteBuffer.wrap(output.single { it.first == "acTL" }.second)
        assertEquals(2, animation.int)
        assertEquals(0, animation.int)
    }

    @Test
    fun metadataDoesNotDisturbMultipleDataChunksOrFullCanvasFrameControls() {
        val random = Random(67)
        val frames =
            List(2) {
                PngCodec.encode(PixelBuffer(128, 128, IntArray(128 * 128) { random.nextInt() }), dpi = 300)
            }
        val output = chunks(ApngEncoder.encode(frames, listOf(16, 10_000), loops = 1))
        val controls = output.filter { it.first == "fcTL" || it.first == "fdAT" }
        assertEquals(controls.indices.toList(), controls.map { ByteBuffer.wrap(it.second).int })
        for ((index, frame) in frames.withIndex()) {
            val inputData = chunks(frame).filter { it.first == "IDAT" }.map { it.second }
            val type = if (index == 0) "IDAT" else "fdAT"
            val outputData = output.filter { it.first == type }.map { it.second }
            assertTrue(inputData.size > 1)
            assertEquals(inputData.size, outputData.size)
            inputData.indices.forEach { chunk ->
                val actual = if (index == 0) outputData[chunk] else outputData[chunk].copyOfRange(4, outputData[chunk].size)
                assertArrayEquals(inputData[chunk], actual)
            }
        }
        val animation = ByteBuffer.wrap(output.single { it.first == "acTL" }.second)
        assertEquals(2, animation.int)
        assertEquals(1, animation.int)
        output.filter { it.first == "fcTL" }.forEachIndexed { index, (_, bytes) ->
            val control = ByteBuffer.wrap(bytes)
            control.int // Sequence numbers were checked across both control and data chunks above.
            assertEquals(128, control.int)
            assertEquals(128, control.int)
            assertEquals(0, control.int)
            assertEquals(0, control.int)
            assertEquals(if (index == 0) 16 else 10_000, control.short.toInt() and 0xFFFF)
            assertEquals(1000, control.short.toInt() and 0xFFFF)
            assertEquals(0, control.get().toInt()) // No disposal.
            assertEquals(0, control.get().toInt()) // Source replacement, including transparent pixels.
            assertEquals(0, control.remaining())
        }
    }

    private fun chunks(bytes: ByteArray): List<Pair<String, ByteArray>> {
        assertArrayEquals(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10), bytes.copyOfRange(0, 8))
        val input = ByteBuffer.wrap(bytes, 8, bytes.size - 8)
        val found = mutableListOf<Pair<String, ByteArray>>()
        while (input.hasRemaining()) {
            assertTrue(input.remaining() >= 12)
            val length = input.int
            assertTrue(length >= 0 && length <= input.remaining() - 8)
            val type = ByteArray(4).also { input.get(it) }
            val data = ByteArray(length).also { input.get(it) }
            val crc =
                CRC32().apply {
                    update(type)
                    update(data)
                }
            assertEquals(crc.value.toInt(), input.int)
            found += type.decodeToString() to data
        }
        assertEquals("IEND", found.last().first)
        assertEquals(0, found.last().second.size)
        return found
    }
}
