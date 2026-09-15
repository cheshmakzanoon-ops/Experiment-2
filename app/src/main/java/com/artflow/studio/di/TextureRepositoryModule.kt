package com.artflow.studio.di

import com.artflow.studio.data.repository.texture.TextureRepositoryImpl
import com.artflow.studio.domain.repository.texture.TextureRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module for texture repository dependency injection
 * Implements Phase 10: Brush Textures & Stamps
 *
 * [TextureRepositoryImpl] is constructor-injected with an `@ApplicationContext`
 * Context, so only the interface binding is declared here.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class TextureRepositoryModule {

    @Binds
    @Singleton
    abstract fun bindTextureRepository(
        textureRepositoryImpl: TextureRepositoryImpl
    ): TextureRepository
}
