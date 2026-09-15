package com.artflow.studio.domain.model

import androidx.compose.ui.graphics.Color

/**
 * Domain model representing a color with ARGB components
 * Used throughout the app for brush colors, shape fills, etc.
 */
data class Color(
    val alpha: Int = 255,
    val red: Int = 0,
    val green: Int = 0,
    val blue: Int = 0
) {
    /**
     * Create a color from ARGB values (0-255 each)
     */
    constructor(argb: Int) : this(
        alpha = (argb shr 24) and 0xFF,
        red = (argb shr 16) and 0xFF,
        green = (argb shr 8) and 0xFF,
        blue = argb and 0xFF
    )

    /**
     * Convert to Android Compose Color
     */
    fun toComposeColor(): androidx.compose.ui.graphics.Color {
        return androidx.compose.ui.graphics.Color(alpha, red, green, blue)
    }

    /**
     * Convert to Android Graphics Color (Int)
     */
    fun toAndroidColor(): Int {
        return android.graphics.Color.argb(alpha, red, green, blue)
    }

    /**
     * Convert to HSV representation
     */
    fun toHSV(): FloatArray {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(toAndroidColor(), hsv)
        return hsv
    }

    /**
     * Create a copy with modified alpha
     */
    fun withAlpha(newAlpha: Int): Color {
        return copy(alpha = newAlpha.coerceIn(0, 255))
    }

    /**
     * Create a copy with modified alpha (float 0.0 - 1.0)
     */
    fun withAlpha(alphaFloat: Float): Color {
        return withAlpha((alphaFloat * 255).toInt().coerceIn(0, 255))
    }

    /**
     * Blend this color with another using a factor (0.0 - 1.0)
     * @param other The color to blend with
     * @param factor Blend factor (0.0 = this color, 1.0 = other color)
     */
    fun blendWith(other: Color, factor: Float): Color {
        val clampedFactor = factor.coerceIn(0f, 1f)
        return Color(
            alpha = ((alpha * (1 - clampedFactor)) + (other.alpha * clampedFactor)).toInt(),
            red = ((red * (1 - clampedFactor)) + (other.red * clampedFactor)).toInt(),
            green = ((green * (1 - clampedFactor)) + (other.green * clampedFactor)).toInt(),
            blue = ((blue * (1 - clampedFactor)) + (other.blue * clampedFactor)).toInt()
        )
    }

    /**
     * Get the luminance of this color (0.0 - 1.0)
     */
    fun getLuminance(): Float {
        return (0.299f * red + 0.587f * green + 0.114f * blue) / 255f
    }

    /**
     * Get a contrasting color (black or white) based on luminance
     */
    fun getContrastingColor(): Color {
        return if (getLuminance() > 0.5f) BLACK else WHITE
    }

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
        fun fromHSV(hue: Float, saturation: Float, value: Float, alpha: Int = 255): Color {
            val hsv = floatArrayOf(hue, saturation, value)
            val rgb = android.graphics.Color.HSVToColor(hsv)
            return Color(
                alpha = alpha,
                red = (rgb shr 16) and 0xFF,
                green = (rgb shr 8) and 0xFF,
                blue = rgb and 0xFF
            )
        }

        /**
         * Create a color from RGB values (0-255 each)
         */
        fun fromRGB(red: Int, green: Int, blue: Int, alpha: Int = 255): Color {
            return Color(
                alpha = alpha,
                red = red.coerceIn(0, 255),
                green = green.coerceIn(0, 255),
                blue = blue.coerceIn(0, 255)
            )
        }

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
