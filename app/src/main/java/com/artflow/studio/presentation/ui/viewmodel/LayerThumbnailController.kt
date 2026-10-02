package com.artflow.studio.presentation.ui.viewmodel

import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.domain.repository.canvas.CanvasRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt

/** Small per-layer previews for the layer panel, rebuilt after edits settle while it is open. */
class LayerThumbnailController(
    private val repository: CanvasRepository,
    private val scope: CoroutineScope,
) {
    private val _thumbnails = MutableStateFlow<Map<Long, PixelBuffer>>(emptyMap())
    val thumbnails: StateFlow<Map<Long, PixelBuffer>> = _thumbnails.asStateFlow()
    private var job: Job? = null

    fun refresh() {
        job?.cancel()
        job =
            scope.launch {
                delay(SETTLE_MS)
                val previews = mutableMapOf<Long, PixelBuffer>()
                for (layer in repository.getAllLayers().filterNot { it.isGroup }) {
                    val pixels = repository.layerPixels(layer.id) ?: continue
                    previews[layer.id] = withContext(Dispatchers.Default) { shrink(pixels) }
                }
                _thumbnails.value = previews
            }
    }

    private fun shrink(source: PixelBuffer): PixelBuffer {
        val factor = SIZE.toFloat() / max(source.width, source.height)
        if (factor >= 1f) return source
        return source.scaled(
            (source.width * factor).roundToInt().coerceAtLeast(1),
            (source.height * factor).roundToInt().coerceAtLeast(1),
        )
    }

    private companion object {
        const val SIZE = 96
        const val SETTLE_MS = 300L
    }
}
