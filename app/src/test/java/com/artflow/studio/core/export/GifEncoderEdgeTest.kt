package com.artflow.studio.core.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GifEncoderEdgeTest {
    /** Offset of the graphics control extension (21 F9 04). The image descriptor follows it. */
    private fun controlAt(gif: ByteArray): Int =
        (0 until gif.size - 3).first { i ->
            gif[i] == 0x21.toByte() && gif[i + 1] == 0xF9.toByte() && gif[i + 2] == 0x04.toByte()
        }

    /** The first frame's local colour table, read straight from the encoded bytes. */
    private fun firstFramePalette(gif: ByteArray): List<IntArray> {
        // The image descriptor is 8 bytes after the control extension and its table starts 10 bytes into it.
        val table = controlAt(gif) + 8 + 10
        return (0 until 256).map { i ->
            intArrayOf(
                gif[table + 3 * i].toInt() and 0xFF,
                gif[table + 3 * i + 1].toInt() and 0xFF,
                gif[table + 3 * i + 2].toInt() and 0xFF,
            )
        }
    }

    @Test
    fun aTransparentGifEdgeKeepsItsColourInsteadOfTakingTheMatte() {
        // A mostly opaque blue pixel next to a fully transparent one. Blending the blue onto the white matte
        // would leave only a tinted blue in the palette, so the palette must hold a pure blue entry.
        val mostlyOpaque = 0xC80000FF.toInt()
        val clear = 0x00000000
        val gif = GifEncoder.encode(listOf(intArrayOf(mostlyOpaque, clear)), 2, 1, listOf(100), keepTransparency = true)

        val flags = gif[controlAt(gif) + 3].toInt() and 0xFF
        assertEquals("transparency is flagged", 1L, (flags and 1).toLong())

        val hasPureBlue = firstFramePalette(gif).any { it[0] < 16 && it[1] < 16 && it[2] > 240 }
        assertTrue("the partly opaque edge keeps its blue", hasPureBlue)
    }
}
