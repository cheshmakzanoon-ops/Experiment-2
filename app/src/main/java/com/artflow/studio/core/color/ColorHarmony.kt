package com.artflow.studio.core.color

import com.artflow.studio.domain.model.Color
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Colour-picker maths (Phase 31): harmonies, naming, contrast checks and the conversions the
 * picker needs. Pure Kotlin so the colour logic is unit-testable.
 */
object ColorHarmony {
    enum class Harmony(
        val displayName: String,
        val description: String,
    ) {
        COMPLEMENTARY("Complementary", "Opposite on the colour wheel - maximum contrast"),
        SPLIT_COMPLEMENTARY("Split Complementary", "Two colours either side of the complement"),
        ANALOGOUS("Analogous", "Neighbours on the wheel - naturally harmonious"),
        TRIADIC("Triadic", "Three colours evenly spaced - vibrant but balanced"),
        TETRADIC("Tetradic", "Two complementary pairs - rich and varied"),
        SQUARE("Square", "Four colours at 90 degree steps"),
        MONOCHROMATIC("Monochromatic", "One hue at different saturation and value"),
    }

    /**
     * Colours that harmonise with [baseColor].
     * @param count for harmonies with a natural size, the canonical set is returned; for
     *   monochromatic, [count] controls how many steps are generated.
     */
    fun harmony(
        baseColor: Int,
        harmony: Harmony,
        count: Int = 5,
    ): List<Int> {
        val hsv = Color.rgbToHsv(baseColor)
        val hue = hsv[0]
        val saturation = hsv[1]
        val value = hsv[2]
        val alpha = (baseColor ushr 24) and 0xFF

        return when (harmony) {
            Harmony.COMPLEMENTARY ->
                listOf(
                    baseColor,
                    fromHsv(hue + 180f, saturation, value, alpha),
                )
            Harmony.SPLIT_COMPLEMENTARY ->
                listOf(
                    baseColor,
                    fromHsv(hue + 150f, saturation, value, alpha),
                    fromHsv(hue + 210f, saturation, value, alpha),
                )
            Harmony.ANALOGOUS ->
                listOf(
                    fromHsv(hue - 30f, saturation, value, alpha),
                    baseColor,
                    fromHsv(hue + 30f, saturation, value, alpha),
                )
            Harmony.TRIADIC ->
                listOf(
                    baseColor,
                    fromHsv(hue + 120f, saturation, value, alpha),
                    fromHsv(hue + 240f, saturation, value, alpha),
                )
            Harmony.TETRADIC ->
                listOf(
                    baseColor,
                    fromHsv(hue + 60f, saturation, value, alpha),
                    fromHsv(hue + 180f, saturation, value, alpha),
                    fromHsv(hue + 240f, saturation, value, alpha),
                )
            Harmony.SQUARE ->
                listOf(
                    baseColor,
                    fromHsv(hue + 90f, saturation, value, alpha),
                    fromHsv(hue + 180f, saturation, value, alpha),
                    fromHsv(hue + 270f, saturation, value, alpha),
                )
            Harmony.MONOCHROMATIC -> {
                val steps = count.coerceIn(2, 12)
                (0 until steps).map { index ->
                    val t = index / (steps - 1f)
                    // Sweep value up and saturation down so the ramp stays usable.
                    val v = (0.35f + 0.65f * t).coerceIn(0f, 1f)
                    val s = (saturation * (1.15f - 0.5f * t)).coerceIn(0f, 1f)
                    fromHsv(hue, s, v, alpha)
                }
            }
        }
    }

    /** HSV triple to a packed ARGB int, clamped for safe use from sliders. */
    fun fromHsv(
        hue: Float,
        saturation: Float,
        value: Float,
        alpha: Int = 255,
    ): Int {
        val rgb = Color.hsvToRgb(((hue % 360f) + 360f) % 360f, saturation.coerceIn(0f, 1f), value.coerceIn(0f, 1f))
        return (rgb and 0x00FFFFFF) or ((alpha.coerceIn(0, 255)) shl 24)
    }

    /** RGB channels to a packed int without touching `android.graphics.Color`. */
    fun fromRgb(
        red: Int,
        green: Int,
        blue: Int,
        alpha: Int = 255,
    ): Int =
        ((alpha.coerceIn(0, 255)) shl 24) or
            ((red.coerceIn(0, 255)) shl 16) or
            ((green.coerceIn(0, 255)) shl 8) or
            (blue.coerceIn(0, 255))

