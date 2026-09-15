package com.artflow.studio.di

import com.artflow.studio.core.layer.LayerGroupManager
import com.artflow.studio.domain.usecase.layer.*
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module for providing LayerGroup-related dependencies
 * Implements Phase 26: Layer Groups dependency injection
 */
@Module
@InstallIn(SingletonComponent::class)
object LayerGroupModule {

    @Provides
    @Singleton
    fun provideLayerGroupManager(): LayerGroupManager {
        return LayerGroupManager()
    }

    @Provides
    fun provideCreateLayerGroup(layerGroupManager: LayerGroupManager): CreateLayerGroup {
        return CreateLayerGroup(layerGroupManager)
    }

    @Provides
    fun provideRemoveLayerGroup(layerGroupManager: LayerGroupManager): RemoveLayerGroup {
        return RemoveLayerGroup(layerGroupManager)
    }

    @Provides
    fun provideAddLayerToGroup(layerGroupManager: LayerGroupManager): AddLayerToGroup {
        return AddLayerToGroup(layerGroupManager)
    }

    @Provides
    fun provideRemoveLayerFromGroup(layerGroupManager: LayerGroupManager): RemoveLayerFromGroup {
        return RemoveLayerFromGroup(layerGroupManager)
    }

    @Provides
    fun provideToggleGroupExpansion(layerGroupManager: LayerGroupManager): ToggleGroupExpansion {
        return ToggleGroupExpansion(layerGroupManager)
    }

    @Provides
    fun provideSetLayerGroupVisibility(layerGroupManager: LayerGroupManager): SetLayerGroupVisibility {
        return SetLayerGroupVisibility(layerGroupManager)
    }

    @Provides
    fun provideRenameLayerGroup(layerGroupManager: LayerGroupManager): RenameLayerGroup {
        return RenameLayerGroup(layerGroupManager)
    }
}
