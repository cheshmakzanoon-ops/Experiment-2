package com.artflow.studio.core.color

import com.artflow.studio.core.pixels.Channels
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Physically inspired 6-band Kubelka-Munk spectral pigment mixer.
 *
 * Unlike RGB linear interpolation (where blue + yellow produces muddy neutral grey),
 * mixing pigments in K/S absorption-scattering space reproduces real subtractive paint
 * optics: ultramarine + cadmium yellow yields vivid natural green, red + green yields
 * warm earth umber, and white/black tints/shades stay clean and luminous.
 */
object SpectralMixer {
    // 6 spectral bands: Violet (430nm), Blue (470nm), Cyan (510nm), Green (550nm), Yellow (590nm), Red (640nm).
    private const val BANDS = 6
    private const val MIN_REFLECTANCE = 0.004f
    private const val MAX_REFLECTANCE = 0.996f

    // Forward projection: linear RGB -> 6-band reflectance (row-normalized so white (1,1,1) -> 1.0 across all bands).
    private val RGB_TO_SPEC_R = floatArrayOf(0.10f, 0.02f, 0.02f, 0.14f, 0.62f, 0.96f)
    private val RGB_TO_SPEC_G = floatArrayOf(0.06f, 0.16f, 0.56f, 0.80f, 0.36f, 0.03f)
    private val RGB_TO_SPEC_B = floatArrayOf(0.84f, 0.82f, 0.42f, 0.06f, 0.02f, 0.01f)

    // Inverse projection: 6-band reflectance -> linear RGB (rows sum to 1.0 so neutral grays round-trip cleanly).
    private val SPEC_TO_R = floatArrayOf(0.08f, -0.06f, -0.10f, 0.04f, 0.44f, 0.60f)
    private val SPEC_TO_G = floatArrayOf(-0.04f, 0.04f, 0.28f, 0.54f, 0.22f, -0.04f)
    private val SPEC_TO_B = floatArrayOf(0.46f, 0.48f, 0.16f, -0.06f, -0.02f, -0.02f)

    /**
     * Mixes two ARGB colors [a] and [b] by [t] (0 = all [a], 1 = all [b]) using Kubelka-Munk
     * spectral pigment optics, interpolating alpha linearly.
     */
    fun mixArgb(
        a: Int,
        b: Int,
        t: Float,
    ): Int {
        val factor = t.coerceIn(0f, 1f)
        if (factor <= 0f) return a
        if (factor >= 1f) return b
        if (a == b) return a

        val alphaA = (a ushr 24) and 0xFF
        val alphaB = (b ushr 24) and 0xFF
        val outAlpha = (alphaA + (alphaB - alphaA) * factor + 0.5f).toInt().coerceIn(0, 255)

        val rA = srgbToLinear(((a shr 16) and 0xFF) / 255f)
        val gA = srgbToLinear(((a shr 8) and 0xFF) / 255f)
        val bA = srgbToLinear((a and 0xFF) / 255f)

        val rB = srgbToLinear(((b shr 16) and 0xFF) / 255f)
        val gB = srgbToLinear(((b shr 8) and 0xFF) / 255f)
        val bB = srgbToLinear((b and 0xFF) / 255f)

        val oneMinusT = 1f - factor
        var outR = 0f
        var outG = 0f
        var outB = 0f

        for (i in 0 until BANDS) {
            val reflA = (rA * RGB_TO_SPEC_R[i] + gA * RGB_TO_SPEC_G[i] + bA * RGB_TO_SPEC_B[i]).coerceIn(MIN_REFLECTANCE, MAX_REFLECTANCE)
            val reflB = (rB * RGB_TO_SPEC_R[i] + gB * RGB_TO_SPEC_G[i] + bB * RGB_TO_SPEC_B[i]).coerceIn(MIN_REFLECTANCE, MAX_REFLECTANCE)

            val ksA = reflectanceToKS(reflA)
            val ksB = reflectanceToKS(reflB)
            val ksMix = ksA * oneMinusT + ksB * factor
            val reflMix = ksToReflectance(ksMix)

            outR += reflMix * SPEC_TO_R[i]
            outG += reflMix * SPEC_TO_G[i]
            outB += reflMix * SPEC_TO_B[i]
        }

        // Preserve endpoint fidelity near 0 and 1 so saturated primaries never drift when t -> 0 or 1.
        val endpointWeight = 4f * factor * oneMinusT
        val linR = (rA * oneMinusT + rB * factor) * (1f - endpointWeight) + outR.coerceIn(0f, 1f) * endpointWeight
        val linG = (gA * oneMinusT + gB * factor) * (1f - endpointWeight) + outG.coerceIn(0f, 1f) * endpointWeight
        val linB = (bA * oneMinusT + bB * factor) * (1f - endpointWeight) + outB.coerceIn(0f, 1f) * endpointWeight

        return Channels.argb(
            outAlpha,
            (linearToSrgb(linR.coerceIn(0f, 1f)) * 255f).roundToInt(),
            (linearToSrgb(linG.coerceIn(0f, 1f)) * 255f).roundToInt(),
            (linearToSrgb(linB.coerceIn(0f, 1f)) * 255f).roundToInt(),
        )
    }

    /**
     * Subtractive transparent watercolor glaze: filters [backdrop] through a translucent
     * pigment layer [paint] of strength [amount] (0..1).
     */
    fun glazeArgb(
        backdrop: Int,
        paint: Int,
        amount: Float,
    ): Int {
        val alpha = amount.coerceIn(0f, 1f)
        if (alpha <= 0f) return backdrop
        val backAlpha = (backdrop ushr 24) and 0xFF
        if (backAlpha == 0) return Channels.withAlpha(paint, (alpha * 255f).roundToInt().coerceIn(0, 255))

        val rBack = srgbToLinear(((backdrop shr 16) and 0xFF) / 255f)
        val gBack = srgbToLinear(((backdrop shr 8) and 0xFF) / 255f)
        val bBack = srgbToLinear((backdrop and 0xFF) / 255f)

        val rPaint = srgbToLinear(((paint shr 16) and 0xFF) / 255f)
        val gPaint = srgbToLinear(((paint shr 8) and 0xFF) / 255f)
        val bPaint = srgbToLinear((paint and 0xFF) / 255f)

        // Beer-Lambert-like multiplicative glazing combined with subtle spectral scatter.
        val outR = rBack * (1f - alpha + alpha * rPaint)
        val outG = gBack * (1f - alpha + alpha * gPaint)
        val outB = bBack * (1f - alpha + alpha * bPaint)
        val outA = max(backAlpha, (backAlpha + (255 - backAlpha) * alpha).roundToInt()).coerceIn(0, 255)

        return Channels.argb(
            outA,
            (linearToSrgb(outR.coerceIn(0f, 1f)) * 255f).roundToInt(),
            (linearToSrgb(outG.coerceIn(0f, 1f)) * 255f).roundToInt(),
            (linearToSrgb(outB.coerceIn(0f, 1f)) * 255f).roundToInt(),
        )
    }

    private fun reflectanceToKS(r: Float): Float = ((1f - r) * (1f - r)) / (2f * r)

    private fun ksToReflectance(ks: Float): Float = (1f + ks - sqrt(ks * ks + 2f * ks)).coerceIn(0f, 1f)

    private fun srgbToLinear(c: Float): Float =
        if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)

    private fun linearToSrgb(c: Float): Float =
        if (c <= 0.0031308f) c * 12.92f else 1.055f * c.pow(1f / 2.4f) - 0.055f
}
