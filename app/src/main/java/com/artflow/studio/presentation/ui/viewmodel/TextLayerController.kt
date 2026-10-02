package com.artflow.studio.presentation.ui.viewmodel

import com.artflow.studio.core.text.TextLayerContent
import com.artflow.studio.data.renderer.TextRasterizer
import com.artflow.studio.domain.repository.canvas.CanvasRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Procreate-style text: Add Text makes an editable text layer, and Edit Text re-renders it. */
class TextLayerController(
    private val repository: CanvasRepository,
    private val perform: (suspend () -> Unit) -> Unit,
) {
    fun place(content: TextLayerContent) =
        perform {
            if (content.text.isBlank()) return@perform
            checkNotNull(repository.addTextLayer(content, render(content))) { "The text could not be added" }
        }

    fun edit(
        layerId: Long,
        content: TextLayerContent,
    ) = perform {
        if (content.text.isBlank()) return@perform
        check(repository.setTextLayer(layerId, content, render(content))) { "This text layer can no longer be edited" }
    }

    private suspend fun render(content: TextLayerContent) =
        withContext(Dispatchers.Default) {
            val size = repository.getCanvasSize()
            TextRasterizer.render(size.width, size.height, content)
        }
}
