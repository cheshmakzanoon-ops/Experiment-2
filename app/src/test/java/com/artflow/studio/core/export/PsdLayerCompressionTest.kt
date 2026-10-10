package com.artflow.studio.core.export

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater

class PsdLayerCompressionTest {
    private val red = intArrayOf(10, 20, 30, 40)
    private val green = intArrayOf(50, 60, 70, 80)
    private val blue = intArrayOf(90, 100, 110, 120)

    private fun argb(
        r: Int,
        g: Int,
        b: Int,
    ): Int = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    /**
     * One 2 x 2 channel: 8-bit samples are the values; 16-bit samples carry the value as the high byte and a
     * low byte that falls as the value rises, so each predicted difference borrows from the high byte.
     */
    private fun samples(
        values: IntArray,
        depth: Int,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        values.forEach {
            out.write(it)
            if (depth == 16) out.write(0xFF - it)
        }
        return out.toByteArray()
    }

    /** ZIP prediction stores each sample as its difference from the sample before it in its row, modulo the sample size. */
    private fun predict(
        plane: ByteArray,
        bytesPerSample: Int,
    ): ByteArray {
        val out = plane.copyOf()
        val mask = (1 shl (8 * bytesPerSample)) - 1
        for (row in 0 until 2) {
            val start = row * 2 * bytesPerSample
            val first = bigEndian(plane, start, bytesPerSample)
            val second = bigEndian(plane, start + bytesPerSample, bytesPerSample)
            val difference = (second - first) and mask
            for (b in 0 until bytesPerSample) {
                out[start + bytesPerSample + b] = (difference ushr (8 * (bytesPerSample - 1 - b))).toByte()
            }
        }
        return out
    }

    private fun bigEndian(
        bytes: ByteArray,
        start: Int,
        count: Int,
    ): Int {
        var value = 0
        for (b in 0 until count) value = (value shl 8) or (bytes[start + b].toInt() and 0xFF)
        return value
    }

    /** One channel's data as the layer record stores it: its compression code, then its pixels. */
    private fun channelData(
        values: IntArray,
        depth: Int,
        compression: Int,
    ): ByteArray {
        val rowBytes = 2 * (depth / 8)
        val data = samples(values, depth)
        val out = ByteArrayOutputStream()
        out.short(compression)
        when (compression) {
            RAW -> out.write(data)

            RLE -> {
                // Each row is one PackBits literal run: a count of its length minus one, then the bytes.
                val rows =
                    (0 until 2).map { row ->
                        byteArrayOf((rowBytes - 1).toByte()) + data.copyOfRange(row * rowBytes, (row + 1) * rowBytes)
                    }
                rows.forEach { out.short(it.size) }
                rows.forEach { out.write(it) }
            }

            else -> {
                val source = if (compression == ZIP_PREDICTION) predict(data, depth / 8) else data
                val deflater = Deflater()
                deflater.setInput(source)
                deflater.finish()
                val buffer = ByteArray(256)
                val size = deflater.deflate(buffer)
                deflater.end()
                out.write(buffer, 0, size)
            }
        }
        return out.toByteArray()
    }

    /** A 2 x 2 RGB document holding one layer whose channels use the given depth and compression. */
    private fun psd(
        depth: Int,
        compression: Int,
    ): ByteArray {
        val channels =
            listOf(RED to red, GREEN to green, BLUE to blue).map { (id, values) ->
                id to channelData(values, depth, compression)
            }

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
        out.short(depth)
        out.short(3) // colour mode: RGB
        out.int(0) // colour mode data
        out.int(0) // image resources
        out.int(4 + layerInfo.size()) // layer and mask information
        out.int(layerInfo.size())
        out.write(layerInfo.toByteArray())
        return out.toByteArray()
    }

    private fun assertLayerPixels(
        depth: Int,
        compression: Int,
    ) {
        val label = "depth=$depth compression=$compression"
        val layers = PsdCodec.read(psd(depth, compression))?.layers.orEmpty()
        assertEquals("$label: one layer", 1L, layers.size.toLong())
        val pixels = layers.single().pixels.pixels
        for (i in 0 until 4) {
            val expected = argb(red[i], green[i], blue[i]).toLong()
            assertEquals("$label pixel=$i", expected, pixels[i].toLong())
        }
    }

    @Test
    fun eightBitRawLayersReadEveryChannel() {
        assertLayerPixels(depth = 8, compression = RAW)
    }

    @Test
    fun eightBitRunLengthLayersReadEveryChannel() {
        assertLayerPixels(depth = 8, compression = RLE)
    }

    @Test
    fun eightBitZipLayersReadEveryChannel() {
        assertLayerPixels(depth = 8, compression = ZIP)
    }

    @Test
    fun eightBitPredictedZipLayersUndoThePrediction() {
        assertLayerPixels(depth = 8, compression = ZIP_PREDICTION)
    }

    @Test
    fun sixteenBitRawLayersKeepEachSamplesHighByte() {
        assertLayerPixels(depth = 16, compression = RAW)
    }

    @Test
    fun sixteenBitRunLengthLayersKeepEachSamplesHighByte() {
        assertLayerPixels(depth = 16, compression = RLE)
    }

    @Test
    fun sixteenBitZipLayersKeepEachSamplesHighByte() {
        assertLayerPixels(depth = 16, compression = ZIP)
    }

    @Test
    fun sixteenBitPredictedZipLayersUndoThePredictionOnWholeSamples() {
        assertLayerPixels(depth = 16, compression = ZIP_PREDICTION)
    }

    private companion object {
        const val RED = 0
        const val GREEN = 1
        const val BLUE = 2
        const val RAW = 0
        const val RLE = 1
        const val ZIP = 2
        const val ZIP_PREDICTION = 3
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
