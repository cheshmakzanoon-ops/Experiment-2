package com.artflow.studio.di

import com.artflow.studio.core.layer.LayerManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module for providing LayerManager dependencies
 */
@Module
@InstallIn(SingletonComponent::class)
object LayerManagerModule {

    @Provides
    @Singleton
    fun provideLayerManager(): LayerManager {
        return LayerManager()
    }
}
