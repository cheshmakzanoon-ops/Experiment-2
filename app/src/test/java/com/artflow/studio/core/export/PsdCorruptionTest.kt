package com.artflow.studio.core.export

import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.domain.model.layer.BlendMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

/**
 * [PsdCodec.read] documents null for data that is not a PSD or is damaged, so no corrupted file may throw.
 * The seeds are a layered PSD with a folder and a clipped layer, written both RLE and raw.
 */
class PsdCorruptionTest {
    private fun pattern(
        width: Int,
        height: Int,
        salt: Int,
    ) = PixelBuffer(width, height).also { image ->
        for (index in image.pixels.indices) {
            val x = index % width
            val y = index / width
            image.pixels[index] = (((x * 37 + salt) and 0xFF) shl 24) or ((x * 5 shl 16)) or ((y * 11 + salt) and 0xFF)
        }
    }

    private fun seed(useRle: Boolean): ByteArray {
        val inner = pattern(9, 7, salt = 3)
        val base = pattern(9, 7, salt = 8)
        val layers =
            listOf(
                PsdCodec.PsdLayer.groupEnd(),
                PsdCodec.PsdLayer("Inner", inner, opacity = 200, blendMode = BlendMode.MULTIPLY, isClippingMask = true),
                PsdCodec.PsdLayer.groupHeader("Folder", opacity = 128),
                PsdCodec.PsdLayer("Base", base, left = 2, top = 1, isVisible = false),
            )
        return PsdCodec.write(12, 10, layers, composite = pattern(12, 10, salt = 1), useRle = useRle)
    }

    private fun parse(bytes: ByteArray) {
        val document = PsdCodec.read(bytes) ?: return
        document.layers.forEach { layer -> layer.pixels.pixels.size }
        document.composite?.pixels?.size
    }

    @Test
    fun aCorruptedRleFileIsReadOrRefusedWithoutThrowing() {
        Corruption.check("psd rle", seed(useRle = true), seed = 1001L, accepted = { false }, parse = ::parse)
    }

    @Test
    fun aCorruptedRawFileIsReadOrRefusedWithoutThrowing() {
        Corruption.check("psd raw", seed(useRle = false), seed = 1002L, accepted = { false }, parse = ::parse)
    }

    /**
     * The offset of the blend key "8BIM"+"norm" of the seed's last record, "Base" (the folder records come first, and
     * their own blend keys are the same): the last occurrence, as the records are written in order.
     */
    private fun recordAt(bytes: ByteArray): Int {
        val needle = "8BIMnorm".toByteArray(Charsets.US_ASCII)
        return (0..bytes.size - needle.size).last { at -> needle.indices.all { bytes[at + it] == needle[it] } }
    }

    @Test
    fun aNegativeChannelLengthDoesNotMoveTheReaderPastTheData() {
        val bytes = seed(useRle = false)
        // The record is: bounds (16 bytes), channel count (2), four channels of id and length (6 each), then the blend key.
        val channelZeroLength = recordAt(bytes) - 24 + 2
        ByteBuffer.wrap(bytes).putInt(channelZeroLength, -1)
        assertTrue(runCatching { PsdCodec.read(bytes) }.isSuccess)
    }

    @Test
    fun aLayerWiderThanTheCanvasLimitReadsAsNotAPsd() {
        val bytes = seed(useRle = false)
        val boundsAt = recordAt(bytes) - 24 - 2 - 16
        ByteBuffer.wrap(bytes).putInt(boundsAt + 12, Int.MAX_VALUE)
        assertNull(PsdCodec.read(bytes))
    }

    @Test
    fun theReaderKeepsItsPositionInsideTheData() {
        val reader = PsdCodec.PsdReader(ByteArray(8))
        reader.position = -3
        assertEquals(0, reader.position)
        reader.position = 100
        assertEquals(8, reader.position)
        assertEquals(0, reader.readByte())
    }
}
