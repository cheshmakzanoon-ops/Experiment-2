package com.artflow.studio.di

import com.artflow.studio.core.selection.SelectionManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module for providing SelectionManager dependency
 */
@Module
@InstallIn(SingletonComponent::class)
object SelectionManagerModule {

    @Provides
    @Singleton
    fun provideSelectionManager(): SelectionManager {
        return SelectionManager()
    }
}
