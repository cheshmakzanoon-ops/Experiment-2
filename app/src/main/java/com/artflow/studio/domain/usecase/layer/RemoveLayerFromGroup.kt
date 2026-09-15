package com.artflow.studio.domain.usecase.layer

import com.artflow.studio.core.layer.LayerGroupManager
import javax.inject.Inject

/**
 * Use case for removing a layer from a group
 * Implements Phase 26: Layer Groups
 */
class RemoveLayerFromGroup @Inject constructor(
    private val layerGroupManager: LayerGroupManager
) {
    /**
     * Execute the remove layer from group use case
     * @param groupId The group ID
     * @param layerId The layer ID to remove
     * @return Result indicating success or failure
     */
    operator fun invoke(
        groupId: Long,
        layerId: Long
    ): Result {
        val success = layerGroupManager.removeLayerFromGroup(groupId, layerId)
        
        return if (success) {
            Result.Success
        } else {
            Result.Failure("Failed to remove layer from group")
        }
    }
    
    sealed class Result {
        object Success : Result()
        data class Failure(val error: String) : Result()
    }
}
