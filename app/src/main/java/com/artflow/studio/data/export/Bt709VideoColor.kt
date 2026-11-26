package com.artflow.studio.data.export

import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Opaque sRGB artwork to limited-range BT.709 video. The primaries and white point match,
 * but the transfer curves do not: composite in sRGB before calling [fromSrgb].
 *
 * Transfer and YCbCr equations: ITU-R BT.709-6, sections 1 and 3; sRGB: IEC 61966-2-1.
 */
internal object Bt709VideoColor {
    // Private and never mutated after initialization. No per-pixel powers or allocations.
    private val transfer =
        IntArray(256) { channel ->
            val srgb = channel / 255.0
            val linear = if (srgb <= 0.04045) srgb / 12.92 else ((srgb + 0.055) / 1.055).pow(2.4)
            val bt709 = if (linear < 0.018) 4.5 * linear else 1.099 * linear.pow(0.45) - 0.099
            (bt709 * 255).roundToInt().coerceIn(0, 255)
        }

    /** Packs full-range, nonlinear BT.709 RGB channels for matrix conversion and subsampling. */
    fun fromSrgb(color: Int): Int =
        (transfer[color shr 16 and 255] shl 16) or
            (transfer[color shr 8 and 255] shl 8) or
            transfer[color and 255]

    // Q16 coefficients include the 219/255 luma or 224/255 chroma range scaling.
    // Round once at the end; all intermediate sums fit a signed Int.
    fun luma(rgb: Int): Int = ((11966 * (rgb shr 16 and 255) + 40254 * (rgb shr 8 and 255) + 4064 * (rgb and 255) + 32768) shr 16) + 16

    fun chromaBlue(rgb: Int): Int =
        ((-6596 * (rgb shr 16 and 255) - 22189 * (rgb shr 8 and 255) + 28784 * (rgb and 255) + 32768) shr 16) + 128

    fun chromaRed(rgb: Int): Int =
        ((28784 * (rgb shr 16 and 255) - 26145 * (rgb shr 8 and 255) - 2639 * (rgb and 255) + 32768) shr 16) + 128

    /** Average the already-transferred channels for one 4:2:0 chroma sample. */
    fun average2x2(
        pixels: IntArray,
        width: Int,
        x: Int,
        y: Int,
    ): Int {
        var red = 0
        var green = 0
        var blue = 0
        for (dy in 0..1) {
            for (dx in 0..1) {
                val color = pixels[(y + dy) * width + x + dx]
                red += color shr 16 and 255
                green += color shr 8 and 255
                blue += color and 255
            }
        }
        return ((red / 4) shl 16) or ((green / 4) shl 8) or (blue / 4)
    }
}