    /** CMYK channels (0..1 each) to packed ARGB. */
    fun fromCmyk(
        cyan: Float,
        magenta: Float,
        yellow: Float,
        black: Float,
        alpha: Int = 255,
    ): Int {
        val c = cyan.coerceIn(0f, 1f)
        val m = magenta.coerceIn(0f, 1f)
        val y = yellow.coerceIn(0f, 1f)
        val k = black.coerceIn(0f, 1f)
        val red = (255f * (1f - c) * (1f - k)).roundToInt()
        val green = (255f * (1f - m) * (1f - k)).roundToInt()
        val blue = (255f * (1f - y) * (1f - k)).roundToInt()
        return fromRgb(red, green, blue, alpha)
    }

    /** Packed ARGB to CMYK (0..1 each). */
    fun toCmyk(argb: Int): FloatArray {
        val r = ((argb shr 16) and 0xFF) / 255f
        val g = ((argb shr 8) and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f
        val k = 1f - max(r, max(g, b))
        if (k >= 1f) return floatArrayOf(0f, 0f, 0f, 1f)
        val denominator = 1f - k
        return floatArrayOf(
            (1f - r - k) / denominator,
            (1f - g - k) / denominator,
            (1f - b - k) / denominator,
            k,
        )
    }

    /** `#RRGGBB` for opaque colours, `#AARRGGBB` when the alpha channel is in use. */
    fun toHex(argb: Int): String {
        val alpha = (argb ushr 24) and 0xFF
        // Locale-independent on purpose: hex digits must not vary by locale.
        return if (alpha == 255) {
            String.format(Locale.ROOT, "#%06X", argb and 0x00FFFFFF)
        } else {
            String.format(Locale.ROOT, "#%08X", argb)
        }
    }

    /** Parses `#RGB`, `#RRGGBB`, `#AARRGGBB` or `RRGGBB`. Returns null when unparseable. */
    fun parseHex(input: String): Int? {
        val cleaned = input.trim().removePrefix("#")
        return try {
            when (cleaned.length) {
                3 -> {
                    val r = cleaned[0].digitToInt(16) * 17
                    val g = cleaned[1].digitToInt(16) * 17
                    val b = cleaned[2].digitToInt(16) * 17
                    fromRgb(r, g, b)
                }
                6 -> 0xFF000000.toInt() or cleaned.toInt(16)
                8 -> cleaned.toLong(16).toInt()
                else -> null
            }
        } catch (e: NumberFormatException) {
            null
        }
    }

    /**
     * Nearest named colour, used by the picker's "colour name" readout.
     * The table is intentionally small and curated rather than the full CSS list, because artists
     * care about the difference between "terracotta" and "burnt sienna", not "gainsboro".
     */
    val NAMED_COLORS: List<Pair<String, Int>> =
        listOf(
            "Black" to 0xFF000000.toInt(),
            "Charcoal" to 0xFF2E2E2E.toInt(),
            "Slate" to 0xFF4A5568.toInt(),
            "Gray" to 0xFF808080.toInt(),
            "Silver" to 0xFFC0C0C0.toInt(),
            "White" to 0xFFFFFFFF.toInt(),
            "Cream" to 0xFFFFF8E1.toInt(),
            "Ivory" to 0xFFFFFFF0.toInt(),
            "Sand" to 0xFFE0C9A6.toInt(),
            "Tan" to 0xFFD2B48C.toInt(),
            "Terracotta" to 0xFFCB6843.toInt(),
            "Burnt Sienna" to 0xFFE97451.toInt(),
            "Rust" to 0xFFB7410E.toInt(),
            "Crimson" to 0xFFDC143C.toInt(),
            "Coral" to 0xFFFF6B5C.toInt(),
            "Salmon" to 0xFFFA8072.toInt(),
            "Peach" to 0xFFFFCBA4.toInt(),
            "Apricot" to 0xFFFBCEB1.toInt(),
            "Amber" to 0xFFFFBF00.toInt(),
            "Gold" to 0xFFFFD700.toInt(),
            "Mustard" to 0xFFD4AF37.toInt(),
            "Olive" to 0xFF808000.toInt(),
            "Moss" to 0xFF4A5D23.toInt(),
            "Forest" to 0xFF228B22.toInt(),
            "Emerald" to 0xFF2ECC71.toInt(),
            "Mint" to 0xFF98FF98.toInt(),
            "Seafoam" to 0xFF7FFFD4.toInt(),
            "Teal" to 0xFF008080.toInt(),
            "Turquoise" to 0xFF40E0D0.toInt(),
            "Cyan" to 0xFF00FFFF.toInt(),
            "Sky" to 0xFF87CEEB.toInt(),
            "Cerulean" to 0xFF007BA7.toInt(),
            "Azure" to 0xFF007FFF.toInt(),
            "Cobalt" to 0xFF0047AB.toInt(),
            "Navy" to 0xFF000080.toInt(),
            "Indigo" to 0xFF4B0082.toInt(),
            "Violet" to 0xFF8F00FF.toInt(),
            "Lavender" to 0xFFE6E6FA.toInt(),
            "Periwinkle" to 0xFFCCCCFF.toInt(),
            "Magenta" to 0xFFFF00FF.toInt(),
            "Fuchsia" to 0xFFFF77FF.toInt(),
            "Rose" to 0xFFFF007F.toInt(),
            "Maroon" to 0xFF800000.toInt(),
            "Chocolate" to 0xFF7B3F00.toInt(),
            "Umber" to 0xFF635147.toInt(),
            "Sepia" to 0xFF704214.toInt(),
            "Khaki" to 0xFFC3B091.toInt(),
        )

    /** Best-guess name for [argb]. */
    fun nameOf(argb: Int): String {
        val target = Color.rgbToHsv(argb)
        var best = "Custom"
        var bestScore = Float.MAX_VALUE
        NAMED_COLORS.forEach { (name, color) ->
            val hsv = Color.rgbToHsv(color)
            // Hue is circular, so compare with a wrap-around distance.
            val hueDelta = min(abs(hsv[0] - target[0]), 360f - abs(hsv[0] - target[0])) / 180f
            val saturationDelta = abs(hsv[1] - target[1])
            val valueDelta = abs(hsv[2] - target[2])
            val score = hueDelta * 1.4f + saturationDelta + valueDelta
            if (score < bestScore) {
                bestScore = score
                best = name
            }
        }
        return best
    }

    /**
     * Contrast ratio per WCAG 2.1, used by the picker's accessibility mode.
     * Values range from 1 (no contrast) to 21 (black on white).
     */
    fun contrastRatio(
        foreground: Int,
        background: Int,
    ): Float {
        val l1 = relativeLuminance(foreground)
        val l2 = relativeLuminance(background)
        val lighter = max(l1, l2)
        val darker = min(l1, l2)
        return (lighter + 0.05f) / (darker + 0.05f)
    }

    /** WCAG relative luminance. */
    fun relativeLuminance(argb: Int): Float {
        fun channel(value: Int): Float {
            val c = value / 255f
            return if (c <= 0.03928f) c / 12.92f else Math.pow(((c + 0.055) / 1.055).toDouble(), 2.4).toFloat()
        }
        val r = channel((argb shr 16) and 0xFF)
        val g = channel((argb shr 8) and 0xFF)
        val b = channel(argb and 0xFF)
        return 0.2126f * r + 0.7152f * g + 0.0722f * b
    }

    /** True when text in [foreground] is readable on [background]. */
    fun isReadableOn(
        foreground: Int,
        background: Int,
        largeText: Boolean = false,
    ): Boolean = contrastRatio(foreground, background) >= if (largeText) 3f else 4.5f

    /** Black or white, whichever reads better on [background]. */
    fun bestTextColor(background: Int): Int = if (relativeLuminance(background) > 0.35f) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()

    /** The classic colour-wheel position in degrees, for drawing a hue ring. */
    fun wheelPosition(
        hue: Float,
        radius: Float,
    ): Pair<Float, Float> {
        val radians = Math.toRadians(hue.toDouble())
        return (radius * kotlin.math.cos(radians)).toFloat() to (radius * kotlin.math.sin(radians)).toFloat()
    }

    /** Inverse of [wheelPosition]: the hue under a point on the wheel. */
    fun hueAt(
        dx: Float,
        dy: Float,
    ): Float {
        val degrees = Math.toDegrees(kotlin.math.atan2(dy.toDouble(), dx.toDouble())).toFloat()
        return ((degrees % 360f) + 360f) % 360f
    }

    /** Perceived lightness of a colour, independent of hue. */
    fun lightness(argb: Int): Float {
        val r = ((argb shr 16) and 0xFF) / 255f
        val g = ((argb shr 8) and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f
        val maxChannel = max(r, max(g, b))
        val minChannel = min(r, min(g, b))
        return (maxChannel + minChannel) / 2f
    }

    /** Distance between two colours in RGB space, for "similar colour" deduplication. */
    fun distance(
        a: Int,
        b: Int,
    ): Float {
        val dr = ((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)
        val dg = ((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)
        val db = (a and 0xFF) - (b and 0xFF)
        return sqrt((dr * dr + dg * dg + db * db).toFloat())
    }

    /** Lightens or darkens while keeping hue and saturation (used by tint/shade swatches). */
    fun adjustLightness(
        argb: Int,
        amount: Float,
    ): Int {
        val hsv = Color.rgbToHsv(argb)
        val value =
            if (amount >= 0f) {
                hsv[2] + (1f - hsv[2]) * amount
            } else {
                hsv[2] * (1f + amount)
            }
        val rgb = Color.hsvToRgb(hsv[0], hsv[1], value.coerceIn(0f, 1f))
        return (rgb and 0x00FFFFFF) or (argb and 0xFF000000.toInt())
    }

    /** Tint (mix with white) and shade (mix with black) rows shown under the picker. */
    fun tintsAndShades(
        argb: Int,
        steps: Int = 5,
    ): List<Int> {
        val result = mutableListOf<Int>()
        for (i in steps downTo 1) result += adjustLightness(argb, i * -0.16f)
        result += argb
        for (i in 1..steps) result += adjustLightness(argb, i * 0.16f)
        return result
    }

    /** Deduplicates a recent-colour list while preserving order (most recent first). */
    fun deduplicateRecent(
        colors: List<Int>,
        maxSize: Int = 24,
        threshold: Float = 6f,
    ): List<Int> {
        val result = mutableListOf<Int>()
        colors.forEach { candidate ->
            if (result.none { distance(it, candidate) < threshold }) result += candidate
        }
        return result.take(maxSize)
    }

    /** Adds a colour to the front of a recent list. */
    fun pushRecent(
        colors: List<Int>,
        color: Int,
        maxSize: Int = 24,
    ): List<Int> = deduplicateRecent(listOf(color) + colors, maxSize)

    /** All harmonies with their generated swatches, for the picker's harmony grid. */
    fun allHarmonies(baseColor: Int): Map<Harmony, List<Int>> = Harmony.entries.associateWith { harmony(baseColor, it) }

    /** Colour model the picker is currently showing. */
    enum class ColorMode(
        val displayName: String,
    ) {
        HSV("HSV"),
        RGB("RGB"),
        CMYK("CMYK"),
        HEX("Hex"),
    }

    /** Formats channel values for the numeric readouts in each mode. */
    fun formatChannels(
        argb: Int,
        mode: ColorMode,
    ): List<Pair<String, Float>> {
        val hsv = Color.rgbToHsv(argb)
        return when (mode) {
            ColorMode.HSV ->
                listOf(
                    "H" to hsv[0],
                    "S" to hsv[1],
                    "V" to hsv[2],
                )
            ColorMode.RGB ->
                listOf(
                    "R" to ((argb shr 16) and 0xFF).toFloat(),
                    "G" to ((argb shr 8) and 0xFF).toFloat(),
                    "B" to (argb and 0xFF).toFloat(),
                )
            ColorMode.CMYK -> {
                val cmyk = toCmyk(argb)
                listOf(
                    "C" to cmyk[0],
                    "M" to cmyk[1],
                    "Y" to cmyk[2],
                    "K" to cmyk[3],
                )
            }
            ColorMode.HEX -> listOf("Hex" to 0f)
        }
    }

    /** Ranges for each channel slider. */
    fun channelRange(
        mode: ColorMode,
        index: Int,
    ): ClosedFloatingPointRange<Float> =
        when (mode) {
            ColorMode.HSV ->
                when (index) {
                    0 -> 0f..360f
                    else -> 0f..1f
                }
            ColorMode.RGB -> 0f..255f
            ColorMode.CMYK -> 0f..1f
            ColorMode.HEX -> 0f..0f
        }

    /** Builds a colour from edited channel values and the untouched channels of [base]. */
    fun withChannel(
        base: Int,
        mode: ColorMode,
        index: Int,
        value: Float,
    ): Int {
        val hsv = Color.rgbToHsv(base)
        val alpha = (base ushr 24) and 0xFF
        return when (mode) {
            ColorMode.HSV -> {
                val edited = hsv.copyOf()
                edited[index] = value
                fromHsv(edited[0], edited[1], edited[2], alpha)
            }
            ColorMode.RGB -> {
                val channels = intArrayOf((base shr 16) and 0xFF, (base shr 8) and 0xFF, base and 0xFF)
                channels[index] = value.roundToInt().coerceIn(0, 255)
                fromRgb(channels[0], channels[1], channels[2], alpha)
            }
            ColorMode.CMYK -> {
                val cmyk = toCmyk(base)
                cmyk[index] = value
                fromCmyk(cmyk[0], cmyk[1], cmyk[2], cmyk[3], alpha)
            }
            ColorMode.HEX -> base
        }
    }
}
