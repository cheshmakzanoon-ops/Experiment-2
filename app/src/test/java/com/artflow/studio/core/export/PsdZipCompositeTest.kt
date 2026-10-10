package com.artflow.studio.core.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater

class PsdZipCompositeTest {
    private val red = intArrayOf(10, 20, 30, 40)
    private val green = intArrayOf(50, 60, 70, 80)
    private val blue = intArrayOf(90, 100, 110, 120)

    private fun argb(
        r: Int,
        g: Int,
        b: Int,
    ): Int = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    /** Stores each byte as its difference from the byte before it in its row, as ZIP prediction does. */
    private fun predict(plane: IntArray) {
        for (row in 0 until 2) {
            val start = row * 2
            plane[start + 1] = (plane[start + 1] - plane[start]) and 0xFF
        }
    }

    /** A 2 x 2 RGB document whose merged image is one zlib stream, with or without ZIP prediction. */
    private fun zipPsd(predicted: Boolean): ByteArray {
        val data = ByteArrayOutputStream()
        listOf(red, green, blue).forEach { channel ->
            val plane = channel.copyOf()
            if (predicted) predict(plane)
            plane.forEach { data.write(it) }
        }
        val deflater = Deflater()
        deflater.setInput(data.toByteArray())
        deflater.finish()
        val compressed = ByteArray(256)
        val size = deflater.deflate(compressed)
        deflater.end()

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
        short(3) // channels
        int(2) // height
        int(2) // width
        short(8) // depth
        short(3) // colour mode: RGB
        int(0) // colour mode data
        int(0) // image resources
        int(0) // layer and mask information
        short(if (predicted) 3 else 2) // ZIP, with or without prediction
        out.write(compressed, 0, size)
        return out.toByteArray()
    }

    @Test
    fun aZipCompressedMergedImageReadsEveryChannel() {
        for (predicted in listOf(false, true)) {
            val composite = PsdCodec.read(zipPsd(predicted))?.composite
            assertNotNull("predicted=$predicted", composite)
            for (i in 0 until 4) {
                val expected = argb(red[i], green[i], blue[i]).toLong()
                val actual = composite!!.pixels[i].toLong()
                assertEquals("predicted=$predicted pixel=$i", expected, actual)
            }
        }
    }
}
