package com.artflow.studio.core.color

import com.artflow.studio.core.pixels.PixelBuffer
import kotlin.math.pow

/** The colour space a document's pixel values are stored in, chosen per canvas as in Procreate. */
enum class ColorProfile(
    val label: String,
) {
    SRGB("sRGB IEC61966-2.1"),
    DISPLAY_P3("Display P3"),
    ;

    companion object {
        fun from(name: String?): ColorProfile = entries.firstOrNull { it.name == name } ?: SRGB
    }
}

/** Conversions between [ColorProfile]s: both use the sRGB transfer curve and a D65 white. */
object ColorProfiles {
    // Linear Display P3 to linear sRGB, and back, row by row.
    private val P3_TO_SRGB = floatArrayOf(1.2249402f, -0.2249402f, 0f, -0.0420570f, 1.0420570f, 0f, -0.0196376f, -0.0786361f, 1.0982736f)
    private val SRGB_TO_P3 = floatArrayOf(0.8224620f, 0.1775380f, 0f, 0.0331942f, 0.9668058f, 0f, 0.0170826f, 0.0723974f, 0.9105199f)
    private const val ENCODE_STEPS = 4095

    private val decode = FloatArray(256) { toLinear(it / 255f) }
    private val encode = IntArray(ENCODE_STEPS + 1) { (fromLinear(it / ENCODE_STEPS.toFloat()) * 255f + 0.5f).toInt() }

    fun toLinear(value: Float): Float = if (value <= 0.04045f) value / 12.92f else ((value + 0.055f) / 1.055f).pow(2.4f)

    fun fromLinear(value: Float): Float = if (value <= 0.0031308f) value * 12.92f else 1.055f * value.pow(1f / 2.4f) - 0.055f

    /** [color] (ARGB) re-expressed in [to]; colours outside the target gamut are clipped. */
    fun convert(
        color: Int,
        from: ColorProfile,
        to: ColorProfile,
    ): Int {
        if (from == to) return color
        val m = if (to == ColorProfile.SRGB) P3_TO_SRGB else SRGB_TO_P3
        val r = decode[(color shr 16) and 0xFF]
        val g = decode[(color shr 8) and 0xFF]
        val b = decode[color and 0xFF]

        fun channel(row: Int) = encode[((m[row] * r + m[row + 1] * g + m[row + 2] * b).coerceIn(0f, 1f) * ENCODE_STEPS + 0.5f).toInt()]
        return (color and ALPHA) or (channel(0) shl 16) or (channel(3) shl 8) or channel(6)
    }

    /** A converted copy of [buffer], or [buffer] itself when the profiles match. */
    fun convert(
        buffer: PixelBuffer,
        from: ColorProfile,
        to: ColorProfile,
    ): PixelBuffer {
        if (from == to) return buffer
        return PixelBuffer(buffer.width, buffer.height, IntArray(buffer.pixels.size) { convert(buffer.pixels[it], from, to) })
    }

    private const val ALPHA = 0xFF000000.toInt()
}
