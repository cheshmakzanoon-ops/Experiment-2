package com.artflow.studio.domain.model.brush

/**
 * Domain model representing brush parameters
 * Used by the brush engine to control stroke rendering
 */
data class BrushParams(
    val size: Float = 20f,                    // Brush size in pixels
    val opacity: Float = 1.0f,                // Opacity 0.0 - 1.0
    val spacing: Float = 0.1f,                // Spacing between dabs (0.0 - 1.0)
    val scatter: Float = 0.0f,                // Scatter amount (0.0 - 1.0)
    val count: Int = 1,                       // Number of dabs per spacing interval
    val rotation: Float = 0f,                 // Brush rotation in degrees
    val taperStart: Float = 0f,               // Taper at stroke start (0.0 - 1.0)
    val taperEnd: Float = 0f,                 // Taper at stroke end (0.0 - 1.0)
    
    // Pressure dynamics
    val pressureToSize: Float = 0.5f,         // How much pressure affects size
    val pressureToOpacity: Float = 0.3f,      // How much pressure affects opacity
    val pressureCurve: PressureCurve = PressureCurve.LINEAR,
    
    // Color dynamics
    val hueJitter: Float = 0f,                // Hue variation (0.0 - 1.0)
    val saturationJitter: Float = 0f,         // Saturation variation (0.0 - 1.0)
    val brightnessJitter: Float = 0f,         // Brightness variation (0.0 - 1.0)
    val colorPressure: Boolean = false,       // Enable pressure-based color dynamics
    
    // Stroke behavior
    val smoothing: Float = 0.5f,              // Stroke smoothing amount (0.0 - 1.0)
    val wetMix: Float = 0f,                   // Wet paint mixing (0.0 - 1.0)
    val flow: Float = 1.0f,                   // Paint flow rate (0.0 - 1.0)
    
    // Texture
    val textureId: String? = null,            // Texture identifier
    val textureScale: Float = 1.0f,           // Texture scale factor
    val textureRotation: Float = 0f,          // Texture rotation in degrees
    val blendTexture: Boolean = false,        // Blend texture with color
    
    // Advanced
    val tiltInfluence: Float = 0f,            // How much tilt affects brush
    val tiltToRotation: Boolean = false,      // Map tilt to brush rotation
    val velocityToSize: Float = 0f,           // Speed affects size
    val velocityToOpacity: Float = 0f         // Speed affects opacity
) {
    /**
     * Pressure curve types for mapping stylus pressure
     */
    enum class PressureCurve {
        LINEAR,       // Direct 1:1 mapping
        EASE_IN,      // Gradual start, sharp end
        EASE_OUT,     // Sharp start, gradual end
        EASE_IN_OUT,  // Gradual start and end
        CUSTOM        // Custom curve (not yet implemented)
    }
}
