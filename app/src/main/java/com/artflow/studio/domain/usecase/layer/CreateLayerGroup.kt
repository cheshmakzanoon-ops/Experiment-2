package com.artflow.studio.domain.usecase.layer

import com.artflow.studio.core.layer.LayerGroupManager
import javax.inject.Inject

/**
 * Use case for creating a new layer group
 * Implements Phase 26: Layer Groups
 */
class CreateLayerGroup @Inject constructor(
    private val layerGroupManager: LayerGroupManager
) {
    /**
     * Execute the create layer group use case
     * @param name Optional group name (defaults to "Group N")
     * @param index Position in group stack (defaults to end)
     * @param parentGroupId Optional parent group ID for nesting
     * @return The created LayerGroup or null if failed
     */
    operator fun invoke(
        name: String? = null,
        index: Int = -1,
        parentGroupId: Long? = null
    ): Result {
        val actualIndex = if (index == -1) {
            layerGroupManager.getRootGroups().size
        } else {
            index
        }
        
        val group = layerGroupManager.createGroup(name, actualIndex, parentGroupId)
        
        return if (group != null) {
            Result.Success(group)
        } else {
            Result.Failure("Failed to create layer group")
        }
    }
    
    sealed class Result {
        data class Success(val group: com.artflow.studio.domain.model.layer.LayerGroup) : Result()
        data class Failure(val error: String) : Result()
    }
}
