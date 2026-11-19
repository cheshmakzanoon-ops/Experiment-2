package com.artflow.studio.core.export

import com.artflow.studio.core.pixels.PixelBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.zip.DeflaterOutputStream

/** Tiny independent fixtures exercise merged-image boundaries without allocating large rasters. */
class PsdCompositeBoundsTest {
    @Test
    fun rawMergedImageRequiresEverySampleOfEveryPlane() {
        for (depth in listOf(8, 16)) {
            for (psb in listOf(false, true)) {
                val body = ByteArray(12 * depth / 8) { (it + 1).toByte() }
                for (size in body.indices) {
                    assertNull("depth=$depth psb=$psb size=$size", PsdCodec.read(psd(0, body.copyOf(size), depth, psb, channels = 3)))
                }
                assertNotNull(PsdCodec.read(psd(0, body, depth, psb, channels = 3))?.composite)
            }
        }
    }

    @Test
    fun mergedRawPlanesHaveNoUndeclaredTrailingSamples() {
        assertNull(PsdCodec.read(psd(0, byteArrayOf(10, 20, 30, 40, 50))))
    }

    @Test
    fun rleRowsMustProduceTheirExactWidth() {
        val rows =
            listOf(
                byteArrayOf(0, 10), // Only one sample instead of two.
                byteArrayOf(2, 10, 20, 30), // Three literal samples instead of two.
                byteArrayOf(-2, 10), // Three repeated samples instead of two.
                byteArrayOf(-128), // No-op without any samples.
            )
        for (psb in listOf(false, true)) {
            rows.forEach { row ->
                assertNull(PsdCodec.read(psd(1, rle(listOf(row, byteArrayOf(-1, 80)), psb), psb = psb)))
            }
        }
    }

    @Test
    fun rleLiteralCannotBorrowSamplesFromTheNextRow() {
        val rows = listOf(byteArrayOf(1, 10), byteArrayOf(1, 20, 30))
        assertNull(PsdCodec.read(psd(1, rle(rows))))
    }

    @Test
    fun rleRepeatCannotBorrowItsValueFromTheNextRow() {
        val rows = listOf(byteArrayOf(-1), byteArrayOf(1, 20, 30))
        assertNull(PsdCodec.read(psd(1, rle(rows))))
    }

    @Test
    fun rleTablesMustAccountForTheWholePayload() {
        val rows = listOf(byteArrayOf(-1, 10), byteArrayOf(-1, 20))
        for (psb in listOf(false, true)) {
            val valid = rle(rows, psb)
            assertNull(PsdCodec.read(psd(1, valid.copyOf(valid.size - 1), psb = psb)))
            assertNull(PsdCodec.read(psd(1, valid + byteArrayOf(-128), psb = psb)))
            assertNull(PsdCodec.read(psd(1, rle(rows, psb, intArrayOf(0, 4)), psb = psb)))
            assertNull(PsdCodec.read(psd(1, rle(rows, psb, intArrayOf(3, 1)), psb = psb)))
            assertNull(PsdCodec.read(psd(1, valid.copyOf(if (psb) 7 else 3), psb = psb)))
        }
    }

    @Test
    fun negativeAndOverflowingPsbRowCountsAreRejectedBeforeDecoding() {
        val rows = listOf(byteArrayOf(-1, 10), byteArrayOf(-1, 20))
        val lengths = listOf(intArrayOf(-1, 5), intArrayOf(Int.MAX_VALUE, Int.MAX_VALUE))
        lengths.forEach {
            assertNull(PsdCodec.read(psd(1, rle(rows, psb = true, lengths = it), psb = true)))
        }
    }

    @Test
    fun packBitsNoOpsStayInsideTheirRowsAndDoNotShiftTheNextChannel() {
        val rows =
            listOf(
                byteArrayOf(-128, 1, 10, 20, -128),
                byteArrayOf(-1, 30, -128),
                byteArrayOf(1, 40, 50),
                byteArrayOf(-128, -1, 60),
                byteArrayOf(-1, 70, -128),
                byteArrayOf(1, 80, 90, -128),
            )
        val expected = intArrayOf(0xFF0A2846.toInt(), 0xFF143246.toInt(), 0xFF1E3C50.toInt(), 0xFF1E3C5A.toInt())
        for (psb in listOf(false, true)) {
            val image = requireNotNull(PsdCodec.read(psd(1, rle(rows, psb), psb = psb, channels = 3))?.composite)
            assertArrayEquals(expected, image.pixels)
        }
    }

