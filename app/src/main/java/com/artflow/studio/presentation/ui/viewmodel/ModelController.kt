package com.artflow.studio.presentation.ui.viewmodel

import com.artflow.studio.core.three.Mesh
import com.artflow.studio.core.three.ObjParser
import com.artflow.studio.domain.model.brush.BrushParams
import com.artflow.studio.domain.repository.canvas.CanvasRepository
import com.artflow.studio.presentation.ui.components.canvas.ModelPainter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * 3D painting: the artwork is the texture of a model. Strokes painted on the model arrive in
 * texture space and become ordinary strokes on the active layer, so undo, layers and export work
 * exactly as they do on a flat canvas.
 */
class ModelController(
    private val repository: CanvasRepository,
    private val scope: CoroutineScope,
    private val notify: (String) -> Unit,
) {
    private val _mesh = MutableStateFlow<Mesh?>(null)
    val mesh: StateFlow<Mesh?> = _mesh.asStateFlow()

    /** Loads the open artwork's model, if it has one. */
    fun load() {
        scope.launch {
            // Most artworks have no model; an unreadable one is treated the same way.
            val text =
                try {
                    repository.loadModel()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    Timber.w(failure, "Saved 3D model could not be loaded")
                    null
                }
            _mesh.value = text?.let { withContext(Dispatchers.Default) { runCatching { ObjParser.parse(it) }.getOrNull() } }
        }
    }

    /** Makes the open artwork the texture of the model in [objText]. */
    fun attach(objText: String) {
        scope.launch {
            val parsed = withContext(Dispatchers.Default) { runCatching { ObjParser.parse(objText) } }
            parsed.onFailure { notify(it.message ?: "That 3D model could not be read") }
            val model = parsed.getOrNull() ?: return@launch
            if (repository.saveModel(objText)) _mesh.value = model
        }
    }

    /** Paints with the current brush on the active layer; [params] and [layer] are read per stroke. */
    fun painter(
        params: () -> BrushParams,
        layer: () -> Long,
    ): ModelPainter =
        object : ModelPainter {
            private var stroke = 0L

            override fun begin(
                u: Float,
                v: Float,
                pressure: Float,
            ) {
                val (x, y) = toCanvas(u, v)
                stroke = repository.beginStroke(x, y, pressure, params(), layer())
                if (stroke == 0L) notify("Choose an unlocked, visible layer to paint the model")
            }

            override fun move(
                u: Float,
                v: Float,
                pressure: Float,
            ) {
                if (stroke == 0L) return
                val (x, y) = toCanvas(u, v)
                repository.continueStroke(stroke, x, y, pressure)
            }

            override fun end() {
                if (stroke != 0L) repository.endStroke(stroke)
                stroke = 0L
            }
        }

    /** Texture coordinates have v = 0 at the bottom; the artwork's first row is its top. */
    private fun toCanvas(
        u: Float,
        v: Float,
    ): Pair<Float, Float> {
        val size = repository.getCanvasSize()
        return u.coerceIn(0f, 1f) * size.width to (1f - v.coerceIn(0f, 1f)) * size.height
    }
}
