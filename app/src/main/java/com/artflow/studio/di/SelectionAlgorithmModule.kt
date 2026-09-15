package com.artflow.studio.di

import com.artflow.studio.core.selection.ColorSelectionAlgorithm
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module for providing selection algorithm dependencies
 */
@Module
@InstallIn(SingletonComponent::class)
object SelectionAlgorithmModule {

    @Provides
    @Singleton
    fun provideColorSelectionAlgorithm(): ColorSelectionAlgorithm {
        return ColorSelectionAlgorithm()
    }
}
