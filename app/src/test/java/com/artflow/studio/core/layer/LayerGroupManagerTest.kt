package com.artflow.studio.core.layer

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for LayerGroupManager
 * Implements Phase 26: Layer Groups testing
 */
class LayerGroupManagerTest {

    private lateinit var layerGroupManager: LayerGroupManager

    @Before
    fun setup() {
        layerGroupManager = LayerGroupManager()
    }

    @Test
    fun `createGroup creates a new group with default name`() {
        val group = layerGroupManager.createGroup()

        assertNotNull(group)
        assertEquals("Group 1", group?.name)
        assertEquals(0, group?.index)
        assertTrue(group?.isVisible == true)
        assertTrue(group?.isExpanded == true)
    }

    @Test
    fun `createGroup creates a new group with custom name`() {
        val group = layerGroupManager.createGroup(name = "My Custom Group")

        assertNotNull(group)
        assertEquals("My Custom Group", group?.name)
    }

    @Test
    fun `createGroup assigns correct index`() {
        val group1 = layerGroupManager.createGroup("Group 1")
        val group2 = layerGroupManager.createGroup("Group 2")

        assertEquals(0, group1?.index)
        assertEquals(1, group2?.index)
    }

    @Test
    fun `removeGroup removes the group successfully`() {
        val group = layerGroupManager.createGroup("To Remove")
        val groupId = group?.id ?: throw AssertionError("Group creation failed")

        val result = layerGroupManager.removeGroup(groupId)

        assertTrue(result)
        assertNull(layerGroupManager.getGroup(groupId))
    }

    @Test
    fun `addLayerToGroup adds layer to group`() {
        val group = layerGroupManager.createGroup("Test Group") ?: throw AssertionError("Group creation failed")
        val layerId = 100L

        val result = layerGroupManager.addLayerToGroup(group.id, layerId)

        assertTrue(result)
        val updatedGroup = layerGroupManager.getGroup(group.id)
        assertTrue(layerId in updatedGroup?.layerIds ?: emptyList())
    }

    @Test
    fun `removeLayerFromGroup removes layer from group`() {
        val group = layerGroupManager.createGroup("Test Group") ?: throw AssertionError("Group creation failed")
        val layerId = 100L

        layerGroupManager.addLayerToGroup(group.id, layerId)
        val result = layerGroupManager.removeLayerFromGroup(group.id, layerId)

        assertTrue(result)
        val updatedGroup = layerGroupManager.getGroup(group.id)
        assertFalse(layerId in updatedGroup?.layerIds ?: emptyList())
    }

    @Test
    fun `toggleGroupExpansion toggles expansion state`() {
        val group = layerGroupManager.createGroup("Test Group") ?: throw AssertionError("Group creation failed")

        assertTrue(group.isExpanded)

        layerGroupManager.toggleGroupExpansion(group.id)
        val updatedGroup = layerGroupManager.getGroup(group.id)
        assertFalse(updatedGroup?.isExpanded == true)

        layerGroupManager.toggleGroupExpansion(group.id)
        val twiceUpdatedGroup = layerGroupManager.getGroup(group.id)
        assertTrue(twiceUpdatedGroup?.isExpanded == true)
    }

    @Test
    fun `setGroupVisibility sets visibility correctly`() {
        val group = layerGroupManager.createGroup("Test Group") ?: throw AssertionError("Group creation failed")

        assertTrue(group.isVisible)

        layerGroupManager.setGroupVisibility(group.id, false)
        val updatedGroup = layerGroupManager.getGroup(group.id)
        assertFalse(updatedGroup?.isVisible == true)
    }

    @Test
    fun `renameGroup changes group name`() {
        val group = layerGroupManager.createGroup("Original Name") ?: throw AssertionError("Group creation failed")

        val result = layerGroupManager.renameGroup(group.id, "New Name")

        assertTrue(result)
        val updatedGroup = layerGroupManager.getGroup(group.id)
        assertEquals("New Name", updatedGroup?.name)
    }

    @Test
    fun `getRootGroups returns only root level groups`() {
        val rootGroup1 = layerGroupManager.createGroup("Root 1") ?: throw AssertionError("Group creation failed")
        val rootGroup2 = layerGroupManager.createGroup("Root 2") ?: throw AssertionError("Group creation failed")
        val childGroup = layerGroupManager.createGroup("Child", parentGroupId = rootGroup1.id) 
            ?: throw AssertionError("Child group creation failed")

        val rootGroups = layerGroupManager.getRootGroups()

        assertEquals(2, rootGroups.size)
        assertTrue(rootGroups.any { it.id == rootGroup1.id })
        assertTrue(rootGroups.any { it.id == rootGroup2.id })
        assertFalse(rootGroups.any { it.id == childGroup.id })
    }

