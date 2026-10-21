package com.artflow.studio.core.export

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * A layer mask written independently of ArtFlow's writer must
 * hide the same pixels after import. The file is built byte by byte so the mask record is exact.
 */
class PsdLayerMaskTest {
    private fun ByteArrayOutputStream.int(value: Int) {
        write(value ushr 24)
        write(value ushr 16)
        write(value ushr 8)
        write(value)
    }

    private fun ByteArrayOutputStream.short(value: Int) {
        write(value ushr 8)
        write(value)
    }

    /**
     * A 2 x 2 document with one opaque layer. Its mask covers column 1 only (top 0, left 1, bottom 2,
     * right 2) with coverage 0 above 128; column 0 lies outside the mask and takes the default, 255.
     */
    private fun psdWithMask(flags: Int): ByteArray {
        val opaque = ByteArray(4) { 0xFF.toByte() }
        val empty = ByteArray(4)
        val maskPlane = byteArrayOf(0, 128.toByte())
        val channels = listOf(0 to opaque, 1 to empty, 2 to empty, -1 to opaque, -2 to maskPlane)

        val record = ByteArrayOutputStream()
        record.int(0) // top
        record.int(0) // left
        record.int(2) // bottom
        record.int(2) // right
        record.short(channels.size)
        channels.forEach { (id, plane) ->
            record.short(id)
            record.int(2 + plane.size) // the compression flag is part of each channel's length
        }
        record.write("8BIM".toByteArray())
        record.write("norm".toByteArray())
        record.write(255) // opacity
        record.write(0) // clipping
        record.write(0) // flags: visible
        record.write(0) // filler

        val extra = ByteArrayOutputStream()
        extra.int(20) // mask record length
        extra.int(0) // mask top
        extra.int(1) // mask left
        extra.int(2) // mask bottom
        extra.int(2) // mask right
        extra.write(255) // default value outside the mask
        extra.write(flags)
        extra.write(0)
        extra.write(0)
        extra.int(0) // blending ranges
        extra.write(1) // name: "L", padded to a multiple of four
        extra.write('L'.code)
        extra.write(0)
        extra.write(0)
        val extraBytes = extra.toByteArray()
        record.int(extraBytes.size)
        record.write(extraBytes)

        val layerInfo = ByteArrayOutputStream()
        layerInfo.short(-1) // a negative count: the first alpha channel holds transparency
        layerInfo.write(record.toByteArray())
        channels.forEach { (_, plane) ->
            layerInfo.short(0) // raw compression
            layerInfo.write(plane)
        }
        val layerInfoBytes = layerInfo.toByteArray()

        val section = ByteArrayOutputStream()
        section.int(layerInfoBytes.size)
        section.write(layerInfoBytes)
        section.int(0) // global layer mask info
        val sectionBytes = section.toByteArray()

        val psd = ByteArrayOutputStream()
        psd.write("8BPS".toByteArray())
        psd.short(1) // version
        psd.write(ByteArray(6))
        psd.short(4) // merged channels
        psd.int(2) // height
        psd.int(2) // width
        psd.short(8) // depth
        psd.short(3) // RGB
        psd.int(0) // colour mode data
        psd.int(0) // image resources
        psd.int(sectionBytes.size)
        psd.write(sectionBytes)
        psd.short(0) // merged image, raw
        repeat(4) { psd.write(ByteArray(4)) }
        return psd.toByteArray()
    }

    @Test
    fun importedMaskKeepsItsRectangleAndDefaultAndLeavesPixelsUnmasked() {
        val document = checkNotNull(PsdCodec.read(psdWithMask(flags = 0)))
        val layer = document.layers.single()
        val mask = checkNotNull(layer.mask)
        assertEquals(1, mask.left)
        assertEquals(0, mask.top)
        assertEquals(1, mask.width)
        assertEquals(2, mask.height)
        assertEquals(255, mask.outside)
        assertArrayEquals(byteArrayOf(0, -128), mask.coverage)
        // The file's pixels stay as Photoshop stored them; the mask is applied at composition.
        assertEquals(255, layer.pixels.pixels[1] ushr 24)
    }

    @Test
    fun maskIsPlacedOnTheCanvasAtTheDocumentOffset() {
        val mask = checkNotNull(checkNotNull(PsdCodec.read(psdWithMask(flags = 0))).layers.single().mask)
        // Column 0 lies outside the mask and takes its default; column 1 takes the coverage.
        assertArrayEquals(byteArrayOf(-1, 0, -1, -128), mask.coverageFor(2, 2, 0, 0))
        // Shifted one pixel right on a wider canvas, the mask starts at canvas column 2.
        assertArrayEquals(byteArrayOf(-1, -1, 0, -1, -1, -128), mask.coverageFor(3, 2, 1, 0))
    }

    @Test
    fun disabledMaskKeepsEditableCoverageAndItsDisabledFlag() {
        val mask = checkNotNull(checkNotNull(PsdCodec.read(psdWithMask(flags = 2))).layers.single().mask)
        assertFalse(mask.isEnabled)
        assertArrayEquals(byteArrayOf(0, -128), mask.coverage)
    }
}
