package com.artflow.studio.core.render

import com.artflow.studio.domain.model.layer.BlendMode
import com.artflow.studio.domain.model.layer.Layer

/**
 * Applies group headers to their member layers. A plain group (Normal, fully opaque) passes its
 * members straight through, hidden when the group is hidden. A group with its own blend mode or
 * opacity is isolated: its members are merged first and the result is blended as one layer, the
 * way Procreate and Photoshop treat group blend modes.
 */
object LayerGroups {
    /** One step of the stack: a layer, or an isolated group whose [members] are merged first. */
    data class Entry(
        val layer: Layer,
        val input: Compositor.LayerInput? = null,
        val members: List<Compositor.LayerInput> = emptyList(),
    )

    fun isolates(group: Layer): Boolean = group.isGroup && (group.blendMode != BlendMode.NORMAL || group.opacity < 1f)

    /** Stack entries, bottom first. Isolated groups sit at their header's position. */
    fun plan(inputs: List<Compositor.LayerInput>): List<Entry> {
        val isolated = inputs.map { it.layer }.filter(::isolates).associateBy { it.id }
        val loose = resolve(inputs.filter { it.layer.parentGroupId !in isolated })
        val groups =
            isolated.values.map { header ->
                Entry(header, members = inputs.filter { it.layer.parentGroupId == header.id }.sortedBy { it.layer.index })
            }
        return (loose.map { Entry(it.layer, it) } + groups).sortedBy { it.layer.index }
    }

    /** Pass-through groups: a hidden group hides its members. Group headers are dropped. */
    fun resolve(inputs: List<Compositor.LayerInput>): List<Compositor.LayerInput> {
        if (inputs.none { it.layer.isGroup }) return inputs
        val groups = inputs.filter { it.layer.isGroup }.associate { it.layer.id to it.layer }
        return inputs.mapNotNull { input ->
            val layer = input.layer
            if (layer.isGroup) return@mapNotNull null
            val group = layer.parentGroupId?.let(groups::get) ?: return@mapNotNull input
            input.copy(
                layer =
                    layer.copy(
                        isVisible = layer.isVisible && group.isVisible,
                        opacity = layer.opacity * group.opacity,
                    ),
            )
        }
    }
}
