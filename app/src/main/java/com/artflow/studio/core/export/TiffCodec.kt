package com.artflow.studio.core.export

import com.artflow.studio.core.pixels.PixelBuffer
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Baseline TIFF writer: one uncompressed strip of 8-bit RGBA with unassociated alpha, so artwork
 * keeps its transparency and resolution in print and design tools.
 */
object TiffCodec {
    private const val ENTRY_COUNT = 14
    private const val HEADER = 8
    private const val IFD_SIZE = 2 + ENTRY_COUNT * 12 + 4
    private const val BITS_OFFSET = HEADER + IFD_SIZE
    private const val X_RES_OFFSET = BITS_OFFSET + 8
    private const val Y_RES_OFFSET = X_RES_OFFSET + 8
    private const val PIXELS_OFFSET = Y_RES_OFFSET + 8

    private const val SHORT = 3
    private const val LONG = 4
    private const val RATIONAL = 5

    fun write(
        image: PixelBuffer,
        dpi: Int,
    ): ByteArray {
        val pixelBytes = image.width.toLong() * image.height * 4
        require(pixelBytes <= Int.MAX_VALUE - PIXELS_OFFSET) { "The image is too large for a TIFF file" }
        val out = ByteBuffer.allocate(PIXELS_OFFSET + pixelBytes.toInt()).order(ByteOrder.LITTLE_ENDIAN)
        out.put('I'.code.toByte())
        out.put('I'.code.toByte())
        out.putShort(42)
        out.putInt(HEADER)
        out.putShort(ENTRY_COUNT.toShort())

        fun entry(
            tag: Int,
            type: Int,
            count: Int,
            value: Int,
        ) {
            out.putShort(tag.toShort())
            out.putShort(type.toShort())
            out.putInt(count)
            if (type == SHORT && count == 1) {
                out.putShort(value.toShort())
                out.putShort(0)
            } else {
                out.putInt(value)
            }
        }
        entry(256, LONG, 1, image.width)
        entry(257, LONG, 1, image.height)
        entry(258, SHORT, 4, BITS_OFFSET)
        entry(259, SHORT, 1, 1) // no compression
        entry(262, SHORT, 1, 2) // RGB
        entry(273, LONG, 1, PIXELS_OFFSET)
        entry(277, SHORT, 1, 4)
        entry(278, LONG, 1, image.height)
        entry(279, LONG, 1, pixelBytes.toInt())
        entry(282, RATIONAL, 1, X_RES_OFFSET)
        entry(283, RATIONAL, 1, Y_RES_OFFSET)
        entry(284, SHORT, 1, 1) // chunky pixels
        entry(296, SHORT, 1, 2) // inches
        entry(338, SHORT, 1, 2) // the fourth sample is unassociated alpha
        out.putInt(0)
        repeat(4) { out.putShort(8) }
        val resolution = dpi.coerceAtLeast(1)
        repeat(2) {
            out.putInt(resolution)
            out.putInt(1)
        }
        for (argb in image.pixels) {
            out.put((argb shr 16).toByte())
            out.put((argb shr 8).toByte())
            out.put(argb.toByte())
            out.put((argb ushr 24).toByte())
        }
        return out.array()
    }
}
