package com.artflow.studio.domain.usecase.layer

import com.artflow.studio.core.layer.LayerGroupManager
import javax.inject.Inject

/**
 * Use case for adding a layer to a group
 * Implements Phase 26: Layer Groups
 */
class AddLayerToGroup @Inject constructor(
    private val layerGroupManager: LayerGroupManager
) {
    /**
     * Execute the add layer to group use case
     * @param groupId The target group ID
     * @param layerId The layer ID to add
     * @param index Position within the group (defaults to end)
     * @return Result indicating success or failure
     */
    operator fun invoke(
        groupId: Long,
        layerId: Long,
        index: Int = -1
    ): Result {
        val success = layerGroupManager.addLayerToGroup(groupId, layerId, index)
        
        return if (success) {
            Result.Success
        } else {
            Result.Failure("Failed to add layer to group")
        }
    }
    
    sealed class Result {
        object Success : Result()
        data class Failure(val error: String) : Result()
    }
}
