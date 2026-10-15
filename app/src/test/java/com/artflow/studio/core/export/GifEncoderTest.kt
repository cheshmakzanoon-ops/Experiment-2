package com.artflow.studio.core.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class GifEncoderTest {
    private fun encode(delaysMs: List<Int>): List<Int> {
        val frames = delaysMs.map { IntArray(4) { -0x10000 } }
        return graphicControlDelays(GifEncoder.encode(frames, width = 2, height = 2, delaysMs = delaysMs))
    }

    /** Walks the blocks this encoder writes and returns each graphic-control delay in centiseconds. */
    private fun graphicControlDelays(gif: ByteArray): List<Int> {
        val delays = mutableListOf<Int>()
        // Signature and logical screen descriptor; the encoder writes no global colour table.
        var position = 13
        while (true) {
            when (gif[position].toInt() and 0xFF) {
                0x21 -> {
                    if ((gif[position + 1].toInt() and 0xFF) == 0xF9) {
                        delays += (gif[position + 4].toInt() and 0xFF) or ((gif[position + 5].toInt() and 0xFF) shl 8)
                    }
                    position = skipSubBlocks(gif, position + 2)
                }
                0x2C -> {
                    val packed = gif[position + 9].toInt() and 0xFF
                    var next = position + 10
                    if ((packed and 0x80) != 0) next += 3 * (1 shl ((packed and 0x07) + 1))
                    // The LZW minimum code size byte precedes the image data sub-blocks.
                    position = skipSubBlocks(gif, next + 1)
                }
                0x3B -> return delays
                else -> throw AssertionError("Unexpected GIF block at $position")
            }
        }
    }

    private fun skipSubBlocks(
        gif: ByteArray,
        start: Int,
    ): Int {
        var position = start
        while (true) {
            val size = gif[position].toInt() and 0xFF
            position += 1 + size
            if (size == 0) return position
        }
    }

    @Test fun roundedCumulativeTimesKeepTheTotalLengthOfAClip() {
        val delays = encode(List(12) { 83 })
        assertEquals(12, delays.size)
        // 12 x 83 ms is 996 ms. Truncating each frame to 80 ms would have shortened the clip to 960 ms.
        assertTrue(abs(delays.sum() * 10 - 996) <= 5)
    }

    @Test fun eachHoldIsTheRoundedGapBetweenCumulativeTimes() {
        assertEquals(listOf(10, 25, 3), encode(listOf(100, 250, 33)))
    }

    @Test fun holdsBelowTheGifMinimumStayAtTwoCentiseconds() {
        assertEquals(listOf(2, 2, 2), encode(listOf(16, 16, 16)))
    }
}
