package com.artflow.studio.presentation.ui.viewmodel

import com.artflow.studio.core.three.MaterialChannel
import com.artflow.studio.core.three.Mesh
import com.artflow.studio.core.three.ObjParser
import com.artflow.studio.data.export.ModelImports
import com.artflow.studio.data.export.PendingImports
import com.artflow.studio.domain.model.brush.BrushParams
import com.artflow.studio.domain.model.brush.StrokePoint
import com.artflow.studio.domain.repository.canvas.CanvasRepository
import com.artflow.studio.presentation.ui.components.canvas.ModelPainter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.IOException

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
    private val _paintValue = MutableStateFlow(1f)
    val paintValue: StateFlow<Float> = _paintValue.asStateFlow()

    fun selectChannel(channel: MaterialChannel) {
        scope.launch {
            if (repository.selectMaterialChannel(channel) == null) notify("Finish the current edit before changing the material channel")
        }
    }

    fun setPaintValue(value: Float) {
        if (!value.isFinite()) return
        _paintValue.value = value.coerceIn(0f, 1f)
        repository.setMaterialPaintValue(_paintValue.value)
    }

    /** Loads the open artwork's model, if it has one. */
    fun load() {
        scope.launch { loadAwaited() }
    }

    /** The canvas stays Loading until the entire pending bundle and its model file have committed. */
    suspend fun initialize(
        projectId: Long,
        onSaved: suspend (String) -> Unit = {},
    ) {
        val bundle = PendingImports.modelBundle(projectId)
        if (bundle != null) {
            val parsed = withContext(Dispatchers.Default) { prepared(bundle.objText) }
            check(repository.projectId() == projectId && ModelImports.install(repository, bundle)) {
                "The model could not be installed; reopen the artwork to try again"
            }
            // The repository recorded a retry receipt with the plane commit. Cancellation or any
            // save failure keeps both it and the payload until the initial document is durable.
            val thumbnail =
                checkNotNull(repository.saveCanvas(projectId)) { "The model layers could not be saved; reopen the artwork to try again" }
            check(repository.projectId() == projectId) { "The imported artwork is no longer open" }
            onSaved(thumbnail)
            PendingImports.completeModelBundle(projectId, bundle)
            if (repository.projectId() == projectId) _mesh.value = parsed
        } else {
            val legacy = PendingImports.takeModel(projectId)
            if (legacy != null) attachAwaited(legacy) else loadAwaited()
        }
    }

    private suspend fun loadAwaited() {
        val projectId = repository.projectId()
        // Most artworks have no model; an unreadable saved one is treated the same way.
        val text =
            try {
                repository.loadModel()
            } catch (failure: IOException) {
                unreadable(failure)
            } catch (failure: IllegalArgumentException) {
                unreadable(failure)
            } catch (failure: IllegalStateException) {
                unreadable(failure)
            }
        val parsed = text?.let { withContext(Dispatchers.Default) { runCatching { prepared(it) }.getOrNull() } }
        if (repository.projectId() == projectId) _mesh.value = parsed
    }

    /** Parses [objText] and works out its UV islands here, off the main thread, before painting needs them. */
    private fun prepared(objText: String): Mesh = ObjParser.parse(objText).also { it.islands }

    private fun unreadable(failure: Exception): String? {
        Timber.w(failure, "Saved 3D model could not be loaded")
        return null
    }

    /** Makes the open artwork the texture of the model in [objText]. */
    fun attach(objText: String) {
        scope.launch {
            attachAwaited(objText)
        }
    }

    private suspend fun attachAwaited(objText: String) {
        val projectId = repository.projectId()
        val parsed = withContext(Dispatchers.Default) { runCatching { prepared(objText) } }
        parsed.onFailure { notify(it.message ?: "That 3D model could not be read") }
        val model = parsed.getOrNull() ?: return
        if (repository.projectId() == projectId && repository.saveModel(objText) && repository.projectId() == projectId) _mesh.value = model
    }

    /** Captures the brush and layer once per pointer gesture, including its seam-separated segments. */
    fun painter(
        params: () -> BrushParams,
        layer: () -> Long,
        onPreviewChanged: (Boolean) -> Unit = {},
    ): ModelPainter =
        object : ModelPainter {
            private var stroke = 0L
            private val segments = mutableListOf<Segment>()
            private var target: GestureTarget? = null
            private var rejected = false

            override fun begin(
                u: Float,
                v: Float,
                pressure: Float,
            ) {
                if (rejected) return
                val current =
                    target ?: GestureTarget(repository.projectId(), repository.historyMark, layer(), params(), _paintValue.value)
                        .also { target = it }
                if (!current.matches(repository)) {
                    rejected = true
                    return
                }
                val (x, y) = toCanvas(u, v)
                repository.setMaterialPaintValue(current.materialValue)
                stroke = repository.beginStroke(x, y, pressure, current.params, current.layerId)
                if (stroke == 0L) {
                    rejected = true
                    notify("Choose an unlocked, visible layer to paint the model")
                } else {
                    segments += Segment(stroke, requireNotNull(repository.activeStroke(stroke)).points.first())
                    onPreviewChanged(true)
                }
            }

            override fun move(
                u: Float,
                v: Float,
                pressure: Float,
            ) {
                if (stroke == 0L) return
                val segment = segments.lastOrNull()?.takeIf { it.id == stroke }
                if (target?.matches(repository) != true || segment?.belongsTo(repository) != true) {
                    rejected = true
                    return
                }
                val (x, y) = toCanvas(u, v)
                repository.continueStroke(stroke, x, y, pressure)
                onPreviewChanged(true)
            }

            override fun end() {
                val segment = segments.lastOrNull()?.takeIf { it.id == stroke }
                if (segment?.belongsTo(repository) == true) {
                    repository.sealStrokeSegment(segment.id)
                    onPreviewChanged(true)
                }
                stroke = 0L
            }

            override fun finish() {
                end()
                val current = target
                val valid =
                    !rejected &&
                        current?.matches(repository) == true &&
                        segments.all { it.belongsTo(repository) }
                try {
                    if (valid) segments.forEach { repository.endStroke(it.id) }
                } finally {
                    // Also releases any remaining provisional state if rendering throws part-way through.
                    cancel()
                }
            }

            override fun cancel() {
                segments.forEach { if (it.belongsTo(repository)) repository.cancelStroke(it.id) }
                segments.clear()
                stroke = 0L
                target = null
                rejected = false
                repository.setMaterialPaintValue(_paintValue.value)
                onPreviewChanged(false)
            }
        }

    private data class Segment(
        val id: Long,
        val first: StrokePoint,
    ) {
        // An activeStroke snapshot preserves immutable point instances. IDs alone can be reused
        // after a reload, so a stale model gesture must not touch an unrelated replacement stroke.
        fun belongsTo(repository: CanvasRepository): Boolean = repository.activeStroke(id)?.points?.firstOrNull() === first
    }

    private data class GestureTarget(
        val projectId: Long,
        val history: Long,
        val layerId: Long,
        val params: BrushParams,
        val materialValue: Float,
    ) {
        fun matches(repository: CanvasRepository): Boolean =
            projectId == repository.projectId() && history == repository.historyMark && layerId == repository.getActiveLayerId()
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
