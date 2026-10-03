package com.artflow.studio.presentation.ui.viewmodel

import com.artflow.studio.domain.repository.canvas.CanvasRepository

/** Several layers chosen together in the Layers panel: group them or delete them in one step. */
class LayerBatchController(
    private val repository: CanvasRepository,
    private val perform: (suspend () -> Unit) -> Unit,
) {
    fun group(layerIds: List<Long>) = perform { checkNotNull(repository.groupLayers(layerIds)) { "These layers cannot be grouped" } }

    fun delete(layerIds: List<Long>) = perform { check(repository.removeLayers(layerIds) > 0) { "The last layer cannot be deleted" } }
}
