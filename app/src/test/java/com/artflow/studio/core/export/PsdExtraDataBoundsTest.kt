package com.artflow.studio.core.export

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/** Independent records keep valid channel bytes immediately after the layer's extra-data boundary. */
class PsdExtraDataBoundsTest {
    @Test
    fun maskPayloadCannotExtendPastItsLayerExtraData() {
        val extra =
            data {
                writeInt(20)
                write(maskHeader().copyOf(18)) // Both mask padding bytes are missing.
            }
        assertRejected(extra)
    }

    @Test
    fun completeMaskStillRequiresItsFollowingBlendingRangesLength() {
        val extra =
            data {
                writeInt(20)
                write(maskHeader())
            }
        assertRejected(extra)
    }

    @Test
    fun truncatedMaskAndBlendingRangesLengthsCannotReadChannelBytes() {
        for (size in 1..3) {
            assertRejected(ByteArray(size))
            assertRejected(data { writeInt(0) } + ByteArray(size))
        }
    }

    @Test
    fun blendingRangesCannotExtendPastTheirLayerExtraData() {
        assertRejected(
            data {
                writeInt(0)
                writeInt(8)
                write(ByteArray(7))
            },
        )
    }

    @Test
    fun pascalNameCannotBorrowItsTextOrPaddingFromChannelBytes() {
        val lengths =
            data {
                writeInt(0)
                writeInt(0)
            }
        assertRejected(lengths + byteArrayOf(2, 'A'.code.toByte()))
        assertRejected(lengths + byteArrayOf(1, 'A'.code.toByte()))
        assertRejected(lengths + byteArrayOf(0))
    }

    @Test
    fun completeExtraDataPreservesMaskNameAndFollowingPixels() {
        val extra =
            data {
                writeInt(20)
                write(maskHeader())
                writeInt(8)
                write(ByteArray(8))
                write(byteArrayOf(1, 'A'.code.toByte(), 0, 0))
            }
        for (psb in listOf(false, true)) {
            val layer = requireNotNull(PsdCodec.read(psd(extra, psb))).layers.single()
            assertEquals("A", layer.name)
            assertArrayEquals(intArrayOf(0xFF000000.toInt()), layer.pixels.pixels)
            assertArrayEquals(byteArrayOf(-1), requireNotNull(layer.mask).coverage)
        }
    }

    @Test
    fun anOmittedExtraDataSectionKeepsTheSupportedDefaultName() {
        for (psb in listOf(false, true)) {
            val layer = requireNotNull(PsdCodec.read(psd(ByteArray(0), psb))).layers.single()
            assertEquals("Layer 1", layer.name)
            assertArrayEquals(intArrayOf(0xFF000000.toInt()), layer.pixels.pixels)
        }
    }

    private fun assertRejected(extra: ByteArray) {
        for (psb in listOf(false, true)) {
            val bytes = psd(extra, psb)
            assertNull("Metadata must reject extra-data overflow: psb=$psb", PsdCodec.inspect(bytes))
            assertNull("Decode must reject extra-data overflow: psb=$psb", PsdCodec.read(bytes))
        }
    }

    private fun maskHeader(): ByteArray =
        data {
            writeInt(0)
            writeInt(0)
            writeInt(1)
            writeInt(1)
            writeByte(255)
            writeByte(0)
            writeShort(0)
        }

    /** One raw RGB pixel, no merged image, with no calls to the production PSD writer. */
    private fun psd(
        extra: ByteArray,
        psb: Boolean,
    ): ByteArray {
        val layerInfo =
            data {
                writeShort(1)
                writeInt(0)
                writeInt(0)
                writeInt(1)
                writeInt(1)
                writeShort(3)
                repeat(3) { channel ->
                    writeShort(channel)
                    if (psb) writeLong(3) else writeInt(3)
                }
                writeBytes("8BIMnorm")
                write(byteArrayOf(-1, 0, 0, 0))
                writeInt(extra.size)
                write(extra)
                repeat(3) {
                    writeShort(0)
                    writeByte(0)
                }
            }.let { if (it.size % 2 == 0) it else it + byteArrayOf(0) }
        val section =
            data {
                if (psb) writeLong(layerInfo.size.toLong()) else writeInt(layerInfo.size)
                write(layerInfo)
                writeInt(0)
            }
        return data {
            writeBytes("8BPS")
            writeShort(if (psb) 2 else 1)
            write(ByteArray(6))
            writeShort(3)
            writeInt(1)
            writeInt(1)
            writeShort(8)
            writeShort(3)
            writeInt(0)
            writeInt(0)
            if (psb) writeLong(section.size.toLong()) else writeInt(section.size)
            write(section)
        }
    }

    private fun data(write: DataOutputStream.() -> Unit): ByteArray {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { it.write() }
        return output.toByteArray()
    }
}
