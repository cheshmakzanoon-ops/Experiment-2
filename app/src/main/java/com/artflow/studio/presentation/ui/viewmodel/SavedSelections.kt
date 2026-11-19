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
    private val onFailure: (String) -> Unit = {},
    private val onRestored: () -> Unit,
) {
    private val _saved = MutableStateFlow(repository.savedSelections())
    val saved: StateFlow<List<SelectionMask>> = _saved.asStateFlow()

    /** Re-reads the slots after another document is opened in the same editor. */
    fun refresh() {
        _saved.value = repository.savedSelections()
    }

    /** Keeps the repository's owned selection copy; false when absent or rejected by its budget. */
    fun save(): Boolean {
        val mask = repository.selection()?.takeIf { it.isActive() }
        if (mask == null) {
            onFailure("Make a selection first")
            return false
        }
        return persist((_saved.value + mask).takeLast(MAX_SAVED))
    }

    fun load(index: Int) {
        val mask = _saved.value.getOrNull(index) ?: return
        val size = repository.getCanvasSize()
        if (mask.width != size.width || mask.height != size.height) return
        // The repository owns its incoming copy; the slot remains unchanged by later live edits.
        repository.setSelection(mask)
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

    /** Publish slots only after the document accepts them, retaining both views on rejection. */
    private fun persist(masks: List<SelectionMask>): Boolean =
        try {
            repository.setSavedSelections(masks)
            _saved.value = masks
            true
        } catch (rejected: IllegalArgumentException) {
            onFailure(rejected.message?.takeIf { it.isNotBlank() } ?: "Saved selections could not be updated")
            false
        }

    private companion object {
        const val MAX_SAVED = 8
    }
}
