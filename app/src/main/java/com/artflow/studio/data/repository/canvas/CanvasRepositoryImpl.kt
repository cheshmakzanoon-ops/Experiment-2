package com.artflow.studio.data.repository.canvas

import android.graphics.Bitmap
import android.graphics.Color
import com.artflow.studio.data.local.CanvasDocument
import com.artflow.studio.data.local.ProjectStorage
import com.artflow.studio.data.renderer.CanvasRasterizer
import com.artflow.studio.data.renderer.opengl.OpenGLCanvasRenderer
import com.artflow.studio.domain.model.brush.BrushParams
import com.artflow.studio.domain.model.brush.Stroke
import com.artflow.studio.domain.model.brush.StrokePoint
import com.artflow.studio.domain.model.layer.BlendMode
import com.artflow.studio.domain.model.layer.Layer
import com.artflow.studio.domain.repository.canvas.CanvasInvalidationEvent
import com.artflow.studio.domain.repository.canvas.CanvasRepository
import com.artflow.studio.domain.repository.canvas.CanvasSize
import com.artflow.studio.domain.repository.canvas.CanvasState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Canvas repository: owns the editable layer/stroke model and keeps the GPU renderer,
 * on-disk project files and exported bitmaps in sync with it.
 *
 * Design notes:
 * - The layer stack is an ordered [MutableList]; a layer's index is its position, which
 *   removes a whole class of stale-index bugs.
 * - The stroke list is the source of truth. Rendering, export, thumbnails and persistence all
 *   derive from it, so they cannot drift apart.
 * - Undo/redo uses whole-stack snapshots. Strokes are immutable, so a snapshot only copies
 *   list structure and stays cheap even for large documents.
 */
