package com.artflow.studio.domain.usecase.texture

import com.artflow.studio.domain.model.texture.BrushTexture
import com.artflow.studio.domain.repository.texture.TextureRepository
import javax.inject.Inject

/**
 * Use case for loading a texture bitmap
 * Implements Phase 10: Brush Textures & Stamps
 */
class LoadTextureBitmap @Inject constructor(
    private val textureRepository: TextureRepository
) {
    
    data class Result(
        val success: Boolean,
        val texture: BrushTexture?,
        val errorMessage: String? = null
    )
    
    suspend operator fun invoke(textureId: String): Result {
        return try {
            val result = textureRepository.loadTextureBitmap(textureId)
            result.fold(
                onSuccess = { texture ->
                    Result(success = true, texture = texture)
                },
                onFailure = { error ->
                    Result(success = false, texture = null, errorMessage = error.message)
                }
            )
        } catch (e: Exception) {
            Result(success = false, texture = null, errorMessage = e.message)
        }
    }
}
