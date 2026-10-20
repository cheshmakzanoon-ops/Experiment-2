package com.artflow.studio.presentation.ui.viewmodel

import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.core.pixels.SelectionClipboard
import com.artflow.studio.domain.repository.canvas.CanvasRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Cut, copy, paste, clear and fill for the active layer (or the selection on it), as in
 * Procreate's Copy & Paste menu and layer options. [perform] executes an edit and refreshes the layers.
 */
class ClipboardController(
    private val repository: CanvasRepository,
    private val perform: (suspend () -> Unit) -> Unit,
) {
    private var image: PixelBuffer? = null
    private val _hasContent = MutableStateFlow(false)
    val hasContent: StateFlow<Boolean> = _hasContent.asStateFlow()

    fun copy(cut: Boolean = false) = perform { copyLayer(cut) }

    fun paste() = perform { pasteAsLayer() }

    fun copyAndPaste() =
        perform {
            copyLayer(cut = false)
            pasteAsLayer()
        }

    fun cutAndPaste() =
        perform {
            copyLayer(cut = true)
            pasteAsLayer()
        }

    /** Copy All: the visible artwork (all layers merged) inside the selection. */
    fun copyMerged() =
        perform {
            val composite = checkNotNull(repository.compositeBuffer()) { "Nothing to copy" }
            val selection = repository.selection()
            image = withContext(Dispatchers.Default) { SelectionClipboard.extract(composite, selection) }
            _hasContent.value = true
        }

    /** Clears the layer, or only its selected pixels (Procreate's three-finger scrub). */
    fun clear(layerId: Long? = null) =
        perform {
            val selection = repository.selection()
            repository.applyRasterEdit(layerId ?: repository.getActiveLayerId(), "Clear layer") {
                SelectionClipboard.erase(it, selection)
            }
        }

    /** Fill Layer: paints the current colour over the layer or its selection, within the layer's paint if alpha is locked. */
    fun fill(
        color: Int,
        layerId: Long? = null,
    ) = perform {
        val selection = repository.selection()
        val target = layerId ?: repository.getActiveLayerId()
        val alphaLocked = repository.getAllLayers().firstOrNull { it.id == target }?.isAlphaLocked == true
        val stored = repository.documentColor(color)
        repository.applyRasterEdit(target, "Fill layer") {
            SelectionClipboard.fill(it, selection, stored, alphaLocked)
        }
    }

    private suspend fun copyLayer(cut: Boolean) {
        val layerId = repository.getActiveLayerId()
        val pixels = checkNotNull(repository.layerPixels(layerId)) { "This layer has no pixels to copy" }
        val selection = repository.selection()
        image = withContext(Dispatchers.Default) { SelectionClipboard.extract(pixels, selection) }
        _hasContent.value = true
        if (cut) repository.applyRasterEdit(layerId, "Cut") { SelectionClipboard.erase(it, selection) }
    }

    private suspend fun pasteAsLayer() {
        val copied = image ?: error("Nothing has been copied yet")
        val layer = repository.addLayer(name = "Pasted")
        repository.applyRasterEdit(layer.id, "Paste") { target -> target.drawInto(copied, 0, 0) }
    }
}
