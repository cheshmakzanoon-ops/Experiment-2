package com.artflow.studio.core.export

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream

class Psd16BitLayerTest {
    private val red = intArrayOf(10, 20, 30, 40)
    private val green = intArrayOf(50, 60, 70, 80)
    private val blue = intArrayOf(90, 100, 110, 120)

    private fun argb(
        r: Int,
        g: Int,
        b: Int,
    ): Int = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    /** One channel as big-endian 16-bit samples: the high byte is the image value, the low byte is noise. */
    private fun samples(values: IntArray): ByteArray {
        val out = ByteArrayOutputStream()
        values.forEach {
            out.write(it)
            out.write(0x5A)
        }
        return out.toByteArray()
    }

    /** Compression code, a byte count per row, then each 2 x 2 row as one PackBits literal run of 4 bytes. */
    private fun rleChannel(values: IntArray): ByteArray {
        val data = samples(values)
        val rows = (0 until 2).map { row -> byteArrayOf(3) + data.copyOfRange(row * 4, row * 4 + 4) }
        val out = ByteArrayOutputStream()
        out.short(RLE)
        rows.forEach { out.short(it.size) }
        rows.forEach { out.write(it) }
        return out.toByteArray()
    }

    /** A 2 x 2 RGB, 16-bit document whose only content is one run-length encoded layer. */
    private fun psd(): ByteArray {
        val channels = listOf(RED to red, GREEN to green, BLUE to blue).map { (id, values) -> id to rleChannel(values) }

        val record = ByteArrayOutputStream()
        record.int(0) // top
        record.int(0) // left
        record.int(2) // bottom
        record.int(2) // right
        record.short(channels.size)
        channels.forEach { (id, data) ->
            record.short(id)
            record.int(data.size)
        }
        record.write("8BIM".toByteArray(Charsets.US_ASCII))
        record.write("norm".toByteArray(Charsets.US_ASCII))
        record.write(255) // opacity
        record.write(0) // clipping
        record.write(0) // flags
        record.write(0) // filler
        record.int(0) // no extra data
        channels.forEach { record.write(it.second) }

        val layerInfo = ByteArrayOutputStream()
        layerInfo.short(1) // layer count
        layerInfo.write(record.toByteArray())

        val out = ByteArrayOutputStream()
        out.write("8BPS".toByteArray(Charsets.US_ASCII))
        out.short(1) // version
        out.write(ByteArray(6))
        out.short(3) // channels
        out.int(2) // height
        out.int(2) // width
        out.short(16) // depth
        out.short(3) // colour mode: RGB
        out.int(0) // colour mode data
        out.int(0) // image resources
        out.int(4 + layerInfo.size()) // layer and mask information
        out.int(layerInfo.size())
        out.write(layerInfo.toByteArray())
        return out.toByteArray()
    }

    @Test
    fun aRunLengthSixteenBitLayerKeepsEachSamplesHighByte() {
        val layers = PsdCodec.read(psd())?.layers.orEmpty()
        assertEquals("one layer", 1L, layers.size.toLong())
        for (i in 0 until 4) {
            val expected = argb(red[i], green[i], blue[i]).toLong()
            assertEquals("pixel=$i", expected, layers.single().pixels.pixels[i].toLong())
        }
    }

    private companion object {
        const val RED = 0
        const val GREEN = 1
        const val BLUE = 2
        const val RLE = 1
    }
}

private fun ByteArrayOutputStream.short(value: Int) {
    write(value ushr 8)
    write(value)
}

private fun ByteArrayOutputStream.int(value: Int) {
    short(value ushr 16)
    short(value)
}
