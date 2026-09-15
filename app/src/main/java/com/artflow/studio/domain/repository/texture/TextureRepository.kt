package com.artflow.studio.domain.repository.texture

import com.artflow.studio.domain.model.texture.BrushTexture
import com.artflow.studio.domain.model.texture.BrushStamp
import kotlinx.coroutines.flow.Flow

/**
 * Repository interface for brush texture and stamp operations
 * Implements Phase 10: Brush Textures & Stamps
 */
interface TextureRepository {
    
    /**
     * Get all available textures as a Flow
     */
    fun getAllTextures(): Flow<List<BrushTexture>>
    
    /**
     * Get textures by category
     */
    fun getTexturesByCategory(category: BrushTexture.TextureCategory): Flow<List<BrushTexture>>
    
    /**
     * Get a specific texture by ID
     */
    suspend fun getTextureById(id: String): BrushTexture?
    
    /**
     * Load texture bitmap asynchronously
     */
    suspend fun loadTextureBitmap(textureId: String): Result<BrushTexture>
    
    /**
     * Add a new custom texture
     */
    suspend fun addCustomTexture(texture: BrushTexture): Result<String>
    
    /**
     * Delete a texture
     */
    suspend fun deleteTexture(textureId: String): Result<Unit>
    
    /**
     * Update texture metadata
     */
    suspend fun updateTexture(texture: BrushTexture): Result<Unit>
    
    /**
     * Get all available stamps as a Flow
     */
    fun getAllStamps(): Flow<List<BrushStamp>>
    
    /**
     * Get stamps by category
     */
    fun getStampsByCategory(category: BrushStamp.StampCategory): Flow<List<BrushStamp>>
    
    /**
     * Get a specific stamp by ID
     */
    suspend fun getStampById(id: String): BrushStamp?
    
    /**
     * Add a new custom stamp
     */
    suspend fun addCustomStamp(stamp: BrushStamp): Result<String>
    
    /**
     * Delete a stamp
     */
    suspend fun deleteStamp(stampId: String): Result<Unit>
    
    /**
     * Generate procedural stamp shape data
     */
    suspend fun generateProceduralStamp(
        shapeType: BrushStamp.ShapeType,
        parameters: Map<String, Float>,
        complexity: Int
    ): Result<BrushStamp>
    
    /**
     * Import texture from file path
     */
    suspend fun importTextureFromPath(filePath: String, name: String): Result<BrushTexture>
    
    /**
     * Export texture to file path
     */
    suspend fun exportTextureToPath(textureId: String, filePath: String): Result<Unit>
    
    /**
     * Clear texture cache to free memory
     */
    suspend fun clearCache(): Unit
    
    /**
     * Get total memory usage of loaded textures in bytes
     */
    suspend fun getMemoryUsage(): Long
}
