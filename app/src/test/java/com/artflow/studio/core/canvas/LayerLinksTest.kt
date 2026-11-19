package com.artflow.studio.core.canvas

import com.artflow.studio.domain.model.layer.Layer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LayerLinksTest {
    private val first = Layer(1, "Ink", 0, linkGroupId = 10)
    private val peer = Layer(2, "Shade", 1, linkGroupId = 10)
    private val other = Layer(3, "Other", 2)

    @Test
    fun choosingOneMemberIncludesItsPeersWithoutIncludingUnrelatedLayers() {
        val layers = listOf(first, peer, other)
        assertEquals(listOf(first, peer), LayerLinks.expand(layers, setOf(first.id)))
        assertEquals(layers, LayerLinks.expand(layers, setOf(first.id, other.id)))
        assertNull(LayerLinks.expand(layers, setOf(first.id, 999)))
        assertNull(LayerLinks.expand(layers, emptySet()))
    }

    @Test
    fun aHiddenOrLockedPeerBlocksTheWholeLinkedOperation() {
        for (blocked in listOf(peer.copy(isLocked = true), peer.copy(isVisible = false), peer.copy(isReference = true))) {
            val layers = listOf(first, blocked, other)
            assertFalse(LayerLinks.editable(layers, requireNotNull(LayerLinks.expand(layers, setOf(first.id)))))
            assertFalse(LayerLinks.canLink(layers, setOf(first.id, other.id)))
        }
    }

    @Test
    fun groupHeadersAndProtectedAncestorsCannotBeBypassed() {
        val group = Layer(4, "Folder", 3, isGroup = true)
        val child = first.copy(parentGroupId = group.id)
        for (protected in listOf(group.copy(isLocked = true), group.copy(isVisible = false))) {
            val layers = listOf(child, peer, protected)
            assertFalse(LayerLinks.canLink(layers, setOf(child.id, peer.id)))
        }
        assertFalse(LayerLinks.canLink(listOf(first, group), setOf(first.id, group.id)))
        assertTrue(LayerLinks.canLink(listOf(child, peer, group), setOf(child.id, peer.id)))
        assertFalse(LayerLinks.canLink(listOf(first, peer), setOf(first.id)))
    }
}
