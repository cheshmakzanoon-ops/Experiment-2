package com.artflow.studio.data.repository.canvas

import com.artflow.studio.core.animation.AnimationTimeline
import com.artflow.studio.core.canvas.CanvasOperations
import com.artflow.studio.core.pixels.AdjustmentProcessor
import com.artflow.studio.core.pixels.IntBounds
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.core.pixels.SelectionMask
import com.artflow.studio.core.pixels.Stamping
import com.artflow.studio.core.render.Compositor
import com.artflow.studio.core.symmetry.SymmetryEngine
import com.artflow.studio.core.render.StrokeRasterizer
import com.artflow.studio.data.local.CanvasDocument
import com.artflow.studio.data.local.ProjectStorage
import com.artflow.studio.data.renderer.BitmapPixelBridge
import com.artflow.studio.domain.model.animation.AnimationFrame
import com.artflow.studio.domain.model.animation.AnimationSettings
import com.artflow.studio.domain.model.brush.BrushParams
import com.artflow.studio.domain.model.brush.Stroke
import com.artflow.studio.domain.model.brush.StrokePoint
import com.artflow.studio.domain.model.layer.AdjustmentType
import com.artflow.studio.domain.model.layer.BlendMode
import com.artflow.studio.domain.model.layer.FilterType
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Owns the editable document.
 *
 * Design decisions worth knowing:
 * - **One source of truth per concern.** The layer stack is an ordered list (so a layer's index is
 *   its position and cannot go stale); strokes are immutable; layer pixels live in [PixelBuffer]s
 *   that the pure tools operate on. The compositor, the saved file and every export read from the
 *   same model, which is why the app is WYSIWYG.
 * - **Copy-on-write undo.** A snapshot records the *current* pixel buffers by reference; before a
 *   tool mutates pixels it clones the buffer, so the snapshot stays valid without re-encoding
 *   anything. History is bounded by both step count and estimated pixel memory, because a 4K layer
 *   is 32 MB and an unbounded stack would exhaust the heap.
 * - **Pixel buffers are written to disk on save/autosave**, not on every edit, so painting never
 *   blocks on I/O.
 */
