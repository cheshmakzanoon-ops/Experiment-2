package com.artflow.studio.core.pixels

import kotlin.math.roundToInt

/** The eyedropper's sample sizes: one pixel, or the average of a square around it. */
object AreaSample {
    val SIZES = listOf(1, 3, 5, 11)

    /**
     * The average colour of the [size] × [size] square centred on ([x], [y]), weighted by alpha so
     * transparent pixels do not darken it; 0 when nothing in the square is painted.
     */
    fun average(
        buffer: PixelBuffer,
        x: Int,
        y: Int,
        size: Int,
    ): Int {
        if (size <= 1) return buffer.getSafe(x, y)
        val reach = size / 2
        var alpha = 0f
        var red = 0f
        var green = 0f
        var blue = 0f
        var count = 0
        for (sy in y - reach..y + reach) {
            for (sx in x - reach..x + reach) {
                if (!buffer.contains(sx, sy)) continue
                val p = buffer.getUnchecked(sx, sy)
                val a = (p ushr 24) / 255f
                alpha += a
                red += ((p shr 16) and 0xFF) * a
                green += ((p shr 8) and 0xFF) * a
                blue += (p and 0xFF) * a
                count++
            }
        }
        if (alpha <= 0f || count == 0) return 0
        val outAlpha = (alpha / count * 255f).roundToInt()
        return Channels.argb(outAlpha, (red / alpha).roundToInt(), (green / alpha).roundToInt(), (blue / alpha).roundToInt())
    }
}