    @Test
    fun `getAllSubGroups returns all nested subgroups`() {
        val rootGroup = layerGroupManager.createGroup("Root") ?: throw AssertionError("Group creation failed")
        val childGroup = layerGroupManager.createGroup("Child", parentGroupId = rootGroup.id) 
            ?: throw AssertionError("Child group creation failed")
        val grandchildGroup = layerGroupManager.createGroup("Grandchild", parentGroupId = childGroup.id) 
            ?: throw AssertionError("Grandchild group creation failed")

        val subGroups = layerGroupManager.getAllSubGroups(rootGroup.id)

        assertEquals(2, subGroups.size)
        assertTrue(subGroups.any { it.id == childGroup.id })
        assertTrue(subGroups.any { it.id == grandchildGroup.id })
    }

    @Test
    fun `getAllLayersInGroup returns all layers including nested`() {
        val rootGroup = layerGroupManager.createGroup("Root") ?: throw AssertionError("Group creation failed")
        val childGroup = layerGroupManager.createGroup("Child", parentGroupId = rootGroup.id) 
            ?: throw AssertionError("Child group creation failed")

        layerGroupManager.addLayerToGroup(rootGroup.id, 1L)
        layerGroupManager.addLayerToGroup(rootGroup.id, 2L)
        layerGroupManager.addLayerToGroup(childGroup.id, 3L)

        val allLayers = layerGroupManager.getAllLayersInGroup(rootGroup.id)

        assertEquals(3, allLayers.size)
        assertTrue(allLayers.containsAll(listOf(1L, 2L, 3L)))
    }

    @Test
    fun `isGroupEmpty returns true for empty group`() {
        val group = layerGroupManager.createGroup("Empty Group") ?: throw AssertionError("Group creation failed")

        assertTrue(layerGroupManager.isGroupEmpty(group.id))
    }

    @Test
    fun `isGroupEmpty returns false for group with layers`() {
        val group = layerGroupManager.createGroup("Non-Empty Group") ?: throw AssertionError("Group creation failed")
        layerGroupManager.addLayerToGroup(group.id, 1L)

        assertFalse(layerGroupManager.isGroupEmpty(group.id))
    }

    @Test
    fun `findGroupContainingLayer finds correct group`() {
        val group1 = layerGroupManager.createGroup("Group 1") ?: throw AssertionError("Group creation failed")
        val group2 = layerGroupManager.createGroup("Group 2") ?: throw AssertionError("Group creation failed")

        layerGroupManager.addLayerToGroup(group1.id, 100L)
        layerGroupManager.addLayerToGroup(group2.id, 200L)

        val foundGroup1 = layerGroupManager.findGroupContainingLayer(100L)
        val foundGroup2 = layerGroupManager.findGroupContainingLayer(200L)

        assertEquals(group1.id, foundGroup1?.id)
        assertEquals(group2.id, foundGroup2?.id)
    }

    @Test
    fun `getGroupDepth returns correct depth`() {
        val rootGroup = layerGroupManager.createGroup("Root") ?: throw AssertionError("Group creation failed")
        val childGroup = layerGroupManager.createGroup("Child", parentGroupId = rootGroup.id) 
            ?: throw AssertionError("Child group creation failed")
        val grandchildGroup = layerGroupManager.createGroup("Grandchild", parentGroupId = childGroup.id) 
            ?: throw AssertionError("Grandchild group creation failed")

        assertEquals(0, layerGroupManager.getGroupDepth(rootGroup.id))
        assertEquals(1, layerGroupManager.getGroupDepth(childGroup.id))
        assertEquals(2, layerGroupManager.getGroupDepth(grandchildGroup.id))
    }

    @Test
    fun `duplicateGroup creates copy with all contents`() {
        val originalGroup = layerGroupManager.createGroup("Original") ?: throw AssertionError("Group creation failed")
        layerGroupManager.addLayerToGroup(originalGroup.id, 1L)
        layerGroupManager.addLayerToGroup(originalGroup.id, 2L)

        val layerIdMapper: (Long) -> Long = { it + 1000 }
        val duplicatedGroup = layerGroupManager.duplicateGroup(originalGroup.id, layerIdMapper)

        assertNotNull(duplicatedGroup)
        assertEquals("${originalGroup.name} Copy", duplicatedGroup?.name)
        assertNotEquals(originalGroup.id, duplicatedGroup?.id)
        assertTrue(duplicatedGroup?.layerIds?.containsAll(listOf(1001L, 1002L)) == true)
    }

    @Test
    fun `clearAllGroups removes all groups`() {
        layerGroupManager.createGroup("Group 1")
        layerGroupManager.createGroup("Group 2")
        layerGroupManager.createGroup("Group 3")

        layerGroupManager.clearAllGroups()

        assertTrue(layerGroupManager.groups.value.isEmpty())
    }
}
