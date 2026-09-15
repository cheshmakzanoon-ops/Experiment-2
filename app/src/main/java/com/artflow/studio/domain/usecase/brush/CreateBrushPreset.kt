package com.artflow.studio.domain.usecase.brush

import com.artflow.studio.domain.model.brush.BrushParams
import javax.inject.Inject

/**
 * Use case for creating brush presets with predefined configurations
 * Provides factory methods for common brush types
 */
class CreateBrushPreset @Inject constructor() {
    
    /**
     * Brush preset types
     */
    enum class PresetType {
        PENCIL,
        PEN,
        MARKER,
        AIRBRUSH,
        WATERCOLOR,
        OIL_PAINT,
        CHARCOAL,
        ERASER_SOFT,
        ERASER_HARD,
        BLENDER,
        SPLATTER
    }
    
    /**
     * Create a brush preset by type
     * @param type The type of brush preset to create
     * @param baseColor Base color for the brush (optional)
     * @return Configured BrushParams for the preset
     */
    operator fun invoke(
        type: PresetType,
        baseSize: Float = 20f
    ): BrushParams {
        return when (type) {
            PresetType.PENCIL -> createPencil(baseSize)
            PresetType.PEN -> createPen(baseSize)
            PresetType.MARKER -> createMarker(baseSize)
            PresetType.AIRBRUSH -> createAirbrush(baseSize)
            PresetType.WATERCOLOR -> createWatercolor(baseSize)
            PresetType.OIL_PAINT -> createOilPaint(baseSize)
            PresetType.CHARCOAL -> createCharcoal(baseSize)
            PresetType.ERASER_SOFT -> createEraserSoft(baseSize)
            PresetType.ERASER_HARD -> createEraserHard(baseSize)
            PresetType.BLENDER -> createBlender(baseSize)
            PresetType.SPLATTER -> createSplatter(baseSize)
        }
    }
    
    /**
     * Pencil preset - hard edge with pressure sensitivity
     */
    private fun createPencil(size: Float): BrushParams {
        return BrushParams(
            size = size,
            opacity = 0.8f,
            spacing = 0.05f,
            scatter = 0.0f,
            count = 1,
            rotation = 0f,
            taperStart = 0.3f,
            taperEnd = 0.3f,
            pressureToSize = 0.7f,
            pressureToOpacity = 0.5f,
            pressureCurve = BrushParams.PressureCurve.EASE_IN,
            hueJitter = 0f,
            saturationJitter = 0f,
            brightnessJitter = 0f,
            colorPressure = false,
            smoothing = 0.3f,
            wetMix = 0f,
            flow = 1.0f,
            textureId = "pencil_grain",
            textureScale = 0.8f,
            blendTexture = true
        )
    }
    
    /**
     * Pen preset - consistent line width, minimal pressure effect
     */
    private fun createPen(size: Float): BrushParams {
        return BrushParams(
            size = size,
            opacity = 1.0f,
            spacing = 0.03f,
            scatter = 0.0f,
            count = 1,
            rotation = 0f,
            taperStart = 0.1f,
            taperEnd = 0.1f,
            pressureToSize = 0.2f,
            pressureToOpacity = 0f,
            pressureCurve = BrushParams.PressureCurve.LINEAR,
            hueJitter = 0f,
            saturationJitter = 0f,
            brightnessJitter = 0f,
            colorPressure = false,
            smoothing = 0.5f,
            wetMix = 0f,
            flow = 1.0f
        )
    }
    
    /**
     * Marker preset - broad stroke with slight texture
     */
    private fun createMarker(size: Float): BrushParams {
        return BrushParams(
            size = size * 1.5f,
            opacity = 0.9f,
            spacing = 0.08f,
            scatter = 0.0f,
            count = 1,
            rotation = 0f,
            taperStart = 0f,
            taperEnd = 0f,
            pressureToSize = 0.3f,
            pressureToOpacity = 0.2f,
            pressureCurve = BrushParams.PressureCurve.LINEAR,
            hueJitter = 0f,
            saturationJitter = 0f,
            brightnessJitter = 0f,
            colorPressure = false,
            smoothing = 0.2f,
            wetMix = 0.3f,
            flow = 0.9f,
            textureId = "marker_texture",
            textureScale = 1.2f,
            blendTexture = true
        )
    }
    
    /**
     * Airbrush preset - soft edges, buildable opacity
     */
    private fun createAirbrush(size: Float): BrushParams {
        return BrushParams(
            size = size * 2f,
            opacity = 0.15f,
            spacing = 0.02f,
            scatter = 0.1f,
            count = 3,
            rotation = 0f,
            taperStart = 0f,
            taperEnd = 0f,
            pressureToSize = 0.4f,
            pressureToOpacity = 0.6f,
            pressureCurve = BrushParams.PressureCurve.EASE_OUT,
            hueJitter = 0f,
            saturationJitter = 0f,
            brightnessJitter = 0f,
            colorPressure = false,
            smoothing = 0.7f,
            wetMix = 0f,
            flow = 0.3f
        )
    }
    
