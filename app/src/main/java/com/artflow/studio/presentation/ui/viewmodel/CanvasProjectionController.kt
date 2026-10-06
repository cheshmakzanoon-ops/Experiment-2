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
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Procreate's Project Canvas: while an external display shows the artwork, [image] follows every
 * edit, at most a few times a second and no larger than the display needs. Nothing runs otherwise.
 */
class CanvasProjectionController(
    private val repository: CanvasRepository,
    private val scope: CoroutineScope,
) {
    private val _image = MutableStateFlow<PixelBuffer?>(null)
    val image: StateFlow<PixelBuffer?> = _image.asStateFlow()
    private var job: Job? = null

    /** Starts following the artwork for a display whose longest side is [maxSide] pixels. */
    fun start(maxSide: Int) {
        job?.cancel()
        job =
            scope.launch {
                refresh(maxSide)
                repository.observeCanvasInvalidation().conflate().collect {
                    refresh(maxSide)
                    delay(INTERVAL_MS)
                }
            }
    }

    fun stop() {
        job?.cancel()
        job = null
        _image.value = null
    }

    private suspend fun refresh(maxSide: Int) {
        val composite = repository.compositeBuffer() ?: return
        _image.value = withContext(Dispatchers.Default) { fit(composite, maxSide) }
    }

    private fun fit(
        source: PixelBuffer,
        maxSide: Int,
    ): PixelBuffer {
        val factor = maxSide.toFloat() / max(source.width, source.height)
        if (factor >= 1f) return source
        return source.scaled(
            (source.width * factor).roundToInt().coerceAtLeast(1),
            (source.height * factor).roundToInt().coerceAtLeast(1),
        )
    }

    private companion object {
        const val INTERVAL_MS = 150L
    }
}
