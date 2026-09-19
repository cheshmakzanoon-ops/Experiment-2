package com.artflow.studio.presentation.ui.theme

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/** Opaque sRGB UI colours only. Never apply these adjustments to artwork or colour-picker values. */
object ThemeContrast {
    private const val BLACK = -0x1000000
    private const val WHITE = -1

    /** WCAG relative-luminance contrast; thresholds are compared without rounding. */
    fun ratio(first: Int, second: Int): Double {
        val a = luminance(first)
        val b = luminance(second)
        return (max(a, b) + 0.05) / (min(a, b) + 0.05)
    }

    fun contentOn(background: Int): Int =
        if (ratio(WHITE, background) >= ratio(BLACK, background)) WHITE else BLACK

    /** Retain the seed when readable; otherwise find the nearest mixture toward black or white. */
    fun fit(seed: Int, background: Int, minimum: Double): Int {
        require(minimum.isFinite() && minimum in 1.0..21.0) { "Invalid contrast target" }
        if (ratio(seed, background) >= minimum) return seed
        val endpoint = contentOn(background)
        require(ratio(endpoint, background) >= minimum) { "Contrast target is impossible on this background" }
        var low = 0.0
        var high = 1.0
        var result = endpoint
        repeat(24) {
            val fraction = (low + high) / 2.0
            val candidate = mix(seed, endpoint, fraction)
            if (ratio(candidate, background) >= minimum) {
                high = fraction
                result = candidate
            } else {
                low = fraction
            }
        }
        return result
    }

    /** An opaque tonal surface is predictable; a translucent token depends on what is behind it. */
    fun mix(from: Int, to: Int, fraction: Double): Int {
        requireOpaque(from)
        requireOpaque(to)
        require(fraction.isFinite() && fraction in 0.0..1.0) { "Invalid colour mixture" }
        fun channel(shift: Int): Int {
            val start = (from ushr shift) and 255
            val end = (to ushr shift) and 255
            return (start + (end - start) * fraction).roundToInt().coerceIn(0, 255)
        }
        return BLACK or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }

    private fun luminance(color: Int): Double {
        requireOpaque(color)
        fun linear(shift: Int): Double {
            val value = ((color ushr shift) and 255) / 255.0
            return if (value <= 0.04045) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * linear(16) + 0.7152 * linear(8) + 0.0722 * linear(0)
    }

    private fun requireOpaque(color: Int) {
        require(color ushr 24 == 255) { "Composite translucent colours before checking contrast" }
    }
}
