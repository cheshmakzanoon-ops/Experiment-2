package com.artflow.studio.core.export

import com.artflow.studio.core.pixels.PixelBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.util.zip.DeflaterOutputStream

class PsdReadBudgetTest {
    @Test
    fun compressedLayersAreRejectedBeforeTheirGigabyteOfPixelsCanBeAllocated() {
        // Stream the compressed fixture: constructing it never allocates a full raster plane.
        val bytes = layeredZip(width = 4096, height = 4096, layers = 20)
        val info = requireNotNull(PsdCodec.inspect(bytes))
        assertTrue(bytes.size < 2 * 1024 * 1024)
        assertTrue(info.requiredMemoryBytes >= 20L * 4096 * 4096 * 4)
        assertNull(PsdCodec.read(bytes, memoryBudgetBytes = 16L * 1024 * 1024))
    }

    @Test
    fun theExactConservativeBudgetIsAcceptedButOneByteLessIsRejected() {
        val bytes = layeredZip(width = 8, height = 8, layers = 2)
        val budget = requireNotNull(PsdCodec.inspect(bytes)).requiredMemoryBytes
        assertNull(PsdCodec.read(bytes, memoryBudgetBytes = budget - 1))
        val result = requireNotNull(PsdCodec.read(bytes, memoryBudgetBytes = budget))
        assertEquals(2, result.layers.size)
        assertEquals(
            64,
            result.layers
                .first()
                .pixels.pixels.size,
        )
    }

    @Test
    fun sixteenBitWorkingBuffersAreIncludedBeforeDownsampling() {
        val eightBit = layeredZip(width = 32, height = 32, layers = 1)
        val sixteenBit = layeredZip(width = 32, height = 32, layers = 1, depth = 16)
        val eightBudget = requireNotNull(PsdCodec.inspect(eightBit)).requiredMemoryBytes
        val sixteenBudget = requireNotNull(PsdCodec.inspect(sixteenBit)).requiredMemoryBytes
        assertTrue(sixteenBudget > eightBudget)
        assertNull(PsdCodec.read(sixteenBit, memoryBudgetBytes = eightBudget))
        assertNotNull(PsdCodec.read(sixteenBit, memoryBudgetBytes = sixteenBudget))
    }

    @Test
    fun retainedLayersAndTheMergedZipWorkspaceShareOneBudget() {
        val withoutComposite = layeredZip(width = 32, height = 32, layers = 2)
        val withComposite = layeredZip(width = 32, height = 32, layers = 2, composite = true)
        val layerBudget = requireNotNull(PsdCodec.inspect(withoutComposite)).requiredMemoryBytes
        val totalBudget = requireNotNull(PsdCodec.inspect(withComposite)).requiredMemoryBytes
        assertTrue(totalBudget > layerBudget)
        assertNull(PsdCodec.read(withComposite, memoryBudgetBytes = layerBudget))
        assertNotNull(PsdCodec.read(withComposite, memoryBudgetBytes = totalBudget)?.composite)
    }

    @Test
    fun independentlySizedMasksAreIncludedInTheBudget() {
        val pixels = PixelBuffer(2, 2)
        val layer = PsdCodec.PsdLayer("Small pixels", pixels)
        val mask = PsdCodec.PsdMask(0, 0, 64, 64, ByteArray(64 * 64), 0)
        val withoutMask = PsdCodec.write(2, 2, listOf(layer), composite = pixels)
        val withMask = PsdCodec.write(2, 2, listOf(layer.copy(mask = mask)), composite = pixels)
        val unmaskedBudget = requireNotNull(PsdCodec.inspect(withoutMask)).requiredMemoryBytes
        val maskedBudget = requireNotNull(PsdCodec.inspect(withMask)).requiredMemoryBytes
        assertTrue(maskedBudget >= unmaskedBudget + 64L * 64)
        assertNull(PsdCodec.read(withMask, memoryBudgetBytes = unmaskedBudget))
        assertEquals(
            4096,
            PsdCodec
                .read(withMask, memoryBudgetBytes = maskedBudget)
                ?.layers
                ?.single()
                ?.mask
                ?.coverage
                ?.size,
        )
    }

    @Test
    fun psbUsesTheSamePreallocationBudget() {
        val bytes = layeredZip(width = 16, height = 16, layers = 3, psb = true)
        val budget = requireNotNull(PsdCodec.inspect(bytes)).requiredMemoryBytes
        assertNull(PsdCodec.read(bytes, memoryBudgetBytes = budget - 1))
        assertEquals(3, PsdCodec.read(bytes, memoryBudgetBytes = budget)?.layers?.size)
    }

