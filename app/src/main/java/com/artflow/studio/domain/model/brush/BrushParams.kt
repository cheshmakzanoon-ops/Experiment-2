package com.artflow.studio.domain.model.brush

import com.artflow.studio.domain.model.layer.BlendMode
import kotlinx.serialization.Serializable
import java.util.Random

/**
 * Domain model representing brush parameters
 * Used by the brush engine to control stroke rendering
 * Implements Phase 9: Advanced Brush Parameters
 */
@Serializable
data class BrushParams(
    val size: Float = 20f, // Brush size in pixels
    val opacity: Float = 1.0f, // Opacity 0.0 - 1.0
    val spacing: Float = 0.1f, // Spacing between dabs (0.0 - 1.0)
    val scatter: Float = 0.0f, // Scatter amount (0.0 - 1.0)
    val count: Int = 1, // Number of dabs per spacing interval
    val rotation: Float = 0f, // Brush rotation in degrees
    val taperStart: Float = 0f, // Taper at stroke start (0.0 - 1.0)
    val taperEnd: Float = 0f, // Taper at stroke end (0.0 - 1.0)
    // Pressure dynamics
    val pressureToSize: Float = 0.5f, // How much pressure affects size
    val pressureToOpacity: Float = 0.3f, // How much pressure affects opacity
    val pressureCurve: PressureCurve = PressureCurve.LINEAR,
    val customPressure: PressureResponse = PressureResponse(),
    // Color dynamics - Phase 9: Advanced Brush Parameters
    val hueJitter: Float = 0f, // Hue variation (0.0 - 1.0)
    val saturationJitter: Float = 0f, // Saturation variation (0.0 - 1.0)
    val brightnessJitter: Float = 0f, // Brightness variation (0.0 - 1.0)
    val colorPressure: Boolean = false, // Enable pressure-based color dynamics
    // Size and Opacity Jitter - Phase 9
    val sizeJitter: Float = 0f, // Random size variation (0.0 - 1.0)
    val opacityJitter: Float = 0f, // Random opacity variation (0.0 - 1.0)
    // Stroke behavior
    val smoothing: Float = 0.5f, // Stroke smoothing amount (0.0 - 1.0)
    val wetMix: Float = 0f, // Wet paint mixing (0.0 - 1.0)
    val flow: Float = 1.0f, // Paint flow rate (0.0 - 1.0)
    // Texture
    val textureId: String? = null, // Texture identifier
    val textureScale: Float = 1.0f, // Texture scale factor
    val textureRotation: Float = 0f, // Texture rotation in degrees
    val blendTexture: Boolean = false, // Blend texture with color
    // Advanced
    val tiltInfluence: Float = 0f, // How much tilt affects brush
    val tiltToRotation: Boolean = false, // Map tilt to brush rotation
    val velocityToSize: Float = 0f, // Speed affects size (0.0 - 1.0)
    val velocityToOpacity: Float = 0f, // Speed affects opacity (0.0 - 1.0)
    val velocityToHue: Float = 0f, // Speed affects hue shift (0.0 - 1.0)
    val roundness: Float = 1f, // Tip shape: 1 is round, lower values flatten it along [rotation]
    val shapeId: String? = null, // Imported tip image ("custom-…"); null stamps the round/flat tip
    val blendMode: BlendMode = BlendMode.NORMAL, // How each stroke combines with the layer's existing paint
    val wetEdges: Float = 0f, // 0..1: paint pools toward the stroke's edges like watercolour
    val dual: DualBrush? = null, // Second brush combined with this one along the same path
    val taperOpacity: Float = 0f, // 0..1: how much the tapered ends also fade
    val falloff: Float = 0f, // 0..1: the stroke fades out along its path; 1 fades within one brush size
    val tipFlipX: Boolean = false, // Mirror the shape image across its vertical axis
    val tipFlipY: Boolean = false, // Mirror the shape image across its horizontal axis
    val tipRandomized: Boolean = false, // Each stroke starts the tip at a random angle
    val buildUp: Boolean = false, // Blending rendering: overlapping dabs build up within one stroke instead of glazing
    val grainMoving: Boolean = false, // Grain starts afresh with each stroke instead of staying fixed to the canvas
    val secondaryPressure: Float = 0f, // 0..1: firmer pressure blends the colour toward the secondary colour
    val secondaryJitter: Float = 0f, // 0..1: each dab blends a random amount toward the secondary colour
    val burntEdges: Float = 0f, // 0..1: paint darkens where the stroke's coverage falls off, like a scorched rim
    val grainDepth: Float = 1f, // 0..1: how strongly the grain shows; 0 paints as if there were no grain
    val dilution: Float = 0f, // 0..1: water in the paint; thins each dab and lets it pick up more of the layer
    val pull: Float = 0f, // 0..1: how far the brush drags the colour it carries along the stroke
    val minSize: Float = MIN_BRUSH_SIZE, // Brush Studio Properties: the sidebar's size slider runs from this...
    val maxSize: Float = MAX_BRUSH_SIZE, // ...to this, in pixels
    val minOpacity: Float = MIN_BRUSH_OPACITY, // and its opacity slider from this...
    val maxOpacity: Float = 1f, // ...to this
    val grainBrightness: Float = 0f, // -1..1: lightens or darkens the grain
    val grainContrast: Float = 0f, // -1..1: flattens the grain or sharpens it toward black and white
    val wetBlur: Float = 0f, // 0..1: Wet Mix Blur; the paint picked up is averaged over this share of the brush
    val countJitter: Float = 0f, // 0..1: each dab stamps a random number of copies, from count down to one at 1
    val colorJitterPerStroke: Boolean = false,
    /** Procreate's Colour Pressure (0..1 each): lighter presses drift the hue, wash out saturation, darken. */
    val colorDynamics: ColorDynamics = ColorDynamics(),
    /** Procreate's rendering mode; null keeps the older [buildUp] switch (Light glaze or Uniform blending). */
    val renderingMode: RenderingMode? = null, // Colour jitter picks one colour per stroke instead of varying each dab
) {
    /** How dabs accumulate within one stroke. */
    val rendering: RenderingMode
        get() = renderingMode ?: if (buildUp) RenderingMode.UNIFORM_BLENDING else RenderingMode.LIGHT_GLAZE

    /** The sizes the sidebar offers for this brush (Procreate's Min and Max size). */
    val sizeLimits: ClosedFloatingPointRange<Float>
        get() = limits(minSize, maxSize, MIN_BRUSH_SIZE, MAX_BRUSH_SIZE)

    /** The opacities the sidebar offers for this brush (Procreate's Min and Max opacity). */
    val opacityLimits: ClosedFloatingPointRange<Float>
        get() = limits(minOpacity, maxOpacity, MIN_BRUSH_OPACITY, 1f)

    /** Imported grain and shape images this brush (and its second brush) paints with. */
    val imageIds: List<String>
        get() = listOfNotNull(textureId, shapeId) + (dual?.params?.imageIds ?: emptyList())

    /**
     * Pressure curve types for mapping stylus pressure
     */
    @Serializable
    enum class PressureCurve {
        LINEAR, // Direct 1:1 mapping
        EASE_IN, // Gradual start, sharp end
        EASE_OUT, // Sharp start, gradual end
        EASE_IN_OUT, // Gradual start and end
        CUSTOM, // Editable monotone response with three pressure control points
    }

    /**
     * Calculate effective size; velocity is pixels per millisecond.
     * A caller-supplied stroke seed makes jitter reproducible. Without a generator, jitter is neutral.
     */
    fun calculateEffectiveSize(
        pressure: Float,
        velocity: Float = 0f,
        random: Random? = null,
    ): Float {
        var effectiveSize = size.coerceAtLeast(1f)

        // Apply pressure dynamics
        if (pressureToSize > 0f) {
            val pressureFactor = pressureResponse(pressure)
            val influence = pressureToSize.coerceIn(0f, 1f)
            effectiveSize *= 1f - influence + pressureFactor * influence
        }

        // Apply velocity dynamics
        if (velocityToSize > 0f && velocity > 0f) {
            val normalizedVelocity = velocity.coerceIn(0f, 10f) / 10f
            effectiveSize *= (1f - normalizedVelocity * velocityToSize * 0.5f)
        }

        // Apply size jitter
        if (sizeJitter > 0f) {
            val jitterFactor = 1f + ((random?.nextFloat() ?: 0.5f) * 2f - 1f) * sizeJitter
            effectiveSize *= jitterFactor
        }

        return effectiveSize.coerceAtLeast(1f)
    }

    /**
     * Calculate effective opacity without losing the artist's base opacity.
     * Zero is genuinely transparent, including when pressure dynamics or jitter are enabled.
     */
    fun calculateEffectiveOpacity(
        pressure: Float,
        velocity: Float = 0f,
        random: Random? = null,
    ): Float {
        var effectiveOpacity = opacity.coerceIn(0f, 1f)

        // Apply pressure dynamics
        if (pressureToOpacity > 0f) {
            val pressureFactor = pressureResponse(pressure)
            val influence = pressureToOpacity.coerceIn(0f, 1f)
            effectiveOpacity *= 1f - influence + pressureFactor * influence
        }

        // Apply velocity dynamics
        if (velocityToOpacity > 0f && velocity > 0f) {
            val normalizedVelocity = velocity.coerceIn(0f, 10f) / 10f
            effectiveOpacity *= (1f - normalizedVelocity * velocityToOpacity * 0.3f)
        }

        // Apply opacity jitter
        if (opacityJitter > 0f) {
            val jitterFactor = ((random?.nextFloat() ?: 0.5f) * 2f - 1f) * opacityJitter
            effectiveOpacity *= 1f + jitterFactor
        }

        return effectiveOpacity.coerceIn(0f, 1f)
    }

    /** The same pressure mapping is used by painting, erasing, color dynamics and the UI graph. */
    fun pressureResponse(pressure: Float): Float {
        require(pressure.isFinite()) { "Pressure must be finite" }
        val input = pressure.coerceIn(0f, 1f)
        return when (pressureCurve) {
            PressureCurve.LINEAR -> input
            PressureCurve.EASE_IN -> input * input
            PressureCurve.EASE_OUT -> input * (2f - input)
            PressureCurve.EASE_IN_OUT -> {
                if (input < 0.5f) {
                    2f * input * input
                } else {
                    1f - (-2f * input + 2f).let { it * it } / 2f
                }
            }
            PressureCurve.CUSTOM -> customPressure.map(input)
        }
    }

    /**
     * Calculate color with hue/saturation/brightness jitter applied
     */
    fun applyColorJitter(
        baseColor: Int,
        pressure: Float = 1f,
        velocity: Float = 0f,
        random: Random? = null,
        secondary: Int? = null,
        tilt: Float = 0f,
    ): Int {
        val mixed = towardSecondary(baseColor, secondary, pressure, random)
        return colorDynamics.apply(jittered(mixed, pressure, velocity, random), pressureResponse(pressure), tilt)
    }

    /** Procreate's secondary colour dynamics: pressure and per-dab jitter blend toward [secondary]. */
    private fun towardSecondary(
        baseColor: Int,
        secondary: Int?,
        pressure: Float,
        random: Random?,
    ): Int {
        if (secondary == null || (secondaryPressure <= 0f && secondaryJitter <= 0f)) return baseColor
        val amount =
            (
                secondaryPressure.coerceIn(0f, 1f) * pressureResponse(pressure) +
                    secondaryJitter.coerceIn(0f, 1f) * (random?.nextFloat() ?: 0.5f)
            ).coerceIn(0f, 1f)

        fun channel(shift: Int): Int {
            val from = (baseColor shr shift) and 0xFF
            val to = (secondary shr shift) and 0xFF
            return (from + (to - from) * amount + 0.5f).toInt().coerceIn(0, 255)
        }
        return (baseColor and 0xFF000000.toInt()) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }

    private fun jittered(
        baseColor: Int,
        pressure: Float,
        velocity: Float,
        random: Random?,
    ): Int {
        if (hueJitter <= 0f &&
            saturationJitter <= 0f &&
            brightnessJitter <= 0f &&
            !colorPressure &&
            velocityToHue <= 0f
        ) {
            return baseColor
        }

        val hsv =
            com.artflow.studio.domain.model.Color
                .rgbToHsv(baseColor)

        // Apply hue jitter
        if (hueJitter > 0f) {
            val hueShift = ((random?.nextFloat() ?: 0.5f) * 2f - 1f) * hueJitter * 360f
            hsv[0] = (hsv[0] + hueShift) % 360f
            if (hsv[0] < 0f) hsv[0] += 360f
        }

        // Apply velocity-based hue shift
        if (velocityToHue > 0f && velocity > 0f) {
            val normalizedVelocity = velocity.coerceIn(0f, 10f) / 10f
            val velocityHueShift = normalizedVelocity * velocityToHue * 60f
            hsv[0] = (hsv[0] + velocityHueShift) % 360f
        }

        // Apply saturation jitter
        if (saturationJitter > 0f) {
            val satShift = ((random?.nextFloat() ?: 0.5f) * 2f - 1f) * saturationJitter
            hsv[1] = (hsv[1] + satShift).coerceIn(0f, 1f)
        }

        // Apply brightness jitter
        if (brightnessJitter > 0f) {
            val brightShift = ((random?.nextFloat() ?: 0.5f) * 2f - 1f) * brightnessJitter
            hsv[2] = (hsv[2] + brightShift).coerceIn(0f, 1f)
        }

        // Apply pressure-based color dynamics
        if (colorPressure) {
            val pressureFactor = pressureResponse(pressure)
            hsv[2] *= (0.5f + pressureFactor * 0.5f) // Darker at low pressure
        }

        // Preserve the base colour's alpha channel; only hue/sat/value are modulated.
        val rgb =
            com.artflow.studio.domain.model.Color
                .hsvToRgb(hsv[0], hsv[1], hsv[2])
        return (baseColor and 0xFF000000.toInt()) or (rgb and 0x00FFFFFF)
    }
}

