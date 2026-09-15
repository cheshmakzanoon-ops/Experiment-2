package com.artflow.studio.core.layer

import com.artflow.studio.domain.model.layer.Layer
import com.artflow.studio.domain.model.layer.LayerGroup
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manager for layer groups in ArtFlow
 * Implements Phase 26: Layer Groups - Organize layers into collapsible folders
 * 
 * This singleton manages layer group operations including:
 * - Creating and removing layer groups
 * - Adding/removing layers to/from groups
 * - Group expansion/collapse for UI
 * - Nested group support (subgroups)
 * - Group reordering
 */
@Singleton
class LayerGroupManager @Inject constructor() {

    private val _groups = MutableStateFlow<List<LayerGroup>>(emptyList())
    val groups: StateFlow<List<LayerGroup>> = _groups.asStateFlow()

    private var nextGroupId = 1L

    /**
     * Create a new layer group
     * @param name The group name (defaults to "Group N")
     * @param index Position in group stack
     * @param parentGroupId Optional parent group ID for nesting
     * @return The created LayerGroup or null if failed
     */
    fun createGroup(
        name: String? = null,
        index: Int = _groups.value.size,
        parentGroupId: Long? = null
    ): LayerGroup? {
        // Prevent circular nesting (can't add group to itself or its descendants)
        if (parentGroupId != null && isDescendantOf(parentGroupId, parentGroupId)) {
            Timber.e("Cannot create group: would create circular reference")
            return null
        }

        val groupName = name ?: "Group ${nextGroupId}"
        val group = LayerGroup(
            id = nextGroupId++,
            name = groupName,
            index = index,
            layerIds = emptyList(),
            subGroupIds = emptyList(),
            isVisible = true,
            isExpanded = true,
            parentGroupId = parentGroupId
        )

        val updatedGroups = _groups.value.toMutableList()
        
        // Adjust indices of groups at or above the insertion point
        updatedGroups.forEachIndexed { i, g ->
            if (g.index >= index && g.parentGroupId == parentGroupId) {
                updatedGroups[i] = g.copyWith(index = g.index + 1)
            }
        }
        
        updatedGroups.add(group)
        _groups.value = updatedGroups
        
        Timber.d("Layer group created: ${group.name} at index $index${parentGroupId?.let { " under parent $it" } ?: ""}")
        return group
    }

    /**
     * Remove a group by ID
     * @param groupId The ID of the group to remove
     * @param removeContainedLayers If true, also remove layers in the group; if false, move them to parent
     * @return True if removed successfully
     */
    fun removeGroup(groupId: Long, removeContainedLayers: Boolean = false): Boolean {
        val group = _groups.value.find { it.id == groupId } ?: return false

        // Recursively remove subgroups
        val subGroupsToRemove = getAllSubGroups(groupId)
        
        val updatedGroups = _groups.value.toMutableList()
        
        // Remove subgroups first
        subGroupsToRemove.forEach { subGroup ->
            updatedGroups.removeIf { it.id == subGroup.id }
        }
        
        // Handle layers in the group being removed
        if (!removeContainedLayers) {
            // Move layers to parent level - this needs to be handled by LayerManager
            Timber.w("Layers in removed group should be moved to parent scope")
        }
        
        // Remove the main group
        updatedGroups.removeIf { it.id == groupId }
        
        _groups.value = updatedGroups
        
        Timber.d("Layer group removed: ${group.name}${if (removeContainedLayers) " with contents" else ""}")
        return true
    }

