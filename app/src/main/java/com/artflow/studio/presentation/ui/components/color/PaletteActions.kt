package com.artflow.studio.presentation.ui.components.color

import com.artflow.studio.core.color.Palette

/** Everything the Palettes tab can do with palettes and their swatches. */
data class PaletteActions(
    val onCreate: (String) -> Unit,
    val onRename: (Long, String) -> Unit,
    val onDuplicate: (Palette) -> Unit,
    val onSetDefault: (Long) -> Unit,
    val onDelete: (Long) -> Unit,
    val onAddSwatch: (Long) -> Unit,
    val onReplaceSwatch: (Long, Int) -> Unit,
    val onRemoveSwatch: (Long, Int) -> Unit,
    /** Shares a palette as a Procreate `.swatches` file; null hides the command. */
    val onShare: ((Palette) -> Unit)? = null,
)