@Singleton
class CanvasRepositoryImpl @Inject constructor(
    private val renderer: OpenGLCanvasRenderer,
    private val rasterizer: CanvasRasterizer,
    private val storage: ProjectStorage
) : CanvasRepository {

    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val renderMutex = Mutex()

    private var currentCanvasId: Long = 0
    private var canvasWidth = 1920
    private var canvasHeight = 1080
    private var canvasDpi = 72
    private var backgroundColor = Color.WHITE

    // Ordered bottom -> top. Position in this list is the layer index.
    private val layerList = mutableListOf<LayerData>()
    private var activeLayerId = 0L
    private var nextLayerId = 1L

    private var strokeColor: Int = Color.BLACK

    private val activeStrokes = mutableMapOf<Long, MutableList<StrokePoint>>()
    private val strokeBrushParams = mutableMapOf<Long, BrushParams>()
    private val strokeLayerIds = mutableMapOf<Long, Long>()
    private var nextStrokeId = 1L

    private val invalidationFlow = MutableSharedFlow<CanvasInvalidationEvent>(replay = 0)

    private val undoStack = ArrayDeque<Snapshot>()
    private val redoStack = ArrayDeque<Snapshot>()

    private var dirty = false

    private data class Snapshot(val layers: List<LayerData>, val activeLayerId: Long)

    // -----------------------------------------------------------------------------------------
    // Canvas lifecycle
    // -----------------------------------------------------------------------------------------

    override suspend fun createCanvas(width: Int, height: Int, dpi: Int): Long =
        renderMutex.withLock {
            currentCanvasId = System.nanoTime()
            canvasWidth = width.coerceAtLeast(1)
            canvasHeight = height.coerceAtLeast(1)
            canvasDpi = dpi

            layerList.clear()
            nextLayerId = 1
            layerList.add(LayerData(id = nextLayerId++, name = "Background"))
            activeLayerId = layerList.first().id

            activeStrokes.clear()
            strokeBrushParams.clear()
            strokeLayerIds.clear()
            undoStack.clear()
            redoStack.clear()
            dirty = false

            renderer.setCanvasSize(canvasWidth, canvasHeight, canvasDpi)
            renderer.setBackgroundArgb(backgroundColor)
            syncRenderer()
            emitFull()

            Timber.d("Canvas created: ${canvasWidth}x$canvasHeight @${canvasDpi}dpi")
            currentCanvasId
        }

    override suspend fun loadCanvas(projectId: Long): CanvasState? = renderMutex.withLock {
        val document = storage.loadDocument(projectId)
        if (document == null) {
            Timber.w("No saved document for project $projectId")
            return@withLock null
        }

        canvasWidth = document.width.coerceAtLeast(1)
        canvasHeight = document.height.coerceAtLeast(1)
        canvasDpi = document.dpi
        backgroundColor = document.backgroundColor
        currentCanvasId = projectId
        nextLayerId = maxOf(document.nextLayerId, (document.layers.maxOfOrNull { it.id } ?: 0L) + 1)

        layerList.clear()
        document.layers.sortedBy { it.index }.forEach { layer ->
            layerList.add(
                LayerData(
                    id = layer.id,
                    name = layer.name,
                    isVisible = layer.isVisible,
                    opacity = layer.opacity,
                    isLocked = layer.isLocked,
                    blendMode = layer.blendMode,
                    strokes = layer.strokes.toMutableList(),
                    isAlphaLocked = layer.isAlphaLocked,
                    isClippingMask = layer.isClippingMask
                )
            )
        }
        if (layerList.isEmpty()) {
            layerList.add(LayerData(id = nextLayerId++, name = "Background"))
        }
        activeLayerId = document.activeLayerId
            .takeIf { id -> layerList.any { it.id == id } }
            ?: layerList.last().id

        activeStrokes.clear()
        strokeBrushParams.clear()
        strokeLayerIds.clear()
        undoStack.clear()
        redoStack.clear()
        dirty = false

        renderer.setCanvasSize(canvasWidth, canvasHeight, canvasDpi)
        renderer.setBackgroundArgb(backgroundColor)
        syncRenderer()
        emitFull()

        Timber.d("Loaded project $projectId (${layerList.size} layers)")
        CanvasState(
            id = projectId,
            width = canvasWidth,
            height = canvasHeight,
            dpi = canvasDpi,
            backgroundColor = backgroundColor,
            layerIds = layerList.map { it.id },
            activeLayerId = activeLayerId,
            zoom = 1f,
            offsetX = 0f,
            offsetY = 0f,
            rotation = 0f
        )
    }

    /**
     * Persist the project: JSON document (full editable fidelity) + flattened PNG + thumbnail.
     * @return absolute path of the written thumbnail, or null when there is nothing to save.
     */
    override suspend fun saveCanvas(projectId: Long): String? {
        val layers = renderMutex.withLock { currentLayersDomain() }
        if (layers.isEmpty()) return null

        val document = CanvasDocument(
            width = canvasWidth,
            height = canvasHeight,
            dpi = canvasDpi,
            backgroundColor = backgroundColor,
            activeLayerId = activeLayerId,
            nextLayerId = nextLayerId,
            layers = layers
        )
        storage.saveDocument(projectId, document)

        val composite = rasterizer.rasterizeLayers(
            layers = layers,
            width = canvasWidth,
            height = canvasHeight,
            backgroundColor = backgroundColor
        )
        return try {
            storage.saveFlattened(projectId, composite)
            val thumbnail = rasterizer.createThumbnail(composite)
            val path = storage.saveThumbnail(projectId, thumbnail)
            if (thumbnail !== composite) thumbnail.recycle()
            renderMutex.withLock { dirty = false }
            Timber.d("Project $projectId saved (${layers.size} layers)")
            path
        } finally {
            composite.recycle()
        }
    }

    override suspend fun getCanvasBitmap(): ByteArray? {
        val layers = renderMutex.withLock { currentLayersDomain() }
        if (layers.isEmpty()) return null

        val composite = rasterizer.rasterizeLayers(
            layers = layers,
            width = canvasWidth,
            height = canvasHeight,
            backgroundColor = backgroundColor
        )
        return try {
            ByteArrayOutputStream().use { out ->
                composite.compress(Bitmap.CompressFormat.PNG, 100, out)
                out.toByteArray()
            }
        } finally {
            composite.recycle()
        }
    }

    /**
     * Flatten the current document. Callers own the returned bitmap and must recycle it.
     */
    suspend fun rasterizeComposite(includeHidden: Boolean = false): Bitmap? {
        val layers = renderMutex.withLock { currentLayersDomain() }
        if (layers.isEmpty()) return null
        return rasterizer.rasterizeLayers(
            layers = layers,
            width = canvasWidth,
            height = canvasHeight,
            backgroundColor = backgroundColor,
            includeHidden = includeHidden
        )
    }

    override suspend fun clearCanvas(color: Int) {
        renderMutex.withLock {
            pushUndo()
            backgroundColor = color
            layerList.forEach { it.strokes.clear() }
            renderer.setBackgroundArgb(color)
            syncRenderer()
            markDirty()
            emitFull()
        }
    }

    override fun setBackgroundColor(color: Int) {
        backgroundColor = color
        renderer.setBackgroundArgb(color)
        emitFull()
    }

    override fun getCanvasSize(): CanvasSize = CanvasSize(canvasWidth, canvasHeight, canvasDpi)

    override fun observeCanvasInvalidation(): Flow<CanvasInvalidationEvent> = invalidationFlow

    override fun dispose() {
        coroutineScope.cancel()
        layerList.clear()
        activeStrokes.clear()
        strokeBrushParams.clear()
        strokeLayerIds.clear()
        undoStack.clear()
        redoStack.clear()
    }

    /**
     * Set the ink colour used by subsequent strokes. In-flight strokes keep the colour they
     * started with, matching how a real brush behaves.
     */
    fun setStrokeColor(color: Int) {
        strokeColor = color
    }

    fun getStrokeColor(): Int = strokeColor

    /** True when the document has changes that have not been written to disk yet. */
    fun hasUnsavedChanges(): Boolean = dirty

    // -----------------------------------------------------------------------------------------
    // Stroke input
    // -----------------------------------------------------------------------------------------

    override fun beginStroke(
        x: Float,
        y: Float,
        pressure: Float,
        brushParams: BrushParams,
        layerId: Long
    ): Long {
        val strokeId = nextStrokeId++
        val targetLayer = layerById(layerId)
        if (targetLayer != null && !targetLayer.canPaint()) {
            Timber.w("Stroke started on non-paintable layer ${targetLayer.name}")
        }
        activeStrokes[strokeId] = mutableListOf(
            StrokePoint(x = x, y = y, pressure = pressure, color = strokeColor)
        )
        strokeBrushParams[strokeId] = brushParams
        strokeLayerIds[strokeId] = layerId
        return strokeId
    }

    override fun continueStroke(
        strokeId: Long,
        x: Float,
        y: Float,
        pressure: Float,
        tiltX: Float,
        tiltY: Float
    ) {
        val points = activeStrokes[strokeId] ?: return
        points.add(
            StrokePoint(
                x = x,
                y = y,
                pressure = pressure,
                tiltX = tiltX,
                tiltY = tiltY,
                color = strokeColor
            )
        )
    }

    override fun endStroke(strokeId: Long) {
        val points = activeStrokes.remove(strokeId) ?: return
        val brushParams = strokeBrushParams.remove(strokeId) ?: return
        val layerId = strokeLayerIds.remove(strokeId) ?: return

        val layer = layerById(layerId)
        if (layer == null) {
            Timber.w("Dropping stroke for unknown layer $layerId")
            return
        }
        if (!layer.canPaint()) {
            Timber.w("Dropping stroke on non-paintable layer ${layer.name}")
            return
        }

        pushUndo()
        val stroke = Stroke(
            id = strokeId,
            points = points.toList(),
            brushParams = brushParams,
            layerId = layerId,
            color = points.firstOrNull()?.color ?: strokeColor
        )
        layer.strokes.add(stroke)
        renderer.addStroke(stroke)
        markDirty()

        emitAsync(CanvasInvalidationEvent.StrokeCompleted(strokeId))
    }

    override suspend fun renderStroke(stroke: Stroke) = renderMutex.withLock {
        pushUndo()
        layerList.firstOrNull { it.id == stroke.layerId }?.strokes?.add(stroke)
        renderer.addStroke(stroke)
        markDirty()
        emitFull()
    }

    // -----------------------------------------------------------------------------------------
    // Undo / redo
    // -----------------------------------------------------------------------------------------

    override val canUndo: Boolean get() = undoStack.isNotEmpty()

    override val canRedo: Boolean get() = redoStack.isNotEmpty()

    override fun undo(): Boolean {
        val snapshot = undoStack.removeLastOrNull() ?: return false
        redoStack.addLast(snapshot())
        restore(snapshot)
        markDirty()
        emitFull()
        return true
    }

    override fun redo(): Boolean {
        val snapshot = redoStack.removeLastOrNull() ?: return false
        undoStack.addLast(snapshot())
        restore(snapshot)
        markDirty()
        emitFull()
        return true
    }

    private fun pushUndo() {
        undoStack.addLast(snapshot())
        while (undoStack.size > MAX_HISTORY) undoStack.removeFirst()
        redoStack.clear()
    }

    private fun snapshot(): Snapshot = Snapshot(
        layers = layerList.map { it.copy(strokes = it.strokes.toMutableList()) },
        activeLayerId = activeLayerId
    )

    private fun restore(snapshot: Snapshot) {
        layerList.clear()
        layerList.addAll(snapshot.layers)
        activeLayerId = snapshot.activeLayerId
        syncRenderer()
    }

    // -----------------------------------------------------------------------------------------
    // Layer operations
    // -----------------------------------------------------------------------------------------

    override suspend fun addLayer(name: String?, index: Int?, opacity: Float): Layer =
        renderMutex.withLock {
            pushUndo()
            val layerId = nextLayerId++
            val layer = LayerData(
                id = layerId,
                name = name ?: "Layer ${layerList.size}",
                opacity = opacity.coerceIn(0f, 1f)
            )

            val activeIndex = layerList.indexOfFirst { it.id == activeLayerId }
            val insertAt = (index ?: (activeIndex + 1)).coerceIn(0, layerList.size)
            layerList.add(insertAt, layer)

            activeLayerId = layerId
            markDirty()
            emitFull()
            layer.toDomain(index = insertAt)
        }

    override suspend fun removeLayer(layerId: Long): Boolean = renderMutex.withLock {
        if (layerList.size <= 1) return@withLock false
        val position = layerList.indexOfFirst { it.id == layerId }
        if (position == -1) return@withLock false

        pushUndo()
        layerList.removeAt(position)
        if (activeLayerId == layerId) {
            activeLayerId = layerList[position.coerceAtMost(layerList.lastIndex)].id
        }
        syncRenderer()
        markDirty()
        emitFull()
        true
    }

    override suspend fun reorderLayer(layerId: Long, newIndex: Int): Boolean =
        renderMutex.withLock {
            val from = layerList.indexOfFirst { it.id == layerId }
            if (from == -1) return@withLock false
            val to = newIndex.coerceIn(0, layerList.lastIndex)
            if (from == to) return@withLock true

            pushUndo()
            val layer = layerList.removeAt(from)
            layerList.add(to, layer)
            syncRenderer()
            markDirty()
            emitFull()
            true
        }

    override suspend fun duplicateLayer(layerId: Long): Long? = renderMutex.withLock {
        val position = layerList.indexOfFirst { it.id == layerId }
        if (position == -1) return@withLock null

        pushUndo()
        val source = layerList[position]
        val newId = nextLayerId++
        val duplicate = source.copy(
            id = newId,
            name = "${source.name} copy",
            strokes = source.strokes.map { it.copy(id = nextStrokeId++) }.toMutableList()
        )
        layerList.add(position + 1, duplicate)
        activeLayerId = newId
        syncRenderer()
        markDirty()
        emitFull()
        newId
    }

    override suspend fun mergeLayers(sourceLayerId: Long, targetLayerId: Long): Boolean =
        renderMutex.withLock {
            if (sourceLayerId == targetLayerId) return@withLock false
            val sourceIndex = layerList.indexOfFirst { it.id == sourceLayerId }
            val targetIndex = layerList.indexOfFirst { it.id == targetLayerId }
            if (sourceIndex == -1 || targetIndex == -1) return@withLock false

            pushUndo()
            layerList[targetIndex].strokes.addAll(layerList[sourceIndex].strokes)
            layerList.removeAt(sourceIndex)
            activeLayerId = targetLayerId
            syncRenderer()
            markDirty()
            emitFull()
            true
        }

    override suspend fun mergeVisibleLayers(keepOriginals: Boolean): Long? =
        renderMutex.withLock {
            val visibleCount = layerList.count { it.isVisible }
            if (visibleCount < 2) return@withLock null

            pushUndo()
            val insertionIndex = layerList.indexOfFirst { it.isVisible }.coerceAtLeast(0)
            val merged = LayerData(
                id = nextLayerId++,
                name = "Merged",
                strokes = layerList.filter { it.isVisible }
                    .flatMap { it.strokes }
                    .toMutableList()
            )

            if (!keepOriginals) {
                layerList.removeAll { it.isVisible }
            }
            layerList.add(insertionIndex.coerceIn(0, layerList.size), merged)

            activeLayerId = merged.id
            syncRenderer()
            markDirty()
            emitFull()
            merged.id
        }

    override suspend fun mergeLayerDown(layerId: Long): Boolean = renderMutex.withLock {
        val index = layerList.indexOfFirst { it.id == layerId }
        if (index <= 0) return@withLock false

        pushUndo()
        layerList[index - 1].strokes.addAll(layerList[index].strokes)
        val lowerId = layerList[index - 1].id
        layerList.removeAt(index)
        activeLayerId = lowerId
        syncRenderer()
        markDirty()
        emitFull()
        true
    }

    override suspend fun setLayerVisibility(layerId: Long, isVisible: Boolean?): Boolean =
        renderMutex.withLock {
            val layer = layerById(layerId) ?: return@withLock false
            pushUndo()
            layer.isVisible = isVisible ?: !layer.isVisible
            syncRenderer()
            markDirty()
            emitFull()
            true
        }

    override suspend fun setLayerOpacity(layerId: Long, opacity: Float): Boolean =
        renderMutex.withLock {
            val layer = layerById(layerId) ?: return@withLock false
            val clamped = opacity.coerceIn(0f, 1f)
            if (layer.opacity == clamped) return@withLock true
            pushUndo()
            layer.opacity = clamped
            markDirty()
            emitFull()
            true
        }

    override suspend fun setLayerName(layerId: Long, newName: String): Boolean =
        renderMutex.withLock {
            val layer = layerById(layerId) ?: return@withLock false
            val trimmed = newName.trim()
            if (trimmed.isEmpty() || trimmed == layer.name) return@withLock false
            pushUndo()
            layer.name = trimmed
            markDirty()
            emitFull()
            true
        }

    override suspend fun setLayerLock(layerId: Long, isLocked: Boolean): Boolean =
        renderMutex.withLock {
            val layer = layerById(layerId) ?: return@withLock false
            pushUndo()
            layer.isLocked = isLocked
            markDirty()
            emitFull()
            true
        }

    override suspend fun setLayerBlendMode(layerId: Long, blendMode: BlendMode): Boolean =
        renderMutex.withLock {
            val layer = layerById(layerId) ?: return@withLock false
            pushUndo()
            layer.blendMode = blendMode
            markDirty()
            emitFull()
            true
        }

    override suspend fun setLayerAlphaLock(layerId: Long, isLocked: Boolean?): Boolean =
        renderMutex.withLock {
            val layer = layerById(layerId) ?: return@withLock false
            pushUndo()
            layer.isAlphaLocked = isLocked ?: !layer.isAlphaLocked
            markDirty()
            emitFull()
            true
        }

    override suspend fun setLayerClippingMask(layerId: Long, isClipping: Boolean?): Boolean =
        renderMutex.withLock {
            val layer = layerById(layerId) ?: return@withLock false
            pushUndo()
            layer.isClippingMask = isClipping ?: !layer.isClippingMask
            markDirty()
            emitFull()
            true
        }

    override fun getAllLayers(): List<Layer> = currentLayersDomain()

    override fun getActiveLayer(): Layer? {
        val position = layerList.indexOfFirst { it.id == activeLayerId }
        if (position == -1) return null
        return layerList[position].toDomain(position)
    }

    override fun setActiveLayer(layerId: Long): Boolean {
        if (layerList.none { it.id == layerId }) return false
        activeLayerId = layerId
        return true
    }

    fun getActiveLayerId(): Long = activeLayerId

    // -----------------------------------------------------------------------------------------
    // Internals
    // -----------------------------------------------------------------------------------------

    private fun layerById(layerId: Long): LayerData? = layerList.firstOrNull { it.id == layerId }

    private fun currentLayersDomain(): List<Layer> =
        layerList.mapIndexed { index, data -> data.toDomain(index) }

    /** Push the full stroke set to the GPU renderer so the view matches the model exactly. */
    private fun syncRenderer() {
        renderer.clearAllStrokes()
        layerList.filter { it.isVisible }.forEach { layer ->
            layer.strokes.forEach { renderer.addStroke(it) }
        }
    }

    private fun markDirty() {
        dirty = true
    }

    private fun emitFull() {
        coroutineScope.launch { invalidationFlow.emit(CanvasInvalidationEvent.Full) }
    }

    private fun emitAsync(event: CanvasInvalidationEvent) {
        coroutineScope.launch { invalidationFlow.emit(event) }
    }

    /**
     * In-memory layer. Mutable so property setters stay O(1); every failure mode routes through
     * the repository so undo snapshots stay consistent.
     */
    private data class LayerData(
        val id: Long,
        var name: String,
        var isVisible: Boolean = true,
        var opacity: Float = 1.0f,
        var isLocked: Boolean = false,
        var blendMode: BlendMode = BlendMode.NORMAL,
        val strokes: MutableList<Stroke> = mutableListOf(),
        var isAlphaLocked: Boolean = false,
        var isClippingMask: Boolean = false
    ) {
        fun canPaint(): Boolean = isVisible && !isLocked

        fun toDomain(index: Int): Layer = Layer(
            id = id,
            name = name,
            index = index,
            isVisible = isVisible,
            opacity = opacity,
            isLocked = isLocked,
            blendMode = blendMode,
            strokes = strokes.toList(),
            isAlphaLocked = isAlphaLocked,
            isClippingMask = isClippingMask
        )
    }

    companion object {
        private const val MAX_HISTORY = 40
    }
}
