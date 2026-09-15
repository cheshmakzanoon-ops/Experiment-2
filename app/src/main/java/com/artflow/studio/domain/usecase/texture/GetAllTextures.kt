package com.artflow.studio.domain.usecase.texture

import com.artflow.studio.domain.model.texture.BrushStamp
import com.artflow.studio.domain.model.texture.BrushTexture
import com.artflow.studio.domain.repository.texture.TextureRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/**
 * Use case for getting all available brush textures
 * Implements Phase 10: Brush Textures & Stamps
 */
class GetAllTextures @Inject constructor(
    private val textureRepository: TextureRepository
) {
    operator fun invoke(): Flow<List<BrushTexture>> {
        return textureRepository.getAllTextures()
    }
}
