package com.artflow.studio.core.export

import com.artflow.studio.core.pixels.PixelBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PsdMergedPreviewTest {
    private val pixels = intArrayOf(0x80123456.toInt(), 0xFF654321.toInt(), 0x00112233)

    /** These fixtures were encoded by psd-tools 1.26.0, independently of ArtFlow's writer. */
    private fun fixture(compression: String): ByteArray =
        requireNotNull(javaClass.getResourceAsStream("/psd/merged-preview-$compression.psd")).use { it.readBytes() }

    @Test
    fun externalRawRleAndZipMergedPreviewsRemoveWhiteMatteButLayersStayStraight() {
        for (compression in listOf("raw", "rle", "zip")) {
            val document = requireNotNull(PsdCodec.read(fixture(compression)))
            assertArrayEquals(
                compression,
                pixels,
                document.layers
                    .single()
                    .pixels.pixels,
            )
            // The external encoder keeps white RGB beneath zero alpha in its merged preview.
            assertArrayEquals(
                compression,
                intArrayOf(0x80123456.toInt(), 0xFF654321.toInt(), 0x00FFFFFF),
                requireNotNull(document.composite).pixels,
            )
        }
    }

    @Test
    fun rawExportStoresMattedRgbPlanesAndTheOriginalAlphaPlane() {
        val source = PixelBuffer(3, 1, pixels)
        val bytes = PsdCodec.write(3, 1, listOf(PsdCodec.PsdLayer("Source", source)), source, useRle = false)
        val merged = mergedData(bytes)
        assertEquals(0, merged.short.toInt())
        val expectedPlanes = byteArrayOf(136.toByte(), 101, 17, 153.toByte(), 67, 34, 170.toByte(), 33, 51, 128.toByte(), -1, 0)
        val actual = ByteArray(merged.remaining()).also { merged.get(it) }
        assertArrayEquals(expectedPlanes, actual)
        val back = requireNotNull(PsdCodec.read(bytes))
        assertArrayEquals(
            pixels,
            back.layers
                .single()
                .pixels.pixels,
        )
        assertArrayEquals(pixels, requireNotNull(back.composite).pixels)
    }

    @Test
    fun rleExportStoresTheSameMattedPlanesAndRereadsWithoutDoubleMatting() {
        val source = PixelBuffer(3, 1, pixels)
        val bytes = PsdCodec.write(3, 1, listOf(PsdCodec.PsdLayer("Source", source)), source, useRle = true)
        val merged = mergedData(bytes)
        assertEquals(1, merged.short.toInt())
        repeat(4) { assertEquals(4, merged.short.toInt()) }
        // Three distinct samples in each single-row plane use one PackBits literal run.
        val expectedRows =
            byteArrayOf(2, 136.toByte(), 101, 17, 2, 153.toByte(), 67, 34, 2, 170.toByte(), 33, 51, 2, 128.toByte(), -1, 0)
        val actual = ByteArray(merged.remaining()).also { merged.get(it) }
        assertArrayEquals(expectedRows, actual)
        val back = requireNotNull(PsdCodec.read(bytes))
        assertArrayEquals(
            pixels,
            back.layers
                .single()
                .pixels.pixels,
        )
        assertArrayEquals(pixels, requireNotNull(back.composite).pixels)
    }

    @Test
    fun positiveLayerCountDoesNotTriggerAnUnrelatedRgbCorrection() {
        val bytes = fixture("raw")
        val header = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        header.position(layerSectionOffset(bytes) + 8)
        assertEquals(-1, header.short.toInt())
        header.putShort(header.position() - 2, 1)
        val composite = requireNotNull(PsdCodec.read(bytes)?.composite)
        assertEquals(0x008899AA, composite.pixels[0] and 0x00FFFFFF)
    }

    @Test
    fun anEmptyLayerListGetsAnArtworkLayerSoMergedTransparencyNeverUsesNegativeZero() {
        val source = PixelBuffer(3, 1, pixels)
        val bytes = PsdCodec.write(3, 1, emptyList(), source)
        assertEquals(-1, ByteBuffer.wrap(bytes).getShort(layerSectionOffset(bytes) + 8).toInt())
        val back = requireNotNull(PsdCodec.read(bytes))
        assertEquals("Artwork", back.layers.single().name)
        assertArrayEquals(
            pixels,
            back.layers
                .single()
                .pixels.pixels,
        )
        assertArrayEquals(pixels, requireNotNull(back.composite).pixels)
    }

    private fun mergedData(bytes: ByteArray): ByteBuffer =
        ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).apply {
            position(layerSectionOffset(bytes))
            val sectionLength = int
            position(position() + sectionLength)
        }

    private fun layerSectionOffset(bytes: ByteArray): Int {
        val header = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        header.position(26)
        repeat(2) {
            val length = header.int
            header.position(header.position() + length)
        }
        return header.position()
    }
}
