package com.artflow.studio.presentation.ui.viewmodel

import com.artflow.studio.domain.repository.canvas.CanvasRepository
import com.artflow.studio.domain.repository.canvas.CanvasRepository.LayerParameter
import com.artflow.studio.domain.repository.canvas.CanvasRepository.LayerParameterGesture
import kotlinx.coroutines.CancellationException

/** One pointer gesture at a time; delayed samples and old disposal callbacks cannot claim a new one. */
class LayerParameterController(
    private val repository: CanvasRepository,
    private val refresh: () -> Unit,
    private val onFailure: (Exception) -> Unit,
) {
    private var active: LayerParameterGesture? = null

    fun begin(
        layerId: Long,
        parameter: LayerParameter,
    ): LayerParameterGesture? {
        cancel()
        return attempt(null) { repository.beginLayerParameterGesture(layerId, parameter).also { active = it } }
    }

    fun update(
        gesture: LayerParameterGesture,
        value: Float,
    ) {
        if (active !== gesture) return
        // UI pointer samples apply synchronously: a normal release cannot outrun its last value.
        attempt(Unit) {
            if (repository.updateLayerParameterGesture(gesture, value)) refresh()
        }
    }

    fun end(gesture: LayerParameterGesture) {
        repository.endLayerParameterGesture(gesture)
        if (active === gesture) active = null
    }

    /** Tool switches, document navigation and disposal retain accepted values and close their group. */
    fun cancel() {
        active?.let(::end)
    }

    @Suppress("TooGenericExceptionCaught") // UI error boundary; report layer failures while cancellation and fatal errors propagate.
    private fun <T> attempt(
        fallback: T,
        block: () -> T,
    ): T =
        try {
            block()
        } catch (cancelled: CancellationException) {
            cancel()
            throw cancelled
        } catch (error: Exception) {
            cancel()
            onFailure(error)
            fallback
        }
}
