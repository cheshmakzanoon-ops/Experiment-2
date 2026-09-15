package com.artflow.studio.domain.model.texture

/**
 * Domain model representing a brush stamp
 * Used for stamp-based brushes in Phase 10: Brush Textures & Stamps
 */
data class BrushStamp(
    val id: String,
    val name: String,
    val category: StampCategory = StampCategory.CUSTOM,
    val shapeData: StampShapeData? = null,   // Vector shape data if procedural
    val isProcedural: Boolean = false,       // True if generated algorithmically
    val rotationSteps: Int = 1,              // Number of rotation variants (1 = no rotation)
    val scaleVariants: Int = 1,              // Number of scale variants
    val createdAt: Long = System.currentTimeMillis()
) {
    /**
     * Stamp categories for organization
     */
    enum class StampCategory {
        NATURE,         // Leaves, flowers, trees, grass
        WEATHER,        // Clouds, rain, snow, lightning
        TEXTURES,       // Fur, scales, skin patterns
        SHAPES,         // Geometric shapes, stars, hearts
        LETTERING,      // Calligraphy elements, bubbles
        EFFECTS,        // Sparks, smoke, magic effects
        CUSTOM          // User-created stamps
    }
    
    /**
     * Procedural shape data for generating stamps algorithmically
     */
    data class StampShapeData(
        val shapeType: ShapeType,
        val parameters: Map<String, Float> = emptyMap(),
        val complexity: Int = 50            // Number of points/segments
    )
    
    /**
     * Types of procedural shapes
     */
    enum class ShapeType {
        CIRCLE,
        ELLIPSE,
        RECTANGLE,
        POLYGON,
        STAR,
        SPIRAL,
        NOISE,
        GRADIENT,
        CUSTOM_PATH
    }
}
