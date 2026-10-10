package com.artflow.studio.core.render

import com.artflow.studio.domain.model.layer.BlendMode
import com.artflow.studio.domain.model.layer.Layer

/**
 * Applies group headers to their member layers, at any nesting depth. A plain group (Normal, fully
 * opaque) passes its members straight through, hidden when the group is hidden. A group with its
 * own blend mode or opacity is isolated: its members are merged first and the result is blended as
 * one layer, the way Procreate and Photoshop treat group blend modes.
 */
object LayerGroups {
    /** One step of the stack: a layer, or an isolated group whose [members] are merged first. */
    data class Entry(
        val layer: Layer,
        val input: Compositor.LayerInput? = null,
        val members: List<Entry> = emptyList(),
    )

    /**
     * Whether [group] merges its members before blending. Pass Through is the Photoshop folder
     * default and, like Normal, leaves members blending with the layers below, so it does not
     * isolate on its own.
     */
    fun isolates(group: Layer): Boolean =
        group.isGroup &&
            (group.blendMode != BlendMode.NORMAL && group.blendMode != BlendMode.PASS_THROUGH || group.opacity < 1f)

    /**
     * The blend mode a PSD folder header records for [group]: its own mode when it is isolated,
     * otherwise Pass Through, so Photoshop keeps the members blending with the layers below.
     */
    fun folderBlendMode(group: Layer): BlendMode = if (isolates(group)) group.blendMode else BlendMode.PASS_THROUGH

    /** Stack entries, bottom first. Isolated groups sit at their header's position with their own plan. */
    fun plan(inputs: List<Compositor.LayerInput>): List<Entry> {
        val groups = inputs.map { it.layer }.filter { it.isGroup }.associateBy { it.id }
        return planScope(inputs, groups, scope = null)
    }

    private fun planScope(
        inputs: List<Compositor.LayerInput>,
        groups: Map<Long, Layer>,
        scope: Long?,
    ): List<Entry> =
        inputs
            .mapNotNull { input ->
                val layer = input.layer
                if (layer.isGroup && !isolates(layer)) return@mapNotNull null
                val shown = throughGroups(layer, groups, scope) ?: return@mapNotNull null
                if (layer.isGroup) Entry(shown, members = planScope(inputs, groups, layer.id)) else Entry(shown, input.copy(layer = shown))
            }.sortedBy { it.layer.index }

    /** Pass-through groups: a hidden group hides its members, at any depth. Group headers are dropped. */
    fun resolve(inputs: List<Compositor.LayerInput>): List<Compositor.LayerInput> {
        if (inputs.none { it.layer.isGroup }) return inputs
        val groups = inputs.filter { it.layer.isGroup }.associate { it.layer.id to it.layer }
        return inputs.mapNotNull { input ->
            if (input.layer.isGroup) return@mapNotNull null
            val chain = ancestors(input.layer, groups)
            input.copy(
                layer =
                    input.layer.copy(
                        isVisible = input.layer.isVisible && chain.all { it.isVisible },
                        opacity = chain.fold(input.layer.opacity) { opacity, group -> opacity * group.opacity },
                    ),
            )
        }
    }

    /** The groups holding [layer], innermost first. */
    fun ancestors(
        layer: Layer,
        groups: Map<Long, Layer>,
    ): List<Layer> {
        val chain = mutableListOf<Layer>()
        var parent = layer.parentGroupId?.let(groups::get)
        // The size bound stops a corrupt cycle of groups from looping forever.
        while (parent != null && chain.size < groups.size) {
            chain += parent
            parent = parent.parentGroupId?.let(groups::get)
        }
        return chain
    }

    /**
     * [layer] as seen inside [scope] (an isolated group, or the root for null): the pass-through
     * groups between them apply their visibility. Null when [layer] belongs to another scope.
     */
    private fun throughGroups(
        layer: Layer,
        groups: Map<Long, Layer>,
        scope: Long?,
    ): Layer? {
        val chain = ancestors(layer, groups)
        val end = chain.indexOfFirst { it.id == scope || isolates(it) }
        if (chain.getOrNull(end)?.id != scope) return null
        val passing = if (end == -1) chain else chain.subList(0, end)
        return if (passing.all { it.isVisible }) layer else layer.copy(isVisible = false)
    }
}
