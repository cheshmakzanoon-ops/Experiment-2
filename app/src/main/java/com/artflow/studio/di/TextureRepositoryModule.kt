package com.artflow.studio.di

import android.content.Context
import com.artflow.studio.data.repository.texture.TextureRepositoryImpl
import com.artflow.studio.domain.repository.texture.TextureRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module for texture repository dependency injection
 * Implements Phase 10: Brush Textures & Stamps
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class TextureRepositoryModule {
    
    @Binds
    @Singleton
    abstract fun bindTextureRepository(
        textureRepositoryImpl: TextureRepositoryImpl
    ): TextureRepository
    
    companion object {
        
        @Provides
        @Singleton
        fun provideTextureRepositoryInitializer(
            @ApplicationContext context: Context
        ): TextureRepositoryImpl {
            return TextureRepositoryImpl(context)
        }
    }
}
