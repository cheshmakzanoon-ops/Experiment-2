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

/** Small previews of every page for Page Assist's page strip, rebuilt after edits settle. */
class PageThumbnailController(
    private val repository: CanvasRepository,
    private val scope: CoroutineScope,
) {
    private val _pages = MutableStateFlow<List<PixelBuffer?>>(emptyList())
    val pages: StateFlow<List<PixelBuffer?>> = _pages.asStateFlow()
    private var job: Job? = null

    fun refresh() {
        job?.cancel()
        job =
            scope.launch {
                delay(SETTLE_MS)
                val count = repository.frames().size
                _pages.value =
                    List(count) { index ->
                        repository.compositeFrame(index)?.let { withContext(Dispatchers.Default) { shrink(it) } }
                    }
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
        const val SIZE = 120
        const val SETTLE_MS = 300L
    }
}
