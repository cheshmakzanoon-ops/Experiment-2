package com.artflow.studio.domain.usecase.texture

import com.artflow.studio.domain.model.texture.BrushTexture
import com.artflow.studio.domain.repository.texture.TextureRepository
import javax.inject.Inject

/**
 * Use case for importing a custom texture from file
 * Implements Phase 10: Brush Textures & Stamps
 */
class ImportCustomTexture @Inject constructor(
    private val textureRepository: TextureRepository
) {
    
    data class Params(
        val filePath: String,
        val name: String
    )
    
    data class Result(
        val success: Boolean,
        val textureId: String?,
        val texture: BrushTexture?,
        val errorMessage: String? = null
    )
    
    suspend operator fun invoke(params: Params): Result {
        return try {
            val result = textureRepository.importTextureFromPath(params.filePath, params.name)
            result.fold(
                onSuccess = { texture ->
                    Result(success = true, textureId = texture.id, texture = texture)
                },
                onFailure = { error ->
                    Result(success = false, textureId = null, texture = null, errorMessage = error.message)
                }
            )
        } catch (e: Exception) {
            Result(success = false, textureId = null, texture = null, errorMessage = e.message)
        }
    }
}