    @Test
    fun sixteenBitRleRowsAreValidatedBeforeDiscardingTheirLowBytes() {
        val validRows = listOf(byteArrayOf(3, 0x12, 0x34, 0x56, 0x78), byteArrayOf(-3, -1))
        val invalidRows = listOf(byteArrayOf(2, 0x12, 0x34, 0x56), byteArrayOf(-3, -1))
        for (psb in listOf(false, true)) {
            assertNull(PsdCodec.read(psd(1, rle(invalidRows, psb), depth = 16, psb = psb)))
            val image = requireNotNull(PsdCodec.read(psd(1, rle(validRows, psb), depth = 16, psb = psb))?.composite)
            assertArrayEquals(intArrayOf(0xFF121212.toInt(), 0xFF565656.toInt(), -1, -1), image.pixels)
        }
    }

    @Test
    fun brokenZipCompositeRejectsTheDocumentInsteadOfSilentlyDroppingItsImage() {
        val exact = zip(byteArrayOf(10, 20, 30, 40))
        val damagedChecksum = exact.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        val damaged =
            listOf(
                zip(byteArrayOf(10, 20, 30)),
                zip(byteArrayOf(10, 20, 30, 40, 50)),
                exact.copyOf(exact.size - 1),
                damagedChecksum,
            )
        for (compression in listOf(2, 3)) {
            damaged.forEach { assertNull(PsdCodec.read(psd(compression, it))) }
            assertNotNull(PsdCodec.read(psd(compression, exact))?.composite)
        }
    }

    @Test
    fun unknownCompressionAndPartialCompressionHeadersAreRejected() {
        assertNull(PsdCodec.read(psd(99, byteArrayOf(10, 20, 30, 40))))
        val header = psd(0, ByteArray(0))
        assertNull(PsdCodec.read(header.copyOf(header.size - 1)))
    }

    @Test
    fun aLayerOnlyFileIsSupportedButAPresentBrokenCompositeIsRejected() {
        val pixels = PixelBuffer(2, 2, IntArray(4) { 0xFF123456.toInt() })
        val bytes = PsdCodec.write(2, 2, listOf(PsdCodec.PsdLayer("Source", pixels)), pixels, useRle = false)
        val layerOnly = requireNotNull(PsdCodec.read(bytes.copyOf(bytes.size - 18)))
        assertEquals(1, layerOnly.layers.size)
        val restored = layerOnly.layers.single().pixels
        assertArrayEquals(pixels.pixels, restored.pixels)
        assertNull(layerOnly.composite)
        assertNull(PsdCodec.read(bytes.copyOf(bytes.size - 1)))
    }

    private fun rle(
        rows: List<ByteArray>,
        psb: Boolean = false,
        lengths: IntArray = rows.map { it.size }.toIntArray(),
    ): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { data ->
            lengths.forEach { if (psb) data.writeInt(it) else data.writeShort(it) }
            rows.forEach { data.write(it) }
        }
        return out.toByteArray()
    }

    /** A 2 x 2 grayscale/RGB file with independently authored image data and no layers. */
    private fun psd(
        compression: Int,
        body: ByteArray,
        depth: Int = 8,
        psb: Boolean = false,
        channels: Int = 1,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { data ->
            data.writeBytes("8BPS")
            data.writeShort(if (psb) 2 else 1)
            data.write(ByteArray(6))
            data.writeShort(channels)
            data.writeInt(2)
            data.writeInt(2)
            data.writeShort(depth)
            data.writeShort(if (channels == 1) 1 else 3)
            data.writeInt(0) // Colour mode data.
            data.writeInt(0) // Image resources.
            if (psb) data.writeLong(0) else data.writeInt(0) // Layer and mask information.
            data.writeShort(compression)
            data.write(body)
        }
        return out.toByteArray()
    }

    private fun zip(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        DeflaterOutputStream(out).use { it.write(bytes) }
        return out.toByteArray()
    }
}
