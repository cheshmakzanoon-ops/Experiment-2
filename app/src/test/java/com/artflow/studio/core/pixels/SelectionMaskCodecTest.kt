package com.artflow.studio.core.pixels

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/** Save & Load keeps its masks in the document, so the encoded form must round-trip exactly. */
class SelectionMaskCodecTest {
    @Test
    fun everyCoverageValueComesBackUnchanged() {
        val mask = SelectionMask(4, 3)
        for (i in mask.coverage.indices) mask.coverage[i] = (i * 23 and 0xFF).toByte()

        val decoded = requireNotNull(SelectionMaskCodec.decode(SelectionMaskCodec.encode(mask), 4, 3))

        assertEquals(4, decoded.width)
        assertEquals(3, decoded.height)
        assertArrayEquals(mask.coverage, decoded.coverage)
    }

    @Test
    fun aSelectAllMaskIsStillFullAfterRoundTrip() {
        val mask = SelectionMask(8, 8).apply { selectAll() }
        val decoded = requireNotNull(SelectionMaskCodec.decode(SelectionMaskCodec.encode(mask), 8, 8))
        assertEquals(true, decoded.isFull())
    }

    /** Raw 2048 × 2048 coverage is 4 MB; a full-canvas selection must not cost that per save. */
    @Test
    fun aFullCanvasMaskStaysSmallOnDisk() {
        val mask = SelectionMask(2048, 2048).apply { selectAll() }
        val encoded = SelectionMaskCodec.encode(mask)

        assertTrue("Encoded ${encoded.length} characters", encoded.length < 64 * 1024)
        assertEquals(true, requireNotNull(SelectionMaskCodec.decode(encoded, 2048, 2048)).isFull())
    }

    @Test
    fun noisyCoverageStillRoundTrips() {
        val mask = SelectionMask(64, 64)
        for (i in mask.coverage.indices) mask.coverage[i] = (i * 37 + i / 5).toByte()
        val decoded = requireNotNull(SelectionMaskCodec.decode(SelectionMaskCodec.encode(mask), 64, 64))
        assertArrayEquals(mask.coverage, decoded.coverage)
    }

    @Test
    fun aMaskForAnotherCanvasIsRejectedInsteadOfStretched() {
        val encoded = SelectionMaskCodec.encode(SelectionMask(4, 4))
        assertNull(SelectionMaskCodec.decode(encoded, 8, 8))
    }

    @Test
    fun damagedOrForeignTextIsRejected() {
        val encoded = SelectionMaskCodec.encode(SelectionMask(4, 4))
        assertNull("Not Base64", SelectionMaskCodec.decode("not base64 at all", 4, 4))
        assertNull("Truncated payload", SelectionMaskCodec.decode(encoded.take(encoded.length - 4), 4, 4))
        val bytes = Base64.getDecoder().decode(encoded)
        bytes[0] = 'X'.code.toByte()
        assertNull("Foreign header", SelectionMaskCodec.decode(Base64.getEncoder().encodeToString(bytes), 4, 4))
        val garbage = bytes.copyOf()
        garbage[HEADER] = 0x7F
        garbage[HEADER + 1] = 0x7F
        assertNull("Broken deflate stream", SelectionMaskCodec.decode(Base64.getEncoder().encodeToString(garbage), 4, 4))
    }

    private companion object {
        const val HEADER = 12
    }
}