    /**
     * Watercolor preset - wet mix with color bleeding
     */
    private fun createWatercolor(size: Float): BrushParams {
        return BrushParams(
            size = size * 1.8f,
            opacity = 0.4f,
            spacing = 0.1f,
            scatter = 0.05f,
            count = 1,
            rotation = 0f,
            taperStart = 0.2f,
            taperEnd = 0.2f,
            pressureToSize = 0.5f,
            pressureToOpacity = 0.4f,
            pressureCurve = BrushParams.PressureCurve.EASE_IN_OUT,
            hueJitter = 0.1f,
            saturationJitter = 0.15f,
            brightnessJitter = 0.2f,
            colorPressure = true,
            smoothing = 0.4f,
            wetMix = 0.8f,
            flow = 0.6f,
            textureId = "watercolor_paper",
            textureScale = 1.5f,
            blendTexture = true
        )
    }
    
    /**
     * Oil paint preset - thick strokes with heavy texture
     */
    private fun createOilPaint(size: Float): BrushParams {
        return BrushParams(
            size = size * 2.5f,
            opacity = 0.85f,
            spacing = 0.15f,
            scatter = 0.1f,
            count = 2,
            rotation = 0f,
            taperStart = 0f,
            taperEnd = 0f,
            pressureToSize = 0.6f,
            pressureToOpacity = 0.3f,
            pressureCurve = BrushParams.PressureCurve.LINEAR,
            hueJitter = 0.05f,
            saturationJitter = 0.1f,
            brightnessJitter = 0.1f,
            colorPressure = true,
            smoothing = 0.3f,
            wetMix = 0.7f,
            flow = 0.8f,
            textureId = "canvas_grain",
            textureScale = 2.0f,
            blendTexture = true,
            tiltInfluence = 0.5f,
            tiltToRotation = true
        )
    }
    
    /**
     * Charcoal preset - rough texture with high scatter
     */
    private fun createCharcoal(size: Float): BrushParams {
        return BrushParams(
            size = size * 2f,
            opacity = 0.7f,
            spacing = 0.12f,
            scatter = 0.3f,
            count = 2,
            rotation = 0f,
            taperStart = 0.4f,
            taperEnd = 0.4f,
            pressureToSize = 0.6f,
            pressureToOpacity = 0.5f,
            pressureCurve = BrushParams.PressureCurve.EASE_IN,
            hueJitter = 0f,
            saturationJitter = 0f,
            brightnessJitter = 0.3f,
            colorPressure = false,
            smoothing = 0.2f,
            wetMix = 0f,
            flow = 0.9f,
            textureId = "charcoal_grain",
            textureScale = 1.8f,
            blendTexture = true
        )
    }
    
    /**
     * Soft eraser preset - gradual erase with soft edges
     */
    private fun createEraserSoft(size: Float): BrushParams {
        return BrushParams(
            size = size * 1.5f,
            opacity = 0.3f,
            spacing = 0.05f,
            scatter = 0.0f,
            count = 1,
            rotation = 0f,
            taperStart = 0f,
            taperEnd = 0f,
            pressureToSize = 0.3f,
            pressureToOpacity = 0.4f,
            pressureCurve = BrushParams.PressureCurve.EASE_OUT,
            hueJitter = 0f,
            saturationJitter = 0f,
            brightnessJitter = 0f,
            colorPressure = false,
            smoothing = 0.5f,
            wetMix = 0f,
            flow = 0.5f
        )
    }
    
    /**
     * Hard eraser preset - precise erase with hard edges
     */
    private fun createEraserHard(size: Float): BrushParams {
        return BrushParams(
            size = size,
            opacity = 1.0f,
            spacing = 0.03f,
            scatter = 0.0f,
            count = 1,
            rotation = 0f,
            taperStart = 0f,
            taperEnd = 0f,
            pressureToSize = 0.1f,
            pressureToOpacity = 0f,
            pressureCurve = BrushParams.PressureCurve.LINEAR,
            hueJitter = 0f,
            saturationJitter = 0f,
            brightnessJitter = 0f,
            colorPressure = false,
            smoothing = 0.3f,
            wetMix = 0f,
            flow = 1.0f
        )
    }
    
    /**
     * Blender preset - smudge and mix colors
     */
    private fun createBlender(size: Float): BrushParams {
        return BrushParams(
            size = size * 2f,
            opacity = 0.5f,
            spacing = 0.08f,
            scatter = 0.0f,
            count = 1,
            rotation = 0f,
            taperStart = 0f,
            taperEnd = 0f,
            pressureToSize = 0.4f,
            pressureToOpacity = 0.3f,
            pressureCurve = BrushParams.PressureCurve.LINEAR,
            hueJitter = 0f,
            saturationJitter = 0f,
            brightnessJitter = 0f,
            colorPressure = false,
            smoothing = 0.6f,
            wetMix = 1.0f,
            flow = 0.7f
        )
    }
    
    /**
     * Splatter preset - random scattered dots
     */
    private fun createSplatter(size: Float): BrushParams {
        return BrushParams(
            size = size * 0.8f,
            opacity = 0.9f,
            spacing = 0.3f,
            scatter = 0.8f,
            count = 5,
            rotation = 0f,
            taperStart = 0f,
            taperEnd = 0f,
            pressureToSize = 0.5f,
            pressureToOpacity = 0.3f,
            pressureCurve = BrushParams.PressureCurve.LINEAR,
            hueJitter = 0.2f,
            saturationJitter = 0.2f,
            brightnessJitter = 0.3f,
            colorPressure = true,
            smoothing = 0f,
            wetMix = 0.2f,
            flow = 1.0f,
            textureId = "splatter_mask",
            textureScale = 1.0f,
            blendTexture = false
        )
    }
}
