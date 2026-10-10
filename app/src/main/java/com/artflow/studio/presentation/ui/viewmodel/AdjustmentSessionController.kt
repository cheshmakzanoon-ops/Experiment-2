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
        /** Pencil mode: the effect shows only where it has been painted on, as in Procreate. */
        val pencil: Boolean = false,
    )

    private val _state = MutableStateFlow<State?>(null)
    val state: StateFlow<State?> = _state.asStateFlow()

    private var session: CanvasRepository.RasterEditSession? = null
    private var original: PixelBuffer? = null
    private var selection: SelectionMask? = null
    private var previewJob: Job? = null

    /** The whole-layer effect and the settings it was made for; Pencil mode blends it in where painted. */
    @Volatile private var filtered: Pair<State, PixelBuffer>? = null
    private var painted: SelectionMask? = null

    /** Opens [kind] on the active layer; [color] is what Recolor paints with. */
    fun start(
        kind: LiveAdjustments.Kind,
        color: Int = 0,
    ) {
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
            val parameters =
                if (kind == LiveAdjustments.Kind.RECOLOR) {
                    mapOf(LiveAdjustments.RECOLOR_RGB to (color and 0xFFFFFF).toFloat())
                } else {
                    kind.adjustmentType?.defaultParameters.orEmpty()
                }
            val amount =
                when {
                    kind.usesPoint -> RECOLOR_THRESHOLD
                    kind.slidesAmount -> 0f
                    else -> 1f
                }
            _state.value = State(kind, LiveAdjustments.Settings(amount = amount, parameters = parameters))
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

    /** Places Recolor's crosshair at canvas point ([x], [y]). */
    fun setPoint(
        x: Float,
        y: Float,
    ) = update { it.copy(parameters = it.parameters + (LiveAdjustments.RECOLOR_X to x) + (LiveAdjustments.RECOLOR_Y to y)) }

    /** Switches between adjusting the whole layer and painting the adjustment on with the brush. */
    fun setPencil(enabled: Boolean) {
        val current = _state.value ?: return
        val source = original ?: return
        painted = if (enabled) SelectionMask(source.width, source.height) else null
        _state.value = current.copy(pencil = enabled)
        render()
    }

    /** Paints the adjustment on around canvas point ([x], [y]) with a soft dab of [radius]. */
    fun paintAt(
        x: Float,
        y: Float,
        radius: Float,
    ) {
        val mask = painted ?: return
        AdjustmentPaint.dab(mask, x, y, radius.coerceAtLeast(1f))
        render()
    }

    private fun render() {
        val current = _state.value ?: return
        val target = session ?: return
        previewJob?.cancel()
        previewJob =
            scope.launch {
                // A superseded preview throws from its next checkpoint, so it stops within a row instead of finishing.
                val result = withContext(Dispatchers.Default) { result(current) { ensureActive() } } ?: return@launch
                ensureActive()
                result.pixels.copyInto(target.buffer.pixels)
                repository.requestPreviewRefresh()
            }
    }

    /** The layer as it would be applied: the effect, limited to the selection and any painted area. */
    private fun result(
        current: State,
        checkpoint: () -> Unit = {},
    ): PixelBuffer? {
        val source = original ?: return null
        val settingsState = current.copy(pencil = false)
        val effect =
            filtered?.takeIf { it.first == settingsState }?.second
                ?: LiveAdjustments
                    .apply(
                        current.kind,
                        source,
                        current.settings,
                        null,
                        alphaLocked = session?.alphaLocked == true,
                        checkpoint = checkpoint,
                    )
                    .also { filtered = settingsState to it }
        return AdjustmentPaint.mix(source, effect, selection, painted.takeIf { current.pencil })
    }

    /** Records the adjustment as one undoable step; an untouched preview is simply closed. */
    fun apply() {
        val current = _state.value ?: return
        val target = session ?: return
        val source = original ?: return
        previewJob?.cancel()
        scope.launch {
            try {
                val result = withContext(Dispatchers.Default) { result(current) } ?: return@launch
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
        filtered = null
        painted = null
        _state.value = null
        repository.requestPreviewRefresh()
    }
}

private const val RECOLOR_THRESHOLD = 0.25f
