package com.artflow.studio.di

import com.artflow.studio.core.brush.BrushEngine
import com.artflow.studio.core.brush.TextureMapper
import com.artflow.studio.domain.repository.texture.TextureRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module for providing BrushEngine dependencies
 * Implements Phase 7: Brush Engine Foundation
 */
@Module
@InstallIn(SingletonComponent::class)
object BrushEngineModule {

    @Provides
    @Singleton
    fun provideBrushEngine(
        textureMapper: TextureMapper,
        textureRepository: TextureRepository
    ): BrushEngine {
        return BrushEngine(textureMapper, textureRepository)
    }
}
