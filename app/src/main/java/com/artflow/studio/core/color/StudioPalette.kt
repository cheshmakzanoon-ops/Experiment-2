package com.artflow.studio.core.color

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/** Opaque sRGB UI tones only. Artwork pixels and working colour values never pass through this code. */
object StudioPalette {
    data class Surfaces(
        val background: Int,
        val surface: Int,
        val variant: Int,
        val text: Int,
        val secondaryText: Int,
        val lowest: Int,
        val low: Int,
        val normal: Int,
        val high: Int,
        val highest: Int,
    )

    data class Accent(
        val primary: Int,
        val onPrimary: Int,
        val container: Int,
        val onContainer: Int,
    )

    fun surfaces(
        dark: Boolean,
        highContrast: Boolean,
    ): Surfaces =
        if (dark) {
            Surfaces(
                background = if (highContrast) 0xFF0B0B0B.toInt() else 0xFF171717.toInt(),
                surface = 0xFF202020.toInt(),
                variant = 0xFF303030.toInt(),
                text = 0xFFF5F5F5.toInt(),
                secondaryText = if (highContrast) 0xFFF0F0F0.toInt() else 0xFFBDBDBD.toInt(),
                lowest = 0xFF121212.toInt(),
                low = 0xFF1C1C1C.toInt(),
                normal = 0xFF242424.toInt(),
                high = 0xFF2B2B2B.toInt(),
                highest = 0xFF343434.toInt(),
            )
        } else {
            Surfaces(
                background = 0xFFFAFAFA.toInt(),
                surface = 0xFFF8F8F8.toInt(),
                variant = 0xFFE9E9E9.toInt(),
                text = 0xFF181818.toInt(),
                secondaryText = if (highContrast) 0xFF282828.toInt() else 0xFF555555.toInt(),
                lowest = 0xFFFFFFFF.toInt(),
                low = 0xFFF5F5F5.toInt(),
                normal = 0xFFF0F0F0.toInt(),
                high = 0xFFEAEAEA.toInt(),
                highest = 0xFFE3E3E3.toInt(),
            )
        }

    fun accent(
        seed: Int,
        dark: Boolean,
        highContrast: Boolean,
    ): Accent {
        val surfaces = surfaces(dark, highContrast)
        val target = if (highContrast) 7.1 else 4.6
        val primary = readable(seed, surfaces.highest, target)
        val onPrimary = if (contrast(primary, BLACK) >= contrast(primary, WHITE)) BLACK else WHITE
        val container = mix(surfaces.surface, primary, if (highContrast) 0.2 else 0.12)
        return Accent(primary, onPrimary, container, readable(surfaces.text, container, target))
    }

    /** Ratios are computed on the final 8-bit colours, not rounded estimates or alpha assumptions. */
    fun contrast(
        first: Int,
        second: Int,
    ): Double {
        val a = luminance(first)
        val b = luminance(second)
        return (max(a, b) + 0.05) / (min(a, b) + 0.05)
    }

    fun readable(
        seed: Int,
        background: Int,
        minimum: Double,
    ): Int {
        require(minimum in 1.0..21.0)
        if (contrast(seed, background) >= minimum) return seed
        val endpoint = if (contrast(WHITE, background) >= contrast(BLACK, background)) WHITE else BLACK
        require(contrast(endpoint, background) >= minimum) { "The requested contrast cannot be reached on this background" }
        for (step in 1..255) {
            val candidate = mix(seed, endpoint, step / 255.0)
            if (contrast(candidate, background) >= minimum) return candidate
        }
        return endpoint
    }

    fun mix(
        background: Int,
        foreground: Int,
        amount: Double,
    ): Int {
        require(background ushr 24 == 255 && foreground ushr 24 == 255)
        require(amount in 0.0..1.0)
        var result = BLACK
        for (shift in intArrayOf(16, 8, 0)) {
            val a = background ushr shift and 255
            val b = foreground ushr shift and 255
            result = result or ((a + (b - a) * amount).roundToInt() shl shift)
        }
        return result
    }

    private fun luminance(color: Int): Double {
        require(color ushr 24 == 255) { "UI contrast requires opaque colours" }

        fun channel(shift: Int): Double {
            val value = (color ushr shift and 255) / 255.0
            return if (value <= 0.04045) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
    }

    private const val BLACK = 0xFF000000.toInt()
    private const val WHITE = 0xFFFFFFFF.toInt()
}
