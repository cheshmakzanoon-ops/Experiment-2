package com.artflow.studio.core.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.ByteArrayOutputStream

class PsdGrayscaleTest {
    /** A one-channel, 8-bit grayscale PSD with raw image data and no resources, layers or colour data. */
    private fun grayscalePsd(
        width: Int,
        height: Int,
        grays: IntArray,
    ): ByteArray {
        val out = ByteArrayOutputStream()

        fun short(value: Int) {
            out.write(value ushr 8)
            out.write(value)
        }

        fun int(value: Int) {
            short(value ushr 16)
            short(value)
        }
        out.write("8BPS".toByteArray(Charsets.US_ASCII))
        short(1) // version
        out.write(ByteArray(6))
        short(1) // channels: gray only
        int(height)
        int(width)
        short(8) // depth
        short(1) // colour mode: grayscale
        int(0) // colour mode data
        int(0) // image resources
        int(0) // layer and mask information
        short(0) // raw image data
        grays.forEach { out.write(it) }
        return out.toByteArray()
    }

    @Test
    fun aGrayscaleDocumentImportsAsGreysNotRed() {
        val bytes = grayscalePsd(width = 2, height = 1, grays = intArrayOf(0x20, 0xE0))
        val composite = PsdCodec.read(bytes)?.composite
        assertNotNull(composite)
        assertEquals(0xFF202020.toInt(), composite!!.pixels[0])
        assertEquals(0xFFE0E0E0.toInt(), composite.pixels[1])
    }
}