    @Test
    fun metadataInspectionRejectsInvalidRectanglesAndTruncatedPayloads() {
        val bytes = layeredZip(width = 8, height = 8, layers = 1)
        val invalid = bytes.copyOf()
        ByteBuffer.wrap(invalid).putInt(56, Int.MAX_VALUE) // first layer's right edge
        assertNull(PsdCodec.inspect(invalid))
        assertNull(PsdCodec.read(invalid))
        assertNull(PsdCodec.inspect(bytes.copyOf(bytes.size - 2)))
        assertNull(PsdCodec.read(bytes, memoryBudgetBytes = 0))
    }

    @Test
    fun truncatedZipPixelsAreRejectedInsteadOfBeingPaddedWithZeroes() {
        val bytes = layeredZip(width = 8, height = 8, layers = 1, decodedSamples = 1)
        assertNotNull(PsdCodec.inspect(bytes))
        assertNull(PsdCodec.read(bytes))
    }

    @Test
    fun oversizedZipPixelsAreRejectedWithoutExpandingBeyondThePlane() {
        val bytes = layeredZip(width = 8, height = 8, layers = 1, decodedSamples = 65)
        assertNotNull(PsdCodec.inspect(bytes))
        assertNull(PsdCodec.read(bytes))
    }

    @Test
    fun aZipStreamWithoutItsChecksumIsRejectedEvenAfterAllPixelsWereProduced() {
        val bytes = layeredZip(width = 8, height = 8, layers = 1, truncateZip = true)
        assertNotNull(PsdCodec.inspect(bytes))
        assertNull(PsdCodec.read(bytes))
    }

    @Test
    fun unknownLayerCompressionDoesNotCreateAnApparentlySuccessfulBlankLayer() {
        assertNull(PsdCodec.read(layeredZip(width = 8, height = 8, layers = 1, compression = 99)))
    }

    @Test
    fun oversizedUnicodeNamesAreBoundedBeforeAllocatingCharacters() {
        val bytes = ByteArray(32 * 1024)
        ByteBuffer.wrap(bytes).putInt(Int.MAX_VALUE)
        for (at in 5 until bytes.size step 2) bytes[at] = 'A'.code.toByte()
        val name = PsdCodec.PsdReader(bytes).readUnicodeString(bytes.size)
        assertEquals(4096, name.length)
        assertTrue(name.all { it == 'A' })
    }

    @Test(expected = IllegalArgumentException::class)
    fun negativeBudgetsAreProgrammingErrors() {
        PsdCodec.read(ByteArray(0), memoryBudgetBytes = -1)
    }

    private fun layeredZip(
        width: Int,
        height: Int,
        layers: Int,
        depth: Int = 8,
        composite: Boolean = false,
        psb: Boolean = false,
        decodedSamples: Int? = null,
        truncateZip: Boolean = false,
        compression: Int = 2,
    ): ByteArray {
        val compressed = compressedZeros(decodedSamples?.toLong() ?: (width.toLong() * height * (depth / 8)))
        val channel = if (truncateZip) compressed.copyOf(compressed.size - 2) else compressed
        val layerInfoBytes = ByteArrayOutputStream()
        DataOutputStream(layerInfoBytes).use { info ->
            info.writeShort(layers)
            repeat(layers) {
                info.writeInt(0)
                info.writeInt(0)
                info.writeInt(height)
                info.writeInt(width)
                info.writeShort(4)
                listOf(-1, 0, 1, 2).forEach { id ->
                    info.writeShort(id)
                    if (psb) info.writeLong(channel.size.toLong() + 2) else info.writeInt(channel.size + 2)
                }
                info.writeBytes("8BIMnorm")
                info.writeByte(255)
                info.writeByte(0)
                info.writeByte(0)
                info.writeByte(0)
                info.writeInt(0)
            }
            repeat(layers * 4) {
                info.writeShort(compression)
                info.write(channel)
            }
        }
        val layerInfo = layerInfoBytes.toByteArray()
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { out ->
            out.writeBytes("8BPS")
            out.writeShort(if (psb) 2 else 1)
            out.write(ByteArray(6))
            out.writeShort(4)
            out.writeInt(height)
            out.writeInt(width)
            out.writeShort(depth)
            out.writeShort(3)
            out.writeInt(0)
            out.writeInt(0)
            if (psb) {
                out.writeLong(layerInfo.size.toLong() + 8)
                out.writeLong(layerInfo.size.toLong())
            } else {
                out.writeInt(layerInfo.size + 4)
                out.writeInt(layerInfo.size)
            }
            out.write(layerInfo)
            if (composite) {
                out.writeShort(2)
                out.write(compressedZeros(width.toLong() * height * 4 * (depth / 8)))
            }
        }
        return output.toByteArray()
    }

    private fun compressedZeros(size: Long): ByteArray {
        val out = ByteArrayOutputStream()
        DeflaterOutputStream(out).use { zip ->
            val block = ByteArray(8192)
            var remaining = size
            while (remaining > 0) {
                val count = minOf(remaining, block.size.toLong()).toInt()
                zip.write(block, 0, count)
                remaining -= count
            }
        }
        return out.toByteArray()
    }
}
