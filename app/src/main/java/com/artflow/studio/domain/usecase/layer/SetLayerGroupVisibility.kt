package com.artflow.studio.domain.usecase.layer

import com.artflow.studio.core.layer.LayerGroupManager
import javax.inject.Inject

/**
 * Use case for setting layer group visibility
 * Implements Phase 26: Layer Groups
 */
class SetLayerGroupVisibility @Inject constructor(
    private val layerGroupManager: LayerGroupManager
) {
    /**
     * Execute the set layer group visibility use case
     * @param groupId The group ID
     * @param isVisible Visibility state
     * @return Result indicating success or failure
     */
    operator fun invoke(groupId: Long, isVisible: Boolean): Result {
        val success = layerGroupManager.setGroupVisibility(groupId, isVisible)
        
        return if (success) {
            Result.Success
        } else {
            Result.Failure("Failed to set group visibility")
        }
    }
    
    sealed class Result {
        object Success : Result()
        data class Failure(val error: String) : Result()
    }
}
