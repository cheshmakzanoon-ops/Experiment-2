package com.artflow.studio.core.export

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Random
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
        val decoded = firstFrame(GifEncoder.encode(listOf(pixels), width, height, listOf(100)))
        assertEquals(pixels.size, decoded.size)
        assertEquals(0, pixels.indices.count { !close(decoded[it], pixels[it]) })
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

    /** Palette quantisation may move a colour by a few levels; a misread code lands far away. */
    private fun close(
        a: Int,
        b: Int,
    ): Boolean = (0..16 step 8).all { shift -> abs(((a shr shift) and 0xFF) - ((b shr shift) and 0xFF)) <= TOLERANCE }

    /** The first frame's colours, decoded the way GIF readers do. */
    private fun firstFrame(gif: ByteArray): IntArray {
        fun u8(i: Int) = gif[i].toInt() and 0xFF
        var at = 13 + if (u8(10) and 0x80 != 0) 3 * (2 shl (u8(10) and 7)) else 0
        while (u8(at) == 0x21) {
            at += 2
            while (u8(at) != 0) at += u8(at) + 1
            at++
        }
        check(u8(at) == 0x2C) { "No image descriptor" }
        val packed = u8(at + 9)
        at += 10
        val palette =
            IntArray(2 shl (packed and 7)) {
                val c = at + 3 * it
                0xFF000000.toInt() or (u8(c) shl 16) or (u8(c + 1) shl 8) or u8(c + 2)
            }
        if (packed and 0x80 != 0) at += 3 * palette.size
        val minCodeSize = u8(at++)
        val data = java.io.ByteArrayOutputStream()
        while (u8(at) != 0) {
            data.write(gif, at + 1, u8(at))
            at += u8(at) + 1
        }
        return lzwDecode(data.toByteArray(), minCodeSize).map { palette[it] }.toIntArray()
    }

    private fun lzwDecode(
        bytes: ByteArray,
        minCodeSize: Int,
    ): List<Int> {
        val clear = 1 shl minCodeSize
        val end = clear + 1
        val table = ArrayList<List<Int>>()

        fun reset() {
            table.clear()
            for (i in 0 until clear) table += listOf(i)
            table += emptyList<Int>()
            table += emptyList<Int>()
        }
        reset()
        var size = minCodeSize + 1
        var bit = 0
        var previous: List<Int>? = null
        val out = ArrayList<Int>()
        while (bit + size <= bytes.size * 8) {
            var code = 0
            for (k in 0 until size) {
                val b = bit + k
                code = code or (((bytes[b / 8].toInt() shr (b % 8)) and 1) shl k)
            }
            bit += size
            when {
                code == clear -> {
                    reset()
                    size = minCodeSize + 1
                    previous = null
                }
                code == end -> return out
                else -> {
                    val entry = if (code < table.size) table[code] else previous!! + previous.first()
                    out += entry
                    if (previous != null && table.size < MAX_CODES) table += previous + entry.first()
                    // Readers widen once the next code to be added would not fit.
                    if (table.size == (1 shl size) && size < MAX_CODE_SIZE) size++
                    previous = entry
                }
            }
        }
        return out
    }

    private companion object {
        const val TOLERANCE = 12
        const val MAX_CODES = 4096
        const val MAX_CODE_SIZE = 12
    }
}