/** The smallest and largest brush sizes the app offers, in pixels. */
const val MIN_BRUSH_SIZE = 1f
const val MAX_BRUSH_SIZE = 512f

/** The faintest opacity a brush slider offers. */
const val MIN_BRUSH_OPACITY = 0.01f

/** [low]..[high] kept finite, within [floor]..[ceiling] and in order; the full range when they are not. */
private fun limits(
    low: Float,
    high: Float,
    floor: Float,
    ceiling: Float,
): ClosedFloatingPointRange<Float> {
    val from = if (low.isFinite()) low.coerceIn(floor, ceiling) else floor
    val to = if (high.isFinite()) high.coerceIn(floor, ceiling) else ceiling
    return if (from < to) from..to else floor..ceiling
}

/**
 * Procreate's Colour Pressure and Colour Tilt: how far a light press (and a pen tilted toward flat)
 * moves the colour from the one chosen. Each amount is 0 to 1; at full press and upright pen the
 * colour is exactly the chosen one.
 */
@Serializable
data class ColorDynamics(
    val pressureHue: Float = 0f,
    val pressureSaturation: Float = 0f,
    val pressureBrightness: Float = 0f,
    val tiltHue: Float = 0f,
    val tiltSaturation: Float = 0f,
    val tiltBrightness: Float = 0f,
) {
    val isActive: Boolean get() = amounts().any { it > 0f }

    fun amounts(): List<Float> = listOf(pressureHue, pressureSaturation, pressureBrightness, tiltHue, tiltSaturation, tiltBrightness)

    /** [color] moved by a press of [pressure] (after the pressure curve) with the pen [tilt] from upright (0) to flat (1). */
    fun apply(
        color: Int,
        pressure: Float,
        tilt: Float,
    ): Int {
        if (!isActive) return color
        val light = 1f - pressure.coerceIn(0f, 1f)
        val flat = tilt.coerceIn(0f, 1f)
        val hsv =
            com.artflow.studio.domain.model.Color
                .rgbToHsv(color)
        val hueShift = (unit(pressureHue) * light + unit(tiltHue) * flat) * HALF_TURN
        hsv[0] = ((hsv[0] + hueShift) % FULL_TURN + FULL_TURN) % FULL_TURN
        hsv[1] *= (1f - unit(pressureSaturation) * light) * (1f - unit(tiltSaturation) * flat)
        hsv[2] *= (1f - unit(pressureBrightness) * light) * (1f - unit(tiltBrightness) * flat)
        val rgb =
            com.artflow.studio.domain.model.Color
                .hsvToRgb(hsv[0], hsv[1].coerceIn(0f, 1f), hsv[2].coerceIn(0f, 1f))
        return (color and 0xFF000000.toInt()) or (rgb and 0x00FFFFFF)
    }

    private fun unit(value: Float): Float = if (value.isFinite()) value.coerceIn(0f, 1f) else 0f

    private companion object {
        const val HALF_TURN = 180f
        const val FULL_TURN = 360f
    }
}

/**
 * Procreate's rendering modes. Glazes cap the paint a stroke lays down at its strongest dab; blendings
 * let overlapping dabs build up. Intense modes lay more paint per dab; uniform and heavy modes keep
 * the dab's edge as hard at low flow as at full flow.
 */
@Serializable
enum class RenderingMode(
    val displayName: String,
    val blending: Boolean,
    val intense: Boolean,
    val uniformEdges: Boolean,
) {
    LIGHT_GLAZE("Light glaze", blending = false, intense = false, uniformEdges = false),
    UNIFORM_GLAZE("Uniform glaze", blending = false, intense = false, uniformEdges = true),
    INTENSE_GLAZE("Intense glaze", blending = false, intense = true, uniformEdges = false),
    HEAVY_GLAZE("Heavy glaze", blending = false, intense = true, uniformEdges = true),
    UNIFORM_BLENDING("Uniform blending", blending = true, intense = false, uniformEdges = false),
    INTENSE_BLENDING("Intense blending", blending = true, intense = true, uniformEdges = false),
}
