package com.artflow.studio.core.color

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Print soft-proofing: shows how colours change when printed with four process inks on white
 * paper. A colour is split into cyan, magenta, yellow and black coverage, then rebuilt from the
 * colours real inks actually have. With perfect inks the result would equal the input; real inks
 * dull saturated blues, greens and purples and lift the deepest blacks, which is what the proof
 * shows. The display shader in OpenGLCanvasRenderer does the same sums per pixel.
 */
object CmykProof {
    enum class Mode { OFF, PROOF, GAMUT_WARNING }

    // Encoded sRGB colours of printed process inks on white paper.
    private val CYAN = floatArrayOf(0f, 0.682f, 0.937f)
    private val MAGENTA = floatArrayOf(0.925f, 0f, 0.549f)
    private val YELLOW = floatArrayOf(1f, 0.949f, 0f)
    private val BLACK = floatArrayOf(0.137f, 0.122f, 0.125f)

    /** How far (0..1 per channel) a colour may move before the gamut warning marks it. */
    const val GAMUT_TOLERANCE = 0.08f
    const val WARNING_GREY = 0.5f
    const val WARNING_MIX = 0.6f

    /** Cyan, magenta, yellow and black coverage (0..1) for an encoded sRGB colour. */
    fun separate(
        r: Float,
        g: Float,
        b: Float,
    ): FloatArray {
        val k = 1f - maxOf(r, g, b)
        if (k >= 1f) return floatArrayOf(0f, 0f, 0f, 1f)
        val white = 1f - k
        return floatArrayOf((white - r) / white, (white - g) / white, (white - b) / white, k)
    }

    /** The printed appearance of an encoded sRGB colour, as encoded sRGB (r, g, b). */
    fun proof(
        r: Float,
        g: Float,
        b: Float,
    ): FloatArray {
        val inks = separate(r.coerceIn(0f, 1f), g.coerceIn(0f, 1f), b.coerceIn(0f, 1f))
        return FloatArray(3) { channel ->
            ink(CYAN[channel], inks[0]) * ink(MAGENTA[channel], inks[1]) * ink(YELLOW[channel], inks[2]) * ink(BLACK[channel], inks[3])
        }
    }

    /** True when printing would visibly change the colour. */
    fun outOfGamut(
        r: Float,
        g: Float,
        b: Float,
    ): Boolean {
        // A grey is printed with black ink alone. That ink's cast is a tint, not a gamut limit.
        if (r == g && g == b) return false
        val printed = proof(r, g, b)
        return maxOf(abs(printed[0] - r), abs(printed[1] - g), abs(printed[2] - b)) > GAMUT_TOLERANCE
    }

    /** Applies [mode] to an ARGB colour (alpha kept), as the display does. */
    fun apply(
        argb: Int,
        mode: Mode,
    ): Int {
        if (mode == Mode.OFF) return argb
        val r = (argb shr 16 and 0xFF) / 255f
        val g = (argb shr 8 and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f
        var out = proof(r, g, b)
        if (mode == Mode.GAMUT_WARNING && outOfGamut(r, g, b)) {
            out = FloatArray(3) { out[it] + (WARNING_GREY - out[it]) * WARNING_MIX }
        }
        return (argb and 0xFF000000.toInt()) or (byte(out[0]) shl 16) or (byte(out[1]) shl 8) or byte(out[2])
    }

    private fun ink(
        colour: Float,
        coverage: Float,
    ): Float = 1f - coverage * (1f - colour)

    private fun byte(value: Float): Int = (value.coerceIn(0f, 1f) * 255f).roundToInt()
}
