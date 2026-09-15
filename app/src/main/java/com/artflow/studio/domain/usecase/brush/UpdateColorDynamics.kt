package com.artflow.studio.domain.usecase.brush

import com.artflow.studio.domain.model.brush.BrushParams
import javax.inject.Inject

/**
 * Use case for managing brush color dynamics
 * Implements Phase 11: Color Dynamics
 * 
 * This use case provides specialized control over color-related brush parameters
 * including hue, saturation, brightness jitter, and pressure/velocity-based color shifts.
 */
class UpdateColorDynamics @Inject constructor() {
    
    /**
     * Result of color dynamics update operation
     */
    data class ColorDynamicsResult(
        val success: Boolean,
        val updatedParams: BrushParams?,
        val errorMessage: String? = null
    )
    
    /**
     * Color dynamics configuration
     */
    data class ColorDynamicsConfig(
        val hueJitter: Float = 0f,
        val saturationJitter: Float = 0f,
        val brightnessJitter: Float = 0f,
        val colorPressure: Boolean = false,
        val velocityToHue: Float = 0f,
        val pressureCurve: BrushParams.PressureCurve = BrushParams.PressureCurve.LINEAR
    )
    
    /**
     * Update hue jitter parameter
     * @param currentParams Current brush parameters
     * @param hueJitter Hue variation amount (0.0 - 1.0)
     * @return ColorDynamicsResult with updated params
     */
    fun updateHueJitter(
        currentParams: BrushParams,
        hueJitter: Float
    ): ColorDynamicsResult {
        return try {
            val validatedValue = hueJitter.coerceIn(0f, 1f)
            ColorDynamicsResult(
                success = true,
                updatedParams = currentParams.copy(hueJitter = validatedValue)
            )
        } catch (e: Exception) {
            ColorDynamicsResult(
                success = false,
                updatedParams = null,
                errorMessage = "Failed to update hue jitter: ${e.message}"
            )
        }
    }
    
    /**
     * Update saturation jitter parameter
     * @param currentParams Current brush parameters
     * @param saturationJitter Saturation variation amount (0.0 - 1.0)
     * @return ColorDynamicsResult with updated params
     */
    fun updateSaturationJitter(
        currentParams: BrushParams,
        saturationJitter: Float
    ): ColorDynamicsResult {
        return try {
            val validatedValue = saturationJitter.coerceIn(0f, 1f)
            ColorDynamicsResult(
                success = true,
                updatedParams = currentParams.copy(saturationJitter = validatedValue)
            )
        } catch (e: Exception) {
            ColorDynamicsResult(
                success = false,
                updatedParams = null,
                errorMessage = "Failed to update saturation jitter: ${e.message}"
            )
        }
    }
    
    /**
     * Update brightness jitter parameter
     * @param currentParams Current brush parameters
     * @param brightnessJitter Brightness variation amount (0.0 - 1.0)
     * @return ColorDynamicsResult with updated params
     */
    fun updateBrightnessJitter(
        currentParams: BrushParams,
        brightnessJitter: Float
    ): ColorDynamicsResult {
        return try {
            val validatedValue = brightnessJitter.coerceIn(0f, 1f)
            ColorDynamicsResult(
                success = true,
                updatedParams = currentParams.copy(brightnessJitter = validatedValue)
            )
        } catch (e: Exception) {
            ColorDynamicsResult(
                success = false,
                updatedParams = null,
                errorMessage = "Failed to update brightness jitter: ${e.message}"
            )
        }
    }
    
    /**
     * Toggle pressure-based color dynamics
     * @param currentParams Current brush parameters
     * @param enabled Whether to enable pressure-based color variation
     * @return ColorDynamicsResult with updated params
     */
    fun toggleColorPressure(
        currentParams: BrushParams,
        enabled: Boolean
    ): ColorDynamicsResult {
        return try {
            ColorDynamicsResult(
                success = true,
                updatedParams = currentParams.copy(colorPressure = enabled)
            )
        } catch (e: Exception) {
            ColorDynamicsResult(
                success = false,
                updatedParams = null,
                errorMessage = "Failed to toggle color pressure: ${e.message}"
            )
        }
    }
    
    /**
     * Update velocity-to-hue shift parameter
     * @param currentParams Current brush parameters
     * @param velocityToHue How much stroke speed affects hue (0.0 - 1.0)
     * @return ColorDynamicsResult with updated params
     */
    fun updateVelocityToHue(
        currentParams: BrushParams,
        velocityToHue: Float
    ): ColorDynamicsResult {
        return try {
            val validatedValue = velocityToHue.coerceIn(0f, 1f)
            ColorDynamicsResult(
                success = true,
                updatedParams = currentParams.copy(velocityToHue = validatedValue)
            )
        } catch (e: Exception) {
            ColorDynamicsResult(
                success = false,
                updatedParams = null,
                errorMessage = "Failed to update velocity to hue: ${e.message}"
            )
        }
    }
    
    /**
     * Apply a complete color dynamics configuration
     * @param currentParams Current brush parameters
     * @param config New color dynamics configuration
     * @return ColorDynamicsResult with updated params
     */
    fun applyColorDynamicsConfig(
        currentParams: BrushParams,
        config: ColorDynamicsConfig
    ): ColorDynamicsResult {
        return try {
            ColorDynamicsResult(
                success = true,
                updatedParams = currentParams.copy(
                    hueJitter = config.hueJitter.coerceIn(0f, 1f),
                    saturationJitter = config.saturationJitter.coerceIn(0f, 1f),
                    brightnessJitter = config.brightnessJitter.coerceIn(0f, 1f),
                    colorPressure = config.colorPressure,
                    velocityToHue = config.velocityToHue.coerceIn(0f, 1f),
                    pressureCurve = config.pressureCurve
                )
            )
        } catch (e: Exception) {
            ColorDynamicsResult(
                success = false,
                updatedParams = null,
                errorMessage = "Failed to apply color dynamics config: ${e.message}"
            )
        }
    }
    
