package com.artflow.studio.presentation.ui.viewmodel

import com.artflow.studio.core.color.Palette
import com.artflow.studio.domain.repository.settings.SettingsRepository
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * What the Palettes tab does to the user's own palettes: create, rename, copy, extend, empty and delete them.
 * [palettes] is the list the tab shows, built-in palettes included; only the ones in "Custom" can be changed.
 */
class PaletteEditor(
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
    private val errors: CoroutineExceptionHandler,
    private val palettes: () -> List<Palette>,
) {
    /** Procreate's "+" in Palettes: a new, empty palette that becomes the default. */
    fun create(name: String) {
        val label = name.trim().take(MAX_NAME).ifEmpty { "Palette" }
        scope.launch(errors) {
            settings.updatePalette(Palette(id = nextId(), name = label, colors = emptyList(), category = CUSTOM), makeDefault = true)
        }
    }

    fun rename(
        paletteId: Long,
        name: String,
    ) {
        val label = name.trim().take(MAX_NAME)
        if (label.isEmpty()) return
        scope.launch(errors) { settings.renamePalette(paletteId, label) }
    }

    /** An editable copy of any palette, built-in ones included, placed first. */
    fun duplicate(palette: Palette) {
        scope.launch(errors) {
            val copy = palette.renamed("${palette.name} copy".take(MAX_NAME)).copy(id = nextId(), category = CUSTOM)
            settings.updatePalette(copy, makeDefault = true)
        }
    }

    fun setDefault(paletteId: Long) {
        val palette = custom(paletteId) ?: return
        scope.launch(errors) { settings.updatePalette(palette, makeDefault = true) }
    }

    fun delete(paletteId: Long) {
        scope.launch(errors) { settings.removePalette(paletteId) }
    }

    /** Adds [color] as the last swatch. */
    fun addSwatch(
        paletteId: Long,
        color: Int,
    ) = edit(paletteId) { it.withColorAppended(color) }

    fun replaceSwatch(
        paletteId: Long,
        index: Int,
        color: Int,
    ) = edit(paletteId) { it.withColorAt(index, color) }

    fun removeSwatch(
        paletteId: Long,
        index: Int,
    ) = edit(paletteId) { it.withoutColor(index) }

    private fun custom(paletteId: Long): Palette? = palettes().firstOrNull { it.id == paletteId && it.category == CUSTOM }

    private fun edit(
        paletteId: Long,
        change: (Palette) -> Palette,
    ) {
        val palette = custom(paletteId) ?: return
        scope.launch(errors) { settings.updatePalette(change(palette)) }
    }

    /** An id no stored palette has: the clock, or one past the highest in the rare case two arrive in a millisecond. */
    private fun nextId(): Long = maxOf(System.currentTimeMillis(), (palettes().maxOfOrNull { it.id } ?: 0L) + 1L)

    private companion object {
        const val MAX_NAME = 40
        const val CUSTOM = "Custom"
    }
}
