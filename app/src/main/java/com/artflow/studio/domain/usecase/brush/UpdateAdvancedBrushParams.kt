package com.artflow.studio.domain.usecase.brush

import com.artflow.studio.domain.model.brush.BrushParams
import com.artflow.studio.domain.repository.canvas.CanvasRepository
import javax.inject.Inject

/**
 * Use case for updating advanced brush parameters
 * Implements Phase 9: Advanced Brush Parameters
 * 
 * This use case provides fine-grained control over individual brush parameters
 * allowing real-time adjustment during painting sessions.
 */
class UpdateAdvancedBrushParams @Inject constructor(
    private val canvasRepository: CanvasRepository
) {
    
    /**
     * Result of parameter update operation
     */
    data class UpdateResult(
        val success: Boolean,
        val updatedParams: BrushParams?,
        val errorMessage: String? = null
    )
    
    /**
     * Parameter types that can be updated
     */
    enum class ParamType {
        SIZE,
        OPACITY,
        SPACING,
        SCATTER,
        COUNT,
        ROTATION,
        TAPER_START,
        TAPER_END,
        PRESSURE_TO_SIZE,
        PRESSURE_TO_OPACITY,
        PRESSURE_CURVE,
        HUE_JITTER,
        SATURATION_JITTER,
        BRIGHTNESS_JITTER,
        COLOR_PRESSURE,
        SIZE_JITTER,
        OPACITY_JITTER,
        SMOOTHING,
        WET_MIX,
        FLOW,
        TEXTURE_ID,
        TEXTURE_SCALE,
        TEXTURE_ROTATION,
        BLEND_TEXTURE,
        TILT_INFLUENCE,
        TILT_TO_ROTATION,
        VELOCITY_TO_SIZE,
        VELOCITY_TO_OPACITY,
        VELOCITY_TO_HUE
    }
    
    /**
     * Update a single brush parameter
     * @param currentParams Current brush parameters
     * @param paramType Type of parameter to update
     * @param value New value for the parameter
     * @return UpdateResult with success status and updated params
     */
    operator fun invoke(
        currentParams: BrushParams,
        paramType: ParamType,
        value: Any
    ): UpdateResult {
        return try {
            val updatedParams = when (paramType) {
                ParamType.SIZE -> currentParams.copy(size = value as Float)
                ParamType.OPACITY -> currentParams.copy(opacity = value as Float)
                ParamType.SPACING -> currentParams.copy(spacing = value as Float)
                ParamType.SCATTER -> currentParams.copy(scatter = value as Float)
                ParamType.COUNT -> currentParams.copy(count = value as Int)
                ParamType.ROTATION -> currentParams.copy(rotation = value as Float)
                ParamType.TAPER_START -> currentParams.copy(taperStart = value as Float)
                ParamType.TAPER_END -> currentParams.copy(taperEnd = value as Float)
                ParamType.PRESSURE_TO_SIZE -> currentParams.copy(pressureToSize = value as Float)
                ParamType.PRESSURE_TO_OPACITY -> currentParams.copy(pressureToOpacity = value as Float)
                ParamType.PRESSURE_CURVE -> currentParams.copy(pressureCurve = value as BrushParams.PressureCurve)
                ParamType.HUE_JITTER -> currentParams.copy(hueJitter = value as Float)
                ParamType.SATURATION_JITTER -> currentParams.copy(saturationJitter = value as Float)
                ParamType.BRIGHTNESS_JITTER -> currentParams.copy(brightnessJitter = value as Float)
                ParamType.COLOR_PRESSURE -> currentParams.copy(colorPressure = value as Boolean)
                ParamType.SIZE_JITTER -> currentParams.copy(sizeJitter = value as Float)
                ParamType.OPACITY_JITTER -> currentParams.copy(opacityJitter = value as Float)
                ParamType.SMOOTHING -> currentParams.copy(smoothing = value as Float)
                ParamType.WET_MIX -> currentParams.copy(wetMix = value as Float)
                ParamType.FLOW -> currentParams.copy(flow = value as Float)
                ParamType.TEXTURE_ID -> currentParams.copy(textureId = value as String?)
                ParamType.TEXTURE_SCALE -> currentParams.copy(textureScale = value as Float)
                ParamType.TEXTURE_ROTATION -> currentParams.copy(textureRotation = value as Float)
                ParamType.BLEND_TEXTURE -> currentParams.copy(blendTexture = value as Boolean)
                ParamType.TILT_INFLUENCE -> currentParams.copy(tiltInfluence = value as Float)
                ParamType.TILT_TO_ROTATION -> currentParams.copy(tiltToRotation = value as Boolean)
                ParamType.VELOCITY_TO_SIZE -> currentParams.copy(velocityToSize = value as Float)
                ParamType.VELOCITY_TO_OPACITY -> currentParams.copy(velocityToOpacity = value as Float)
                ParamType.VELOCITY_TO_HUE -> currentParams.copy(velocityToHue = value as Float)
            }
            
            UpdateResult(
                success = true,
                updatedParams = updatedParams
            )
        } catch (e: Exception) {
            UpdateResult(
                success = false,
                updatedParams = null,
                errorMessage = "Failed to update ${paramType.name}: ${e.message}"
            )
        }
    }
    
    /**
     * Update multiple brush parameters at once
     * @param currentParams Current brush parameters
     * @param updates Map of parameter types to their new values
     * @return UpdateResult with success status and updated params
     */
    suspend fun updateMultiple(
        currentParams: BrushParams,
        updates: Map<ParamType, Any>
    ): UpdateResult {
        return try {
            var updatedParams = currentParams
            
            updates.forEach { (paramType, value) ->
                val result = invoke(updatedParams, paramType, value)
                if (!result.success) {
                    return UpdateResult(
                        success = false,
                        updatedParams = null,
                        errorMessage = "Failed at ${paramType.name}: ${result.errorMessage}"
                    )
                }
                updatedParams = result.updatedParams!!
            }
            
            UpdateResult(
                success = true,
                updatedParams = updatedParams
            )
        } catch (e: Exception) {
            UpdateResult(
                success = false,
                updatedParams = null,
                errorMessage = "Batch update failed: ${e.message}"
            )
        }
    }
    
    /**
     * Apply a brush preset configuration
     * @param currentParams Current brush parameters
     * @param presetName Name of the preset to apply
     * @return UpdateResult with success status and updated params
     */
    suspend fun applyPreset(
        currentParams: BrushParams,
        presetName: String
    ): UpdateResult {
        return try {
            // Preset configurations - these would typically come from a database
            val presets = mapOf(
                "default" to BrushParams(),
                "pencil_soft" to BrushParams(
                    size = 15f,
                    opacity = 0.8f,
                    spacing = 0.05f,
                    pressureToSize = 0.7f,
                    pressureToOpacity = 0.5f,
                    pressureCurve = BrushParams.PressureCurve.EASE_IN,
                    smoothing = 0.3f,
                    textureId = "pencil_grain"
                ),
                "ink_pen" to BrushParams(
                    size = 10f,
                    opacity = 1.0f,
                    spacing = 0.03f,
                    pressureToSize = 0.2f,
                    pressureToOpacity = 0f,
                    smoothing = 0.5f
                ),
                "airbrush" to BrushParams(
                    size = 40f,
                    opacity = 0.15f,
                    spacing = 0.02f,
                    scatter = 0.1f,
                    count = 3,
                    pressureToSize = 0.4f,
                    pressureToOpacity = 0.6f,
                    pressureCurve = BrushParams.PressureCurve.EASE_OUT,
                    flow = 0.3f
                ),
                "watercolor" to BrushParams(
                    size = 35f,
                    opacity = 0.4f,
                    spacing = 0.1f,
                    scatter = 0.05f,
                    pressureToSize = 0.5f,
                    pressureToOpacity = 0.4f,
                    pressureCurve = BrushParams.PressureCurve.EASE_IN_OUT,
                    hueJitter = 0.1f,
                    saturationJitter = 0.15f,
                    brightnessJitter = 0.2f,
                    colorPressure = true,
                    wetMix = 0.8f,
                    flow = 0.6f,
                    textureId = "watercolor_paper",
                    textureScale = 1.5f,
                    blendTexture = true
                ),
                "oil_paint" to BrushParams(
                    size = 50f,
                    opacity = 0.85f,
                    spacing = 0.15f,
                    scatter = 0.1f,
                    count = 2,
                    pressureToSize = 0.6f,
                    pressureToOpacity = 0.3f,
                    hueJitter = 0.05f,
                    saturationJitter = 0.1f,
                    brightnessJitter = 0.1f,
                    colorPressure = true,
                    wetMix = 0.7f,
                    flow = 0.8f,
                    textureId = "canvas_grain",
                    textureScale = 2.0f,
                    blendTexture = true,
                    tiltInfluence = 0.5f,
                    tiltToRotation = true
                ),
                "charcoal" to BrushParams(
                    size = 40f,
                    opacity = 0.7f,
                    spacing = 0.12f,
                    scatter = 0.3f,
                    count = 2,
                    pressureToSize = 0.6f,
                    pressureToOpacity = 0.5f,
                    pressureCurve = BrushParams.PressureCurve.EASE_IN,
                    brightnessJitter = 0.3f,
                    textureId = "charcoal_grain",
                    textureScale = 1.8f,
                    blendTexture = true
                ),
                "splatter" to BrushParams(
                    size = 16f,
                    opacity = 0.9f,
                    spacing = 0.3f,
                    scatter = 0.8f,
                    count = 5,
                    pressureToSize = 0.5f,
                    pressureToOpacity = 0.3f,
                    hueJitter = 0.2f,
                    saturationJitter = 0.2f,
                    brightnessJitter = 0.3f,
                    colorPressure = true,
                    smoothing = 0f,
                    wetMix = 0.2f
                )
            )
            
            val presetParams = presets[presetName] ?: return UpdateResult(
                success = false,
                updatedParams = null,
                errorMessage = "Preset '$presetName' not found"
            )
            
            UpdateResult(
                success = true,
                updatedParams = presetParams
            )
        } catch (e: Exception) {
            UpdateResult(
                success = false,
                updatedParams = null,
                errorMessage = "Failed to apply preset: ${e.message}"
            )
        }
    }
    
    /**
     * Validate parameter value ranges
     * @param paramType Parameter type to validate
     * @param value Value to validate
     * @return True if value is within valid range
     */
    fun validateParameter(paramType: ParamType, value: Any): Boolean {
        return when (paramType) {
            ParamType.SIZE -> (value as? Float)?.let { it in 1f..500f } ?: false
            ParamType.OPACITY,
            ParamType.SPACING,
            ParamType.SCATTER,
            ParamType.TAPER_START,
            ParamType.TAPER_END,
            ParamType.PRESSURE_TO_SIZE,
            ParamType.PRESSURE_TO_OPACITY,
            ParamType.HUE_JITTER,
            ParamType.SATURATION_JITTER,
            ParamType.BRIGHTNESS_JITTER,
            ParamType.SIZE_JITTER,
            ParamType.OPACITY_JITTER,
            ParamType.SMOOTHING,
            ParamType.WET_MIX,
            ParamType.FLOW,
            ParamType.TEXTURE_SCALE,
            ParamType.TILT_INFLUENCE,
            ParamType.VELOCITY_TO_SIZE,
            ParamType.VELOCITY_TO_OPACITY,
            ParamType.VELOCITY_TO_HUE -> (value as? Float)?.let { it in 0f..1f } ?: false
            ParamType.COUNT -> (value as? Int)?.let { it in 1..20 } ?: false
            ParamType.ROTATION,
            ParamType.TEXTURE_ROTATION -> (value as? Float)?.let { it in 0f..360f } ?: false
            ParamType.PRESSURE_CURVE -> value is BrushParams.PressureCurve
            ParamType.COLOR_PRESSURE,
            ParamType.BLEND_TEXTURE,
            ParamType.TILT_TO_ROTATION -> value is Boolean
            ParamType.TEXTURE_ID -> true // Any string or null is valid
        }
    }
    
    /**
     * Get default value for a parameter type
     * @param paramType Parameter type
     * @return Default value for the parameter
     */
    fun getDefaultValue(paramType: ParamType): Any {
        return when (paramType) {
            ParamType.SIZE -> 20f
            ParamType.OPACITY -> 1.0f
            ParamType.SPACING -> 0.1f
            ParamType.SCATTER -> 0.0f
            ParamType.COUNT -> 1
            ParamType.ROTATION -> 0f
            ParamType.TAPER_START -> 0f
            ParamType.TAPER_END -> 0f
            ParamType.PRESSURE_TO_SIZE -> 0.5f
            ParamType.PRESSURE_TO_OPACITY -> 0.3f
            ParamType.PRESSURE_CURVE -> BrushParams.PressureCurve.LINEAR
            ParamType.HUE_JITTER -> 0f
            ParamType.SATURATION_JITTER -> 0f
            ParamType.BRIGHTNESS_JITTER -> 0f
            ParamType.COLOR_PRESSURE -> false
            ParamType.SIZE_JITTER -> 0f
            ParamType.OPACITY_JITTER -> 0f
            ParamType.SMOOTHING -> 0.5f
            ParamType.WET_MIX -> 0f
            ParamType.FLOW -> 1.0f
            ParamType.TEXTURE_ID -> null
            ParamType.TEXTURE_SCALE -> 1.0f
            ParamType.TEXTURE_ROTATION -> 0f
            ParamType.BLEND_TEXTURE -> false
            ParamType.TILT_INFLUENCE -> 0f
            ParamType.TILT_TO_ROTATION -> false
            ParamType.VELOCITY_TO_SIZE -> 0f
            ParamType.VELOCITY_TO_OPACITY -> 0f
            ParamType.VELOCITY_TO_HUE -> 0f
        }
    }
}
