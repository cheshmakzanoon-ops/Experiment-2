package com.artflow.studio.core.render

import com.artflow.studio.domain.model.layer.Layer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LayerGroupsTest {
    @Test fun groupHidesAndFadesMembers() {
        val group = Layer(id = 10, name = "Group", index = 2, opacity = 0.5f, isGroup = true)
        val member = Layer(id = 1, name = "A", index = 0, opacity = 0.8f, parentGroupId = 10)
        val loose = Layer(id = 2, name = "B", index = 1)
        val resolved = LayerGroups.resolve(listOf(member, loose, group).map { Compositor.LayerInput(it) })
        assertEquals(listOf(1L, 2L), resolved.map { it.layer.id })
        assertEquals(0.4f, resolved[0].layer.opacity, 1e-6f)
        assertEquals(1f, resolved[1].layer.opacity, 1e-6f)
        val hidden = LayerGroups.resolve(listOf(member, group.copy(isVisible = false)).map { Compositor.LayerInput(it) })
        assertFalse(hidden.single().layer.isVisible)
        assertFalse(group.canEdit())
    }
}
