package com.artflow.studio.presentation.ui.viewmodel

import com.artflow.studio.core.pixels.LiveAdjustments
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.core.pixels.SelectionMask
import com.artflow.studio.domain.repository.canvas.CanvasRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One Procreate-style adjustment at a time: the active layer is opened as a provisional raster
 * edit, every change re-renders from the untouched original, and only Apply records history.
 */
class AdjustmentSessionController(
    private val repository: CanvasRepository,
    private val scope: CoroutineScope,
    private val notify: (String) -> Unit,
    private val onApplied: () -> Unit,
) {
    data class State(
        val kind: LiveAdjustments.Kind,
        val settings: LiveAdjustments.Settings,
    )

    private val _state = MutableStateFlow<State?>(null)
    val state: StateFlow<State?> = _state.asStateFlow()

    private var session: CanvasRepository.RasterEditSession? = null
    private var original: PixelBuffer? = null
    private var selection: SelectionMask? = null
    private var previewJob: Job? = null

    fun start(kind: LiveAdjustments.Kind) {
        scope.launch {
            closeSession()
            val opened = repository.beginRasterEdit(repository.getActiveLayerId())
            if (opened == null) {
                notify("Choose a visible, unlocked layer to adjust")
                return@launch
            }
            session = opened
            original = opened.buffer.copy()
            selection = repository.selection()
            val parameters = kind.adjustmentType?.defaultParameters.orEmpty()
            _state.value = State(kind, LiveAdjustments.Settings(amount = if (kind.slidesAmount) 0f else 1f, parameters = parameters))
            render()
        }
    }

    fun setAmount(amount: Float) = update { it.copy(amount = amount.coerceIn(0f, 1f)) }

    fun setAngle(degrees: Float) = update { it.copy(angleDegrees = degrees) }

    fun setParameter(
        key: String,
        value: Float,
    ) = update { it.copy(parameters = it.parameters + (key to value)) }

    private fun update(change: (LiveAdjustments.Settings) -> LiveAdjustments.Settings) {
        val current = _state.value ?: return
        _state.value = current.copy(settings = change(current.settings))
        render()
    }

    private fun render() {
        val current = _state.value ?: return
        val target = session ?: return
        val source = original ?: return
        val mask = selection
        previewJob?.cancel()
        previewJob =
            scope.launch {
                val result = withContext(Dispatchers.Default) { LiveAdjustments.apply(current.kind, source, current.settings, mask) }
                ensureActive()
                result.pixels.copyInto(target.buffer.pixels)
                repository.requestPreviewRefresh()
            }
    }

    /** Records the adjustment as one undoable step; an untouched preview is simply closed. */
    fun apply() {
        val current = _state.value ?: return
        val target = session ?: return
        val source = original ?: return
        val mask = selection
        previewJob?.cancel()
        scope.launch {
            try {
                val result = withContext(Dispatchers.Default) { LiveAdjustments.apply(current.kind, source, current.settings, mask) }
                if (!result.pixels.contentEquals(source.pixels)) {
                    result.pixels.copyInto(target.buffer.pixels)
                    if (repository.commitRasterEdit(target, current.kind.displayName)) {
                        onApplied()
                    } else {
                        notify("The layer changed; the adjustment was not applied")
                    }
                }
            } finally {
                withContext(NonCancellable) { closeSession() }
            }
        }
    }

    fun cancel() {
        previewJob?.cancel()
        scope.launch { closeSession() }
    }

    private suspend fun closeSession() {
        previewJob?.cancel()
        session?.let { repository.cancelRasterEdit(it) }
        session = null
        original = null
        selection = null
        _state.value = null
        repository.requestPreviewRefresh()
    }
}
