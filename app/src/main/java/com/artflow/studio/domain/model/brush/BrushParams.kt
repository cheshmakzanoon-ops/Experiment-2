package com.artflow.studio.domain.model.brush

import kotlinx.serialization.Serializable

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
) {
    /**
     * Pressure curve types for mapping stylus pressure
     */
    @Serializable
    enum class PressureCurve {
        LINEAR, // Direct 1:1 mapping
        EASE_IN, // Gradual start, sharp end
        EASE_OUT, // Sharp start, gradual end
        EASE_IN_OUT, // Gradual start and end
        CUSTOM, // Custom curve (not yet implemented)
    }

    /**
     * Calculate effective size based on pressure and velocity
     */
    fun calculateEffectiveSize(
        pressure: Float,
        velocity: Float = 0f,
    ): Float {
        var effectiveSize = size

        // Apply pressure dynamics
        if (pressureToSize > 0f) {
            val pressureFactor = applyPressureCurve(pressure, pressureCurve)
            val sizeVariation = pressureFactor * pressureToSize
            effectiveSize = size * (0.5f + sizeVariation)
        }

        // Apply velocity dynamics
        if (velocityToSize > 0f && velocity > 0f) {
            val normalizedVelocity = velocity.coerceIn(0f, 10f) / 10f
            effectiveSize *= (1f - normalizedVelocity * velocityToSize * 0.5f)
        }

        // Apply size jitter
        if (sizeJitter > 0f) {
            val jitterFactor = 1f + (Math.random().toFloat() * 2f - 1f) * sizeJitter
            effectiveSize *= jitterFactor
        }

        return effectiveSize.coerceAtLeast(1f)
    }

    /**
     * Calculate effective opacity based on pressure and velocity
     */
    fun calculateEffectiveOpacity(
        pressure: Float,
        velocity: Float = 0f,
    ): Float {
        var effectiveOpacity = opacity

        // Apply pressure dynamics
        if (pressureToOpacity > 0f) {
            val pressureFactor = applyPressureCurve(pressure, pressureCurve)
            val opacityVariation = pressureFactor * pressureToOpacity
            effectiveOpacity = 0.3f + opacityVariation * 0.7f
        }

        // Apply velocity dynamics
        if (velocityToOpacity > 0f && velocity > 0f) {
            val normalizedVelocity = velocity.coerceIn(0f, 10f) / 10f
            effectiveOpacity *= (1f - normalizedVelocity * velocityToOpacity * 0.3f)
        }

        // Apply opacity jitter
        if (opacityJitter > 0f) {
            val jitterFactor = (Math.random().toFloat() * 2f - 1f) * opacityJitter
            effectiveOpacity = (effectiveOpacity + jitterFactor).coerceIn(0.1f, 1f)
        }

        return effectiveOpacity.coerceIn(0.01f, 1f)
    }

    /**
     * Apply pressure curve transformation
     */
    private fun applyPressureCurve(
        pressure: Float,
        curve: PressureCurve,
    ): Float =
        when (curve) {
            PressureCurve.LINEAR -> pressure
            PressureCurve.EASE_IN -> pressure * pressure
            PressureCurve.EASE_OUT -> pressure * (2f - pressure)
            PressureCurve.EASE_IN_OUT -> {
                if (pressure < 0.5f) {
                    2f * pressure * pressure
                } else {
                    1f - (-2f * pressure + 2f).let { it * it } / 2f
                }
            }
            PressureCurve.CUSTOM -> pressure // TODO: Implement custom curve
        }

    /**
     * Calculate color with hue/saturation/brightness jitter applied
     */
    fun applyColorJitter(
        baseColor: Int,
        pressure: Float = 1f,
        velocity: Float = 0f,
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
            val hueShift = (Math.random().toFloat() * 2f - 1f) * hueJitter * 360f
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
            val satShift = (Math.random().toFloat() * 2f - 1f) * saturationJitter
            hsv[1] = (hsv[1] + satShift).coerceIn(0f, 1f)
        }

        // Apply brightness jitter
        if (brightnessJitter > 0f) {
            val brightShift = (Math.random().toFloat() * 2f - 1f) * brightnessJitter
            hsv[2] = (hsv[2] + brightShift).coerceIn(0f, 1f)
        }

        // Apply pressure-based color dynamics
        if (colorPressure) {
            val pressureFactor = applyPressureCurve(pressure, pressureCurve)
            hsv[2] *= (0.5f + pressureFactor * 0.5f) // Darker at low pressure
        }

        // Preserve the base colour's alpha channel; only hue/sat/value are modulated.
        val rgb =
            com.artflow.studio.domain.model.Color
                .hsvToRgb(hsv[0], hsv[1], hsv[2])
        return (baseColor and 0xFF000000.toInt()) or (rgb and 0x00FFFFFF)
    }
}