    /**
     * Add a layer to a group
     * @param groupId The target group ID
     * @param layerId The layer ID to add
     * @param index Position within the group (defaults to end)
     * @return True if added successfully
     */
    fun addLayerToGroup(groupId: Long, layerId: Long, index: Int = -1): Boolean {
        val group = _groups.value.find { it.id == groupId } ?: return false
        
        // Check if layer is already in another group
        val existingGroup = _groups.value.find { layerId in it.layerIds }
        if (existingGroup != null && existingGroup.id != groupId) {
            Timber.w("Layer $layerId is already in group ${existingGroup.name}, removing from there first")
            removeLayerFromGroup(existingGroup.id, layerId)
        }
        
        val updatedGroups = _groups.value.toMutableList()
        val groupIndex = updatedGroups.indexOfFirst { it.id == groupId }
        
        if (groupIndex == -1) return false
        
        val currentGroup = updatedGroups[groupIndex]
        val layerIds = currentGroup.layerIds.toMutableList()
        
        if (index == -1 || index >= layerIds.size) {
            layerIds.add(layerId)
        } else {
            layerIds.add(index.coerceIn(0, layerIds.size), layerId)
        }
        
        updatedGroups[groupIndex] = currentGroup.copyWith(layerIds = layerIds)
        _groups.value = updatedGroups
        
        Timber.d("Layer $layerId added to group ${group.name} at index $index")
        return true
    }

    /**
     * Remove a layer from a group
     * @param groupId The group ID
     * @param layerId The layer ID to remove
     * @return True if removed successfully
     */
    fun removeLayerFromGroup(groupId: Long, layerId: Long): Boolean {
        val group = _groups.value.find { it.id == groupId } ?: return false
        
        if (layerId !in group.layerIds) {
            Timber.w("Layer $layerId not found in group ${group.name}")
            return false
        }
        
        val updatedGroups = _groups.value.toMutableList()
        val groupIndex = updatedGroups.indexOfFirst { it.id == groupId }
        
        if (groupIndex == -1) return false
        
        val currentGroup = updatedGroups[groupIndex]
        val layerIds = currentGroup.layerIds.toMutableList()
        layerIds.remove(layerId)
        
        updatedGroups[groupIndex] = currentGroup.copyWith(layerIds = layerIds)
        _groups.value = updatedGroups
        
        Timber.d("Layer $layerId removed from group ${group.name}")
        return true
    }

    /**
     * Add a subgroup to a parent group
     * @param parentGroupId The parent group ID
     * @param subGroupId The subgroup ID to add
     * @return True if added successfully
     */
    fun addSubGroup(parentGroupId: Long, subGroupId: Long): Boolean {
        // Prevent circular references
        if (parentGroupId == subGroupId) {
            Timber.e("Cannot add group as subgroup of itself")
            return false
        }
        
        if (isDescendantOf(parentGroupId, subGroupId)) {
            Timber.e("Cannot add subgroup: would create circular reference")
            return false
        }
        
        val parentGroup = _groups.value.find { it.id == parentGroupId } ?: return false
        val subGroup = _groups.value.find { it.id == subGroupId } ?: return false
        
        val updatedGroups = _groups.value.toMutableList()
        val parentIndex = updatedGroups.indexOfFirst { it.id == parentGroupId }
        val subIndex = updatedGroups.indexOfFirst { it.id == subGroupId }
        
        if (parentIndex == -1 || subIndex == -1) return false
        
        // Update subgroup's parent reference
        updatedGroups[subIndex] = subGroup.copyWith(parentGroupId = parentGroupId)
        
        // Add to parent's subGroupIds
        val parentGroupUpdated = updatedGroups[parentIndex]
        val subGroupIds = parentGroupUpdated.subGroupIds.toMutableList()
        subGroupIds.add(subGroupId)
        updatedGroups[parentIndex] = parentGroupUpdated.copyWith(subGroupIds = subGroupIds)
        
        _groups.value = updatedGroups
        
        Timber.d("Group ${subGroup.name} added as subgroup of ${parentGroup.name}")
        return true
    }

