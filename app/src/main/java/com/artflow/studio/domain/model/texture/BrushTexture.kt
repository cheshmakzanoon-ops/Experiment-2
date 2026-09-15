package com.artflow.studio.domain.model.texture

import android.graphics.Bitmap

/**
 * Domain model representing a brush texture
 * Used by the brush engine for texture mapping on strokes
 * Implements Phase 10: Brush Textures & Stamps
 */
data class BrushTexture(
    val id: String,
    val name: String,
    val category: TextureCategory = TextureCategory.CUSTOM,
    val bitmap: Bitmap? = null,              // Loaded bitmap (null if not yet loaded)
    val isSeamless: Boolean = false,         // Whether texture tiles seamlessly
    val grayscale: Boolean = true,           // Whether texture is grayscale
    val width: Int = 512,                    // Preferred width
    val height: Int = 512,                   // Preferred height
    val createdAt: Long = System.currentTimeMillis(),
    val isCustom: Boolean = false            // True if user-imported
) {
    /**
     * Texture categories for organization
     */
    enum class TextureCategory {
        PAPER,          // Paper textures (watercolor, canvas, etc.)
        GRAIN,          // Grain textures (pencil, charcoal, etc.)
        NATURAL,        // Natural patterns (wood, stone, etc.)
        GEOMETRIC,      // Geometric patterns
        FABRIC,         // Fabric textures
        CUSTOM,         // User-imported textures
        STAMP           // Shape stamps (leaves, clouds, etc.)
    }
    
    /**
     * Check if texture is ready for use
     */
    fun isReady(): Boolean = bitmap != null && !bitmap.isRecycled
    
    /**
     * Get estimated memory size in bytes
     */
    fun getMemorySize(): Int {
        return bitmap?.let { 
            it.allocationByteCount 
        } ?: (width * height * 4) // Estimate RGBA8888 if not loaded
    }
    
    /**
     * Create a copy with different bitmap
     */
    fun copyWithBitmap(newBitmap: Bitmap?): BrushTexture {
        return copy(bitmap = newBitmap)
    }
}
