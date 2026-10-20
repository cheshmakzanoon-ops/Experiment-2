package com.artflow.studio.core.canvas

import java.util.Locale
import kotlin.math.roundToInt

/**
 * The units a canvas size can be typed in, as in Procreate's Custom Canvas: pixels, inches,
 * centimetres and millimetres. A physical length means a number of pixels only together with a DPI.
 */
enum class CanvasUnit(
    val suffix: String,
    private val perInch: Double,
) {
    PIXELS("px", 0.0),
    INCHES("in", 1.0),
    CENTIMETERS("cm", 2.54),
    MILLIMETERS("mm", 25.4),
    ;

    /** The pixels a length of [value] takes at [dpi]; 0 when the length or the DPI is not usable. */
    fun toPixels(
        value: Double,
        dpi: Int,
    ): Int {
        if (!value.isFinite() || value < 0.0) return 0
        val pixels =
            when {
                this == PIXELS -> value
                dpi > 0 -> value / perInch * dpi
                else -> 0.0
            }
        return pixels.coerceAtMost(Int.MAX_VALUE.toDouble()).roundToInt()
    }

    /** [pixels] as a length in this unit at [dpi]. */
    fun fromPixels(
        pixels: Int,
        dpi: Int,
    ): Double =
        when {
            this == PIXELS -> pixels.toDouble()
            dpi > 0 -> pixels * perInch / dpi
            else -> 0.0
        }

    /**
     * [value] as text to edit: whole pixels, otherwise up to four decimals without trailing zeros.
     * Four decimals keep a size typed in inches or centimetres on the same pixel when it is converted back.
     */
    fun format(value: Double): String =
        if (this == PIXELS) {
            value.roundToInt().toString()
        } else {
            String.format(Locale.ROOT, "%.4f", value).trimEnd('0').trimEnd('.')
        }

    /**
     * What a text field for a length in this unit keeps of [typed]: ASCII digits (other scripts' digits are
     * converted) and, except for pixels, a single decimal mark written ".".
     */
    fun sanitize(typed: String): String {
        val kept = StringBuilder()
        var marked = false
        for (c in ascii(typed)) {
            if (c in '0'..'9') {
                kept.append(c)
            } else if (c == '.' && this != PIXELS && !marked) {
                kept.append(c)
                marked = true
            }
        }
        return kept.take(MAX_CHARS).toString()
    }

    companion object {
        private const val MAX_CHARS = 8

        /** A typed length; "," and the Arabic decimal mark count as ".". Null for blank, negative or non-numeric text. */
        fun parse(text: String): Double? = ascii(text.trim()).toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 }

        /** [text] with every decimal digit of any script written 0-9 and the comma and the Arabic decimal mark written ".". */
        private fun ascii(text: String): String =
            buildString(text.length) {
                for (c in text) {
                    append(
                        when {
                            c.isDigit() -> '0' + c.digitToInt()
                            c == ',' || c == ARABIC_DECIMAL_MARK -> '.'
                            else -> c
                        },
                    )
                }
            }

        private const val ARABIC_DECIMAL_MARK = '٫'
    }
}
