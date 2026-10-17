package com.artflow.studio.data.local

import com.artflow.studio.core.export.PngCodec
import com.artflow.studio.core.pixels.PixelBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.random.Random

/**
 * Layer and mask pixels are saved as PNG files that the document refers to. Reopening a project
 * decodes those files, so every channel of every pixel must come back exactly, including the
 * semi-transparent edges where a premultiplied conversion would lose precision.
 */
class CanvasRasterBytesTest {
    /**
     * Raw RGBA samples. An 8-bit RGBA PNG decodes to a non-premultiplied raster whose band values are
     * the stored bytes, so reading the raster (not `getRGB`) skips every colour-model conversion.
     */
    private fun decodeRawPixels(png: ByteArray): PixelBuffer {
        val reader = ImageIO.getImageReadersByFormatName("png").next()
        try {
            ImageIO.createImageInputStream(ByteArrayInputStream(png)).use { input ->
                reader.input = input
                val raster = reader.read(0).raster
                val samples = IntArray(4)
                val buffer = PixelBuffer(raster.width, raster.height)
                for (index in buffer.pixels.indices) {
                    raster.getPixel(index % raster.width, index / raster.width, samples)
                    buffer.pixels[index] = (samples[3] shl 24) or (samples[0] shl 16) or (samples[1] shl 8) or samples[2]
                }
                return buffer
            }
        } finally {
            reader.dispose()
        }
    }

    /** Random colour with every alpha value, weighted toward fully transparent and fully opaque pixels. */
    private fun randomBuffer(
        random: Random,
        width: Int,
        height: Int,
    ): PixelBuffer {
        val buffer = PixelBuffer(width, height)
        for (index in buffer.pixels.indices) {
            val alpha =
                when (random.nextInt(4)) {
                    0 -> 0
                    1 -> 255
                    else -> random.nextInt(256)
                }
            buffer.pixels[index] = (alpha shl 24) or random.nextInt(0x1000000)
        }
        return buffer
    }

    @Test
    fun layerAndMaskPixelsDecodeToTheSameChannelsEverywhere() {
        val random = Random(20260917)
        val sizes = listOf(1 to 1, 2 to 3, 37 to 19, 128 to 64)
        for ((width, height) in sizes) {
            val original = randomBuffer(random, width, height)
            val png = PngCodec.encode(original)

            val decoded = decodeRawPixels(png)

            assertEquals("${width}x$height width", width, decoded.width)
            assertEquals("${width}x$height height", height, decoded.height)
            for (index in original.pixels.indices) {
                val expected = Integer.toHexString(original.pixels[index])
                val actual = Integer.toHexString(decoded.pixels[index])
                assertEquals("pixel $index of ${width}x$height", expected, actual)
            }
        }
    }

    @Test
    fun savingTheSameLayerTwiceProducesTheSameBytes() {
        val buffer = randomBuffer(Random(7), 16, 9)

        // Content-addressed raster names rely on identical pixels producing identical files.
        assertArrayEquals(PngCodec.encode(buffer), PngCodec.encode(buffer.copy()))
    }

    @Test
    fun fullyTransparentPixelsKeepTheirColourChannels() {
        val buffer = PixelBuffer(2, 1)
        buffer.pixels[0] = 0x00C0FFEE
        buffer.pixels[1] = 0x80402010.toInt()

        val decoded = decodeRawPixels(PngCodec.encode(buffer))

        assertEquals(0x00C0FFEE, decoded.pixels[0])
        assertEquals(0x80402010.toInt(), decoded.pixels[1])
    }
}
