package com.artflow.studio.core.perspective

import kotlin.math.roundToInt

/**
 * How a drawing guide's lines look: thickness, opacity and colour, the values Procreate's guide
 * editor exposes for its grid, isometric and perspective lines and for symmetry lines.
 *
 * The defaults reproduce the rendering that existed before these values were configurable: a
 * 1.2 screen-pixel hairline at 60% opacity in green `0x88CC66`. Symmetry lines use
 * [SYMMETRY_DEFAULT], which reproduces their earlier blue `0x2D9CDB` at 80% opacity.
 *
 * Pure values. The overlay turns them into Compose colours and stroke widths; nothing here is
 * persisted with the artwork (guide settings are session state, as they were before).
 */
data class GuideLineStyle(
    /** Hairline width in screen pixels. The overlay keeps it constant while the artwork zooms. */
    val thickness: Float = DEFAULT_THICKNESS,
    /** Line opacity `0..1`. */
    val opacity: Float = DEFAULT_OPACITY,
    /** Line colour as `0xRRGGBB`; the alpha channel comes from [opacity]. */
    val colour: Int = DEFAULT_COLOUR,
) {
    /** ARGB colour: the RGB of [colour] with alpha taken from [opacity] (rounded to 0..255). */
    fun argb(): Int {
        val alpha = (sanitize().opacity * 255f).roundToInt()
        return (alpha shl 24) or (colour and RGB_MASK)
    }

    /**
     * Stroke width in canvas pixels at zoom [scale]: [thickness] screen pixels, but never thinner
     * than [MIN_CANVAS_WIDTH] so lines stay visible when zoomed far out.
     */
    fun widthInCanvasPixels(scale: Float): Float {
        val safeScale = if (scale.isFinite() && scale > 0f) scale else 1f
        return (sanitize().thickness / safeScale).coerceAtLeast(MIN_CANVAS_WIDTH)
    }

    /** Clamps every value into its range; non-finite numbers fall back to the defaults. */
    fun sanitize(): GuideLineStyle =
        GuideLineStyle(
            thickness = thickness.finiteOr(DEFAULT_THICKNESS).coerceIn(THICKNESS_RANGE),
            opacity = opacity.finiteOr(DEFAULT_OPACITY).coerceIn(OPACITY_RANGE),
            colour = colour and RGB_MASK,
        )

    companion object {
        const val DEFAULT_THICKNESS = 1.2f
        const val DEFAULT_OPACITY = 0.6f
        const val DEFAULT_COLOUR = 0x88CC66

        /** Symmetry lines: the blue and opacity they were drawn with before this setting existed. */
        val SYMMETRY_DEFAULT = GuideLineStyle(opacity = 0.8f, colour = 0x2D9CDB)

        /** Thickness range offered by the guide editor, in screen pixels. */
        val THICKNESS_RANGE = 0.5f..6f

        /** Opacity range offered by the guide editor; zero would make the guide invisible. */
        val OPACITY_RANGE = 0.05f..1f

        /** Narrowest stroke, in canvas pixels, that the overlay draws. */
        const val MIN_CANVAS_WIDTH = 0.2f

        /** Colour choices offered as swatches in the guide editor (`0xRRGGBB`). */
        val COLOUR_CHOICES: List<Int> =
            listOf(
                0x88CC66,
                0x2D9CDB,
                0xFF3B30,
                0xF2C94C,
                0xBB6BD9,
                0xFFFFFF,
                0x000000,
            )
    }
}

private const val RGB_MASK = 0xFFFFFF

private fun Float.finiteOr(fallback: Float): Float = if (isFinite()) this else fallback
