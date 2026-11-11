package com.artflow.studio.core.color

import com.artflow.studio.core.pixels.Channels
import com.artflow.studio.core.pixels.PixelBuffer
import kotlin.math.roundToInt

/**
 * Native 4-channel CMYK layer pixel buffer (8 bits per channel: Cyan, Magenta, Yellow, blacK).
 *
 * Provides exact prepress print storage for CMYK documents without truncating to RGB.
 * Interoperates with [IccColorEngine] for certified ICC color conversions and soft-proofing.
 */
class CmykBuffer(
    val width: Int,
    val height: Int,
    val data: ByteArray = ByteArray(width * height * 4),
) {
    init {
        require(width > 0 && height > 0) { "Dimensions must be positive (got ${width}x$height)" }
        require(data.size == width * height * 4) { "Data size does not match 4 channels per pixel" }
    }

    fun index(x: Int, y: Int): Int {
        require(x in 0 until width && y in 0 until height) { "Coordinates ($x, $y) out of bounds" }
        return (y * width + x) * 4
    }

    fun getCyan(x: Int, y: Int): Int = data[index(x, y)].toInt() and 0xFF
    fun getMagenta(x: Int, y: Int): Int = data[index(x, y) + 1].toInt() and 0xFF
    fun getYellow(x: Int, y: Int): Int = data[index(x, y) + 2].toInt() and 0xFF
    fun getBlack(x: Int, y: Int): Int = data[index(x, y) + 3].toInt() and 0xFF

    fun setCmyk(x: Int, y: Int, c: Int, m: Int, yl: Int, k: Int) {
        val i = index(x, y)
        data[i] = c.coerceIn(0, 255).toByte()
        data[i + 1] = m.coerceIn(0, 255).toByte()
        data[i + 2] = yl.coerceIn(0, 255).toByte()
        data[i + 3] = k.coerceIn(0, 255).toByte()
    }

    /**
     * Converts this CMYK buffer into an ARGB [PixelBuffer] for screen presentation and display pipelines.
     */
    fun toRgbBuffer(profile: IccProfileType = IccProfileType.FOGRA39): PixelBuffer {
        val result = PixelBuffer(width, height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val offset = (y * width + x) * 4
                val c = (data[offset].toInt() and 0xFF) / 255f
                val m = (data[offset + 1].toInt() and 0xFF) / 255f
                val yl = (data[offset + 2].toInt() and 0xFF) / 255f
                val k = (data[offset + 3].toInt() and 0xFF) / 255f

                val rgb = IccColorEngine.cmykToRgb(c, m, yl, k, profile)
                val r = (rgb[0] * 255f).roundToInt().coerceIn(0, 255)
                val g = (rgb[1] * 255f).roundToInt().coerceIn(0, 255)
                val b = (rgb[2] * 255f).roundToInt().coerceIn(0, 255)
                result.pixels[y * width + x] = Channels.argb(255, r, g, b)
            }
        }
        return result
    }

    companion object {
        /**
         * Creates a 4-channel CMYK buffer from an RGB [PixelBuffer] using certified ICC UCR/GCR profiling.
         */
        fun fromRgbBuffer(
            source: PixelBuffer,
            profile: IccProfileType = IccProfileType.FOGRA39,
        ): CmykBuffer {
            val cmyk = CmykBuffer(source.width, source.height)
            for (y in 0 until source.height) {
                for (x in 0 until source.width) {
                    val pixel = source.pixels[y * source.width + x]
                    val r = Channels.red(pixel) / 255f
                    val g = Channels.green(pixel) / 255f
                    val b = Channels.blue(pixel) / 255f

                    val separated = IccColorEngine.rgbToCmyk(r, g, b, profile)
                    cmyk.setCmyk(
                        x, y,
                        (separated[0] * 255f).roundToInt(),
                        (separated[1] * 255f).roundToInt(),
                        (separated[2] * 255f).roundToInt(),
                        (separated[3] * 255f).roundToInt(),
                    )
                }
            }
            return cmyk
        }
    }
}
