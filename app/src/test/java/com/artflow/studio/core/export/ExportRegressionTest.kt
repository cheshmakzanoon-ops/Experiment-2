package com.artflow.studio.core.export

import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.core.pixels.SelectionMask
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.util.zip.CRC32

class ExportRegressionTest {
    @Test
    fun selectionCropsAndPreservesFeatheredAlphaWithoutChangingTheSource() {
        val pixels = PixelBuffer.filled(6, 4, 0x80123456.toInt())
        val mask = SelectionMask(6, 4)
        mask.coverage[1 * 6 + 2] = 255.toByte()
        mask.coverage[2 * 6 + 3] = 128.toByte()
        val region = ExportRegion.resolve(listOf(pixels), mask, ExportArea.SELECTION)
        val result = ExportRegion.apply(pixels, mask, ExportArea.SELECTION, region)
        assertEquals(2, result.width)
        assertEquals(2, result.height)
        assertEquals(128, result.pixels[0] ushr 24)
        assertEquals(64, result.pixels[3] ushr 24)
        assertEquals(0, result.pixels[1] ushr 24)
        assertEquals(0x123456, result.pixels[3] and 0xFFFFFF)
        assertTrue(pixels.pixels.all { it == 0x80123456.toInt() })
    }

    @Test(expected = IllegalArgumentException::class)
    fun emptySelectionIsNotSilentlyExportedAsTheWholeCanvas() {
        ExportRegion.resolve(listOf(PixelBuffer(4, 4)), SelectionMask(4, 4), ExportArea.SELECTION)
    }

    @Test
    fun animationTrimUsesTheUnionOfFrameContent() {
        val first = PixelBuffer(8, 8).apply { pixels[1 * 8 + 2] = -1 }
        val last = PixelBuffer(8, 8).apply { pixels[6 * 8 + 7] = -1 }
        val region = ExportRegion.resolve(listOf(first, last), null, ExportArea.CONTENT_BOUNDS)!!
        assertEquals(2, region.left)
        assertEquals(1, region.top)
        assertEquals(7, region.right)
        assertEquals(6, region.bottom)
        assertEquals(6, ExportRegion.apply(first, null, ExportArea.CONTENT_BOUNDS, region).width)
        assertEquals(6, ExportRegion.apply(last, null, ExportArea.CONTENT_BOUNDS, region).height)
    }

    @Test
    fun pngDensityAndEveryChunkHaveValidMetadataAndCrc() {
        val bytes = PngCodec.encode(PixelBuffer.filled(3, 2, -1), dpi = 300)
        val input = DataInputStream(ByteArrayInputStream(bytes))
        input.skipBytes(8)
        var densitySeen = false
        while (input.available() > 0) {
            val length = input.readInt()
            val type = ByteArray(4).also { input.readFully(it) }
            val payload = ByteArray(length).also { input.readFully(it) }
            val crc =
                CRC32()
                    .apply {
                        update(type)
                        update(payload)
                    }.value
            assertEquals(crc, input.readInt().toLong() and 0xFFFFFFFFL)
            if (String(type, Charsets.US_ASCII) == "pHYs") {
                DataInputStream(ByteArrayInputStream(payload)).use {
                    assertEquals(11811, it.readInt())
                    assertEquals(11811, it.readInt())
                    assertEquals(1, it.readUnsignedByte())
                }
                densitySeen = true
            }
        }
        assertTrue(densitySeen)
    }

    @Test
    fun jpegDensityReplacementPreservesImageSegments() {
        val original = byteArrayOf(-1, -40, -1, -39)
        val once = JpegDensity.withDpi(original, 300)
        val twice = JpegDensity.withDpi(once, 144)
        assertEquals(22, once.size)
        assertEquals(22, twice.size)
        assertEquals(1, twice[13].toInt())
        assertEquals(144, twice[15].toInt() and 255)
        assertArrayEquals(original.copyOfRange(2, 4), twice.takeLast(2).toByteArray())
    }

    @Test
    fun psdRoundTripsAlphaUnicodeClippingAndOddSizedSectionsForRawAndRle() {
        for (rle in listOf(false, true)) {
            val pixels = PixelBuffer(3, 1, intArrayOf(0x00123456, 0x80123456.toInt(), 0xFF654321.toInt()))
            val layers = listOf(PsdCodec.PsdLayer("لایه فارسی", pixels, isClippingMask = true))
            val bytes = PsdCodec.write(3, 1, layers, pixels, dpi = 300, useRle = rle)
            val read = requireNotNull(PsdCodec.read(bytes))
            assertEquals(4, bytes[13].toInt())
            assertEquals(300, read.dpi)
            assertEquals("لایه فارسی", read.layers.single().name)
            assertTrue(read.layers.single().isClippingMask)
            assertArrayEquals(
                pixels.pixels,
                read.layers
                    .single()
                    .pixels.pixels,
            )
            assertArrayEquals(pixels.pixels, read.composite!!.pixels)
        }
    }

    @Test
    fun galleryAcceptsOnlySupportedImagesAndVideo() {
        assertFalse(ExportFormat.PSD.supportsGallery)
        assertFalse(ExportFormat.PDF.supportsGallery)
        assertFalse(ExportFormat.FRAME_SEQUENCE.supportsGallery)
        assertTrue(ExportFormat.MP4.supportsGallery)
        assertTrue(ExportFormat.PNG.supportsGallery)
    }

    @Test(expected = IllegalArgumentException::class)
    fun exportNameCannotEscapeItsDirectory() {
        ExportNaming.fileName("Name", ExportOptions(fileName = "../outside"))
    }
}
