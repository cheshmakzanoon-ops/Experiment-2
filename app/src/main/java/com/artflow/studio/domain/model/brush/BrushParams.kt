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
    // Wet mix Dilution, 0..1: each dab leans toward the canvas colour under it, and loses the same
    // share of its own alpha, so paint laid over paint thins toward what is beneath. Acts on the
    // canvas sample of the dab (see [picksUpCanvas]), and builds up from the stroke's start with [wetAttack].
    val wetDilution: Float = 0f,
    // Wet mix Charge, 0..1: how much of the brush's own colour a dab keeps. At 1 (the default) the dab
    // paints its colour as chosen. Below 1 the rest of the mix is canvas colour taken from under the dab,
    // so a low charge picks up paint even when Wetness is zero. At 0 the dab carries only canvas colour.
    val wetCharge: Float = 1f,
    // Wet mix Attack, 0..1: how quickly pickup reaches full strength after the stroke starts. At 1 (the
    // default) the first dab already picks up at full strength. Lower values ramp pickup in over
    // (1 - attack) x 4 brush sizes of travel, so the start of a stroke stays the brush's own colour.
    val wetAttack: Float = 1f,
    // Wet mix Pull, 0..1: how far back along the path a dab reads the canvas it picks up from, up to
    // two brush sizes. Pulled pickup is taken from where the brush has been, so picked-up colour
    // drags forward along the stroke. Zero reads the canvas under the dab itself.
    val wetPull: Float = 0f,
    // Wet mix Blur, 0..1: softens the picked-up canvas colour. A dab averages five canvas samples,
    // its centre and four points half a brush size times this value away, so a thin line under the
    // brush contributes a share of its colour instead of all or nothing.
    val wetBlur: Float = 0f,
    // Wet mix Blur jitter, 0..1: each dab varies its blur radius by up to this share, at random.
    val wetBlurJitter: Float = 0f,
    // How overlapping dabs combine within one stroke. Null derives it from [buildUp], so brushes saved before
    // this field existed render as they did; see [effectiveRenderingMode].
    val renderingMode: RenderingMode? = null,
) {
    /** Imported grain and shape images this brush (and its second brush) paints with. */
    val imageIds: List<String>
        get() = listOfNotNull(textureId, shapeId) + (dual?.params?.imageIds ?: emptyList())

    /**
     * The rendering mode in force: the explicit [renderingMode] when set, otherwise blending when
     * [buildUp] is on and uniform glazing when it is off.
     */
    val effectiveRenderingMode: RenderingMode
        get() = renderingMode ?: if (buildUp) RenderingMode.BLEND_UNIFORM else RenderingMode.GLAZE_UNIFORM

    /**
     * Whether dabs read the canvas under them, so they can change colour from paint already on the
     * layer. Such brushes cannot take the capsule fast path and their live frames depend on the layer.
     */
    val picksUpCanvas: Boolean
        get() = wetMix > 0f || wetCharge < 1f || wetDilution > 0f

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
    ): Int {
        val mixed = towardSecondary(baseColor, secondary, pressure, random)
        return jittered(mixed, pressure, velocity, random)
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
