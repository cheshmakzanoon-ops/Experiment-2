package com.artflow.studio.core.render

import com.artflow.studio.core.pixels.Channels
import com.artflow.studio.core.pixels.ImageFilters
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.domain.model.brush.BrushParams
import java.util.Random
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * ArtFlow's canvas-pickup model for wet brushes. These are deliberately documented local equations,
 * not a reconstruction of the reference application's native fluid or pigment simulation.
 *
 * All inputs are per-dab, in the sampled buffer's coordinates. A full replay must retain every
 * earlier pickup along the stroke's path: those samples determine its later carried colour.
 * Incremental live rendering retains that history and samples the full source even when only a
 * damage region is displayed. A fallback replay therefore cannot sample just a cropped tail.
 */
internal object WetBrushPickup {
    data class Sample(
        val x: Float,
        val y: Float,
        val directionX: Float,
        val directionY: Float,
        val travelled: Float,
        val pressure: Float,
    )

    fun color(
        brush: BrushParams,
        canvas: PixelBuffer?,
        color: Int,
        sample: Sample,
        random: Random,
    ): Int {
        val dilution = brush.wetDilution.coerceIn(0f, 1f)
        val thinned = Channels.scaleAlpha(color, 1f - dilution)
        if (canvas == null) return thinned
        val wetness = brush.wetMix.coerceIn(0f, 1f)
        // Preserve the older Dilution control's pickup equation for saved brushes.
        val legacyPickup = wetness + (1f - wetness) * DILUTION_PICKUP * brush.dilution.coerceIn(0f, 1f)
        val bleed = brush.bleed.coerceIn(0f, 1f) * brush.channelPressure(sample.pressure, "dynamicsPressureBleedCurve")
        val pickup = (legacyPickup + bleed).coerceIn(0f, 1f) * PICKUP_STRENGTH * attack(brush, sample)
        val charge = brush.wetCharge.coerceIn(0f, 1f)
        var amount = pickup
        if (charge < 1f) amount = 1f - (1f - amount) * charge
        if (dilution > 0f) amount += (1f - amount) * dilution
        if (amount <= 0f) return thinned

        val under = canvasUnder(brush, canvas, sample, random)
        val effective = amount * ((under ushr 24) / 255f)
        if (effective <= 0f) return thinned
        val mixed = ImageFilters.lerpArgb(color or OPAQUE, under or OPAQUE, effective)
        return (thinned and OPAQUE) or (mixed and RGB)
    }

    private fun attack(
        brush: BrushParams,
        sample: Sample,
    ): Float {
        val immediate = brush.wetAttack.coerceIn(0f, 1f)
        if (immediate >= 1f) return 1f
        val ramp = (sample.travelled / (brush.size.coerceAtLeast(1f) * ATTACK_SIZES)).coerceIn(0f, 1f)
        return immediate + (1f - immediate) * ramp
    }

    private fun canvasUnder(
        brush: BrushParams,
        canvas: PixelBuffer,
        sample: Sample,
        random: Random,
    ): Int {
        val size = brush.size.coerceAtLeast(1f)
        val pull = (size * StrokeReach.PULL_SIZES * brush.wetPull.coerceIn(0f, 1f)).coerceAtMost(sample.travelled)
        val x = sample.x - sample.directionX * pull
        val y = sample.y - sample.directionY * pull
        val blur = size * StrokeReach.BLUR_SIZES * brush.wetBlur.coerceIn(0f, 1f)
        val jitter = brush.wetBlurJitter.coerceIn(0f, 1f)
        val reach = if (blur > 0f && jitter > 0f) blur * (1f + (random.nextFloat() * 2f - 1f) * jitter) else blur
        return if (reach >= 1f) blurred(canvas, x, y, reach) else canvas.getSafe(floor(x).toInt(), floor(y).toInt())
    }

    /** Premultiplied averaging prevents transparent pixels' hidden RGB from bleeding into paint. */
    private fun blurred(
        canvas: PixelBuffer,
        x: Float,
        y: Float,
        reach: Float,
    ): Int {
        var alpha = 0f
        var red = 0f
        var green = 0f
        var blue = 0f
        for ((dx, dy) in BLUR_TAPS) {
            val pixel = canvas.getSafe(floor(x + dx * reach).toInt(), floor(y + dy * reach).toInt())
            val weight = (pixel ushr 24) / 255f
            alpha += weight
            red += ((pixel shr 16) and 0xFF) * weight
            green += ((pixel shr 8) and 0xFF) * weight
            blue += (pixel and 0xFF) * weight
        }
        if (alpha <= 0f) return 0
        return Channels.argb(
            (alpha / BLUR_TAPS.size * 255f).roundToInt(),
            (red / alpha).roundToInt(),
            (green / alpha).roundToInt(),
            (blue / alpha).roundToInt(),
        )
    }

    private const val OPAQUE = -0x1000000
    private const val RGB = 0x00FFFFFF
    private const val PICKUP_STRENGTH = 0.6f
    private const val DILUTION_PICKUP = 0.5f
    private const val ATTACK_SIZES = 4f
    private val BLUR_TAPS = listOf(0f to 0f, 1f to 0f, -1f to 0f, 0f to 1f, 0f to -1f)
}
