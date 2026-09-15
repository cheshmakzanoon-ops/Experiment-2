package com.artflow.studio.domain.usecase.layer

import com.artflow.studio.core.layer.LayerGroupManager
import javax.inject.Inject

/**
 * Use case for removing a layer group
 * Implements Phase 26: Layer Groups
 */
class RemoveLayerGroup @Inject constructor(
    private val layerGroupManager: LayerGroupManager
) {
    /**
     * Execute the remove layer group use case
     * @param groupId The ID of the group to remove
     * @param removeContainedLayers If true, also remove layers in the group
     * @return Result indicating success or failure
     */
    operator fun invoke(
        groupId: Long,
        removeContainedLayers: Boolean = false
    ): Result {
        val success = layerGroupManager.removeGroup(groupId, removeContainedLayers)
        
        return if (success) {
            Result.Success
        } else {
            Result.Failure("Failed to remove layer group")
        }
    }
    
    sealed class Result {
        object Success : Result()
        data class Failure(val error: String) : Result()
    }
}
