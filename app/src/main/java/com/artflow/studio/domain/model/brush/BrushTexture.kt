package com.artflow.studio.domain.model.brush

/**
 * Domain model representing a brush texture
 * Textures are used to add grain, patterns, and surface detail to brush strokes
 * Implements Phase 10: Brush Textures & Stamps
 */
data class BrushTexture(
    val id: String,
    val name: String,
    val category: TextureCategory,
    val filePath: String,
    val width: Int = 512,
    val height: Int = 512,
    val isSeamless: Boolean = false,
    val isCustom: Boolean = false,
    val thumbnailPath: String? = null,
    val blendMode: TextureBlendMode = TextureBlendMode.MULTIPLY,
    val scale: Float = 1.0f,
    val rotation: Float = 0f,
    val depth: Float = 0.5f  // How much the texture affects the stroke (0.0 - 1.0)
) {
    /**
     * Texture categories for organization
     */
    enum class TextureCategory {
        PAPER,          // Paper textures (watercolor, canvas, sketch)
        FABRIC,         // Fabric and cloth textures
        NATURAL,        // Natural materials (wood, stone, leather)
        PATTERNS,       // Geometric and decorative patterns
        GRAIN,          // Film grain and noise
        HALFTONE,       // Halftone dot patterns
        BRUSHED,        // Brushed metal effects
        CUSTOM          // User-imported textures
    }
}

/**
 * Texture blend modes for combining texture with brush color
 */
enum class TextureBlendMode {
    MULTIPLY,       // Darken based on texture (default for most brushes)
    SCREEN,         // Lighten based on texture
    OVERLAY,        // Combine multiply and screen
    SOFT_LIGHT,     // Subtle overlay effect
    HARD_LIGHT,     // Strong overlay effect
    COLOR_DODGE,    // Brighten colors
    COLOR_BURN,     // Darken colors
    LINEAR_LIGHT,   // Intense light effect
    PIN_LIGHT,      // Extreme contrast
    DIFFERENCE,     // Invert based on difference
    EXCLUSION,      // Softer difference
    NORMAL          // Direct texture application
}

/**
 * Texture mapping modes for applying texture to strokes
 */
enum class TextureMappingMode {
    STAMP,          // Texture stamps at each dab point
    CONTINUOUS,     // Continuous texture along stroke path
    TILE,           // Tiled texture across entire stroke
    FIT,            // Texture stretched to fit stroke bounds
    REPEAT          // Repeating pattern along stroke
}

/**
 * Dual texture configuration for complex brush effects
 * Allows combining two textures for richer results
 */
data class DualTextureConfig(
    val primaryTextureId: String,
    val secondaryTextureId: String,
    val mixRatio: Float = 0.5f,  // 0.0 = only primary, 1.0 = only secondary
    val blendMode: TextureBlendMode = TextureBlendMode.NORMAL,
    val secondaryOffsetX: Float = 0f,
    val secondaryOffsetY: Float = 0f,
    val secondaryRotation: Float = 0f,
    val secondaryScale: Float = 1.0f
) {
    init {
        require(mixRatio in 0.0f..1.0f) { "Mix ratio must be between 0.0 and 1.0" }
    }
}

/**
 * Texture library for managing available brush textures
 */
data class TextureLibrary(
    val textures: List<BrushTexture> = emptyList(),
    val favorites: Set<String> = emptySet(),  // Set of texture IDs
    val recentlyUsed: List<String> = emptyList()  // Ordered list of texture IDs
) {
    /**
     * Get textures by category
     */
    fun getTexturesByCategory(category: BrushTexture.TextureCategory): List<BrushTexture> {
        return textures.filter { it.category == category }
    }
    
    /**
     * Search textures by name
     */
    fun searchTextures(query: String): List<BrushTexture> {
        val lowerQuery = query.lowercase()
        return textures.filter { 
            it.name.lowercase().contains(lowerQuery) || 
            it.category.name.lowercase().contains(lowerQuery)
        }
    }
    
    /**
     * Add texture to favorites
     */
    fun addToFavorites(textureId: String): TextureLibrary {
        return copy(favorites = favorites + textureId)
    }
    
    /**
     * Remove texture from favorites
     */
    fun removeFromFavorites(textureId: String): TextureLibrary {
        return copy(favorites = favorites - textureId)
    }
    
    /**
     * Mark texture as recently used
     */
    fun markAsRecentlyUsed(textureId: String): TextureLibrary {
        val updatedRecentlyUsed = (listOf(textureId) + recentlyUsed.filter { it != textureId })
            .take(10)  // Keep last 10
        return copy(recentlyUsed = updatedRecentlyUsed)
    }
}

/**
 * Event types for texture operations
 */
sealed class TextureEvent {
    data class TextureLoaded(val texture: BrushTexture) : TextureEvent()
    data class TextureRemoved(val textureId: String) : TextureEvent()
    data class TextureImported(val texture: BrushTexture) : TextureEvent()
    data class TextureFavoriteToggled(val textureId: String, val isFavorite: Boolean) : TextureEvent()
    object TextureLibraryRefreshed : TextureEvent()
}
