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

    private var pendingMode: Mode? = null

    /** Drop the previous artwork immediately and invalidate its queued or suspended renders. */
    fun reset() {
        generation++
        growing = false
        pendingMode = null
        _preview.value = null
        _materialMaps.value = emptyMap()
        // Keep one worker: a slow old render must finish before another full-size render begins.
        // Its ownership checks below prevent it from publishing or starting further material work.
    }

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
        val effectiveMode =
            when {
                mode == Mode.COMMITTED -> Mode.COMMITTED
                pendingMode == Mode.COMMITTED && mode == Mode.SETTLED -> Mode.COMMITTED
                else -> mode
            }
        val shouldIncrement =
            when {
                pendingMode == Mode.COMMITTED && mode == Mode.SETTLED -> false
                effectiveMode == Mode.PROVISIONAL && growing -> false
                else -> true
            }
        if (shouldIncrement) generation++
        growing = effectiveMode == Mode.PROVISIONAL
        pendingMode = effectiveMode
        requests.trySend(Request(effectiveMode, generation, repository.contentRevision, repository.activeFrameIndex()))
        if (worker?.isActive == true) return
        worker = scope.launch { processRequests() }
    }

    private suspend fun processRequests() {
        for (received in requests) {
            var request = received
            while (request.mode == Mode.SETTLED) {
                request = withTimeoutOrNull(SETTLE_MS) { requests.receive() } ?: break
            }
            if (pendingMode == request.mode) {
                pendingMode = null
            }
            renderAndPublish(request)
        }
    }

    private suspend fun renderAndPublish(request: Request) {
        if (!isCurrent(request)) return
        val frame =
            if (request.mode == Mode.PROVISIONAL) {
                repository.compositePreview()
            } else {
                repository.compositeFrame(request.frameIndex)
            }
        if (!isCurrent(request)) return
        val preview = frame?.let { withContext(renderDispatcher) { shrink(it) } }
        val maps = renderMaterials(request) ?: return
        if (!isCurrent(request)) return
        // Color and material outputs must all belong to this same frame/revision. Holding
        // color until material rendering completes avoids showing a partly stale model.
        _preview.value = preview
        _materialMaps.value = maps
        // One serial worker bounds raster work; a burst of moves retains only the latest request.
        if (request.mode == Mode.PROVISIONAL && request.generation == generation) delay(PROVISIONAL_INTERVAL_MS)
    }

    private suspend fun renderMaterials(request: Request): Map<MaterialChannel, PixelBuffer>? {
        val maps = mutableMapOf<MaterialChannel, PixelBuffer>()
        for (channel in MaterialChannel.entries.filter { it.isScalar }) {
            if (!isCurrent(request)) return null
            val pixels = repository.compositeMaterial(channel, request.mode == Mode.PROVISIONAL)
            if (!isCurrent(request)) return null
            if (pixels != null) maps[channel] = withContext(renderDispatcher) { shrink(pixels) }
        }
        return maps
    }

    private fun isCurrent(request: Request): Boolean =
        request.generation == generation &&
            request.revision == repository.contentRevision &&
            request.frameIndex == repository.activeFrameIndex()

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
        val revision: Long,
        val frameIndex: Int,
    )
}
