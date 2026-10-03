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

/** A small copy of the whole artwork for the Reference window's Canvas view, rebuilt after edits settle. */
class CanvasPreviewController(
    private val repository: CanvasRepository,
    private val scope: CoroutineScope,
) {
    private val _preview = MutableStateFlow<PixelBuffer?>(null)
    val preview: StateFlow<PixelBuffer?> = _preview.asStateFlow()
    private var job: Job? = null

    fun refresh() {
        job?.cancel()
        job =
            scope.launch {
                delay(SETTLE_MS)
                val frame = repository.compositeFrame(repository.activeFrameIndex()) ?: return@launch
                _preview.value = withContext(Dispatchers.Default) { shrink(frame) }
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
        const val SIZE = 1024
        const val SETTLE_MS = 250L
    }
}
