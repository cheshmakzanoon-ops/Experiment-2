package com.artflow.studio.di

import com.artflow.studio.data.repository.canvas.CanvasRepositoryImpl
import com.artflow.studio.domain.repository.canvas.CanvasRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module for providing canvas repository implementations
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class CanvasRepositoryModule {

    @Binds
    @Singleton
    abstract fun bindCanvasRepository(
        impl: CanvasRepositoryImpl
    ): CanvasRepository
}