@Singleton
class CanvasRepositoryImpl @Inject constructor(
    private val storage: ProjectStorage
) : CanvasRepository {

    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()

    private val compositor = Compositor(StrokeRasterizer())

    // --- Document state -----------------------------------------------------------------------

    private var currentProjectId: Long = 0
    private var canvasWidth = 1920
    private var canvasHeight = 1080
    private var canvasDpi = 72
    private var backgroundColor = 0xFFFFFFFF.toInt()

    private var frameList: MutableList<FrameData> = mutableListOf()
    private var activeFrame = 0
    private var animationSettings = AnimationSettings()
    private var nextLayerId = 1L
    private var nextFrameId = 1L
    private var nextStrokeId = 1L

    private val activeStrokes = mutableMapOf<Long, MutableList<StrokePoint>>()
    private val strokeBrushParams = mutableMapOf<Long, BrushParams>()
    private val strokeLayerIds = mutableMapOf<Long, Long>()
    private val strokeErasers = mutableMapOf<Long, Boolean>()

    private var strokeColor: Int = 0xFF000000.toInt()
    private var symmetrySettings = SymmetryEngine.Settings()
    private var activeSelection: SelectionMask? = null

    private val _timeline = MutableStateFlow(AnimationTimeline.State())
    override val timeline: StateFlow<AnimationTimeline.State> = _timeline.asStateFlow()

    private val invalidationFlow = MutableSharedFlow<CanvasInvalidationEvent>(extraBufferCapacity = 64)

    private val undoStack = ArrayDeque<Snapshot>()
    private val redoStack = ArrayDeque<Snapshot>()

    private var dirty = false
    /** Layer ids whose pixels changed since the last write to disk. */
    private val dirtyRasters = mutableSetOf<Long>()

    private var rasterEditToken = 0L

    init {
        // A blank frame keeps every accessor total: the repository is usable before a project is
        // created, which removes a whole class of null handling from the ViewModel.
        frameList = mutableListOf(
            FrameData(
                id = nextFrameId++,
                name = "Frame 1",
                durationMs = animationSettings.frameDurationMs,
                layers = mutableListOf(backgroundLayer())
            )
        )
        syncTimeline()
    }

    // -----------------------------------------------------------------------------------------
    // Canvas lifecycle
    // -----------------------------------------------------------------------------------------

    override suspend fun createCanvas(width: Int, height: Int, dpi: Int): Long = mutex.withLock {
        createCanvasLocked(width, height, dpi)
    }

    private fun createCanvasLocked(width: Int, height: Int, dpi: Int): Long {
        canvasWidth = width.coerceIn(1, CanvasOperations.MAX_DIMENSION)
        canvasHeight = height.coerceIn(1, CanvasOperations.MAX_DIMENSION)
        canvasDpi = dpi
        nextLayerId = 1
        nextFrameId = 1
        nextStrokeId = 1

        frameList = mutableListOf(
            FrameData(
                id = nextFrameId++,
                name = "Frame 1",
                durationMs = animationSettings.frameDurationMs,
                layers = mutableListOf(backgroundLayer())
            )
        )
        activeFrame = 0
        activeSelection = null
        activeStrokes.clear()
        strokeErasers.clear()
        undoStack.clear()
        redoStack.clear()
        dirtyRasters.clear()
        dirty = false
        syncTimeline()
        emit(CanvasInvalidationEvent.Full)
        Timber.d("Created canvas ${canvasWidth}x$canvasHeight @${canvasDpi}dpi")
        // A freshly created canvas gets a synthetic id; callers that have a real project id
        // overwrite it via `loadOrCreate`.
        currentProjectId = System.currentTimeMillis()
        return currentProjectId
    }

    /** A layer filled with the canvas background colour, so artists have something to paint on. */
    private fun backgroundLayer(): LayerData {
        val layer = LayerData(id = nextLayerId++, name = "Background")
        layer.raster = PixelBuffer.filled(canvasWidth, canvasHeight, backgroundColor)
        dirtyRasters += layer.id
        return layer
    }

    override suspend fun loadOrCreate(projectId: Long, width: Int, height: Int, dpi: Int): CanvasState? {
        val loaded = loadCanvas(projectId)
        if (loaded != null) return loaded
        createCanvas(width, height, dpi)
        currentProjectId = projectId
        return stateSnapshot()
    }

    override suspend fun loadCanvas(projectId: Long): CanvasState? = mutex.withLock {
        val document = storage.loadDocument(projectId) ?: return@withLock null
        applyDocument(projectId, document)
        Timber.d("Loaded project $projectId (${frameList.size} frames, ${currentLayers().size} layers)")
        stateSnapshot()
    }

    override suspend fun hasRecovery(projectId: Long): Boolean = storage.hasUnsavedRecovery(projectId)

    override suspend fun recoverAutosave(projectId: Long): CanvasState? = mutex.withLock {
        val document = storage.loadAutosave(projectId) ?: return@withLock null
        applyDocument(projectId, document)
        dirty = true
        Timber.d("Recovered autosave for project $projectId")
        stateSnapshot()
    }

    private suspend fun applyDocument(projectId: Long, document: CanvasDocument) {
        currentProjectId = projectId
        canvasWidth = document.width.coerceAtLeast(1)
        canvasHeight = document.height.coerceAtLeast(1)
        canvasDpi = document.dpi
        backgroundColor = document.backgroundColor
        animationSettings = document.animation

        val frames = document.resolvedFrames()
        nextLayerId = max(
            document.nextLayerId,
            frames.flatMap { frame -> frame.layers }.maxOfOrNull { it.id }?.plus(1) ?: 1L
        )
        nextFrameId = (frames.maxOfOrNull { it.id } ?: 0L) + 1
        nextStrokeId = frames.flatMap { it.layers }.flatMap { it.strokes }
            .maxOfOrNull { it.id }?.plus(1) ?: 1L

        frameList = frames.map { frame ->
            FrameData(
                id = frame.id,
                name = frame.name,
                durationMs = frame.durationMs,
                isKeyframe = frame.isKeyframe,
                layers = frame.layers.sortedBy { it.index }
                    .map { layer -> toLayerData(projectId, layer) }
                    .toMutableList()
            )
        }.toMutableList()

        if (frameList.isEmpty()) {
            frameList = mutableListOf(
                FrameData(nextFrameId++, "Frame 1", animationSettings.frameDurationMs, mutableListOf(backgroundLayer()))
            )
        }
        activeFrame = document.resolvedActiveFrameIndex().coerceIn(0, frameList.lastIndex)
        // The document records which layer was selected; apply it to the frame that is active now.
        frameList[activeFrame].activeLayerId = document.activeLayerId.takeIf { id ->
            frameList[activeFrame].layers.any { it.id == id }
        } ?: frameList[activeFrame].layers.firstOrNull()?.id ?: 0L
        activeSelection = null
        activeStrokes.clear()
        strokeErasers.clear()
        undoStack.clear()
        redoStack.clear()
        dirtyRasters.clear()
        dirty = false
        syncTimeline()
        emit(CanvasInvalidationEvent.Full)
    }

    private suspend fun toLayerData(projectId: Long, layer: Layer): LayerData {
        val data = LayerData(
            id = layer.id,
            name = layer.name,
            isVisible = layer.isVisible,
            opacity = layer.opacity,
            isLocked = layer.isLocked,
            blendMode = layer.blendMode,
            strokes = layer.strokes.toMutableList(),
            isAlphaLocked = layer.isAlphaLocked,
            isClippingMask = layer.isClippingMask,
            isReference = layer.isReference,
            linkGroupId = layer.linkGroupId,
            maskEnabled = layer.maskEnabled,
            maskInverted = layer.maskInverted,
            maskDensity = layer.maskDensity,
            maskFeather = layer.maskFeather,
            adjustmentType = layer.adjustmentType,
            adjustmentParams = layer.adjustmentParameters.toMutableMap(),
            filterType = layer.filterType,
            filterAmount = layer.filterAmount,
            smartObjectId = layer.smartObjectId,
            isInternal = layer.isInternal
        )
        data.raster = loadRaster(projectId, layer.rasterFile)
        data.mask = loadRaster(projectId, layer.maskFile)
        data.rasterFile = layer.rasterFile
        data.maskFile = layer.maskFile
        return data
    }

    private suspend fun loadRaster(projectId: Long, path: String?): PixelBuffer? {
        val bytes = storage.readRaster(projectId, path) ?: return null
        return withContext(Dispatchers.Default) {
            runCatching { BitmapPixelBridge.fromEncodedBytes(bytes) }
                .onFailure { Timber.w(it, "Could not decode layer pixels at $path") }
                .getOrNull()
        }
    }

    override suspend fun saveCanvas(projectId: Long): String? {
        val snapshot = mutex.withLock {
            if (frameList.isEmpty()) return null
            canvasSnapshot()
        }
        writeRasters(projectId, force = true)
        storage.saveDocument(projectId, snapshot)
        val composite = compositeBuffer() ?: return null

        val pngBytes = withContext(Dispatchers.Default) { BitmapPixelBridge.toPngBytes(composite) }
        storage.saveFlattened(projectId, pngBytes)

        val thumbnail = withContext(Dispatchers.Default) {
            val scaled = scaleDown(composite, 512)
            BitmapPixelBridge.toPngBytes(scaled)
        }
        val path = storage.saveThumbnail(projectId, thumbnail)
        mutex.withLock { dirty = false }
        Timber.d("Project $projectId saved (${frameList.size} frames)")
        return path
    }

    override suspend fun autosave(projectId: Long) {
        val snapshot = mutex.withLock { if (frameList.isEmpty()) null else canvasSnapshot() } ?: return
        writeRasters(projectId, force = false)
        storage.saveAutosave(projectId, snapshot)
    }

    /** Writes the pixel buffers that changed since the last write. */
    private suspend fun writeRasters(projectId: Long, force: Boolean) {
        val pending: List<Triple<Long, PixelBuffer?, PixelBuffer?>> = mutex.withLock {
            val ids = if (force) currentLayers().map { it.id }.toSet() else dirtyRasters.toSet()
            dirtyRasters.removeAll(ids)
            ids.mapNotNull { id ->
                val layer = allLayers().firstOrNull { it.id == id } ?: return@mapNotNull null
                Triple(id, layer.raster, layer.mask)
            }
        }

        withContext(Dispatchers.Default) {
            pending.forEach { (layerId, raster, mask) ->
                if (raster != null) {
                    val bytes = BitmapPixelBridge.toPngBytes(raster)
                    val path = storage.writeRaster(projectId, layerId, RASTER_VERSION, bytes)
                    mutex.withLock {
                        allLayers().firstOrNull { it.id == layerId }?.rasterFile = path
                    }
                }
                if (mask != null) {
                    val bytes = BitmapPixelBridge.toPngBytes(mask)
                    val path = storage.writeAuxiliaryImage(projectId, layerId, MASK_PREFIX, RASTER_VERSION, bytes)
                    mutex.withLock {
                        allLayers().firstOrNull { it.id == layerId }?.maskFile = path
                    }
                }
            }
        }
    }

    override fun hasUnsavedChanges(): Boolean = dirty

    override val undoDepth: Int get() = undoStack.size

    override val redoDepth: Int get() = redoStack.size

    override fun projectId(): Long = currentProjectId

    // -----------------------------------------------------------------------------------------
    // Strokes
    // -----------------------------------------------------------------------------------------

    override fun beginStroke(
        x: Float,
        y: Float,
        pressure: Float,
        brushParams: BrushParams,
        layerId: Long,
        isEraser: Boolean
    ): Long {
        val strokeId = nextStrokeId++
        val layer = layerById(layerId)
        if (layer != null && !layer.canPaint()) {
            Timber.w("Stroke started on non-paintable layer ${layer.name}")
        }
        activeStrokes[strokeId] = mutableListOf(
            StrokePoint(x = x, y = y, pressure = pressure, color = strokeColor)
        )
        strokeBrushParams[strokeId] = brushParams
        strokeLayerIds[strokeId] = layerId
        strokeErasers[strokeId] = isEraser
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
        activeStrokes[strokeId]?.add(
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
        val isEraser = strokeErasers.remove(strokeId) ?: false

        val layer = layerById(layerId) ?: run {
            Timber.w("Dropping stroke for unknown layer $layerId")
            return
        }
        if (!layer.canPaint()) {
            Timber.w("Dropping stroke on non-paintable layer ${layer.name}")
            return
        }

        val stroke = Stroke(
            id = strokeId,
            points = points.toList(),
            brushParams = brushParams,
            layerId = layerId,
            color = points.firstOrNull()?.color ?: strokeColor,
            isEraser = isEraser
        )
        pushUndo()

        // Strokes are baked into the layer's pixel buffer as soon as they finish.
        //
        // Replaying the stroke list on every composite would make compositing O(strokes) and the
        // editor would get slower with every brush stroke; baking makes a composite a plain layer
        // blend, and the symmetry copies are baked in the same pass so save/export agree with the
        // screen. Vector strokes remain supported for imported documents (the compositor still
        // replays `layer.strokes` when a layer has them), they are just not how painting works.
        val base = layer.raster?.copy() ?: PixelBuffer(canvasWidth, canvasHeight)
        val rasterizer = strokeRasterizer()
        SymmetryEngine.mirrorStroke(stroke, canvasWidth, canvasHeight, symmetrySettings)
            .forEach { copy ->
                rasterizer.draw(
                    target = base,
                    stroke = copy,
                    alphaLock = layer.isAlphaLocked,
                    mask = activeSelection
                )
            }
        layer.raster = base
        layer.rasterFile = null
        dirtyRasters += layer.id
        dirty = true
        emitAsync(CanvasInvalidationEvent.Full)
    }

    override fun setSymmetry(settings: SymmetryEngine.Settings) {
        symmetrySettings = SymmetryEngine.sanitize(settings)
    }

    override fun requestPreviewRefresh() {
        emitAsync(CanvasInvalidationEvent.Full)
    }

    override fun activeStroke(strokeId: Long): Stroke? {
        val points = activeStrokes[strokeId] ?: return null
        val params = strokeBrushParams[strokeId] ?: return null
        val layerId = strokeLayerIds[strokeId] ?: return null
        return Stroke(
            id = strokeId,
            points = points.toList(),
            brushParams = params,
            layerId = layerId,
            color = points.firstOrNull()?.color ?: strokeColor,
            isEraser = strokeErasers[strokeId] ?: false
        )
    }

    override suspend fun replaceLayerStrokes(layerId: Long, strokes: List<Stroke>): Boolean = mutex.withLock {
        val layer = layerById(layerId) ?: return@withLock false
        pushUndo()
        layer.strokes.clear()
        layer.strokes.addAll(strokes)
        dirty = true
        emit(CanvasInvalidationEvent.Full)
        true
    }

    // -----------------------------------------------------------------------------------------
    // Pixel editing
    // -----------------------------------------------------------------------------------------

    override suspend fun layerPixels(layerId: Long): PixelBuffer? = mutex.withLock {
        val layer = layerById(layerId) ?: return@withLock null
        layer.raster
    }

    override suspend fun beginRasterEdit(layerId: Long): CanvasRepository.RasterEditSession? = mutex.withLock {
        val layer = layerById(layerId) ?: return@withLock null
        pushUndo()
        // Copy-on-write: the snapshot keeps the previous buffer, the tool works on the clone.
        val buffer = layer.raster?.copy() ?: PixelBuffer(canvasWidth, canvasHeight)
        layer.raster = buffer
        CanvasRepository.RasterEditSession(layerId, buffer, ++rasterEditToken)
    }

    override suspend fun commitRasterEdit(
        session: CanvasRepository.RasterEditSession,
        description: String
    ): Boolean {
        mutex.withLock {
            val layer = layerById(session.layerId) ?: return false
            dirtyRasters += layer.id
            dirty = true
        }
        emitAsync(CanvasInvalidationEvent.Full)
        Timber.d("Committed pixel edit ($description) on layer ${session.layerId}")
        return true
    }

    override suspend fun cancelRasterEdit(session: CanvasRepository.RasterEditSession) {
        mutex.withLock {
            val previous = undoStack.lastOrNull() ?: return@withLock
            val restored = previous.frames.getOrNull(previous.activeFrame)
                ?.layers?.firstOrNull { it.id == session.layerId }
            val layer = layerById(session.layerId) ?: return@withLock
            layer.raster = restored?.raster
            undoStack.removeLast()
            emit(CanvasInvalidationEvent.Full)
        }
    }

    override suspend fun applyRasterEdit(
        layerId: Long,
        description: String,
        edit: (PixelBuffer) -> Unit
    ): Boolean {
        val session = beginRasterEdit(layerId) ?: return false
        edit(session.buffer)
        return commitRasterEdit(session, description)
    }

    override suspend fun setLayerPixels(layerId: Long, buffer: PixelBuffer, description: String): Boolean =
        mutex.withLock {
            val layer = layerById(layerId) ?: return@withLock false
            pushUndo()
            layer.raster = buffer
            dirtyRasters += layer.id
            dirty = true
            emit(CanvasInvalidationEvent.Full)
            Timber.d("Set pixels for layer ${layer.name} ($description)")
            true
        }

    override fun setStrokeColor(color: Int) {
        strokeColor = color
    }

    override fun getStrokeColor(): Int = strokeColor

    // -----------------------------------------------------------------------------------------
    // Selection
    // -----------------------------------------------------------------------------------------

    override fun selection(): SelectionMask? = activeSelection

    override fun setSelection(mask: SelectionMask?) {
        activeSelection = mask?.takeIf { it.isActive() }
    }

    override fun clearSelection() {
        activeSelection = null
    }

    // -----------------------------------------------------------------------------------------
    // Layers
    // -----------------------------------------------------------------------------------------

    override suspend fun addLayer(name: String?, index: Int?, opacity: Float): Layer = mutex.withLock {
        pushUndo()
        val layerId = nextLayerId++
        val layers = currentLayers()
        val layer = LayerData(
            id = layerId,
            name = name ?: "Layer ${layers.size}",
            opacity = opacity.coerceIn(0f, 1f)
        )
        val activeIndex = layers.indexOfFirst { it.id == activeLayerId() }
        val insertAt = (index ?: (activeIndex + 1)).coerceIn(0, layers.size)
        layers.add(insertAt, layer)
        setActiveLayerId(layerId)
        dirty = true
        emit(CanvasInvalidationEvent.LayersChanged)
        layer.toDomain(insertAt)
    }

    override suspend fun removeLayer(layerId: Long): Boolean = mutex.withLock {
        val layers = currentLayers()
        if (layers.size <= 1) return@withLock false
        val position = layers.indexOfFirst { it.id == layerId }
        if (position == -1) return@withLock false

        pushUndo()
        layers.removeAt(position)
        if (activeLayerId() == layerId) {
            setActiveLayerId(layers[position.coerceAtMost(layers.lastIndex)].id)
        }
        dirty = true
        emit(CanvasInvalidationEvent.LayersChanged)
        true
    }

    override suspend fun reorderLayer(layerId: Long, newIndex: Int): Boolean = mutex.withLock {
        val layers = currentLayers()
        val from = layers.indexOfFirst { it.id == layerId }
        if (from == -1) return@withLock false
        val to = newIndex.coerceIn(0, layers.lastIndex)
        if (from == to) return@withLock true
        pushUndo()
        val layer = layers.removeAt(from)
        layers.add(to, layer)
        dirty = true
        emit(CanvasInvalidationEvent.LayersChanged)
        true
    }

    override suspend fun duplicateLayer(layerId: Long): Long? = mutex.withLock {
        val layers = currentLayers()
        val position = layers.indexOfFirst { it.id == layerId }
        if (position == -1) return@withLock null

        pushUndo()
        val source = layers[position]
        val newId = nextLayerId++
        // Copy-on-write means the duplicate can share pixel buffers with the original until one of
        // them is edited, so duplicating a 4K layer is instant.
        val duplicate = source.duplicate(newId, "${source.name} copy") { nextStrokeId++ }
        layers.add(position + 1, duplicate)
        setActiveLayerId(newId)
        dirtyRasters += newId
        markRastersShared()
        dirty = true
        emit(CanvasInvalidationEvent.LayersChanged)
        newId
    }

    override suspend fun mergeLayers(sourceLayerId: Long, targetLayerId: Long): Boolean = mutex.withLock {
        if (sourceLayerId == targetLayerId) return@withLock false
        val layers = currentLayers()
        val sourceIndex = layers.indexOfFirst { it.id == sourceLayerId }
        val targetIndex = layers.indexOfFirst { it.id == targetLayerId }
        if (sourceIndex == -1 || targetIndex == -1) return@withLock false

        pushUndo()
        val merged = rasterizeLayers(listOf(layers[sourceIndex], layers[targetIndex]))
        layers[targetIndex].raster = merged
        layers[targetIndex].strokes.clear()
        layers[targetIndex].rasterFile = null
        layers.removeAt(sourceIndex)
        setActiveLayerId(targetLayerId)
        dirtyRasters += targetLayerId
        dirty = true
        emit(CanvasInvalidationEvent.LayersChanged)
        true
    }

    override suspend fun mergeVisibleLayers(keepOriginals: Boolean): Long? = mutex.withLock {
        val layers = currentLayers()
        val visible = layers.filter { it.isVisible }
        if (visible.size < 2) return@withLock null

        pushUndo()
        val merged = rasterizeLayers(visible)
        val newId = nextLayerId++
        val insertionIndex = layers.indexOfFirst { it.isVisible }.coerceAtLeast(0)
        if (!keepOriginals) layers.removeAll { it.isVisible }
        val mergedLayer = LayerData(id = newId, name = "Merged")
        mergedLayer.raster = merged
        layers.add(insertionIndex.coerceIn(0, layers.size), mergedLayer)
        setActiveLayerId(newId)
        dirtyRasters += newId
        dirty = true
        emit(CanvasInvalidationEvent.LayersChanged)
        newId
    }

    override suspend fun mergeLayerDown(layerId: Long): Boolean = mutex.withLock {
        val layers = currentLayers()
        val index = layers.indexOfFirst { it.id == layerId }
        if (index <= 0) return@withLock false

        pushUndo()
        val upper = layers[index]
        val lower = layers[index - 1]
        lower.raster = rasterizeLayers(listOf(lower, upper))
        lower.strokes.clear()
        lower.rasterFile = null
        layers.removeAt(index)
        setActiveLayerId(lower.id)
        dirtyRasters += lower.id
        dirty = true
        emit(CanvasInvalidationEvent.LayersChanged)
        true
    }

    override suspend fun flattenAllLayers(): Long? = mutex.withLock {
        val layers = currentLayers()
        if (layers.size <= 1) return@withLock null
        pushUndo()
        val flattened = rasterizeLayers(layers)
        val newId = nextLayerId++
        val merged = LayerData(id = newId, name = "Flattened")
        merged.raster = flattened
        layers.clear()
        layers.add(merged)
        setActiveLayerId(newId)
        dirtyRasters += newId
        dirty = true
        emit(CanvasInvalidationEvent.LayersChanged)
        newId
    }

    override suspend fun setLayerVisibility(layerId: Long, isVisible: Boolean?): Boolean = mutex.withLock {
        val layer = layerById(layerId) ?: return@withLock false
        pushUndo()
        layer.isVisible = isVisible ?: !layer.isVisible
        dirty = true
        emit(CanvasInvalidationEvent.LayersChanged)
        true
    }

    override suspend fun setLayerOpacity(layerId: Long, opacity: Float): Boolean = mutex.withLock {
        val layer = layerById(layerId) ?: return@withLock false
        val clamped = opacity.coerceIn(0f, 1f)
        if (layer.opacity == clamped) return@withLock true
        pushUndo()
        layer.opacity = clamped
        dirty = true
        emit(CanvasInvalidationEvent.LayersChanged)
        true
    }

    override suspend fun setLayerName(layerId: Long, newName: String): Boolean = mutex.withLock {
        val layer = layerById(layerId) ?: return@withLock false
        val trimmed = newName.trim()
        if (trimmed.isEmpty() || trimmed == layer.name) return@withLock false
        pushUndo()
        layer.name = trimmed
        dirty = true
        emit(CanvasInvalidationEvent.LayersChanged)
        true
    }

    override suspend fun setLayerLock(layerId: Long, isLocked: Boolean): Boolean = mutex.withLock {
        val layer = layerById(layerId) ?: return@withLock false
        pushUndo()
        layer.isLocked = isLocked
        dirty = true
        emit(CanvasInvalidationEvent.LayersChanged)
        true
    }

    override suspend fun setLayerBlendMode(layerId: Long, blendMode: BlendMode): Boolean = mutex.withLock {
        val layer = layerById(layerId) ?: return@withLock false
        pushUndo()
        layer.blendMode = blendMode
        dirty = true
        emit(CanvasInvalidationEvent.LayersChanged)
        true
    }

    override suspend fun setLayerAlphaLock(layerId: Long, isLocked: Boolean?): Boolean = mutex.withLock {
        val layer = layerById(layerId) ?: return@withLock false
        pushUndo()
        layer.isAlphaLocked = isLocked ?: !layer.isAlphaLocked
        dirty = true
        emit(CanvasInvalidationEvent.LayersChanged)
        true
    }

    override suspend fun setLayerClippingMask(layerId: Long, isClipping: Boolean?): Boolean = mutex.withLock {
        val layer = layerById(layerId) ?: return@withLock false
        pushUndo()
        layer.isClippingMask = isClipping ?: !layer.isClippingMask
        dirty = true
        emit(CanvasInvalidationEvent.LayersChanged)
        true
    }

    override suspend fun setLayerReference(layerId: Long, isReference: Boolean): Boolean = mutex.withLock {
        val layer = layerById(layerId) ?: return@withLock false
        pushUndo()
        layer.isReference = isReference
        dirty = true
        emit(CanvasInvalidationEvent.LayersChanged)
        true
    }

    override suspend fun linkLayers(layerIds: List<Long>): Boolean = mutex.withLock {
        if (layerIds.size < 2) return@withLock false
        pushUndo()
        val groupId = System.nanoTime()
        layerIds.forEach { id -> layerById(id)?.linkGroupId = groupId }
        dirty = true
        emit(CanvasInvalidationEvent.LayersChanged)
        true
    }

    override suspend fun unlinkLayer(layerId: Long): Boolean = mutex.withLock {
        val layer = layerById(layerId) ?: return@withLock false
        pushUndo()
        layer.linkGroupId = null
        dirty = true
        emit(CanvasInvalidationEvent.LayersChanged)
        true
    }

    override fun getAllLayers(): List<Layer> = currentLayers().mapIndexed { index, data -> data.toDomain(index) }

    override fun getActiveLayer(): Layer? {
        val layers = currentLayers()
        val position = layers.indexOfFirst { it.id == activeLayerId() }
        return if (position == -1) null else layers[position].toDomain(position)
    }

    override fun setActiveLayer(layerId: Long): Boolean {
        if (currentLayers().none { it.id == layerId }) return false
        setActiveLayerId(layerId)
        emitAsync(CanvasInvalidationEvent.LayersChanged)
        return true
    }

    override fun getActiveLayerId(): Long = activeLayerId()

    // -----------------------------------------------------------------------------------------
    // Masks
    // -----------------------------------------------------------------------------------------

    override suspend fun addLayerMask(layerId: Long, fromSelection: SelectionMask?): Boolean = mutex.withLock {
        val layer = layerById(layerId) ?: return@withLock false
        pushUndo()
        layer.mask = fromSelection?.toMaskBitmap() ?: PixelBuffer.filled(canvasWidth, canvasHeight, 0xFFFFFFFF.toInt())
        layer.maskEnabled = true
        layer.maskInverted = false
        layer.maskFile = null
        dirtyRasters += layer.id
        dirty = true
        emit(CanvasInvalidationEvent.Full)
        true
    }

    override suspend fun removeLayerMask(layerId: Long): Boolean = mutex.withLock {
        val layer = layerById(layerId) ?: return@withLock false
        if (layer.mask == null && layer.maskFile == null) return@withLock false
        pushUndo()
        layer.mask = null
        layer.maskFile = null
        dirty = true
        emit(CanvasInvalidationEvent.Full)
        true
    }

    override suspend fun invertLayerMask(layerId: Long): Boolean = mutex.withLock {
        val layer = layerById(layerId) ?: return@withLock false
        pushUndo()
        layer.maskInverted = !layer.maskInverted
        dirty = true
        emit(CanvasInvalidationEvent.Full)
        true
    }

    override suspend fun setLayerMaskEnabled(layerId: Long, enabled: Boolean): Boolean = mutex.withLock {
        val layer = layerById(layerId) ?: return@withLock false
        pushUndo()
        layer.maskEnabled = enabled
        dirty = true
        emit(CanvasInvalidationEvent.Full)
        true
    }

    override suspend fun setLayerMaskDensity(layerId: Long, density: Float): Boolean = mutex.withLock {
        val layer = layerById(layerId) ?: return@withLock false
        pushUndo()
        layer.maskDensity = density.coerceIn(0f, 1f)
        dirty = true
        emit(CanvasInvalidationEvent.Full)
        true
    }

    override suspend fun setLayerMaskFeather(layerId: Long, radius: Float): Boolean = mutex.withLock {
        val layer = layerById(layerId) ?: return@withLock false
        pushUndo()
        layer.maskFeather = radius.coerceIn(0f, 64f)
        dirty = true
        emit(CanvasInvalidationEvent.Full)
        true
    }

    override suspend fun paintLayerMask(
        layerId: Long,
        x: Float,
        y: Float,
        radius: Float,
        reveal: Boolean
    ): Boolean = mutex.withLock {
        val layer = layerById(layerId) ?: return@withLock false
        // Copy-on-write: the mask is cloned once per gesture, not once per pointer sample.
        if (!layer.maskOwned) {
            pushUndo()
            layer.mask = layer.mask?.copy()
                ?: PixelBuffer.filled(canvasWidth, canvasHeight, 0xFFFFFFFF.toInt())
            layer.maskOwned = true
            layer.maskFile = null
        }
        val mask = layer.mask ?: return@withLock false
        // White reveals the layer, black hides it.
        val value = if (reveal) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
        Stamping.dab(
            target = mask,
            x = x,
            y = y,
            radius = radius,
            color = value,
            strength = 1f,
            hardness = 0.8f,
            mode = Stamping.Mode.REPLACE
        )
        dirtyRasters += layer.id
        dirty = true
        emit(CanvasInvalidationEvent.Full)
        true
    }

    // -----------------------------------------------------------------------------------------
    // Adjustments and filters
    // -----------------------------------------------------------------------------------------

    override suspend fun addAdjustmentLayer(type: AdjustmentType, index: Int?): Layer? = mutex.withLock {
        pushUndo()
        val layers = currentLayers()
        val layerId = nextLayerId++
        val layer = LayerData(
            id = layerId,
            name = type.displayName,
            adjustmentType = type,
            adjustmentParams = type.defaultParameters.toMutableMap()
        )
        val activeIndex = layers.indexOfFirst { it.id == activeLayerId() }
        val insertAt = (index ?: (activeIndex + 1)).coerceIn(0, layers.size)
        layers.add(insertAt, layer)
        setActiveLayerId(layerId)
        dirty = true
        emit(CanvasInvalidationEvent.Full)
        layer.toDomain(insertAt)
    }

    override suspend fun setAdjustmentParameter(layerId: Long, key: String, value: Float): Boolean =
        mutex.withLock {
            val layer = layerById(layerId) ?: return@withLock false
            val type = layer.adjustmentType ?: return@withLock false
            layer.adjustmentParams[key] = type.validateParameter(key, value)
            dirty = true
            emit(CanvasInvalidationEvent.Full)
            true
        }

    override suspend fun setAdjustmentParameters(layerId: Long, values: Map<String, Float>): Boolean =
        mutex.withLock {
            val layer = layerById(layerId) ?: return@withLock false
            val type = layer.adjustmentType ?: return@withLock false
            values.forEach { (key, value) ->
                layer.adjustmentParams[key] = type.validateParameter(key, value)
            }
            dirty = true
            emit(CanvasInvalidationEvent.Full)
            true
        }

    override suspend fun resetAdjustment(layerId: Long): Boolean = mutex.withLock {
        val layer = layerById(layerId) ?: return@withLock false
        val type = layer.adjustmentType ?: return@withLock false
        pushUndo()
        layer.adjustmentParams = type.defaultParameters.toMutableMap()
        dirty = true
        emit(CanvasInvalidationEvent.Full)
        true
    }

    override suspend fun addFilterLayer(type: FilterType, index: Int?): Layer? = mutex.withLock {
        pushUndo()
        val layers = currentLayers()
        val layerId = nextLayerId++
        val layer = LayerData(
            id = layerId,
            name = type.displayName,
            filterType = type,
            filterAmount = type.defaultAmount
        )
        val activeIndex = layers.indexOfFirst { it.id == activeLayerId() }
        val insertAt = (index ?: (activeIndex + 1)).coerceIn(0, layers.size)
        layers.add(insertAt, layer)
        setActiveLayerId(layerId)
        dirty = true
        emit(CanvasInvalidationEvent.Full)
        layer.toDomain(insertAt)
    }

    override suspend fun setFilterAmount(layerId: Long, amount: Float): Boolean = mutex.withLock {
        val layer = layerById(layerId) ?: return@withLock false
        if (layer.filterType == null) return@withLock false
        layer.filterAmount = amount.coerceIn(0f, 1f)
        dirty = true
        emit(CanvasInvalidationEvent.Full)
        true
    }

    override suspend fun rasterizeFilterLayer(layerId: Long): Boolean = mutex.withLock {
        val layers = currentLayers()
        val index = layers.indexOfFirst { it.id == layerId }
        if (index <= 0) return@withLock false
        val filterLayer = layers[index]
        val type = filterLayer.filterType ?: return@withLock false

        pushUndo()
        val target = layers[index - 1]
        val buffer = target.raster?.copy() ?: PixelBuffer(canvasWidth, canvasHeight)
        compositor.applyFilter(buffer, type, filterLayer.filterAmount)
        // Bake the filter layer's own pixels (if any) on top of the target.
        filterLayer.raster?.let { extra -> buffer.drawInto(extra, 0, 0) }
        target.raster = buffer
        target.rasterFile = null
        layers.removeAt(index)
        setActiveLayerId(target.id)
        dirtyRasters += target.id
        dirty = true
        emit(CanvasInvalidationEvent.LayersChanged)
        true
    }

    // -----------------------------------------------------------------------------------------
    // Canvas operations
    // -----------------------------------------------------------------------------------------

    override suspend fun resizeCanvas(
        width: Int,
        height: Int,
        resample: Boolean,
        anchor: CanvasOperations.Anchor
    ): Boolean = mutex.withLock {
        if (!CanvasOperations.isSizeSafe(width, height)) return@withLock false
        if (width == canvasWidth && height == canvasHeight) return@withLock true
        pushUndo()

        val properties = CanvasOperations.CanvasProperties(canvasWidth, canvasHeight, canvasDpi, backgroundColor)
        allLayers().forEach { layer ->
            val buffer = layer.raster ?: return@forEach
            val result = if (resample) {
                CanvasOperations.resample(buffer, width, height, properties)
            } else {
                CanvasOperations.resizeCanvas(buffer, width, height, anchor, properties, fillColor = 0)
            }
            layer.raster = result.buffer
            layer.rasterFile = null
            dirtyRasters += layer.id
        }
        allLayers().forEach { layer ->
            layer.mask?.let { mask ->
                val result = if (resample) {
                    CanvasOperations.resample(mask, width, height, properties)
                } else {
                    CanvasOperations.resizeCanvas(mask, width, height, anchor, properties, fillColor = 0xFF000000.toInt())
                }
                layer.mask = result.buffer
                layer.maskFile = null
                dirtyRasters += layer.id
            }
        }

        canvasWidth = width
        canvasHeight = height
        activeSelection = null
        dirty = true
        emit(CanvasInvalidationEvent.Full)
        true
    }

    override suspend fun cropCanvas(bounds: IntBounds): Boolean = mutex.withLock {
        val clamped = bounds.intersect(IntBounds(0, 0, canvasWidth - 1, canvasHeight - 1))
        if (clamped.isEmpty) return@withLock false
        if (clamped.width == canvasWidth && clamped.height == canvasHeight) return@withLock true
        pushUndo()
        val properties = CanvasOperations.CanvasProperties(canvasWidth, canvasHeight, canvasDpi, backgroundColor)
        allLayers().forEach { layer ->
            layer.raster?.let { buffer ->
                layer.raster = CanvasOperations.crop(buffer, clamped, properties).buffer
                layer.rasterFile = null
                dirtyRasters += layer.id
            }
            layer.mask?.let { mask ->
                layer.mask = CanvasOperations.crop(mask, clamped, properties).buffer
                layer.maskFile = null
            }
            // Strokes are stored in canvas coordinates, so shift them into the new origin.
            if (layer.strokes.isNotEmpty() && (clamped.left != 0 || clamped.top != 0)) {
                val shifted = layer.strokes.map { stroke ->
                    stroke.copy(
                        points = stroke.points.map { point ->
                            point.copy(x = point.x - clamped.left, y = point.y - clamped.top)
                        }
                    )
                }
                layer.strokes.clear()
                layer.strokes.addAll(shifted)
            }
        }
        canvasWidth = clamped.width
        canvasHeight = clamped.height
        activeSelection = null
        dirty = true
        emit(CanvasInvalidationEvent.Full)
        true
    }

    override suspend fun rotateCanvas(degrees: Int): Boolean = mutex.withLock {
        val normalized = ((degrees % 360) + 360) % 360
        if (normalized == 0) return@withLock true
        pushUndo()
        val properties = CanvasOperations.CanvasProperties(canvasWidth, canvasHeight, canvasDpi, backgroundColor)
        allLayers().forEach { layer ->
            layer.raster?.let { buffer ->
                layer.raster = CanvasOperations.rotate(buffer, normalized, properties).buffer
                layer.rasterFile = null
                dirtyRasters += layer.id
            }
            layer.mask?.let { mask ->
                layer.mask = CanvasOperations.rotate(mask, normalized, properties).buffer
                layer.maskFile = null
            }
            if (layer.strokes.isNotEmpty()) {
                layer.strokes.clear()
            }
        }
        val rotated = CanvasOperations.rotate(PixelBuffer(canvasWidth, canvasHeight), normalized, properties)
        canvasWidth = rotated.buffer.width
        canvasHeight = rotated.buffer.height
        activeSelection = null
        dirty = true
        emit(CanvasInvalidationEvent.Full)
        true
    }

    override suspend fun flipCanvas(vertical: Boolean): Boolean = mutex.withLock {
        pushUndo()
        val properties = CanvasOperations.CanvasProperties(canvasWidth, canvasHeight, canvasDpi, backgroundColor)
        val axis = if (vertical) CanvasOperations.FlipAxis.VERTICAL else CanvasOperations.FlipAxis.HORIZONTAL
        allLayers().forEach { layer ->
            layer.raster?.let { buffer ->
                layer.raster = CanvasOperations.flip(buffer, axis, properties).buffer
                layer.rasterFile = null
                dirtyRasters += layer.id
            }
            layer.mask?.let { mask ->
                layer.mask = CanvasOperations.flip(mask, axis, properties).buffer
                layer.maskFile = null
            }
            if (layer.strokes.isNotEmpty()) {
                layer.strokes.clear()
            }
        }
        dirty = true
        emit(CanvasInvalidationEvent.Full)
        true
    }

    override suspend fun trimTransparent(): Boolean = mutex.withLock {
        val composite = rasterizeLayers(currentLayers().filter { it.isVisible })
        val content = composite.contentBounds() ?: return@withLock false
        cropCanvas(content)
    }

    override suspend fun setCanvasDpi(dpi: Int): Boolean = mutex.withLock {
        val clamped = dpi.coerceIn(CanvasOperations.MIN_DPI, CanvasOperations.MAX_DPI)
        if (clamped == canvasDpi) return@withLock true
        pushUndo()
        canvasDpi = clamped
        dirty = true
        true
    }

    override suspend fun setCanvasBackgroundColor(color: Int): Boolean = mutex.withLock {
        pushUndo()
        backgroundColor = color
        dirty = true
        emit(CanvasInvalidationEvent.Full)
        true
    }

    override suspend fun clearCanvas(color: Int) = mutex.withLock {
        pushUndo()
        backgroundColor = color
        currentLayers().forEach { layer ->
            layer.strokes.clear()
            layer.raster = PixelBuffer.filled(canvasWidth, canvasHeight, 0)
            dirtyRasters += layer.id
        }
        dirty = true
        emit(CanvasInvalidationEvent.Full)
    }

    override suspend fun applyAdjustmentToCanvas(
        type: AdjustmentType,
        parameters: Map<String, Float>,
        toAllLayers: Boolean
    ): Boolean = mutex.withLock {
        val targets = if (toAllLayers) currentLayers() else listOfNotNull(activeLayerData())
        if (targets.isEmpty()) return@withLock false
        pushUndo()
        targets.forEach { layer ->
            val base = layer.raster ?: rasterizeLayers(listOf(layer))
            layer.raster = AdjustmentProcessor.apply(base, type, parameters, 1f, activeSelection?.coverage)
            layer.rasterFile = null
            dirtyRasters += layer.id
        }
        dirty = true
        emit(CanvasInvalidationEvent.Full)
        true
    }

    // -----------------------------------------------------------------------------------------
    // Animation
    // -----------------------------------------------------------------------------------------

    override fun frames(): List<AnimationFrame> = frameList.map { frame ->
        AnimationFrame(
            id = frame.id,
            name = frame.name,
            layers = frame.layers.mapIndexed { index, data -> data.toDomain(index) },
            durationMs = frame.durationMs,
            isKeyframe = frame.isKeyframe
        )
    }

    override fun activeFrameIndex(): Int = activeFrame

    override suspend fun addFrame(duplicateCurrent: Boolean) = mutex.withLock {
        pushUndo()
        val source = frameList.getOrNull(activeFrame)
        val frame = if (duplicateCurrent && source != null) {
            FrameData(
                id = nextFrameId++,
                name = "${source.name} copy",
                durationMs = source.durationMs,
                isKeyframe = source.isKeyframe,
                layers = source.layers.map { layer ->
                    val newId = nextLayerId++
                    layer.duplicate(newId, layer.name) { nextStrokeId++ }
                        .also { dirtyRasters += newId }
                }.toMutableList()
            )
        } else {
            FrameData(
                id = nextFrameId++,
                name = "Frame ${frameList.size + 1}",
                durationMs = animationSettings.frameDurationMs,
                layers = mutableListOf(backgroundLayer())
            )
        }
        val insertAt = (activeFrame + 1).coerceIn(0, frameList.size)
        frameList.add(insertAt, frame)
        activeFrame = insertAt
        dirty = true
        syncTimeline()
        emit(CanvasInvalidationEvent.FrameChanged(activeFrame))
    }

    override suspend fun deleteFrame(index: Int): Boolean = mutex.withLock {
        if (frameList.size <= 1 || index !in frameList.indices) return@withLock false
        pushUndo()
        frameList.removeAt(index)
        activeFrame = when {
            index < activeFrame -> activeFrame - 1
            index == activeFrame -> index.coerceAtMost(frameList.lastIndex)
            else -> activeFrame
        }.coerceIn(0, frameList.lastIndex)
        dirty = true
        syncTimeline()
        emit(CanvasInvalidationEvent.FrameChanged(activeFrame))
        true
    }

    override suspend fun moveFrame(from: Int, to: Int): Boolean = mutex.withLock {
        if (from !in frameList.indices) return@withLock false
        val target = to.coerceIn(0, frameList.lastIndex)
        if (from == target) return@withLock true
        pushUndo()
        val frame = frameList.removeAt(from)
        frameList.add(target, frame)
        activeFrame = target
        dirty = true
        syncTimeline()
        emit(CanvasInvalidationEvent.FrameChanged(activeFrame))
        true
    }

    override suspend fun selectFrame(index: Int) = mutex.withLock {
        val clamped = index.coerceIn(0, frameList.lastIndex.coerceAtLeast(0))
        if (clamped == activeFrame) return@withLock
        activeFrame = clamped
        syncTimeline()
        emit(CanvasInvalidationEvent.FrameChanged(activeFrame))
    }

    override suspend fun setFrameDuration(index: Int, durationMs: Int): Boolean = mutex.withLock {
        val frame = frameList.getOrNull(index) ?: return@withLock false
        frame.durationMs = durationMs.coerceIn(
            AnimationFrame.MIN_DURATION_MS,
            AnimationFrame.MAX_DURATION_MS
        )
        dirty = true
        syncTimeline()
        true
    }

    override suspend fun updateAnimationSettings(settings: AnimationSettings) = mutex.withLock {
        animationSettings = settings.copy(
            fps = settings.fps.coerceIn(AnimationSettings.MIN_FPS, AnimationSettings.MAX_FPS),
            onionSkinFrames = settings.onionSkinFrames.coerceIn(0, AnimationSettings.MAX_ONION_SKIN_FRAMES),
            onionSkinOpacity = settings.onionSkinOpacity.coerceIn(0.05f, 1f)
        )
        dirty = true
        syncTimeline()
        emit(CanvasInvalidationEvent.Full)
    }

    override suspend fun compositeAllFrames(maxFrames: Int): List<PixelBuffer> {
        val count = min(maxFrames, frameList.size)
        val result = mutableListOf<PixelBuffer>()
        for (index in 0 until count) {
            val frameLayers = mutex.withLock { frameList[index].layers.toList() }
            result += rasterizeLayers(frameLayers)
        }
        return result
    }

    // -----------------------------------------------------------------------------------------
    // Compositing
    // -----------------------------------------------------------------------------------------

    override suspend fun compositeBuffer(includeHidden: Boolean, applyAdjustments: Boolean): PixelBuffer? {
        val layers = mutex.withLock { currentLayers().toList() }
        if (layers.isEmpty()) return null
        return rasterizeLayers(layers, includeHidden, applyAdjustments)
    }

    override suspend fun layerBuffers(): List<Pair<Layer, PixelBuffer>> =
        mutex.withLock { currentLayers().toList() }.mapNotNull { data ->
            val buffer = data.raster ?: run {
                if (data.strokes.isEmpty()) return@mapNotNull null
                strokeRasterizer().rasterize(data.strokes, canvasWidth, canvasHeight)
            }
            data.toDomain(0) to buffer
        }

    override suspend fun getCanvasBitmap(): ByteArray? {
        val composite = compositeBuffer() ?: return null
        return withContext(Dispatchers.Default) {
            ByteArrayOutputStream().use { out ->
                val bitmap = BitmapPixelBridge.toBitmap(composite)
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
                bitmap.recycle()
                out.toByteArray()
            }
        }
    }

    override fun getCanvasSize(): CanvasSize = CanvasSize(canvasWidth, canvasHeight, canvasDpi)

    override fun getBackgroundColor(): Int = backgroundColor

    override fun observeCanvasInvalidation(): Flow<CanvasInvalidationEvent> = invalidationFlow

    // -----------------------------------------------------------------------------------------
    // Undo / redo
    // -----------------------------------------------------------------------------------------

    override fun undo(): Boolean {
        val snapshot = undoStack.removeLastOrNull() ?: return false
        redoStack.addLast(currentSnapshot())
        restore(snapshot)
        dirty = true
        emitAsync(CanvasInvalidationEvent.Full)
        return true
    }

    override fun redo(): Boolean {
        val snapshot = redoStack.removeLastOrNull() ?: return false
        undoStack.addLast(currentSnapshot())
        restore(snapshot)
        dirty = true
        emitAsync(CanvasInvalidationEvent.Full)
        return true
    }

    private fun pushUndo() {
        undoStack.addLast(currentSnapshot())
        while (undoStack.size > MAX_HISTORY) undoStack.removeFirst()
        // Trim by memory as well: a 4K layer is 32 MB, so 30 raster steps would be ~1 GB.
        while (undoStack.size > 1 && rasterBytes(undoStack) > MAX_RASTER_HISTORY_BYTES) {
            undoStack.removeFirst()
        }
        redoStack.clear()
        // Any buffer captured by a snapshot is now shared and must be copied before the next
        // in-place edit (masks are painted in place; layer pixels clone on `beginRasterEdit`).
        allLayers().forEach { it.maskOwned = false }
    }

    private fun rasterBytes(stack: ArrayDeque<Snapshot>): Long {
        val counted = java.util.IdentityHashMap<PixelBuffer, Boolean>()
        var total = 0L
        stack.forEach { snapshot ->
            snapshot.frames.forEach { frame ->
                frame.layers.forEach { layer ->
                    listOfNotNull(layer.raster, layer.mask).forEach { buffer ->
                        if (counted.put(buffer, true) == null) {
                            total += buffer.pixels.size.toLong() * 4
                        }
                    }
                }
            }
        }
        return total
    }

    private data class FrameSnapshot(
        val id: Long,
        val name: String,
        val durationMs: Int,
        val activeLayerId: Long,
        val layers: List<LayerData>
    )

    private data class Snapshot(
        val frames: List<FrameSnapshot>,
        val activeFrame: Int,
        val activeLayerId: Long,
        val width: Int,
        val height: Int,
        val dpi: Int,
        val backgroundColor: Int
    )

    /**
     * Marks every mask as shared so the next in-place mask paint clones it first. Called after a
     * snapshot (undo entry) or a duplicate, both of which hand out references to the same buffer.
     */
    private fun markRastersShared() {
        allLayers().forEach { it.maskOwned = false }
    }

    /**
     * A snapshot shares pixel buffers with the live document (copy-on-write). Layer *objects* are
     * copied, but their `raster`/`mask` references point at the same buffers until an edit clones
     * them, which is what keeps undo cheap.
     */
    private fun currentSnapshot(): Snapshot = Snapshot(
        frames = frameList.map { frame ->
            FrameSnapshot(
                id = frame.id,
                name = frame.name,
                durationMs = frame.durationMs,
                activeLayerId = frame.activeLayerId,
                layers = frame.layers.map { it.snapshotCopy() }
            )
        },
        activeFrame = activeFrame,
        activeLayerId = activeLayerId(),
        width = canvasWidth,
        height = canvasHeight,
        dpi = canvasDpi,
        backgroundColor = backgroundColor
    )

    private fun restore(snapshot: Snapshot) {
        frameList = snapshot.frames.map { frame ->
            FrameData(
                id = frame.id,
                name = frame.name,
                durationMs = frame.durationMs,
                layers = frame.layers.map { it.snapshotCopy() }.toMutableList(),
                activeLayerId = frame.activeLayerId
            )
        }.toMutableList()
        activeFrame = snapshot.activeFrame.coerceIn(0, frameList.lastIndex.coerceAtLeast(0))
        setActiveLayerId(snapshot.activeLayerId)
        canvasWidth = snapshot.width
        canvasHeight = snapshot.height
        canvasDpi = snapshot.dpi
        backgroundColor = snapshot.backgroundColor
        dirtyRasters.addAll(allLayers().map { it.id })
        syncTimeline()
    }

    fun hasUnsavedChangesFor(projectId: Long): Boolean = dirty && currentProjectId == projectId

    // -----------------------------------------------------------------------------------------
    // Internals
    // -----------------------------------------------------------------------------------------

    private fun strokeRasterizer(): StrokeRasterizer = StrokeRasterizer()

    /** Composites [layers] using the shared compositor. */
    private fun rasterizeLayers(
        layers: List<LayerData>,
        includeHidden: Boolean = false,
        applyAdjustments: Boolean = true
    ): PixelBuffer {
        val inputs = layers.mapIndexed { index, data ->
            Compositor.LayerInput(
                layer = data.toDomain(index),
                raster = data.raster,
                strokes = data.strokes.toList(),
                mask = data.mask
            )
        }
        return compositor.composite(
            inputs = inputs,
            width = canvasWidth,
            height = canvasHeight,
            backgroundColor = backgroundColor,
            options = Compositor.Options(
                includeHiddenLayers = includeHidden,
                selection = activeSelection,
                applyAdjustments = applyAdjustments
            )
        )
    }

    private fun scaleDown(buffer: PixelBuffer, maxSize: Int): PixelBuffer {
        val scale = minOf(
            maxSize.toFloat() / buffer.width.coerceAtLeast(1),
            maxSize.toFloat() / buffer.height.coerceAtLeast(1)
        ).coerceAtMost(1f)
        if (scale >= 1f) return buffer
        return buffer.scaled(
            (buffer.width * scale).roundToInt().coerceAtLeast(1),
            (buffer.height * scale).roundToInt().coerceAtLeast(1)
        )
    }

    private fun currentLayers(): MutableList<LayerData> =
        frameList[activeFrame.coerceIn(0, frameList.lastIndex)].layers

    private fun allLayers(): List<LayerData> = frameList.flatMap { it.layers }

    private fun layerById(layerId: Long): LayerData? = allLayers().firstOrNull { it.id == layerId }

    private fun activeLayerData(): LayerData? = currentLayers().firstOrNull { it.id == activeLayerId() }

    private fun activeLayerId(): Long {
        val frame = frameList.getOrNull(activeFrame) ?: return 0
        return frame.activeLayerId.takeIf { id -> frame.layers.any { it.id == id } }
            ?: frame.layers.firstOrNull()?.id
            ?: 0
    }

    private fun setActiveLayerId(layerId: Long) {
        frameList.getOrNull(activeFrame)?.activeLayerId = layerId
    }

    private fun syncTimeline() {
        _timeline.value = AnimationTimeline.State(
            frames = frames(),
            activeIndex = activeFrame,
            settings = animationSettings
        )
    }

    private fun stateSnapshot(): CanvasState {
        val active = currentLayers()
        return CanvasState(
            id = currentProjectId,
            width = canvasWidth,
            height = canvasHeight,
            dpi = canvasDpi,
            backgroundColor = backgroundColor,
            layerIds = active.map { it.id },
            activeLayerId = activeLayerId(),
            zoom = 1f,
            offsetX = 0f,
            offsetY = 0f,
            rotation = 0f,
            frameCount = frameList.size,
            activeFrameIndex = activeFrame,
            hasUnsavedChanges = dirty
        )
    }

    private fun canvasSnapshot(): CanvasDocument = CanvasDocument(
        width = canvasWidth,
        height = canvasHeight,
        dpi = canvasDpi,
        backgroundColor = backgroundColor,
        activeLayerId = activeLayerId(),
        nextLayerId = nextLayerId,
        layers = currentLayers().mapIndexed { index, data -> data.toDomain(index) },
        frames = frames(),
        activeFrameIndex = activeFrame,
        animation = animationSettings
    )

    private fun emit(event: CanvasInvalidationEvent) {
        invalidationFlow.tryEmit(event)
    }

    private fun emitAsync(event: CanvasInvalidationEvent) {
        coroutineScope.launch { invalidationFlow.emit(event) }
    }

    override fun dispose() {
        compositor.release()
        activeStrokes.clear()
        strokeBrushParams.clear()
        strokeLayerIds.clear()
        strokeErasers.clear()
        undoStack.clear()
        redoStack.clear()
    }

    /**
     * In-memory layer. Mutable so property setters stay O(1); every mutation routes through the
     * repository so undo snapshots stay consistent.
     */
    private class LayerData(
        val id: Long,
        var name: String,
        var isVisible: Boolean = true,
        var opacity: Float = 1.0f,
        var isLocked: Boolean = false,
        var blendMode: BlendMode = BlendMode.NORMAL,
        val strokes: MutableList<Stroke> = mutableListOf(),
        var isAlphaLocked: Boolean = false,
        var isClippingMask: Boolean = false,
        var isReference: Boolean = false,
        var linkGroupId: Long? = null,
        var maskEnabled: Boolean = true,
        var maskInverted: Boolean = false,
        var maskDensity: Float = 1.0f,
        var maskFeather: Float = 0f,
        var adjustmentType: AdjustmentType? = null,
        var adjustmentParams: MutableMap<String, Float> = mutableMapOf(),
        var filterType: FilterType? = null,
        var filterAmount: Float = 0f,
        var smartObjectId: String? = null,
        var isInternal: Boolean = false
    ) {
        var raster: PixelBuffer? = null
        var rasterFile: String? = null
        var mask: PixelBuffer? = null
        var maskFile: String? = null

        /**
         * True when [mask] is owned exclusively by this layer and may be mutated in place.
         * Cleared whenever a snapshot is taken, so the first paint after that clones instead of
         * corrupting the history entry that shares the buffer.
         */
        var maskOwned: Boolean = true

        fun canPaint(): Boolean = isVisible && !isLocked && !isReference &&
            adjustmentType == null && smartObjectId == null

        /** Copy for an undo snapshot: shares pixel buffers (copy-on-write) and copies the list. */
        fun snapshotCopy(): LayerData = LayerData(
            id = id,
            name = name,
            isVisible = isVisible,
            opacity = opacity,
            isLocked = isLocked,
            blendMode = blendMode,                strokes = strokes.toMutableList(),
            isAlphaLocked = isAlphaLocked,
            isClippingMask = isClippingMask,
            isReference = isReference,
            linkGroupId = linkGroupId,
            maskEnabled = maskEnabled,
            maskInverted = maskInverted,
            maskDensity = maskDensity,
            maskFeather = maskFeather,
            adjustmentType = adjustmentType,
            adjustmentParams = adjustmentParams.toMutableMap(),
            filterType = filterType,
            filterAmount = filterAmount,
            smartObjectId = smartObjectId,
            isInternal = isInternal
        ).also {
            it.raster = raster
            it.rasterFile = rasterFile
            it.mask = mask
            it.maskFile = maskFile
            // The snapshot now shares the mask buffer, so the live layer must copy before it
            // paints again.
            it.maskOwned = false
        }

        /**
         * Creates a copy of this layer under [newId].
         *
         * Stroke ids are remapped through [nextStrokeId] so the copy does not share identity with
         * the original, while pixel buffers are shared until either copy is edited
         * (copy-on-write), which keeps duplicating a 4K layer instant. The persisted file
         * references are cleared because the copy has no file of its own yet.
         */
        fun duplicate(newId: Long, newName: String, nextStrokeId: () -> Long): LayerData =
            LayerData(
                id = newId,
                name = newName,
                isVisible = isVisible,
                opacity = opacity,
                isLocked = isLocked,
                blendMode = blendMode,
                strokes = strokes.map { it.copy(id = nextStrokeId(), layerId = newId) }.toMutableList(),
                isAlphaLocked = isAlphaLocked,
                isClippingMask = isClippingMask,
                isReference = isReference,
                linkGroupId = linkGroupId,
                maskEnabled = maskEnabled,
                maskInverted = maskInverted,
                maskDensity = maskDensity,
                maskFeather = maskFeather,
                adjustmentType = adjustmentType,
                adjustmentParams = adjustmentParams.toMutableMap(),
                filterType = filterType,
                filterAmount = filterAmount,
                smartObjectId = smartObjectId,
                isInternal = isInternal
            ).also { fresh ->
                fresh.raster = raster
                fresh.mask = mask
                fresh.maskOwned = false
            }

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
            isClippingMask = isClippingMask,
            rasterFile = rasterFile,
            maskFile = maskFile,
            maskEnabled = maskEnabled,
            maskInverted = maskInverted,
            maskDensity = maskDensity,
            maskFeather = maskFeather,
            adjustmentType = adjustmentType,
            adjustmentParameters = adjustmentParams.toMap(),
            filterType = filterType,
            filterAmount = filterAmount,
            isReference = isReference,
            linkGroupId = linkGroupId,
            smartObjectId = smartObjectId,
            isInternal = isInternal
        )
    }

    /** One animation frame's in-memory state. */
    private class FrameData(
        val id: Long,
        var name: String,
        var durationMs: Int,
        val layers: MutableList<LayerData>,
        var isKeyframe: Boolean = false,
        var activeLayerId: Long = layers.firstOrNull()?.id ?: 0L
    )

    companion object {
        private const val MAX_HISTORY = 30

        /**
         * Cap on the pixel memory the undo stack may hold (192 MB). Beyond this the oldest steps
         * are dropped: keeping an artist's whole session of 4K layer snapshots in RAM is not
         * possible on a tablet, and silently failing to allocate is worse than a shorter history.
         */
        private const val MAX_RASTER_HISTORY_BYTES = 192L * 1024 * 1024

        /** Pixel content is written as a single file per layer; undo keeps versions in memory. */
        private const val RASTER_VERSION = 1
        private const val MASK_PREFIX = "mask"
    }
}
