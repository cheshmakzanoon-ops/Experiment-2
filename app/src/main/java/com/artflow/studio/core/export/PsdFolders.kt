package com.artflow.studio.core.export

import com.artflow.studio.domain.model.layer.Layer

/**
 * Turns a layer stack with groups into PSD records, bottom first: each group becomes a folder,
 * written as a divider below its contents and a header above them, nested as the groups are.
 */
object PsdFolders {
    /**
     * [items] are the pixel layers in stack order (bottom first) and [layerOf] gives each one's
     * layer; [groups] are all the stack's groups. Without [includeHidden], layers inside a hidden
     * group are left out, as they are hidden in the artwork. Groups with nothing exported in them
     * are left out too.
     */
    fun <T> records(
        items: List<T>,
        layerOf: (T) -> Layer,
        groups: List<Layer>,
        includeHidden: Boolean,
        header: (Layer) -> PsdCodec.PsdLayer,
        record: (T) -> PsdCodec.PsdLayer,
    ): List<PsdCodec.PsdLayer> {
        val byId = groups.associateBy { it.id }
        val out = mutableListOf<PsdCodec.PsdLayer>()
        var open = emptyList<Layer>()
        for (item in items) {
            val chain = ancestors(layerOf(item), byId)
            if (!includeHidden && chain.any { !it.isVisible }) continue
            val shared = open.zip(chain).takeWhile { (a, b) -> a.id == b.id }.size
            open.drop(shared).asReversed().forEach { out += header(it) }
            repeat(chain.size - shared) { out += PsdCodec.PsdLayer.groupEnd() }
            open = chain
            out += record(item)
        }
        open.asReversed().forEach { out += header(it) }
        return out
    }

    /** The groups containing [layer], outermost first. */
    private fun ancestors(
        layer: Layer,
        groups: Map<Long, Layer>,
    ): List<Layer> {
        val chain = ArrayDeque<Layer>()
        var parent = layer.parentGroupId?.let(groups::get)
        while (parent != null && chain.none { it.id == parent!!.id }) {
            chain.addFirst(parent)
            parent = parent.parentGroupId?.let(groups::get)
        }
        return chain
    }
}
