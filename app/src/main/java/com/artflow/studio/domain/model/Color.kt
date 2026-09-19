package com.artflow.studio.domain.model

import kotlin.math.roundToInt

/**
 * Domain model representing a color with ARGB components
 * Used throughout the app for brush colors, shape fills, etc.
 */
data class Color(
    val alpha: Int = 255,
    val red: Int = 0,
    val green: Int = 0,
    val blue: Int = 0,
) {
    /**
     * Create a color from ARGB values (0-255 each)
     */
    constructor(argb: Int) : this(
        alpha = (argb shr 24) and 0xFF,
        red = (argb shr 16) and 0xFF,
        green = (argb shr 8) and 0xFF,
        blue = argb and 0xFF,
    )

    /**
     * Convert to Android Compose Color
     */
    fun toComposeColor(): androidx.compose.ui.graphics.Color {
        // androidx.compose.ui.graphics.Color's parameter order is (red, green, blue, alpha).
        return androidx.compose.ui.graphics
            .Color(red, green, blue, alpha)
    }

    /**
     * Convert to Android Graphics Color (Int).
     *
     * Implemented directly instead of delegating to `android.graphics.Color.argb` so the
     * domain layer stays free of Android framework calls and is unit-testable on the JVM.
     */
    fun toAndroidColor(): Int =
        ((alpha and 0xFF) shl 24) or
            ((red and 0xFF) shl 16) or
            ((green and 0xFF) shl 8) or
            (blue and 0xFF)

    /**
     * Convert to HSV representation (hue 0-360, saturation 0-1, value 0-1)
     */
    fun toHSV(): FloatArray = rgbToHsv(toAndroidColor())

    /**
     * Create a copy with modified alpha
     */
    fun withAlpha(newAlpha: Int): Color = copy(alpha = newAlpha.coerceIn(0, 255))

    /**
     * Create a copy with modified alpha (float 0.0 - 1.0)
     */
    fun withAlpha(alphaFloat: Float): Color = withAlpha((alphaFloat * 255).toInt().coerceIn(0, 255))

    /**
     * Blend this color with another using a factor (0.0 = this color, 1.0 = other color)
     * @param other The color to blend with
     * @param factor Blend factor (0.0 = this color, 1.0 = other color)
     */
    fun blendWith(
        other: Color,
        factor: Float,
    ): Color {
        val clampedFactor = factor.coerceIn(0f, 1f)
        return Color(
            alpha = ((alpha * (1 - clampedFactor)) + (other.alpha * clampedFactor)).toInt(),
            red = ((red * (1 - clampedFactor)) + (other.red * clampedFactor)).toInt(),
            green = ((green * (1 - clampedFactor)) + (other.green * clampedFactor)).toInt(),
            blue = ((blue * (1 - clampedFactor)) + (other.blue * clampedFactor)).toInt(),
        )
    }

    /**
     * Get the luminance of this color (0.0 - 1.0)
     */
    fun getLuminance(): Float = (0.299f * red + 0.587f * green + 0.114f * blue) / 255f

    /**
     * Get a contrasting color (black or white) based on luminance
     */
    fun getContrastingColor(): Color = if (getLuminance() > 0.5f) BLACK else WHITE

    companion object {
        // Predefined colors
        val BLACK = Color(red = 0, green = 0, blue = 0)
        val WHITE = Color(red = 255, green = 255, blue = 255)
        val RED = Color(red = 255, green = 0, blue = 0)
        val GREEN = Color(red = 0, green = 255, blue = 0)
        val BLUE = Color(red = 0, green = 0, blue = 255)
        val YELLOW = Color(red = 255, green = 255, blue = 0)
        val CYAN = Color(red = 0, green = 255, blue = 255)
        val MAGENTA = Color(red = 255, green = 0, blue = 255)
        val GRAY = Color(red = 128, green = 128, blue = 128)
        val TRANSPARENT = Color(alpha = 0, red = 0, green = 0, blue = 0)

        /**
         * Create a color from HSV values
         * @param hue Hue value (0.0 - 360.0)
         * @param saturation Saturation (0.0 - 1.0)
         * @param value Value/Brightness (0.0 - 1.0)
         * @param alpha Alpha (0-255)
         */
        fun fromHSV(
            hue: Float,
            saturation: Float,
            value: Float,
            alpha: Int = 255,
        ): Color {
            val rgb = hsvToRgb(hue, saturation, value)
            return Color(
                alpha = alpha.coerceIn(0, 255),
                red = (rgb shr 16) and 0xFF,
                green = (rgb shr 8) and 0xFF,
                blue = rgb and 0xFF,
            )
        }

        /**
         * Convert a packed ARGB int to HSV. Pure Kotlin so it works off-device.
         * @return `[hue 0-360, saturation 0-1, value 0-1]`
         */
        fun rgbToHsv(argb: Int): FloatArray {
            val r = ((argb shr 16) and 0xFF) / 255f
            val g = ((argb shr 8) and 0xFF) / 255f
            val b = (argb and 0xFF) / 255f

            val max = maxOf(r, g, b)
            val min = minOf(r, g, b)
            val delta = max - min

            var hue =
                when {
                    delta == 0f -> 0f
                    max == r -> 60f * (((g - b) / delta) % 6f)
                    max == g -> 60f * (((b - r) / delta) + 2f)
                    else -> 60f * (((r - g) / delta) + 4f)
                }
            if (hue < 0f) hue += 360f

            val saturation = if (max == 0f) 0f else delta / max
            return floatArrayOf(hue, saturation, max)
        }

        /**
         * Convert HSV to a packed, fully opaque ARGB int. Pure Kotlin so it works off-device.
         */
        fun hsvToRgb(
            hue: Float,
            saturation: Float,
            value: Float,
        ): Int {
            require(hue.isFinite() && saturation.isFinite() && value.isFinite()) { "HSV components must be finite" }
            val s = saturation.coerceIn(0f, 1f)
            val v = value.coerceIn(0f, 1f)
            val h = ((hue % 360f) + 360f) % 360f

            val c = v * s
            val hp = h / 60f
            val x = c * (1f - kotlin.math.abs((hp % 2f) - 1f))

            val (r1, g1, b1) =
                when {
                    hp < 1f -> Triple(c, x, 0f)
                    hp < 2f -> Triple(x, c, 0f)
                    hp < 3f -> Triple(0f, c, x)
                    hp < 4f -> Triple(0f, x, c)
                    hp < 5f -> Triple(x, 0f, c)
                    else -> Triple(c, 0f, x)
                }

            val m = v - c
            // Round to the nearest channel: truncation darkens exact RGB -> HSV -> RGB
            // round trips and turns full-brightness picker corners into channel 254.
            val r = ((r1 + m) * 255f).roundToInt().coerceIn(0, 255)
            val g = ((g1 + m) * 255f).roundToInt().coerceIn(0, 255)
            val b = ((b1 + m) * 255f).roundToInt().coerceIn(0, 255)

            return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }

        /**
         * Create a color from RGB values (0-255 each)
         */
        fun fromRGB(
            red: Int,
            green: Int,
            blue: Int,
            alpha: Int = 255,
        ): Color =
            Color(
                alpha = alpha,
                red = red.coerceIn(0, 255),
                green = green.coerceIn(0, 255),
                blue = blue.coerceIn(0, 255),
            )

        /**
         * Parse a hex color string (#RRGGBB or #AARRGGBB)
         */
        fun fromHex(hex: String): Color {
            val cleanHex = hex.removePrefix("#")
            require(cleanHex.length == 6 || cleanHex.length == 8) {
                "Hex color must be in format #RRGGBB or #AARRGGBB"
            }

            return try {
                val argb = cleanHex.toLong(16).toInt()
                if (cleanHex.length == 6) {
                    Color(argb = 0xFF000000.toInt() or argb)
                } else {
                    Color(argb = argb)
                }
            } catch (e: NumberFormatException) {
                throw IllegalArgumentException("Invalid hex color format: $hex")
            }
        }
    }
}
