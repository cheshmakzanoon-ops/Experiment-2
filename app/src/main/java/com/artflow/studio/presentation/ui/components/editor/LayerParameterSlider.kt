package com.artflow.studio.presentation.ui.components.editor

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.Interaction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import com.artflow.studio.domain.repository.canvas.CanvasRepository.LayerParameter
import com.artflow.studio.domain.repository.canvas.CanvasRepository.LayerParameterGesture

data class LayerParameterGestureActions(
    val begin: (Long, LayerParameter) -> LayerParameterGesture?,
    val update: (LayerParameterGesture, Float) -> Unit,
    val end: (LayerParameterGesture) -> Unit,
)

/** Shared by the Layers sliders and the canvas opacity overlay. A refused start never falls back to a setter. */
internal class LayerParameterGestureInput(
    private val actions: () -> LayerParameterGestureActions?,
    private val onChange: (Float) -> Unit,
) {
    class PointerGesture(
        val owner: LayerParameterGestureActions?,
        val gesture: LayerParameterGesture?,
    )

    var current: PointerGesture? = null
        private set
    private var disposed = false

    fun start(
        layerId: Long,
        parameter: LayerParameter,
    ) {
        if (disposed) return
        finish()
        val owner = actions()
        current = PointerGesture(owner, owner?.begin?.invoke(layerId, parameter))
    }

    fun change(value: Float) {
        if (disposed) return
        val pointer = current
        val currentOwner = pointer?.owner
        if (currentOwner == null) {
            // Keyboard and accessibility actions have no pointer lifetime and keep their setters.
            onChange(value)
        } else {
            pointer?.gesture?.let { currentOwner.update(it, value) }
        }
    }

    fun finish(pointer: PointerGesture? = current) {
        if (pointer == null || current !== pointer) return
        current = null
        pointer.gesture?.let { pointer.owner?.end?.invoke(it) }
    }

    fun dispose() {
        finish()
        disposed = true
    }
}

/** Observe source emissions synchronously, so a delayed cancellation is tied to its original drag. */
internal class LayerParameterInteractionSource(
    private val input: LayerParameterGestureInput,
    private val delegate: MutableInteractionSource = MutableInteractionSource(),
) : MutableInteractionSource by delegate {
    private var drag: DragInteraction.Start? = null
    private var pointer: LayerParameterGestureInput.PointerGesture? = null
    private var finishedDrag = false

    override suspend fun emit(interaction: Interaction) {
        observe(interaction)
        delegate.emit(interaction)
    }

    override fun tryEmit(interaction: Interaction): Boolean {
        observe(interaction)
        return delegate.tryEmit(interaction)
    }

    private fun observe(interaction: Interaction) {
        when (interaction) {
            is DragInteraction.Start -> {
                drag = interaction
                pointer = input.current
                finishedDrag = false
            }

            is DragInteraction.Stop -> finishDrag(interaction.start)
            is DragInteraction.Cancel -> finishDrag(interaction.start)
        }
    }

    private fun finishDrag(start: DragInteraction.Start) {
        if (drag === start) {
            input.finish(pointer)
            drag = null
            pointer = null
        }
        // Slider's drag-stop callback follows its Stop/Cancel emission. Ignore that redundant
        // finish even if a new down has already opened the next pointer session.
        finishedDrag = true
    }

    fun finish() {
        if (!finishedDrag) input.finish()
        finishedDrag = false
    }
}

@Composable
internal fun rememberLayerParameterGesture(
    layerId: Long,
    parameter: LayerParameter,
    actions: LayerParameterGestureActions?,
    onChange: (Float) -> Unit,
): LayerParameterGestureInput {
    val currentActions by rememberUpdatedState(actions)
    val currentChange by rememberUpdatedState(onChange)
    val input = remember(layerId, parameter) { LayerParameterGestureInput({ currentActions }) { currentChange(it) } }
    DisposableEffect(input) { onDispose { input.dispose() } }
    return input
}

@Composable
internal fun LayerParameterSlider(
    layerId: Long,
    parameter: LayerParameter,
    value: Float,
    onChange: (Float) -> Unit,
    actions: LayerParameterGestureActions?,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
) {
    val input = rememberLayerParameterGesture(layerId, parameter, actions, onChange)
    val interactions = remember(input) { LayerParameterInteractionSource(input) }
    Slider(
        value = value,
        onValueChange = input::change,
        onValueChangeFinished = interactions::finish,
        interactionSource = interactions,
        valueRange = valueRange,
        modifier =
            modifier.pointerInput(input) {
                // Observe before the Slider consumes the down event, without consuming its input.
                // Slider finishes after its final value callback, which may follow pointer-up for
                // taps. Cancellation/disposal closes the lifetime without waiting for that callback.
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    input.start(layerId, parameter)
                    val pointer = input.current
                    var released = false
                    try {
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Final)
                        } while (event.changes.any { it.pressed })
                        released = true
                    } finally {
                        if (!released) input.finish(pointer)
                    }
                }
            },
    )
}
