package com.artflow.studio.core.render

import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.domain.model.layer.BlendMode
import com.artflow.studio.domain.model.layer.Layer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LayerGroupsTest {
    @Test fun plainGroupPassesMembersThrough() {
        val group = Layer(id = 10, name = "Group", index = 2, isGroup = true)
        val member = Layer(id = 1, name = "A", index = 0, opacity = 0.8f, parentGroupId = 10)
        val loose = Layer(id = 2, name = "B", index = 1)
        val resolved = LayerGroups.resolve(listOf(member, loose, group).map { Compositor.LayerInput(it) })
        assertEquals(listOf(1L, 2L), resolved.map { it.layer.id })
        assertEquals(0.8f, resolved[0].layer.opacity, 1e-6f)
        val hidden = LayerGroups.resolve(listOf(member, group.copy(isVisible = false)).map { Compositor.LayerInput(it) })
        assertFalse(hidden.single().layer.isVisible)
        assertFalse(group.canEdit())
    }

    @Test fun fadedOrBlendedGroupIsMergedFirst() {
        val group = Layer(id = 10, name = "Group", index = 3, opacity = 0.5f, isGroup = true)
        val a = Layer(id = 1, name = "A", index = 1, parentGroupId = 10)
        val b = Layer(id = 2, name = "B", index = 2, parentGroupId = 10)
        val loose = Layer(id = 3, name = "C", index = 0)
        val plan = LayerGroups.plan(listOf(a, b, loose, group).map { Compositor.LayerInput(it) })
        assertEquals(listOf(3L, 10L), plan.map { it.layer.id })
        assertEquals(listOf(1L, 2L), plan[1].members.map { it.layer.id })
        assertTrue(LayerGroups.isolates(group.copy(opacity = 1f, blendMode = BlendMode.MULTIPLY)))
        assertFalse(LayerGroups.isolates(group.copy(opacity = 1f)))
    }

    @Test fun groupOpacityAppliesToTheMergedMembers() {
        val red = 0xFFFF0000.toInt()
        val group = Layer(id = 10, name = "Group", index = 2, opacity = 0.5f, isGroup = true)
        val inputs =
            listOf(
                Compositor.LayerInput(Layer(id = 1, name = "A", index = 0, parentGroupId = 10), PixelBuffer(1, 1).also { it.fill(red) }),
                Compositor.LayerInput(Layer(id = 2, name = "B", index = 1, parentGroupId = 10), PixelBuffer(1, 1).also { it.fill(red) }),
                Compositor.LayerInput(group),
            )
        val out = Compositor().composite(inputs, 1, 1)
        // Two overlapping opaque members fade together to half alpha, not to 75%.
        assertEquals(128f, (out.pixels[0] ushr 24).toFloat(), 1.5f)
    }
}
