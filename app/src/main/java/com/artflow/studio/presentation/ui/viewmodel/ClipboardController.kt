package com.artflow.studio.presentation.ui.viewmodel

import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.core.pixels.SelectionClipboard
import com.artflow.studio.core.pixels.SelectionMask
import com.artflow.studio.core.three.MaterialChannel
import com.artflow.studio.domain.repository.canvas.CanvasRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext

/**
 * Cut, copy, paste, clear and fill for the active layer (or the selection on it), as in
 * Procreate's Copy & Paste menu and layer options. [perform] executes an edit and refreshes the layers.
 */
class ClipboardController(
    private val repository: CanvasRepository,
    private val perform: (suspend () -> Unit) -> Unit,
) {
    private data class Image(
        val pixels: PixelBuffer,
        val channel: MaterialChannel,
        val isPrivate: Boolean,
    )

    private data class Request(
        val projectId: Long,
        val generation: Long,
        val copySequence: Long?,
    )

    private var image: Image? = null
    private var generation = 0L
    private var copySequence = 0L
    private val operations = mutableMapOf<Job, Request>()

    private val _hasContent = MutableStateFlow(false)
    val hasContent: StateFlow<Boolean> = _hasContent.asStateFlow()

    /** Closing an artwork cancels its commands; a completed pixel copy remains available to Paste. */
    fun cancelPending() {
        generation++
        operations.keys.toList().forEach { it.cancel() }
    }

    fun copy(cut: Boolean = false) = copyLayer(cut, paste = false)

    fun paste() =
        performOwned {
            val copied = image ?: error("Nothing has been copied yet")
            repository.pasteLayer(copied.pixels, copied.channel, isPrivate = copied.isPrivate)
        }

    fun copyAndPaste() = copyLayer(cut = false, paste = true)

    fun cutAndPaste() = copyLayer(cut = true, paste = true)

    /** Copy All: the visible artwork (all layers merged) inside the selection. */
    fun copyMerged() =
        performOwned(copying = true) { request ->
            val copied = repository.copyMergedSelection()
            publish(request, Image(copied.pixels, MaterialChannel.COLOR, copied.isPrivate))
        }

    /** Clears the layer, or only its selected pixels (Procreate's three-finger scrub). */
    fun clear(layerId: Long? = null) =
        performOwned {
            val selection = repository.selection()
            repository.applyRasterEdit(layerId ?: repository.getActiveLayerId(), "Clear layer") {
                SelectionClipboard.erase(it, selection)
            }
        }

    /** Fill Layer: paints the current colour over the layer or its selection, within the layer's paint if alpha is locked. */
    fun fill(
        color: Int,
        layerId: Long? = null,
    ) = performOwned {
        val selection = repository.selection()
        val target = layerId ?: repository.getActiveLayerId()
        val alphaLocked = repository.getAllLayers().firstOrNull { it.id == target }?.isAlphaLocked == true
        val stored = repository.documentColor(color)
        repository.applyRasterEdit(target, "Fill layer") {
            SelectionClipboard.fill(it, selection, stored, alphaLocked)
        }
    }

    /**
     * The selection Copy and Cut may use. An emptied selection selects nothing, so copying it would
     * only fill the clipboard with blank pixels and Paste would add an invisible layer; refuse instead.
     */
    private fun requireCopiableSelection(): SelectionMask? {
        val selection = repository.selection()
        require(selection == null || selection.isActive()) { "Nothing is selected" }
        return selection
    }

    private fun copyLayer(
        cut: Boolean,
        paste: Boolean,
    ) = performOwned(copying = true) { request ->
        val layer = repository.getActiveLayer()
        val pixels =
            when {
                paste -> repository.copySelectionToLayer(cut)
                cut -> repository.cutSelection()
                else -> extractLayer(repository.getActiveLayerId())
            }
        publish(request, Image(pixels, layer?.materialChannel ?: MaterialChannel.COLOR, layer?.isPrivate == true))
    }

    private suspend fun extractLayer(layerId: Long): PixelBuffer {
        val selection = requireCopiableSelection()
        val pixels = checkNotNull(repository.layerPixels(layerId)) { "This layer has no pixels to copy" }
        return withContext(Dispatchers.Default) { SelectionClipboard.extract(pixels, selection) }
    }

    private suspend fun publish(
        request: Request,
        copied: Image,
    ) {
        ensureOwned(request)
        image = copied
        _hasContent.value = true
    }

    private fun performOwned(
        copying: Boolean = false,
        action: suspend (Request) -> Unit,
    ) {
        if (copying) {
            copySequence++
            operations
                .filterValues { it.copySequence != null }
                .keys
                .toList()
                .forEach { it.cancel() }
        }
        val request = Request(repository.projectId(), generation, copySequence.takeIf { copying })
        perform {
            // A committed cut and publication of its clipboard must have no cancellable dispatcher
            // handoff between them. Repository commits run on this same Main.immediate dispatcher.
            withContext(Dispatchers.Main.immediate) {
                ensureOwned(request)
                val operation = currentCoroutineContext().job
                operations[operation] = request
                try {
                    action(request)
                } finally {
                    operations -= operation
                    // A late failure from a closed artwork must not become feedback in its successor.
                    ensureOwned(request)
                }
            }
        }
    }

    private suspend fun ensureOwned(request: Request) {
        currentCoroutineContext().ensureActive()
        if (request.generation != generation || request.projectId != repository.projectId()) {
            throw CancellationException("The artwork was closed")
        }
        if (request.copySequence != null && request.copySequence != copySequence) {
            throw CancellationException("A newer copy replaced this command")
        }
    }
}
