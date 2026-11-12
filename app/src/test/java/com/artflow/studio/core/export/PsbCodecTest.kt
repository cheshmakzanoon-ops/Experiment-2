package com.artflow.studio.core.export

import com.artflow.studio.domain.model.layer.BlendMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/**
 * Validates version-aware PSB (Large Document Format, Version 2) reading:
 * - 64-bit length fields for layer and mask sections
 * - 64-bit channel length in layer records
 * - 32-bit (4-byte) RLE row-length table entries
 * - Graceful handling of corrupted/truncated PSB inputs
 */
class PsbCodecTest {
    @Test
    fun psbRawLayersReadCorrectly() {
        val width = 8
        val height = 4
        val bytes = buildPsb(width, height, useRle = false)
        val doc = PsdCodec.read(bytes)
        assertNotNull("PSB document should be parsed", doc)
        assertEquals(width, doc!!.width)
        assertEquals(height, doc.height)
        assertEquals(1, doc.layers.size)
        val layer = doc.layers[0]
        assertEquals("Test Layer", layer.name)
        assertEquals(255, layer.opacity)
        assertEquals(BlendMode.NORMAL, layer.blendMode)
        assertEquals(width, layer.pixels.width)
        assertEquals(height, layer.pixels.height)
        val expected = (255 shl 24) or (200 shl 16) or (100 shl 8) or 50
        assertEquals(expected, layer.pixels.pixels[0])
    }

    @Test
    fun psbRleLayersReadCorrectlyWith32BitRowLengths() {
        val width = 16
        val height = 8
        val bytes = buildPsb(width, height, useRle = true)
        val doc = PsdCodec.read(bytes)
        assertNotNull("RLE PSB document should be parsed", doc)
        assertEquals(width, doc!!.width)
        assertEquals(height, doc.height)
        assertEquals(1, doc.layers.size)
        val layer = doc.layers[0]
        assertEquals("Test Layer", layer.name)
        assertEquals(width, layer.pixels.width)
        assertEquals(height, layer.pixels.height)
        val expected = (255 shl 24) or (200 shl 16) or (100 shl 8) or 50
        assertEquals(expected, layer.pixels.pixels[0])
    }

    @Test
    fun truncatedPsbReturnsNullGracefully() {
        val valid = buildPsb(8, 4, useRle = false)
        val truncated = valid.copyOf(valid.size / 2)
        val doc = PsdCodec.read(truncated)
        assertNull(doc)
    }

    @Test
    fun oversizedPsbChannelLengthFailsSafely() {
        val bytes = buildPsb(8, 4, useRle = false)
        val corrupted = bytes.clone()
        corrupted[4] = 0
        corrupted[5] = 3
        assertNull(PsdCodec.read(corrupted))
    }

    private fun buildPsb(
        width: Int,
        height: Int,
        useRle: Boolean,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        val dos = DataOutputStream(out)

        // 1. Header (26 bytes)
        dos.writeBytes("8BPS")
        dos.writeShort(2) // Version 2 = PSB
        dos.write(ByteArray(6))
        dos.writeShort(4) // 4 channels (R, G, B, A)
        dos.writeInt(height)
        dos.writeInt(width)
        dos.writeShort(8)
        dos.writeShort(3) // Color mode: RGB

        // 2. Color Mode Data
        dos.writeInt(0)

        // 3. Image Resources
        dos.writeInt(0)

        // 4. Layer and Mask Info
        val layerInfoBytes = buildLayerInfo(width, height, useRle)
        dos.writeLong(layerInfoBytes.size.toLong())
        dos.write(layerInfoBytes)

        // 5. Image Data
        dos.writeShort(0)
        val planeBytes = width * height
        dos.write(ByteArray(planeBytes * 4) { 0xFF.toByte() })

        dos.flush()
        return out.toByteArray()
    }

    private fun buildLayerInfo(
        width: Int,
        height: Int,
        useRle: Boolean,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        val dos = DataOutputStream(out)

        val channelR: ByteArray
        val channelG: ByteArray
        val channelB: ByteArray
        val channelA: ByteArray

        val planeSize = width * height
        if (!useRle) {
            channelR = byteArrayOf(0, 0) + ByteArray(planeSize) { 200.toByte() }
            channelG = byteArrayOf(0, 0) + ByteArray(planeSize) { 100.toByte() }
            channelB = byteArrayOf(0, 0) + ByteArray(planeSize) { 50.toByte() }
            channelA = byteArrayOf(0, 0) + ByteArray(planeSize) { 255.toByte() }
        } else {
            val rleRowR = encodePackBits(ByteArray(width) { 200.toByte() })
            val rleRowG = encodePackBits(ByteArray(width) { 100.toByte() })
            val rleRowB = encodePackBits(ByteArray(width) { 50.toByte() })
            val rleRowA = encodePackBits(ByteArray(width) { 255.toByte() })

            channelR = buildPsbRleChannel(height, rleRowR)
            channelG = buildPsbRleChannel(height, rleRowG)
            channelB = buildPsbRleChannel(height, rleRowB)
            channelA = buildPsbRleChannel(height, rleRowA)
        }

        val layerRecordsOut = ByteArrayOutputStream()
        val ldos = DataOutputStream(layerRecordsOut)

        ldos.writeInt(0) // top
        ldos.writeInt(0) // left
        ldos.writeInt(height) // bottom
        ldos.writeInt(width) // right
        ldos.writeShort(4) // 4 channels

        val channelSpecs =
            listOf(
                0 to channelR.size,
                1 to channelG.size,
                2 to channelB.size,
                -1 to channelA.size,
            )
        for ((id, len) in channelSpecs) {
            ldos.writeShort(id)
            ldos.writeLong(len.toLong())
        }

        ldos.writeBytes("8BIM")
        ldos.writeBytes("norm")
        ldos.writeByte(255)
        ldos.writeByte(0)
        ldos.writeByte(0)
        ldos.writeByte(0)

        val extraOut = ByteArrayOutputStream()
        val edos = DataOutputStream(extraOut)
        edos.writeInt(0)
        edos.writeInt(0)
        val nameBytes = "Test Layer".toByteArray(Charsets.US_ASCII)
        edos.writeByte(nameBytes.size)
        edos.write(nameBytes)
        val paddedNameLen = 1 + nameBytes.size
        val pad = (4 - (paddedNameLen % 4)) % 4
        repeat(pad) { edos.writeByte(0) }

        ldos.writeInt(extraOut.size())
        ldos.write(extraOut.toByteArray())

        val layersContent = ByteArrayOutputStream()
        val cdos = DataOutputStream(layersContent)
        cdos.writeShort(1)
        cdos.write(layerRecordsOut.toByteArray())
        cdos.write(channelR)
        cdos.write(channelG)
        cdos.write(channelB)
        cdos.write(channelA)

        val layersBytes = layersContent.toByteArray()
        dos.writeLong(layersBytes.size.toLong())
        dos.write(layersBytes)

        dos.flush()
        return out.toByteArray()
    }

    private fun buildPsbRleChannel(
        height: Int,
        rowData: ByteArray,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        val dos = DataOutputStream(out)
        dos.writeShort(1)
        repeat(height) {
            dos.writeInt(rowData.size)
        }
        repeat(height) {
            dos.write(rowData)
        }
        return out.toByteArray()
    }

    private fun encodePackBits(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        var remaining = data.size
        var offset = 0
        while (remaining > 0) {
            val run = minOf(remaining, 128)
            out.write(-(run - 1))
            out.write(data[offset].toInt())
            offset += run
            remaining -= run
        }
        return out.toByteArray()
    }
}
