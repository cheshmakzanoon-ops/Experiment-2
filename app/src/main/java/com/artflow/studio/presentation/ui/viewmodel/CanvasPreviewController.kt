package com.artflow.studio.presentation.ui.viewmodel

import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.core.three.MaterialChannel
import com.artflow.studio.domain.repository.canvas.CanvasRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.max
import kotlin.math.roundToInt

/** A small artwork copy shared by Reference and the model, including provisional model paint. */
class CanvasPreviewController(
    private val repository: CanvasRepository,
    private val scope: CoroutineScope,
    private val renderDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val _preview = MutableStateFlow<PixelBuffer?>(null)
    val preview: StateFlow<PixelBuffer?> = _preview.asStateFlow()
    private val _materialMaps = MutableStateFlow<Map<MaterialChannel, PixelBuffer>>(emptyMap())
    val materialMaps: StateFlow<Map<MaterialChannel, PixelBuffer>> = _materialMaps.asStateFlow()
    private var worker: Job? = null
    private val requests = Channel<Request>(Channel.CONFLATED)
    private var generation = 0L
    private var growing = false

    /** Ordinary Reference updates retain the existing settle delay. */
    fun refresh() {
        submit(Mode.SETTLED)
    }

    /** Coalesces samples without cancelling an in-flight render on every pointer move. */
    fun refreshProvisional() {
        submit(Mode.PROVISIONAL)
    }

    /** Invalidates any outstanding provisional result before publishing the committed artwork. */
    fun finishProvisional() {
        submit(Mode.COMMITTED)
    }

    private fun submit(mode: Mode) {
        if (mode != Mode.PROVISIONAL || !growing) generation++
        growing = mode == Mode.PROVISIONAL
        requests.trySend(Request(mode, generation))
        if (worker?.isActive == true) return
        worker = scope.launch { processRequests() }
    }

    private suspend fun processRequests() {
        for (received in requests) {
            var request = received
            while (request.mode == Mode.SETTLED) {
                request = withTimeoutOrNull(SETTLE_MS) { requests.receive() } ?: break
            }
            if (request.generation != generation) continue
            val frame =
                if (request.mode == Mode.PROVISIONAL) {
                    repository.compositePreview()
                } else {
                    repository.compositeFrame(repository.activeFrameIndex())
                }
            if (frame != null) {
                val preview = withContext(renderDispatcher) { shrink(frame) }
                if (request.generation == generation) _preview.value = preview
            }
            val maps = mutableMapOf<MaterialChannel, PixelBuffer>()
            MaterialChannel.entries.filter { it.isScalar }.forEach { channel ->
                repository.compositeMaterial(channel, request.mode == Mode.PROVISIONAL)?.let { pixels ->
                    maps[channel] = withContext(renderDispatcher) { shrink(pixels) }
                }
            }
            if (request.generation == generation) _materialMaps.value = maps
            // One serial worker bounds raster work; a burst of moves retains only the latest request.
            if (request.mode == Mode.PROVISIONAL && request.generation == generation) delay(PROVISIONAL_INTERVAL_MS)
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
        const val PROVISIONAL_INTERVAL_MS = 32L
    }

    private enum class Mode { SETTLED, PROVISIONAL, COMMITTED }

    private data class Request(
        val mode: Mode,
        val generation: Long,
    )
}
