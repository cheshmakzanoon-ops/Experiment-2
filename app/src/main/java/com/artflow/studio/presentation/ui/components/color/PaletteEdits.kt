package com.artflow.studio.presentation.ui.components.color

import com.artflow.studio.core.color.Palette
import com.artflow.studio.presentation.ui.viewmodel.PaletteActions as CanvasPaletteActions

/** The panel sends an operation and its selection, never a stale whole-palette replacement. */
data class PaletteEdits(
    val onRemove: (Long) -> Unit,
    val onAddColor: (Long, Int) -> Unit,
    val onRemoveColor: (Palette, Int) -> Unit,
    val onShare: (Palette) -> Unit = {},
    /** Makes a palette (by name) the default shown under the pickers, or clears it when it already is. */
    val onToggleDefault: (String) -> Unit = {},
    /** The current default palette's name; empty for none. */
    val defaultName: String = "",
) {
    companion object {
        /** Shared by CanvasScreen and interaction tests so they exercise the same callback binding. */
        fun from(
            actions: CanvasPaletteActions,
            onShare: (Palette) -> Unit = {},
            defaultName: String = "",
        ): PaletteEdits =
            PaletteEdits(
                onRemove = actions::remove,
                onAddColor = actions::addColor,
                onRemoveColor = actions::removeColor,
                onShare = onShare,
                onToggleDefault = actions::toggleDefault,
                defaultName = defaultName,
            )
    }
}