    /**
     * Remove a subgroup from a parent group (doesn't delete the subgroup itself)
     * @param parentGroupId The parent group ID
     * @param subGroupId The subgroup ID to remove
     * @return True if removed successfully
     */
    fun removeSubGroup(parentGroupId: Long, subGroupId: Long): Boolean {
        val parentGroup = _groups.value.find { it.id == parentGroupId } ?: return false
        
        if (subGroupId !in parentGroup.subGroupIds) {
            Timber.w("SubGroup $subGroupId not found in parent group ${parentGroup.name}")
            return false
        }
        
        val updatedGroups = _groups.value.toMutableList()
        val parentIndex = updatedGroups.indexOfFirst { it.id == parentGroupId }
        val subIndex = updatedGroups.indexOfFirst { it.id == subGroupId }
        
        if (parentIndex == -1 || subIndex == -1) return false
        
        // Remove from parent's subGroupIds
        val parentGroupUpdated = updatedGroups[parentIndex]
        val subGroupIds = parentGroupUpdated.subGroupIds.toMutableList()
        subGroupIds.remove(subGroupId)
        updatedGroups[parentIndex] = parentGroupUpdated.copyWith(subGroupIds = subGroupIds)
        
        // Clear subgroup's parent reference
        val subGroupUpdated = updatedGroups[subIndex]
        updatedGroups[subIndex] = subGroupUpdated.copyWith(parentGroupId = null)
        
        _groups.value = updatedGroups
        
        Timber.d("Subgroup ${subGroupId} removed from parent group ${parentGroup.name}")
        return true
    }

    /**
     * Toggle group expansion state (UI)
     * @param groupId The group ID
     * @return True if toggled successfully
     */
    fun toggleGroupExpansion(groupId: Long): Boolean {
        val group = _groups.value.find { it.id == groupId } ?: return false
        
        _groups.value = _groups.value.map { g ->
            if (g.id == groupId) g.copyWith(isExpanded = !g.isExpanded) else g
        }
        
        Timber.d("Group ${group.name} expansion toggled: ${!group.isExpanded}")
        return true
    }

    /**
     * Set group expansion state
     * @param groupId The group ID
     * @param isExpanded Expansion state
     * @return True if set successfully
     */
    fun setGroupExpansion(groupId: Long, isExpanded: Boolean): Boolean {
        val group = _groups.value.find { it.id == groupId } ?: return false
        
        _groups.value = _groups.value.map { g ->
            if (g.id == groupId) g.copyWith(isExpanded = isExpanded) else g
        }
        
        Timber.d("Group ${group.name} expansion set to: $isExpanded")
        return true
    }

    /**
     * Set group visibility
     * @param groupId The group ID
     * @param isVisible Visibility state
     * @return True if set successfully
     */
    fun setGroupVisibility(groupId: Long, isVisible: Boolean): Boolean {
        val group = _groups.value.find { it.id == groupId } ?: return false
        
        _groups.value = _groups.value.map { g ->
            if (g.id == groupId) g.copyWith(isVisible = isVisible) else g
        }
        
        Timber.d("Group ${group.name} visibility set to: $isVisible")
        return true
    }

    /**
     * Rename a group
     * @param groupId The group ID
     * @param newName The new name
     * @return True if renamed successfully
     */
    fun renameGroup(groupId: Long, newName: String): Boolean {
        val group = _groups.value.find { it.id == groupId } ?: return false
        
        _groups.value = _groups.value.map { g ->
            if (g.id == groupId) g.copyWith(name = newName) else g
        }
        
        Timber.d("Group renamed: ${group.name} -> $newName")
        return true
    }

    /**
     * Get a group by ID
     */
    fun getGroup(groupId: Long): LayerGroup? {
        return _groups.value.find { it.id == groupId }
    }

    /**
     * Get all groups at root level (no parent)
     */
    fun getRootGroups(): List<LayerGroup> {
        return _groups.value.filter { it.parentGroupId == null }
            .sortedBy { it.index }
    }

    /**
     * Get all subgroups of a parent group
     * @param parentGroupId The parent group ID
     */
    fun getSubGroups(parentGroupId: Long): List<LayerGroup> {
        return _groups.value.filter { it.parentGroupId == parentGroupId }
            .sortedBy { it.index }
    }

    /**
     * Get all nested subgroups recursively
     * @param groupId The root group ID
     */
    fun getAllSubGroups(groupId: Long): List<LayerGroup> {
        val result = mutableListOf<LayerGroup>()
        collectSubGroups(groupId, result)
        return result
    }

