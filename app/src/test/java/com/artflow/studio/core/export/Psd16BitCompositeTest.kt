package com.artflow.studio.core.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater

class Psd16BitCompositeTest {
    private val red = intArrayOf(10, 20, 30, 40)
    private val green = intArrayOf(50, 60, 70, 80)
    private val blue = intArrayOf(90, 100, 110, 120)

    private fun argb(
        r: Int,
        g: Int,
        b: Int,
    ): Int = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    /**
     * One channel as big-endian 16-bit samples: the high byte is the image value, and the low byte falls as the
     * value rises, so predicted differences borrow from the high byte.
     */
    private fun plane(values: IntArray): ByteArray {
        val out = ByteArrayOutputStream()
        values.forEach {
            out.write(it)
            out.write(0xFF - it)
        }
        return out.toByteArray()
    }

    /** One channel with ZIP prediction: each row keeps its first 16-bit sample, then stores the difference from it. */
    private fun predictedPlane(values: IntArray): ByteArray {
        val words = values.map { (it shl 8) or (0xFF - it) }
        val out = ByteArrayOutputStream()
        for (row in 0 until 2) {
            val first = words[row * 2]
            val difference = (words[row * 2 + 1] - first) and 0xFFFF
            out.write(first ushr 8)
            out.write(first and 0xFF)
            out.write(difference ushr 8)
            out.write(difference and 0xFF)
        }
        return out.toByteArray()
    }

    private val planes = listOf(plane(red), plane(green), plane(blue))

    /** A 2 x 2 RGB, 16-bit document with the given compression and merged-image body. */
    private fun psd(
        compression: Int,
        body: ByteArray,
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
        short(3) // channels
        int(2) // height
        int(2) // width
        short(16) // depth
        short(3) // colour mode: RGB
        int(0) // colour mode data
        int(0) // image resources
        int(0) // layer and mask information
        short(compression)
        out.write(body)
        return out.toByteArray()
    }

    private fun rawBody(): ByteArray = planes.fold(ByteArray(0)) { acc, plane -> acc + plane }

    /** Each row is one PackBits literal run: a count of its length minus one, then the bytes. Rows are 4 bytes. */
    private fun rleBody(): ByteArray {
        val rows = planes.flatMap { plane -> (0 until 2).map { row -> byteArrayOf(3) + plane.copyOfRange(row * 4, row * 4 + 4) } }
        val out = ByteArrayOutputStream()
        rows.forEach { row ->
            out.write(0)
            out.write(row.size)
        }
        rows.forEach { out.write(it) }
        return out.toByteArray()
    }

    private fun zipBody(): ByteArray = deflated(rawBody())

    /** The planes as one zlib stream, each with ZIP prediction applied. */
    private fun predictedZipBody(): ByteArray {
        val data = listOf(red, green, blue).fold(ByteArray(0)) { acc, values -> acc + predictedPlane(values) }
        return deflated(data)
    }

    private fun deflated(data: ByteArray): ByteArray {
        val deflater = Deflater()
        deflater.setInput(data)
        deflater.finish()
        val compressed = ByteArray(256)
        val size = deflater.deflate(compressed)
        deflater.end()
        return compressed.copyOf(size)
    }

    private fun assertComposite(
        label: String,
        bytes: ByteArray,
    ) {
        val composite = PsdCodec.read(bytes)?.composite
        assertNotNull(label, composite)
        for (i in 0 until 4) {
            val expected = argb(red[i], green[i], blue[i]).toLong()
            val actual = composite!!.pixels[i].toLong()
            assertEquals("$label pixel=$i", expected, actual)
        }
    }

    @Test
    fun aRawSixteenBitMergedImageKeepsEachSamplesHighByte() {
        assertComposite("raw", psd(compression = 0, body = rawBody()))
    }

    @Test
    fun aRunLengthSixteenBitMergedImageKeepsEachSamplesHighByte() {
        assertComposite("rle", psd(compression = 1, body = rleBody()))
    }

    @Test
    fun aZipSixteenBitMergedImageKeepsEachSamplesHighByte() {
        assertComposite("zip", psd(compression = 2, body = zipBody()))
    }

    @Test
    fun aPredictedZipSixteenBitMergedImageUndoesThePredictionOnWholeSamples() {
        assertComposite("predicted zip", psd(compression = 3, body = predictedZipBody()))
    }
}
