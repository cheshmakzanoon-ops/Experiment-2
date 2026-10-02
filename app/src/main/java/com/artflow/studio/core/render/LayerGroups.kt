package com.artflow.studio.core.render

/**
 * Applies group headers to their member layers: a hidden group hides its members and the group
 * opacity multiplies theirs. Group headers carry no pixels and are dropped from the result.
 */
object LayerGroups {
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
