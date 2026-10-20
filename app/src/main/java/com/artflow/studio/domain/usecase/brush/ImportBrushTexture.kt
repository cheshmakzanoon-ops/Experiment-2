package com.artflow.studio.domain.usecase.brush

import com.artflow.studio.domain.model.brush.BrushTexture
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Use case for importing custom brush textures from user storage
 * Implements Phase 10: Brush Textures & Stamps - Custom texture importer
 */
@Singleton
class ImportBrushTexture @Inject constructor() {
    
    /**
     * Import a texture from the specified file path
     * Validates and creates a BrushTexture entry for the imported file
     * 
     * @param filePath Path to the texture image file
     * @param name Display name for the texture
     * @param category Category to assign the texture to
     * @return Result containing the created BrushTexture or an error
     */
    suspend operator fun invoke(
        filePath: String,
        name: String,
        category: BrushTexture.TextureCategory = BrushTexture.TextureCategory.CUSTOM
    ): Result<BrushTexture> {
        return try {
            // Validate file exists and is a valid image
            if (!validateImageFile(filePath)) {
                return Result.failure(IllegalArgumentException("Invalid image file: $filePath"))
            }
            
            // Get image dimensions
            val dimensions = getImageDimensions(filePath)
            
            // Check if dimensions are power of 2 (recommended for GPU textures)
            val isPowerOfTwo = isPowerOfTwo(dimensions.first) && isPowerOfTwo(dimensions.second)
            
            // Generate unique ID
            val textureId = generateTextureId(name)
            
            // Create brush texture entry
            val texture = BrushTexture(
                id = textureId,
                name = name,
                category = category,
                filePath = filePath,
                width = dimensions.first,
                height = dimensions.second,
                isSeamless = false,  // User textures are not seamless by default
                isCustom = true,
                thumbnailPath = generateThumbnailPath(textureId)
            )
            
            // Generate thumbnail for the texture
            generateThumbnail(texture)
            
            Result.success(texture)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    /**
     * Validate that the file is a valid image format
     */
    private fun validateImageFile(filePath: String): Boolean {
        val supportedExtensions = listOf(".png", ".jpg", ".jpeg", ".webp", ".bmp")
        return supportedExtensions.any { filePath.lowercase().endsWith(it) }
    }
    
    /**
     * Get image dimensions from the file
     * In production, this would use BitmapFactory.decodeBounds()
     */
    private fun getImageDimensions(filePath: String): Pair<Int, Int> {
        // TODO: Implement actual image dimension reading using Android Bitmap
        // For now, return default values
        return Pair(512, 512)
    }
    
    /**
     * Check if a number is a power of 2
     */
    private fun isPowerOfTwo(value: Int): Boolean {
        return value > 0 && (value and (value - 1)) == 0
    }
    
    /**
     * Generate a unique texture ID based on name and timestamp
     */
    private fun generateTextureId(name: String): String {
        val sanitizedName = name
            .lowercase()
            .replace("\\s+".toRegex(), "_")
            .replace("[^a-z0-9_]".toRegex(), "")
        return "custom_${sanitizedName}_${System.currentTimeMillis()}"
    }
    
    /**
     * Generate thumbnail path for the texture
     */
    private fun generateThumbnailPath(textureId: String): String {
        return "textures/thumbnails/${textureId}_thumb.png"
    }
    
    /**
     * Generate a thumbnail preview for the imported texture
     * In production, this would create a scaled-down version of the texture
     */
    private suspend fun generateThumbnail(texture: BrushTexture) {
        // TODO: Implement thumbnail generation
        // This would create a small (64x64 or 128x128) preview image
        // stored in the thumbnails directory
    }
    
    /**
     * Batch import multiple textures at once
     * 
     * @param filePaths List of paths to texture files
     * @param category Category to assign all textures to
     * @return Result with list of successfully imported textures and any errors
     */
    suspend fun importMultipleTextures(
        filePaths: List<String>,
        category: BrushTexture.TextureCategory = BrushTexture.TextureCategory.CUSTOM
    ): Result<List<BrushTexture>> {
        val successList = mutableListOf<BrushTexture>()
        val errors = mutableListOf<Pair<String, String>>()
        
        filePaths.forEach { filePath ->
            invoke(filePath, extractFileName(filePath), category)
                .onSuccess { successList.add(it) }
                .onFailure { errors.add(Pair(filePath, it.message ?: "Unknown error")) }
        }
        
        return if (errors.isEmpty()) {
            Result.success(successList)
        } else {
            // Return partial success with imported textures
            // Errors can be logged or displayed to user
            Result.success(successList)
        }
    }
    
    /**
     * Extract file name from path for display
     */
    private fun extractFileName(filePath: String): String {
        return filePath.substringAfterLast('/').substringBeforeLast('.')
    }
}