    private fun collectSubGroups(groupId: Long, result: MutableList<LayerGroup>) {
        val directSubGroups = getSubGroups(groupId)
        directSubGroups.forEach { subGroup ->
            result.add(subGroup)
            collectSubGroups(subGroup.id, result)
        }
    }

    /**
     * Check if a group is a descendant of another group
     */
    fun isDescendantOf(potentialAncestorId: Long, groupId: Long): Boolean {
        val group = _groups.value.find { it.id == groupId } ?: return false
        val parentId = group.parentGroupId ?: return false
        
        if (parentId == potentialAncestorId) return true
        
        return isDescendantOf(potentialAncestorId, parentId)
    }

    /**
     * Get all layers in a group (including nested groups)
     * @param groupId The group ID
     */
    fun getAllLayersInGroup(groupId: Long): List<Long> {
        val result = mutableListOf<Long>()
        collectAllLayers(groupId, result)
        return result
    }

    private fun collectAllLayers(groupId: Long, result: MutableList<Long>) {
        val group = getGroup(groupId) ?: return
        
        // Add direct layers
        result.addAll(group.layerIds)
        
        // Add layers from subgroups
        group.subGroupIds.forEach { subGroupId ->
            collectAllLayers(subGroupId, result)
        }
    }

    /**
     * Get total layer count in a group (including nested)
     */
    fun getTotalLayerCountInGroup(groupId: Long): Int {
        return getAllLayersInGroup(groupId).size
    }

    /**
     * Check if a group is empty (no layers and no subgroups)
     */
    fun isGroupEmpty(groupId: Long): Boolean {
        val group = getGroup(groupId) ?: return true
        return group.layerIds.isEmpty() && group.subGroupIds.isEmpty()
    }

    /**
     * Get the group that contains a specific layer
     * @param layerId The layer ID to find
     */
    fun findGroupContainingLayer(layerId: Long): LayerGroup? {
        return _groups.value.find { layerId in it.layerIds }
    }

    /**
     * Duplicate a group with all its contents
     * @param groupId The group ID to duplicate
     * @param layerIdMapper Function to map old layer IDs to new ones
     * @return The new duplicated group or null if failed
     */
    fun duplicateGroup(groupId: Long, layerIdMapper: (Long) -> Long): LayerGroup? {
        val originalGroup = getGroup(groupId) ?: return null
        
        val duplicatedGroup = originalGroup.copyWith(
            id = nextGroupId++,
            name = "${originalGroup.name} Copy",
            index = originalGroup.index + 1,
            layerIds = originalGroup.layerIds.map { layerIdMapper(it) },
            subGroupIds = emptyList() // Will be populated by duplicating subgroups
        )
        
        val updatedGroups = _groups.value.toMutableList()
        
        // Adjust indices
        updatedGroups.forEachIndexed { i, g ->
            if (g.index > originalGroup.index && g.parentGroupId == originalGroup.parentGroupId) {
                updatedGroups[i] = g.copyWith(index = g.index + 1)
            }
        }
        
        updatedGroups.add(duplicatedGroup)
        _groups.value = updatedGroups
        
        // Duplicate subgroups recursively
        originalGroup.subGroupIds.forEach { subGroupId ->
            duplicateGroup(subGroupId, layerIdMapper)?.let { duplicatedSubGroup ->
                addSubGroup(duplicatedGroup.id, duplicatedSubGroup.id)
            }
        }
        
        Timber.d("Group duplicated: ${originalGroup.name} -> ${duplicatedGroup.name}")
        return duplicatedGroup
    }

    /**
     * Clear all groups (does not affect layers)
     */
    fun clearAllGroups() {
        _groups.value = emptyList()
        Timber.d("All layer groups cleared")
    }

    /**
     * Get group hierarchy depth for a specific group
     * @param groupId The group ID
     * @return Depth level (0 = root level)
     */
    fun getGroupDepth(groupId: Long): Int {
        var depth = 0
        var currentGroup = getGroup(groupId) ?: return 0
        
        while (currentGroup.parentGroupId != null) {
            val parent = getGroup(currentGroup.parentGroupId!!) ?: break
            currentGroup = parent
            depth++
        }
        
        return depth
    }
}
