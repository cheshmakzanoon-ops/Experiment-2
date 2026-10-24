package com.artflow.studio.presentation.ui.viewmodel

import com.artflow.studio.core.pixels.SelectionMask
import com.artflow.studio.domain.repository.canvas.CanvasRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Procreate's selection Save & Load: selections restorable at any time.
 *
 * The slots live in the repository, which stores them with the artwork, so closing and reopening a
 * document keeps them instead of losing everything the artist saved.
 */
class SavedSelections(
    private val repository: CanvasRepository,
    private val onRestored: () -> Unit,
) {
    private val _saved = MutableStateFlow(repository.savedSelections())
    val saved: StateFlow<List<SelectionMask>> = _saved.asStateFlow()

    /** Re-reads the slots after another document is opened in the same editor. */
    fun refresh() {
        _saved.value = repository.savedSelections()
    }

    /** Keeps a copy of the current selection; false when nothing is selected. */
    fun save(): Boolean {
        val mask = repository.selection()?.takeIf { it.isActive() } ?: return false
        persist((_saved.value + mask.copy()).takeLast(MAX_SAVED))
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
        if (index !in _saved.value.indices) return
        persist(_saved.value.filterIndexed { i, _ -> i != index })
    }

    fun clear() {
        if (_saved.value.isEmpty()) return
        persist(emptyList())
    }

    /** One place writes both the visible slots and the document copy, so they cannot disagree. */
    private fun persist(masks: List<SelectionMask>) {
        _saved.value = masks
        repository.setSavedSelections(masks)
    }

    private companion object {
        const val MAX_SAVED = 8
    }
}
