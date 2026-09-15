package com.artflow.studio.di

import com.artflow.studio.core.shape.ShapeManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module for providing ShapeManager dependencies
 * Implements Phase 24: Shape Tools dependency injection
 */
@Module
@InstallIn(SingletonComponent::class)
object ShapeManagerModule {

    @Provides
    @Singleton
    fun provideShapeManager(): ShapeManager {
        return ShapeManager()
    }
}
