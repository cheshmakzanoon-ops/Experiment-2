package com.artflow.studio.domain.usecase.layer

import com.artflow.studio.core.layer.LayerGroupManager
import javax.inject.Inject

/**
 * Use case for toggling layer group expansion state
 * Implements Phase 26: Layer Groups
 */
class ToggleGroupExpansion @Inject constructor(
    private val layerGroupManager: LayerGroupManager
) {
    /**
     * Execute the toggle group expansion use case
     * @param groupId The group ID to toggle
     * @return Result indicating success or failure
     */
    operator fun invoke(groupId: Long): Result {
        val success = layerGroupManager.toggleGroupExpansion(groupId)
        
        return if (success) {
            Result.Success
        } else {
            Result.Failure("Failed to toggle group expansion")
        }
    }
    
    sealed class Result {
        object Success : Result()
        data class Failure(val error: String) : Result()
    }
}
