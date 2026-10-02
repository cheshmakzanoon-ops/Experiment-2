package com.artflow.studio.presentation.ui.viewmodel

import com.artflow.studio.core.pixels.SelectionMask
import com.artflow.studio.domain.repository.canvas.CanvasRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Procreate's selection Save & Load: selections kept while the artwork is open, restorable at any time. */
class SavedSelections(
    private val repository: CanvasRepository,
    private val onRestored: () -> Unit,
) {
    private val _saved = MutableStateFlow<List<SelectionMask>>(emptyList())
    val saved: StateFlow<List<SelectionMask>> = _saved.asStateFlow()

    /** Keeps a copy of the current selection; false when nothing is selected. */
    fun save(): Boolean {
        val mask = repository.selection()?.takeIf { it.isActive() } ?: return false
        _saved.value = (_saved.value + mask.copy()).takeLast(MAX_SAVED)
        return true
    }

    fun load(index: Int) {
        val mask = _saved.value.getOrNull(index) ?: return
        val size = repository.getCanvasSize()
        if (mask.width != size.width || mask.height != size.height) return
        repository.setSelection(mask.copy())
        onRestored()
    }

    fun delete(index: Int) {
        _saved.value = _saved.value.filterIndexed { i, _ -> i != index }
    }

    fun clear() {
        _saved.value = emptyList()
    }

    private companion object {
        const val MAX_SAVED = 8
    }
}
