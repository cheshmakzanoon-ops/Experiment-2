package com.artflow.studio.presentation.ui.viewmodel

import com.artflow.studio.domain.repository.canvas.CanvasRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Layers chosen together in the Layers panel (swiped right, besides the active layer): group them,
 * delete them in one step, or transform them with the active layer.
 */
class LayerBatchController(
    private val repository: CanvasRepository,
    private val perform: (suspend () -> Unit) -> Unit,
) {
    private val _picked = MutableStateFlow<Set<Long>>(emptySet())
    val picked: StateFlow<Set<Long>> = _picked.asStateFlow()

    fun pick(layerIds: Set<Long>) {
        _picked.value = layerIds
    }

    fun group(layerIds: List<Long>) =
        perform {
            _picked.value = emptySet()
            checkNotNull(repository.groupLayers(layerIds)) { "These layers cannot be grouped" }
        }

    fun merge(layerIds: List<Long>) =
        perform {
            _picked.value = emptySet()
            check(repository.mergeLayerRange(layerIds)) { "Only neighbouring, visible and unlocked layers can be merged" }
        }

    fun delete(layerIds: List<Long>) =
        perform {
            _picked.value = emptySet()
            check(repository.removeLayers(layerIds) > 0) { "The last layer cannot be deleted" }
        }
}
