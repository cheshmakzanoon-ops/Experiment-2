package com.artflow.studio.core.render

import com.artflow.studio.core.pixels.Channels
import com.artflow.studio.core.pixels.PixelBuffer

/**
 * Bounded active-layer pigment pickup, not a fluid simulation. Five alpha-weighted taps sample
 * the layer before this stroke. Transparent RGB contributes nothing; ink alpha stays unchanged.
 * The caller owns the source snapshot and must not write it while generating the stroke.
 */
object WetPaint {
    fun pickup(
        source: PixelBuffer,
        x: Float,
        y: Float,
        radius: Float,
        ink: Int,
        amount: Float,
    ): Int {
        require(amount in 0f..1f) { "Wet mix must be finite and between zero and one" }
        require(x.isFinite() && y.isFinite()) { "Wet paint coordinates must be finite" }
        require(radius.isFinite() && radius >= 0f) { "Wet paint radius must be finite and non-negative" }
        if (amount == 0f || ink ushr 24 == 0) return ink
        val distance = (radius * 0.5f).coerceAtMost(32f)
        var alpha = 0f
        var red = 0f
        var green = 0f
        var blue = 0f
        // Constant work per dab, even with a very large brush. No per-dab arrays or source copies.
        for (index in 0..4) {
            val dx = if (index == 1) -distance else if (index == 2) distance else 0f
            val dy = if (index == 3) -distance else if (index == 4) distance else 0f
            val sample = source.sampleBilinear(x + dx, y + dy)
            val coverage = Channels.alpha(sample) / 255f
            alpha += coverage
            red += Channels.red(sample) * coverage
            green += Channels.green(sample) * coverage
            blue += Channels.blue(sample) * coverage
        }
        if (alpha == 0f) return ink
        val influence = amount * (alpha / 5f).coerceIn(0f, 1f)
        return Channels.fromFloats(
            Channels.alpha(ink),
            Channels.red(ink) * (1f - influence) + red / alpha * influence,
            Channels.green(ink) * (1f - influence) + green / alpha * influence,
            Channels.blue(ink) * (1f - influence) + blue / alpha * influence,
        )
    }
}
