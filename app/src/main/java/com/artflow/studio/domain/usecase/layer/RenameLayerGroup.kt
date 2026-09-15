package com.artflow.studio.domain.usecase.layer

import com.artflow.studio.core.layer.LayerGroupManager
import javax.inject.Inject

/**
 * Use case for renaming a layer group
 * Implements Phase 26: Layer Groups
 */
class RenameLayerGroup @Inject constructor(
    private val layerGroupManager: LayerGroupManager
) {
    /**
     * Execute the rename layer group use case
     * @param groupId The group ID to rename
     * @param newName The new name for the group
     * @return Result indicating success or failure
     */
    operator fun invoke(groupId: Long, newName: String): Result {
        if (newName.isBlank()) {
            return Result.Failure("Group name cannot be empty")
        }
        
        val success = layerGroupManager.renameGroup(groupId, newName)
        
        return if (success) {
            Result.Success
        } else {
            Result.Failure("Failed to rename group")
        }
    }
    
    sealed class Result {
        object Success : Result()
        data class Failure(val error: String) : Result()
    }
}
