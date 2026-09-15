package com.artflow.studio.domain.usecase.brush

import com.artflow.studio.domain.model.brush.BrushParams
import javax.inject.Inject

/**
 * Use case for updating brush parameters with validation
 * Ensures all brush parameters stay within valid ranges
 */
class UpdateBrushParameters @Inject constructor() {
    
    /**
     * Update brush parameters with validation
     * @param current Current brush parameters
     * @param newSize New size value (nullable if not changing)
     * @param newOpacity New opacity value (nullable if not changing)
     * @param newSpacing New spacing value (nullable if not changing)
     * @param newScatter New scatter value (nullable if not changing)
     * @param newCount New count value (nullable if not changing)
     * @param newRotation New rotation value (nullable if not changing)
     * @param newTaperStart New taper start value (nullable if not changing)
     * @param newTaperEnd New taper end value (nullable if not changing)
     * @param newPressureToSize New pressure to size mapping (nullable if not changing)
     * @param newPressureToOpacity New pressure to opacity mapping (nullable if not changing)
     * @param newHueJitter New hue jitter value (nullable if not changing)
     * @param newSaturationJitter New saturation jitter value (nullable if not changing)
     * @param newBrightnessJitter New brightness jitter value (nullable if not changing)
     * @param newSmoothing New smoothing amount (nullable if not changing)
     * @param newWetMix New wet mix value (nullable if not changing)
     * @param newFlow New flow rate (nullable if not changing)
     * @return Updated BrushParams with validated values
     */
    operator fun invoke(
        current: BrushParams,
        newSize: Float? = null,
        newOpacity: Float? = null,
        newSpacing: Float? = null,
        newScatter: Float? = null,
        newCount: Int? = null,
        newRotation: Float? = null,
        newTaperStart: Float? = null,
        newTaperEnd: Float? = null,
        newPressureToSize: Float? = null,
        newPressureToOpacity: Float? = null,
        newHueJitter: Float? = null,
        newSaturationJitter: Float? = null,
        newBrightnessJitter: Float? = null,
        newSmoothing: Float? = null,
        newWetMix: Float? = null,
        newFlow: Float? = null
    ): BrushParams {
        return current.copy(
            size = newSize?.validateRange(BRUSH_SIZE_MIN, BRUSH_SIZE_MAX) ?: current.size,
            opacity = newOpacity?.validateRange(OPACITY_MIN, OPACITY_MAX) ?: current.opacity,
            spacing = newSpacing?.validateRange(SPACING_MIN, SPACING_MAX) ?: current.spacing,
            scatter = newScatter?.validateRange(SCATTER_MIN, SCATTER_MAX) ?: current.scatter,
            count = newCount?.validateRange(COUNT_MIN, COUNT_MAX) ?: current.count,
            rotation = newRotation?.normalizeRotation() ?: current.rotation,
            taperStart = newTaperStart?.validateRange(TAPER_MIN, TAPER_MAX) ?: current.taperStart,
            taperEnd = newTaperEnd?.validateRange(TAPER_MIN, TAPER_MAX) ?: current.taperEnd,
            pressureToSize = newPressureToSize?.validateRange(PRESSURE_MIN, PRESSURE_MAX) ?: current.pressureToSize,
            pressureToOpacity = newPressureToOpacity?.validateRange(PRESSURE_MIN, PRESSURE_MAX) ?: current.pressureToOpacity,
            hueJitter = newHueJitter?.validateRange(JITTER_MIN, JITTER_MAX) ?: current.hueJitter,
            saturationJitter = newSaturationJitter?.validateRange(JITTER_MIN, JITTER_MAX) ?: current.saturationJitter,
            brightnessJitter = newBrightnessJitter?.validateRange(JITTER_MIN, JITTER_MAX) ?: current.brightnessJitter,
            smoothing = newSmoothing?.validateRange(SMOOTHING_MIN, SMOOTHING_MAX) ?: current.smoothing,
            wetMix = newWetMix?.validateRange(WET_MIX_MIN, WET_MIX_MAX) ?: current.wetMix,
            flow = newFlow?.validateRange(FLOW_MIN, FLOW_MAX) ?: current.flow
        )
    }
    
    /**
     * Validate a float value is within the specified range
     */
    private fun Float.validateRange(min: Float, max: Float): Float {
        return this.coerceIn(min, max)
    }
    
    /**
     * Validate an int value is within the specified range
     */
    private fun Int.validateRange(min: Int, max: Int): Int {
        return this.coerceIn(min, max)
    }
    
    /**
     * Normalize rotation to 0-360 range
     */
    private fun Float.normalizeRotation(): Float {
        var rotation = this % 360f
        if (rotation < 0f) rotation += 360f
        return rotation
    }
    
    companion object {
        // Parameter ranges
        const val BRUSH_SIZE_MIN = 1f
        const val BRUSH_SIZE_MAX = 500f
        
        const val OPACITY_MIN = 0.01f
        const val OPACITY_MAX = 1.0f
        
        const val SPACING_MIN = 0.01f
        const val SPACING_MAX = 1.0f
        
        const val SCATTER_MIN = 0f
        const val SCATTER_MAX = 1f
        
        const val COUNT_MIN = 1
        const val COUNT_MAX = 10
        
        const val TAPER_MIN = 0f
        const val TAPER_MAX = 1f
        
        const val PRESSURE_MIN = 0f
        const val PRESSURE_MAX = 1f
        
        const val JITTER_MIN = 0f
        const val JITTER_MAX = 1f
        
        const val SMOOTHING_MIN = 0f
        const val SMOOTHING_MAX = 1f
        
        const val WET_MIX_MIN = 0f
        const val WET_MIX_MAX = 1f
        
        const val FLOW_MIN = 0.01f
        const val FLOW_MAX = 1.0f
    }
}
