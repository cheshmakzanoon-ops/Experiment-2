package com.artflow.studio.core.canvas

import com.artflow.studio.core.render.LayerGroups
import com.artflow.studio.domain.model.layer.Layer

/** Resolves persistent links within the current frame, without silently dropping blocked members. */
object LayerLinks {
    fun expand(
        layers: List<Layer>,
        chosen: Set<Long>,
    ): List<Layer>? {
        if (chosen.isEmpty() || !layers.map { it.id }.containsAll(chosen)) return null
        val links = layers.filter { it.id in chosen }.mapNotNull { it.linkGroupId }.toSet()
        return layers.filter { it.id in chosen || it.linkGroupId in links }
    }

    /** Hidden/locked parent groups protect their children, just like the child's own controls. */
    fun editable(
        layers: List<Layer>,
        members: List<Layer>,
    ): Boolean {
        val groups = layers.filter { it.isGroup }.associateBy { it.id }
        return members.isNotEmpty() && members.all { layer ->
            layer.acceptsPaint() && !layer.isInternal &&
                LayerGroups.ancestors(layer, groups).all { it.isVisible && !it.isLocked && !it.isReference }
        }
    }

    fun canLink(
        layers: List<Layer>,
        chosen: Set<Long>,
    ): Boolean {
        if (chosen.size < 2) return false
        val members = expand(layers, chosen) ?: return false
        return editable(layers, members)
    }
}
