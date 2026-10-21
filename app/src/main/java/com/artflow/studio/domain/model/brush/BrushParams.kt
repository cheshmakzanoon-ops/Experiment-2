package com.artflow.studio.domain.model.brush

import com.artflow.studio.domain.model.layer.BlendMode
import kotlinx.serialization.Serializable
import java.util.Random
import kotlin.math.abs

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
    val importedPressure: ImportedPressure? = null, // Preserved independent per-brush and per-channel responses
    /** Imported source pressure curves not yet mapped to ArtFlow's renderer; valid points retained for interchange. */
    val importedAuxiliaryCurves: Map<String, PressureMapping> = emptyMap(),
    /** Bounded native settings scalars retained as data, including fields unknown to this renderer. */
    val importedSourceScalars: Map<String, SourceBrushScalar> = emptyMap(),
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
    val velocityToSize: Float = 0f, // -1 shrinks slow marks; +1 shrinks fast marks
    val velocityToOpacity: Float = 0f, // -1 fades slow marks; +1 fades fast marks
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
    val tipFlipXJitter: Boolean = false, // Seeded horizontal mirror choice per dab, separate from a fixed flip
    val tipFlipYJitter: Boolean = false, // Seeded vertical mirror choice per dab, separate from a fixed flip
    val tipRandomized: Boolean = false, // Each stroke starts the tip at a random angle
    val buildUp: Boolean = false, // Blending rendering: overlapping dabs build up within one stroke instead of glazing
    val grainMoving: Boolean = false, // Grain starts afresh with each stroke instead of staying fixed to the canvas
    val secondaryPressure: Float = 0f, // 0..1: firmer pressure blends the colour toward the secondary colour
    val secondaryJitter: Float = 0f, // 0..1: each dab blends a random amount toward the secondary colour
    val burntEdges: Float = 0f, // 0..1: paint darkens where the stroke's coverage falls off, like a scorched rim
    val grainDepth: Float = 1f, // 0..1: how strongly the grain shows; 0 paints as if there were no grain
    val tiltOpacityInfluence: Float? = null, // 0..1: how much tilt lightens the paint; null follows tiltInfluence
    // Reconciled editor and rasterizer fields. Defaults preserve older serialized brushes.
    val pressureToFlow: Float = 0f,
    val touchTaperStart: Float? = null,
    val touchTaperEnd: Float? = null,
    val tipSharpness: Float = 0f,
    val minSize: Float = MIN_BRUSH_SIZE,
    val maxSize: Float = MAX_BRUSH_SIZE,
    val minOpacity: Float = MIN_BRUSH_OPACITY,
    val maxOpacity: Float = 1f,
    val bleed: Float = 0f,
    val grainBrightness: Float = 0f,
    val grainContrast: Float = 0f,
    val grainBlend: GrainBlend = GrainBlend.MULTIPLY,
    val countJitter: Float = 0f,
    val colorJitterPerStroke: Boolean = false,
    val colorDynamics: ColorDynamics = ColorDynamics(),
    val dilution: Float = 0f,
    val wetBlur: Float = 0f,
    val pull: Float = 0f,
    val wetPull: Float = 0f,
    val wetBlurJitter: Float = 0f,
    val wetCharge: Float = 1f,
    val wetDilution: Float = 0f,
    val renderingMode: RenderingMode? = null,
) {
    /** Flow at [pressure], after Pressure → Flow thins it for lighter presses. */
    fun flowAt(pressure: Float): Float = flow.coerceIn(0f, 1f) * (1f - pressureToFlow.coerceIn(0f, 1f) * (1f - pressureResponse(pressure)))

    /** These settings for a finger stroke: the Touch taper replaces the stylus (pressure) taper. */
    fun forTouch(): BrushParams = copy(taperStart = touchTaperStart ?: taperStart, taperEnd = touchTaperEnd ?: taperEnd)

    /** Canonical mode consumed by the rasterizer and Brush Studio. */
    val rendering: RenderingMode
        get() = effectiveRenderingMode

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
        get() = wetMix > 0f || wetCharge < 1f || wetDilution > 0f || bleed > 0f

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
        if (pressureToSize != 0f) {
            val overall = pressureResponse(pressure)
            val response = importedPressure?.size?.map(overall) ?: overall
            effectiveSize *= pressureFactor(response, pressureToSize)
        }

        // Apply velocity dynamics
        if (velocityToSize != 0f) {
            effectiveSize *= speedFactor(velocity, velocityToSize, 0.5f)
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
        if (pressureToOpacity != 0f) {
            val overall = pressureResponse(pressure)
            val response = importedPressure?.opacity?.map(overall) ?: overall
            effectiveOpacity *= pressureFactor(response, pressureToOpacity)
        }

        // Apply velocity dynamics
        if (velocityToOpacity != 0f) {
            effectiveOpacity *= speedFactor(velocity, velocityToOpacity, 0.3f)
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
        val mapped =
            when (pressureCurve) {
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
        return importedPressure?.overall?.map(mapped) ?: mapped
    }

    private fun pressureFactor(
        response: Float,
        amount: Float,
    ): Float {
        val influence = amount.coerceIn(-1f, 1f)
        val value = if (influence < 0f) 1f - response else response
        return 1f - abs(influence) + value * abs(influence)
    }

    private fun speedFactor(
        velocity: Float,
        amount: Float,
        reduction: Float,
    ): Float {
        require(velocity.isFinite()) { "Velocity must be finite" }
        val normalized = velocity.coerceIn(0f, 10f) / 10f
        val influence = amount.coerceIn(-1f, 1f)
        // Negative settings reduce the slow end; positive settings reduce the fast end.
        val value = if (influence < 0f) 1f - normalized else normalized
        return 1f - value * abs(influence) * reduction
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
    val isActive: Boolean get() = amounts().any { it.isFinite() && it != 0f }

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
        // No transform at full pressure with an upright pen: avoid an RGB->HSV->RGB
        // rounding change to the original colour even when dynamics are enabled.
        if (light == 0f && flat == 0f) return color
        val hsv =
            com.artflow.studio.domain.model.Color
                .rgbToHsv(color)
        val hueShift = (signed(pressureHue) * light + signed(tiltHue) * flat) * HALF_TURN
        hsv[0] = ((hsv[0] + hueShift) % FULL_TURN + FULL_TURN) % FULL_TURN
        hsv[1] *= (1f - signed(pressureSaturation) * light) * (1f - signed(tiltSaturation) * flat)
        hsv[2] *= (1f - signed(pressureBrightness) * light) * (1f - signed(tiltBrightness) * flat)
        val rgb =
            com.artflow.studio.domain.model.Color
                .hsvToRgb(hsv[0], hsv[1].coerceIn(0f, 1f), hsv[2].coerceIn(0f, 1f))
        return (color and 0xFF000000.toInt()) or (rgb and 0x00FFFFFF)
    }

    /** Negative reference values increase saturation/value or rotate hue the opposite way. */
    private fun signed(value: Float): Float = if (value.isFinite()) value.coerceIn(-1f, 1f) else 0f

    private companion object {
        const val HALF_TURN = 180f
        const val FULL_TURN = 360f
    }
}

/** Procreate's grain blend modes: how the grain value combines with the stroke's own coverage. */
@Serializable
enum class GrainBlend(
    val displayName: String,
) {
    /** Grain scales the coverage: the classic look. */
    MULTIPLY("Multiply"),

    /** Grain is taken away from the coverage, eating into soft edges first. */
    SUBTRACT("Subtract"),

    /** Coverage and grain together either paint fully or not at all: crisp, printed texture. */
    HARD_MIX("Hard mix"),
    ;

    /** The stroke's [coverage] (0..1) after the grain value [grain] (0..1). */
    fun combine(
        coverage: Float,
        grain: Float,
    ): Float =
        when (this) {
            MULTIPLY -> coverage * grain
            SUBTRACT -> (coverage - (1f - grain)).coerceAtLeast(0f)
            HARD_MIX -> if (coverage + grain > 1f) 1f else 0f
        }
}