    /**
     * Apply a color dynamics preset
     * @param currentParams Current brush parameters
     * @param presetName Name of the preset to apply
     * @return ColorDynamicsResult with updated params
     */
    fun applyPreset(
        currentParams: BrushParams,
        presetName: String
    ): ColorDynamicsResult {
        return try {
            val presets = mapOf(
                "none" to ColorDynamicsConfig(),
                "subtle_variation" to ColorDynamicsConfig(
                    hueJitter = 0.05f,
                    saturationJitter = 0.05f,
                    brightnessJitter = 0.08f,
                    colorPressure = false
                ),
                "watercolor_blend" to ColorDynamicsConfig(
                    hueJitter = 0.1f,
                    saturationJitter = 0.15f,
                    brightnessJitter = 0.2f,
                    colorPressure = true,
                    pressureCurve = BrushParams.PressureCurve.EASE_IN_OUT
                ),
                "oil_paint_rich" to ColorDynamicsConfig(
                    hueJitter = 0.05f,
                    saturationJitter = 0.1f,
                    brightnessJitter = 0.1f,
                    colorPressure = true,
                    pressureCurve = BrushParams.PressureCurve.EASE_IN
                ),
                "rainbow_stroke" to ColorDynamicsConfig(
                    hueJitter = 0.3f,
                    saturationJitter = 0.2f,
                    brightnessJitter = 0.15f,
                    velocityToHue = 0.5f,
                    colorPressure = false
                ),
                "pressure_shade" to ColorDynamicsConfig(
                    hueJitter = 0f,
                    saturationJitter = 0f,
                    brightnessJitter = 0.3f,
                    colorPressure = true,
                    pressureCurve = BrushParams.PressureCurve.EASE_IN
                ),
                "speed_gradient" to ColorDynamicsConfig(
                    hueJitter = 0f,
                    saturationJitter = 0.1f,
                    brightnessJitter = 0f,
                    velocityToHue = 0.8f,
                    colorPressure = false
                ),
                "natural_media" to ColorDynamicsConfig(
                    hueJitter = 0.08f,
                    saturationJitter = 0.12f,
                    brightnessJitter = 0.25f,
                    colorPressure = true,
                    pressureCurve = BrushParams.PressureCurve.EASE_IN_OUT,
                    velocityToHue = 0.2f
                )
            )
            
            val presetConfig = presets[presetName] ?: return ColorDynamicsResult(
                success = false,
                updatedParams = null,
                errorMessage = "Color dynamics preset '$presetName' not found"
            )
            
            applyColorDynamicsConfig(currentParams, presetConfig)
        } catch (e: Exception) {
            ColorDynamicsResult(
                success = false,
                updatedParams = null,
                errorMessage = "Failed to apply preset: ${e.message}"
            )
        }
    }
    
    /**
     * Reset all color dynamics to default values
     * @param currentParams Current brush parameters
     * @return ColorDynamicsResult with reset params
     */
    fun resetToDefaults(currentParams: BrushParams): ColorDynamicsResult {
        return try {
            ColorDynamicsResult(
                success = true,
                updatedParams = currentParams.copy(
                    hueJitter = 0f,
                    saturationJitter = 0f,
                    brightnessJitter = 0f,
                    colorPressure = false,
                    velocityToHue = 0f
                )
            )
        } catch (e: Exception) {
            ColorDynamicsResult(
                success = false,
                updatedParams = null,
                errorMessage = "Failed to reset color dynamics: ${e.message}"
            )
        }
    }
    
    /**
     * Validate color dynamics values
     * @param config Configuration to validate
     * @return True if all values are within valid ranges
     */
    fun validateColorDynamics(config: ColorDynamicsConfig): Boolean {
        return config.hueJitter in 0f..1f &&
                config.saturationJitter in 0f..1f &&
                config.brightnessJitter in 0f..1f &&
                config.velocityToHue in 0f..1f
    }
    
    /**
     * Calculate effective color with all dynamics applied
     * Utility function for preview purposes
     * @param baseColor Base ARGB color
     * @param params Brush parameters with dynamics
     * @param pressure Current pressure value (0.0 - 1.0)
     * @param velocity Current velocity value
     * @return Modified color with dynamics applied
     */
    fun calculateEffectiveColor(
        baseColor: Int,
        params: BrushParams,
        pressure: Float = 1f,
        velocity: Float = 0f
    ): Int {
        return params.applyColorJitter(baseColor, pressure, velocity)
    }
    
    /**
     * Get a preview color array showing color variation range
     * @param baseColor Base ARGB color
     * @param params Brush parameters with dynamics
     * @param sampleCount Number of samples to generate
     * @return Array of preview colors
     */
    fun generateColorPreview(
        baseColor: Int,
        params: BrushParams,
        sampleCount: Int = 5
    ): IntArray {
        val colors = IntArray(sampleCount)
        for (i in 0 until sampleCount) {
            colors[i] = params.applyColorJitter(baseColor, pressure = (i + 1).toFloat() / sampleCount)
        }
        return colors
    }
}
