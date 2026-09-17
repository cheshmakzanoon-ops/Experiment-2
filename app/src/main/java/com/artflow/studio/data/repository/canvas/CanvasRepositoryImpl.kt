package com.artflow.studio.data.repository.canvas

import com.artflow.studio.core.animation.AnimationTimeline
import com.artflow.studio.core.canvas.CanvasOperations
import com.artflow.studio.core.pixels.AdjustmentProcessor
import com.artflow.studio.core.pixels.IntBounds
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.core.pixels.SelectionMask
import com.artflow.studio.core.pixels.Stamping
import com.artflow.studio.core.render.Compositor
import com.artflow.studio.core.render.StrokeRasterizer
import com.artflow.studio.core.symmetry.SymmetryEngine
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
import com.artflow.studio.domain.repository.canvas.CanvasExportSnapshot
import com.artflow.studio.domain.repository.canvas.CanvasInvalidationEvent
import com.artflow.studio.domain.repository.canvas.CanvasRepository
import com.artflow.studio.domain.repository.canvas.CanvasSize
import com.artflow.studio.domain.repository.canvas.CanvasState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
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
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext
import kotlin.math.max
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
class CanvasRepositoryImpl
    @Inject
    constructor(
        private val storage: ProjectStorage,
    ) : CanvasRepository {
        private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private val saveMutex = Mutex()

        // Touch callbacks are synchronous on the UI thread. Confine suspend mutations and snapshot
        // capture to that same thread instead of using a non-reentrant mutex that those callbacks
        // cannot acquire. Heavy tool work, decoding, compositing and storage run on worker threads.
        private suspend fun <T> withState(block: suspend () -> T): T = withContext(Dispatchers.Main.immediate) { block() }

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

        private val invalidationFlow = MutableSharedFlow<CanvasInvalidationEvent>(replay = 1, extraBufferCapacity = 63)

        private val undoStack = ArrayDeque<Snapshot>()
        private val redoStack = ArrayDeque<Snapshot>()

        private var editRevision = 0L
        private var dirty = false
            set(value) {
                if (value) editRevision++
                field = value
            }

        /** Layer ids whose pixels changed since the last write to disk. */
        private val dirtyRasters = mutableSetOf<Long>()

        private var rasterEditToken = 0L

        private data class PendingEdit(
            val projectId: Long,
            val layer: LayerData,
            val original: PixelBuffer?,
            val session: CanvasRepository.RasterEditSession,
        )

        private val pendingEdits = mutableMapOf<Long, PendingEdit>()

        init {
            // A blank frame keeps every accessor total: the repository is usable before a project is
            // created, which removes a whole class of null handling from the ViewModel.
            frameList =
                mutableListOf(
                    FrameData(
                        id = nextFrameId++,
                        name = "Frame 1",
                        durationMs = animationSettings.frameDurationMs,
                        layers = mutableListOf(backgroundLayer()),
                    ),
                )
            syncTimeline()
        }

        // -----------------------------------------------------------------------------------------
        // Canvas lifecycle
        // -----------------------------------------------------------------------------------------

        override suspend fun createCanvas(
            width: Int,
            height: Int,
            dpi: Int,
        ): Long =
            withState {
                createCanvasLocked(width, height, dpi)
            }

        private fun createCanvasLocked(
            width: Int,
            height: Int,
            dpi: Int,
        ): Long {
            require(CanvasOperations.isSizeSafe(width, height)) { CanvasOperations.sizeWarning(width, height) ?: "Invalid canvas size" }
            requireCanvasMemory(width, height)
            canvasWidth = width
            canvasHeight = height
            canvasDpi = dpi.coerceIn(CanvasOperations.MIN_DPI, CanvasOperations.MAX_DPI)
            backgroundColor = 0xFFFFFFFF.toInt()
            animationSettings = AnimationSettings()
            pendingEdits.clear()
            nextLayerId = 1
            nextFrameId = 1
            nextStrokeId = 1

            frameList =
                mutableListOf(
                    FrameData(
                        id = nextFrameId++,
                        name = "Frame 1",
                        durationMs = animationSettings.frameDurationMs,
                        layers = mutableListOf(backgroundLayer()),
                    ),
                )
            activeFrame = 0
            activeSelection = null
            activeStrokes.clear()
            strokeBrushParams.clear()
            strokeLayerIds.clear()
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
            layer.raster = PixelBuffer(canvasWidth, canvasHeight)
            dirtyRasters += layer.id
            return layer
        }

        override suspend fun loadOrCreate(
            projectId: Long,
            width: Int,
            height: Int,
            dpi: Int,
        ): CanvasState? {
            val loaded = loadCanvas(projectId)
            if (loaded != null) return loaded
            createCanvas(width, height, dpi)
            return withState {
                currentProjectId = projectId
                stateSnapshot()
            }
        }

        override suspend fun loadCanvas(projectId: Long): CanvasState? =
            withState {
                val document = storage.loadDocument(projectId) ?: return@withState null
                applyDocument(projectId, document)
                Timber.d("Loaded project $projectId (${frameList.size} frames, ${currentLayers().size} layers)")
                stateSnapshot()
            }

        override suspend fun hasRecovery(projectId: Long): Boolean = storage.hasUnsavedRecovery(projectId)

        override suspend fun recoverAutosave(projectId: Long): CanvasState? =
            withState {
                val document = storage.loadAutosave(projectId) ?: return@withState null
                applyDocument(projectId, document)
                dirty = true
                Timber.d("Recovered autosave for project $projectId")
                stateSnapshot()
            }

        private suspend fun applyDocument(
            projectId: Long,
            document: CanvasDocument,
        ) {
            val frames = document.resolvedFrames()
            requireCanvasMemory(document.width, document.height)
            val rasterCount = frames.sumOf { frame -> frame.layers.sumOf { listOfNotNull(it.rasterFile, it.maskFile).size } }
            val rasterBytes = document.width.toLong() * document.height * 4L * rasterCount
            require(rasterBytes <= Runtime.getRuntime().maxMemory() / 3) { "This artwork needs more memory than this device provides" }
            val decodedFrames =
                frames
                    .map { frame ->
                        FrameData(
                            id = frame.id,
                            name = frame.name,
                            durationMs = frame.durationMs,
                            isKeyframe = frame.isKeyframe,
                            layers =
                                frame.layers
                                    .sortedBy { it.index }
                                    .map { layer ->
                                        toLayerData(projectId, layer).also { data ->
                                            listOfNotNull(data.raster, data.mask).forEach { buffer ->
                                                require(
                                                    buffer.width == document.width && buffer.height == document.height,
                                                ) { "Layer dimensions do not match the document" }
                                            }
                                        }
                                    }.toMutableList(),
                        )
                    }.toMutableList()
            coroutineContext.ensureActive()
            currentProjectId = projectId
            canvasWidth = document.width
            canvasHeight = document.height
            canvasDpi = document.dpi
            backgroundColor = document.backgroundColor
            animationSettings = document.animation
            nextLayerId = max(document.nextLayerId, frames.flatMap { it.layers }.maxOfOrNull { it.id }?.plus(1) ?: 1L)
            nextFrameId = (frames.maxOfOrNull { it.id } ?: 0L) + 1
            nextStrokeId = frames
                .flatMap { it.layers }
                .flatMap { it.strokes }
                .maxOfOrNull { it.id }
                ?.plus(1) ?: 1L
            pendingEdits.clear()
            frameList = decodedFrames

            if (frameList.isEmpty()) {
                frameList =
                    mutableListOf(
                        FrameData(nextFrameId++, "Frame 1", animationSettings.frameDurationMs, mutableListOf(backgroundLayer())),
                    )
            }
            activeFrame = document.resolvedActiveFrameIndex().coerceIn(0, frameList.lastIndex)
            // The document records which layer was selected; apply it to the frame that is active now.
            frameList[activeFrame].activeLayerId = document.activeLayerId.takeIf { id ->
                frameList[activeFrame].layers.any { it.id == id }
            } ?: frameList[activeFrame].layers.firstOrNull()?.id ?: 0L
            activeSelection = null
            activeStrokes.clear()
            strokeBrushParams.clear()
            strokeLayerIds.clear()
            strokeErasers.clear()
            undoStack.clear()
            redoStack.clear()
            dirtyRasters.clear()
            dirty = false
            syncTimeline()
            emit(CanvasInvalidationEvent.Full)
        }

        private suspend fun toLayerData(
            projectId: Long,
            layer: Layer,
        ): LayerData {
            val data =
                LayerData(
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
                    isInternal = layer.isInternal,
                )
            data.raster = loadRaster(projectId, layer.rasterFile)
            data.mask = loadRaster(projectId, layer.maskFile)
            data.rasterFile = layer.rasterFile
            data.maskFile = layer.maskFile
            return data
        }

        private suspend fun loadRaster(
            projectId: Long,
            path: String?,
        ): PixelBuffer? {
            val bytes = storage.readRaster(projectId, path) ?: return null
            return withContext(Dispatchers.Default) {
                requireNotNull(BitmapPixelBridge.fromEncodedBytes(bytes)) { "Could not decode layer pixels at $path" }
            }
        }

        private data class SaveSnapshot(
            val projectId: Long,
            val revision: Long,
            val document: CanvasDocument,
            val frames: List<FrameSnapshot>,
        )

        private suspend fun captureSave(projectId: Long): SaveSnapshot =
            withState {
                check(projectId == currentProjectId && frameList.isNotEmpty()) { "The requested artwork is no longer open" }
                val frozen = currentSnapshot()
                SaveSnapshot(projectId, editRevision, canvasSnapshot(), frozen.frames)
            }

        /** Writes all frames, including masks, then builds metadata from the paths actually written. */
        private suspend fun persistRasters(snapshot: SaveSnapshot): CanvasDocument {
            val savedFrames =
                snapshot.frames.map { frame ->
                    val layers =
                        frame.layers.mapIndexed { index, layer ->
                            val rasterFile =
                                layer.raster?.let { buffer ->
                                    val bytes = withContext(Dispatchers.Default) { BitmapPixelBridge.toPngBytes(buffer) }
                                    storage.writeRaster(snapshot.projectId, layer.id, RASTER_VERSION, bytes)
                                }
                            val maskFile =
                                layer.mask?.let { buffer ->
                                    val bytes = withContext(Dispatchers.Default) { BitmapPixelBridge.toPngBytes(buffer) }
                                    storage.writeAuxiliaryImage(snapshot.projectId, layer.id, MASK_PREFIX, RASTER_VERSION, bytes)
                                }
                            layer.toDomain(index).copy(rasterFile = rasterFile, maskFile = maskFile)
                        }
                    AnimationFrame(frame.id, frame.name, layers, frame.durationMs, frame.isKeyframe)
                }
            return snapshot.document.copy(layers = savedFrames.first().layers, frames = savedFrames)
        }

        override suspend fun saveCanvas(projectId: Long): String? =
            saveMutex.withLock {
                val snapshot = captureSave(projectId)
                val document = persistRasters(snapshot)
                // The manifest is the commit point. A cancelled or failed pre-commit write leaves the
                // old manifest and all of its rasters intact. Never mark newer edits as saved.
                storage.saveDocument(projectId, document)
                storage.discardAutosave(projectId)
                val composite =
                    withContext(Dispatchers.Default) {
                        renderFrozen(snapshot.frames[snapshot.document.activeFrameIndex].layers, snapshot.document)
                    }
                storage.saveFlattened(projectId, withContext(Dispatchers.Default) { BitmapPixelBridge.toPngBytes(composite) })
                val thumbnail = withContext(Dispatchers.Default) { BitmapPixelBridge.toPngBytes(scaleDown(composite, 512)) }
                val path = storage.saveThumbnail(projectId, thumbnail)
                withState {
                    if (currentProjectId == projectId) {
                        val savedLayers = document.frames.flatMap { it.layers }.associateBy { it.id }
                        val frozenLayers = snapshot.frames.flatMap { it.layers }.associateBy { it.id }
                        allLayers().forEach { live ->
                            val saved = savedLayers[live.id]
                            val frozen = frozenLayers[live.id]
                            if (frozen != null && live.raster === frozen.raster && live.mask === frozen.mask) {
                                live.rasterFile = saved?.rasterFile
                                live.maskFile = saved?.maskFile
                                dirtyRasters.remove(live.id)
                            }
                        }
                        if (editRevision == snapshot.revision) dirty = false
                    }
                }
                pruneCommittedRasters(projectId)
                path
            }

        override suspend fun autosave(projectId: Long) =
            saveMutex.withLock {
                val snapshot = captureSave(projectId)
                storage.saveAutosave(projectId, persistRasters(snapshot))
                pruneCommittedRasters(projectId)
            }

        override suspend fun discardRecovery(projectId: Long) =
            saveMutex.withLock {
                storage.discardAutosave(projectId)
                pruneCommittedRasters(projectId)
            }

        private suspend fun pruneCommittedRasters(projectId: Long) {
            try {
                // ProjectStorage independently retains BOTH manifests and fails closed on corruption.
                storage.pruneRasters(projectId, emptySet())
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: IOException) {
                Timber.w(error, "Raster cleanup deferred for project $projectId")
            } catch (error: IllegalArgumentException) {
                Timber.w(error, "Raster cleanup deferred: invalid metadata for project $projectId")
            } catch (error: IllegalStateException) {
                Timber.w(error, "Raster cleanup deferred: project $projectId is not accessible")
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
            isEraser: Boolean,
        ): Long {
            val strokeId = nextStrokeId++
            val layer = layerById(layerId)
            if (layer != null && !layer.canPaint()) {
                Timber.w("Stroke started on non-paintable layer ${layer.name}")
            }
            activeStrokes[strokeId] =
                mutableListOf(
                    StrokePoint(x = x, y = y, pressure = pressure, color = strokeColor),
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
            tiltY: Float,
        ) {
            activeStrokes[strokeId]?.add(
                StrokePoint(
                    x = x,
                    y = y,
                    pressure = pressure,
                    tiltX = tiltX,
                    tiltY = tiltY,
                    color = strokeColor,
                ),
            )
        }

        override fun endStroke(strokeId: Long) {
            val points = activeStrokes.remove(strokeId) ?: return
            val brushParams = strokeBrushParams.remove(strokeId) ?: return
            val layerId = strokeLayerIds.remove(strokeId) ?: return
            val isEraser = strokeErasers.remove(strokeId) ?: false

            val layer =
                layerById(layerId) ?: run {
                    Timber.w("Dropping stroke for unknown layer $layerId")
                    return
                }
            if (!layer.canPaint()) {
                Timber.w("Dropping stroke on non-paintable layer ${layer.name}")
                return
            }

            val stroke =
                Stroke(
                    id = strokeId,
                    points = points.toList(),
                    brushParams = brushParams,
                    layerId = layerId,
                    color = points.firstOrNull()?.color ?: strokeColor,
                    isEraser = isEraser,
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
            SymmetryEngine
                .mirrorStroke(stroke, canvasWidth, canvasHeight, symmetrySettings)
                .forEach { copy ->
                    rasterizer.draw(
                        target = base,
                        stroke = copy,
                        alphaLock = layer.isAlphaLocked,
                        mask = activeSelection,
                    )
                }
            layer.raster = base
            layer.rasterFile = null
            dirtyRasters += layer.id
            dirty = true
            emitAsync(CanvasInvalidationEvent.Full)
        }

        override fun cancelStroke(strokeId: Long) {
            activeStrokes.remove(strokeId)
            strokeBrushParams.remove(strokeId)
            strokeLayerIds.remove(strokeId)
            strokeErasers.remove(strokeId)
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
                isEraser = strokeErasers[strokeId] ?: false,
            )
        }

        override suspend fun replaceLayerStrokes(
            layerId: Long,
            strokes: List<Stroke>,
        ): Boolean =
            withState {
                val layer = layerById(layerId) ?: return@withState false
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

        override suspend fun layerPixels(layerId: Long): PixelBuffer? = withState { layerById(layerId)?.raster?.copy() }

        override suspend fun beginRasterEdit(layerId: Long): CanvasRepository.RasterEditSession? =
            withState {
                val layer = layerById(layerId) ?: return@withState null
                if (!layer.canPaint() || pendingEdits.values.any { it.layer.id == layerId }) return@withState null
                val buffer = layer.raster?.copy() ?: PixelBuffer(canvasWidth, canvasHeight)
                val session = CanvasRepository.RasterEditSession(layerId, buffer, ++rasterEditToken)
                pendingEdits[session.snapshotToken] = PendingEdit(currentProjectId, layer, layer.raster, session)
                session
            }

        override suspend fun commitRasterEdit(
            session: CanvasRepository.RasterEditSession,
            description: String,
        ): Boolean =
            withState {
                val pending = pendingEdits.remove(session.snapshotToken) ?: return@withState false
                val layer = layerById(session.layerId) ?: return@withState false
                if (pending.session !== session || pending.projectId != currentProjectId) return@withState false
                if (layer !== pending.layer || layer.raster !== pending.original || !layer.canPaint()) return@withState false
                if (session.buffer.width != canvasWidth || session.buffer.height != canvasHeight) return@withState false
                // A session is provisional until here: cancellation cannot remove someone else's undo
                // entry, and autosave/export can never publish half a drag or a failed tool operation.
                pushUndo()
                layer.raster = session.buffer.copy()
                layer.rasterFile = null
                dirtyRasters += layer.id
                dirty = true
                emit(CanvasInvalidationEvent.Full)
                Timber.d("Committed pixel edit ($description) on layer ${session.layerId}")
                true
            }

        override suspend fun cancelRasterEdit(session: CanvasRepository.RasterEditSession) =
            withState {
                if (pendingEdits[session.snapshotToken]?.session === session) pendingEdits.remove(session.snapshotToken)
                emit(CanvasInvalidationEvent.Full)
            }

        override suspend fun applyRasterEdit(
            layerId: Long,
            description: String,
            edit: (PixelBuffer) -> Unit,
        ): Boolean {
            val session = beginRasterEdit(layerId) ?: return false
            return try {
                withContext(Dispatchers.Default) { edit(session.buffer) }
                commitRasterEdit(session, description)
            } finally {
                withContext(NonCancellable) { cancelRasterEdit(session) }
            }
        }

        override suspend fun setLayerPixels(
            layerId: Long,
            buffer: PixelBuffer,
            description: String,
        ): Boolean =
            withState {
                val layer = layerById(layerId) ?: return@withState false
                if (!layer.canPaint() || buffer.width != canvasWidth || buffer.height != canvasHeight) return@withState false
                pushUndo()
                layer.raster = buffer.copy()
                layer.rasterFile = null
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

        override suspend fun addLayer(
            name: String?,
            index: Int?,
            opacity: Float,
        ): Layer =
            withState {
                pushUndo()
                val layerId = nextLayerId++
                val layers = currentLayers()
                val layer =
                    LayerData(
                        id = layerId,
                        name = name ?: "Layer ${layers.size}",
                        opacity = opacity.coerceIn(0f, 1f),
                    )
                val activeIndex = layers.indexOfFirst { it.id == activeLayerId() }
                val insertAt = (index ?: (activeIndex + 1)).coerceIn(0, layers.size)
                layers.add(insertAt, layer)
                setActiveLayerId(layerId)
                dirty = true
                emit(CanvasInvalidationEvent.LayersChanged)
                layer.toDomain(insertAt)
            }

        override suspend fun removeLayer(layerId: Long): Boolean =
            withState {
                val layers = currentLayers()
                if (layers.size <= 1) return@withState false
                val position = layers.indexOfFirst { it.id == layerId }
                if (position == -1) return@withState false

                pushUndo()
                layers.removeAt(position)
                if (activeLayerId() == layerId) {
                    setActiveLayerId(layers[position.coerceAtMost(layers.lastIndex)].id)
                }
                dirty = true
                emit(CanvasInvalidationEvent.LayersChanged)
                true
            }

        override suspend fun reorderLayer(
            layerId: Long,
            newIndex: Int,
        ): Boolean =
            withState {
                val layers = currentLayers()
                val from = layers.indexOfFirst { it.id == layerId }
                if (from == -1) return@withState false
                val to = newIndex.coerceIn(0, layers.lastIndex)
                if (from == to) return@withState true
                pushUndo()
                val layer = layers.removeAt(from)
                layers.add(to, layer)
                dirty = true
                emit(CanvasInvalidationEvent.LayersChanged)
                true
            }

        override suspend fun duplicateLayer(layerId: Long): Long? =
            withState {
                val layers = currentLayers()
                val position = layers.indexOfFirst { it.id == layerId }
                if (position == -1) return@withState null

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

        override suspend fun mergeLayers(
            sourceLayerId: Long,
            targetLayerId: Long,
        ): Boolean =
            withState {
                if (sourceLayerId == targetLayerId) return@withState false
                val layers = currentLayers()
                val sourceIndex = layers.indexOfFirst { it.id == sourceLayerId }
                val targetIndex = layers.indexOfFirst { it.id == targetLayerId }
                if (sourceIndex == -1 || targetIndex == -1) return@withState false

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

        override suspend fun mergeVisibleLayers(keepOriginals: Boolean): Long? =
            withState {
                val layers = currentLayers()
                val visible = layers.filter { it.isVisible }
                if (visible.size < 2) return@withState null

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

        override suspend fun mergeLayerDown(layerId: Long): Boolean =
            withState {
                val layers = currentLayers()
                val index = layers.indexOfFirst { it.id == layerId }
                if (index <= 0) return@withState false

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

        override suspend fun flattenAllLayers(): Long? =
            withState {
                val layers = currentLayers()
                if (layers.size <= 1) return@withState null
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

        override suspend fun setLayerVisibility(
            layerId: Long,
            isVisible: Boolean?,
        ): Boolean =
            withState {
                val layer = layerById(layerId) ?: return@withState false
                pushUndo()
                layer.isVisible = isVisible ?: !layer.isVisible
                dirty = true
                emit(CanvasInvalidationEvent.LayersChanged)
                true
            }

        override suspend fun setLayerOpacity(
            layerId: Long,
            opacity: Float,
        ): Boolean =
            withState {
                val layer = layerById(layerId) ?: return@withState false
                val clamped = opacity.coerceIn(0f, 1f)
                if (layer.opacity == clamped) return@withState true
                pushUndo()
                layer.opacity = clamped
                dirty = true
                emit(CanvasInvalidationEvent.LayersChanged)
                true
            }

        override suspend fun setLayerName(
            layerId: Long,
            newName: String,
        ): Boolean =
            withState {
                val layer = layerById(layerId) ?: return@withState false
                val trimmed = newName.trim()
                if (trimmed.isEmpty() || trimmed == layer.name) return@withState false
                pushUndo()
                layer.name = trimmed
                dirty = true
                emit(CanvasInvalidationEvent.LayersChanged)
                true
            }

        override suspend fun setLayerLock(
            layerId: Long,
            isLocked: Boolean,
        ): Boolean =
            withState {
                val layer = layerById(layerId) ?: return@withState false
                pushUndo()
                layer.isLocked = isLocked
                dirty = true
                emit(CanvasInvalidationEvent.LayersChanged)
                true
            }

        override suspend fun setLayerBlendMode(
            layerId: Long,
            blendMode: BlendMode,
        ): Boolean =
            withState {
                val layer = layerById(layerId) ?: return@withState false
                pushUndo()
                layer.blendMode = blendMode
                dirty = true
                emit(CanvasInvalidationEvent.LayersChanged)
                true
            }

        override suspend fun setLayerAlphaLock(
            layerId: Long,
            isLocked: Boolean?,
        ): Boolean =
            withState {
                val layer = layerById(layerId) ?: return@withState false
                pushUndo()
                layer.isAlphaLocked = isLocked ?: !layer.isAlphaLocked
                dirty = true
                emit(CanvasInvalidationEvent.LayersChanged)
                true
            }

        override suspend fun setLayerClippingMask(
            layerId: Long,
            isClipping: Boolean?,
        ): Boolean =
            withState {
                val layer = layerById(layerId) ?: return@withState false
                pushUndo()
                layer.isClippingMask = isClipping ?: !layer.isClippingMask
                dirty = true
                emit(CanvasInvalidationEvent.LayersChanged)
                true
            }

        override suspend fun setLayerReference(
            layerId: Long,
            isReference: Boolean,
        ): Boolean =
            withState {
                val layer = layerById(layerId) ?: return@withState false
                pushUndo()
                layer.isReference = isReference
                dirty = true
                emit(CanvasInvalidationEvent.LayersChanged)
                true
            }

        override suspend fun linkLayers(layerIds: List<Long>): Boolean =
            withState {
                if (layerIds.size < 2) return@withState false
                pushUndo()
                val groupId = System.nanoTime()
                layerIds.forEach { id -> layerById(id)?.linkGroupId = groupId }
                dirty = true
                emit(CanvasInvalidationEvent.LayersChanged)
                true
            }

        override suspend fun unlinkLayer(layerId: Long): Boolean =
            withState {
                val layer = layerById(layerId) ?: return@withState false
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

        override suspend fun addLayerMask(
            layerId: Long,
            fromSelection: SelectionMask?,
        ): Boolean =
            withState {
                val layer = layerById(layerId) ?: return@withState false
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

        override suspend fun removeLayerMask(layerId: Long): Boolean =
            withState {
                val layer = layerById(layerId) ?: return@withState false
                if (layer.mask == null && layer.maskFile == null) return@withState false
                pushUndo()
                layer.mask = null
                layer.maskFile = null
                dirty = true
                emit(CanvasInvalidationEvent.Full)
                true
            }

        override suspend fun invertLayerMask(layerId: Long): Boolean =
            withState {
                val layer = layerById(layerId) ?: return@withState false
                pushUndo()
                layer.maskInverted = !layer.maskInverted
                dirty = true
                emit(CanvasInvalidationEvent.Full)
                true
            }

        override suspend fun setLayerMaskEnabled(
            layerId: Long,
            enabled: Boolean,
        ): Boolean =
            withState {
                val layer = layerById(layerId) ?: return@withState false
                pushUndo()
                layer.maskEnabled = enabled
                dirty = true
                emit(CanvasInvalidationEvent.Full)
                true
            }

        override suspend fun setLayerMaskDensity(
            layerId: Long,
            density: Float,
        ): Boolean =
            withState {
                val layer = layerById(layerId) ?: return@withState false
                pushUndo()
                layer.maskDensity = density.coerceIn(0f, 1f)
                dirty = true
                emit(CanvasInvalidationEvent.Full)
                true
            }

        override suspend fun setLayerMaskFeather(
            layerId: Long,
            radius: Float,
        ): Boolean =
            withState {
                val layer = layerById(layerId) ?: return@withState false
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
            reveal: Boolean,
        ): Boolean =
            withState {
                val layer = layerById(layerId) ?: return@withState false
                // Copy-on-write: the mask is cloned once per gesture, not once per pointer sample.
                if (!layer.maskOwned) {
                    pushUndo()
                    layer.mask = layer.mask?.copy()
                        ?: PixelBuffer.filled(canvasWidth, canvasHeight, 0xFFFFFFFF.toInt())
                    layer.maskOwned = true
                    layer.maskFile = null
                }
                val mask = layer.mask ?: return@withState false
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
                    mode = Stamping.Mode.REPLACE,
                )
                dirtyRasters += layer.id
                dirty = true
                emit(CanvasInvalidationEvent.Full)
                true
            }

        // -----------------------------------------------------------------------------------------
        // Adjustments and filters
        // -----------------------------------------------------------------------------------------

        override suspend fun addAdjustmentLayer(
            type: AdjustmentType,
            index: Int?,
        ): Layer? =
            withState {
                pushUndo()
                val layers = currentLayers()
                val layerId = nextLayerId++
                val layer =
                    LayerData(
                        id = layerId,
                        name = type.displayName,
                        adjustmentType = type,
                        adjustmentParams = type.defaultParameters.toMutableMap(),
                    )
                val activeIndex = layers.indexOfFirst { it.id == activeLayerId() }
                val insertAt = (index ?: (activeIndex + 1)).coerceIn(0, layers.size)
                layers.add(insertAt, layer)
                setActiveLayerId(layerId)
                dirty = true
                emit(CanvasInvalidationEvent.Full)
                layer.toDomain(insertAt)
            }

        override suspend fun setAdjustmentParameter(
            layerId: Long,
            key: String,
            value: Float,
        ): Boolean =
            withState {
                val layer = layerById(layerId) ?: return@withState false
                val type = layer.adjustmentType ?: return@withState false
                layer.adjustmentParams[key] = type.validateParameter(key, value)
                dirty = true
                emit(CanvasInvalidationEvent.Full)
                true
            }

        override suspend fun setAdjustmentParameters(
            layerId: Long,
            values: Map<String, Float>,
        ): Boolean =
            withState {
                val layer = layerById(layerId) ?: return@withState false
                val type = layer.adjustmentType ?: return@withState false
                values.forEach { (key, value) ->
                    layer.adjustmentParams[key] = type.validateParameter(key, value)
                }
                dirty = true
                emit(CanvasInvalidationEvent.Full)
                true
            }

        override suspend fun resetAdjustment(layerId: Long): Boolean =
            withState {
                val layer = layerById(layerId) ?: return@withState false
                val type = layer.adjustmentType ?: return@withState false
                pushUndo()
                layer.adjustmentParams = type.defaultParameters.toMutableMap()
                dirty = true
                emit(CanvasInvalidationEvent.Full)
                true
            }

        override suspend fun addFilterLayer(
            type: FilterType,
            index: Int?,
        ): Layer? =
            withState {
                pushUndo()
                val layers = currentLayers()
                val layerId = nextLayerId++
                val layer =
                    LayerData(
                        id = layerId,
                        name = type.displayName,
                        filterType = type,
                        filterAmount = type.defaultAmount,
                    )
                val activeIndex = layers.indexOfFirst { it.id == activeLayerId() }
                val insertAt = (index ?: (activeIndex + 1)).coerceIn(0, layers.size)
                layers.add(insertAt, layer)
                setActiveLayerId(layerId)
                dirty = true
                emit(CanvasInvalidationEvent.Full)
                layer.toDomain(insertAt)
            }

        override suspend fun setFilterAmount(
            layerId: Long,
            amount: Float,
        ): Boolean =
            withState {
                val layer = layerById(layerId) ?: return@withState false
                if (layer.filterType == null) return@withState false
                layer.filterAmount = amount.coerceIn(0f, 1f)
                dirty = true
                emit(CanvasInvalidationEvent.Full)
                true
            }

        override suspend fun rasterizeFilterLayer(layerId: Long): Boolean =
            withState {
                val layers = currentLayers()
                val index = layers.indexOfFirst { it.id == layerId }
                if (index <= 0) return@withState false
                val filterLayer = layers[index]
                val type = filterLayer.filterType ?: return@withState false

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
            anchor: CanvasOperations.Anchor,
        ): Boolean =
            withState {
                if (!CanvasOperations.isSizeSafe(width, height)) return@withState false
                requireCanvasMemory(width, height)
                if (width == canvasWidth && height == canvasHeight) return@withState true
                pushUndo()

                val properties = CanvasOperations.CanvasProperties(canvasWidth, canvasHeight, canvasDpi, backgroundColor)
                allLayers().forEach { layer ->
                    val buffer = layer.raster ?: return@forEach
                    val result =
                        if (resample) {
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
                        val result =
                            if (resample) {
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

        override suspend fun cropCanvas(bounds: IntBounds): Boolean =
            withState {
                val clamped = bounds.intersect(IntBounds(0, 0, canvasWidth - 1, canvasHeight - 1))
                if (clamped.isEmpty) return@withState false
                if (clamped.width == canvasWidth && clamped.height == canvasHeight) return@withState true
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
                        val shifted =
                            layer.strokes.map { stroke ->
                                stroke.copy(
                                    points =
                                        stroke.points.map { point ->
                                            point.copy(x = point.x - clamped.left, y = point.y - clamped.top)
                                        },
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

        override suspend fun rotateCanvas(degrees: Int): Boolean =
            withState {
                val normalized = ((degrees % 360) + 360) % 360
                if (normalized == 0) return@withState true
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

        override suspend fun flipCanvas(vertical: Boolean): Boolean =
            withState {
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

        override suspend fun trimTransparent(): Boolean =
            withState {
                val composite = rasterizeLayers(currentLayers().filter { it.isVisible })
                val content = composite.contentBounds() ?: return@withState false
                cropCanvas(content)
            }

        override suspend fun setCanvasDpi(dpi: Int): Boolean =
            withState {
                val clamped = dpi.coerceIn(CanvasOperations.MIN_DPI, CanvasOperations.MAX_DPI)
                if (clamped == canvasDpi) return@withState true
                pushUndo()
                canvasDpi = clamped
                dirty = true
                true
            }

        override suspend fun setCanvasBackgroundColor(color: Int): Boolean =
            withState {
                pushUndo()
                backgroundColor = color
                dirty = true
                emit(CanvasInvalidationEvent.Full)
                true
            }

        override suspend fun clearCanvas(color: Int) =
            withState {
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
            toAllLayers: Boolean,
        ): Boolean =
            withState {
                val targets = if (toAllLayers) currentLayers() else listOfNotNull(activeLayerData())
                if (targets.isEmpty()) return@withState false
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

        override fun frames(): List<AnimationFrame> =
            frameList.map { frame ->
                AnimationFrame(
                    id = frame.id,
                    name = frame.name,
                    layers = frame.layers.mapIndexed { index, data -> data.toDomain(index) },
                    durationMs = frame.durationMs,
                    isKeyframe = frame.isKeyframe,
                )
            }

        override fun activeFrameIndex(): Int = activeFrame

        override suspend fun addFrame(duplicateCurrent: Boolean) =
            withState {
                require(frameList.size < ProjectStorage.MAX_FRAMES) { "Maximum ${ProjectStorage.MAX_FRAMES} frames reached" }
                pushUndo()
                val source = frameList.getOrNull(activeFrame)
                val frame =
                    if (duplicateCurrent && source != null) {
                        FrameData(
                            id = nextFrameId++,
                            name = "${source.name} copy",
                            durationMs = source.durationMs,
                            isKeyframe = source.isKeyframe,
                            layers =
                                source.layers
                                    .map { layer ->
                                        val newId = nextLayerId++
                                        layer
                                            .duplicate(newId, layer.name) { nextStrokeId++ }
                                            .also { dirtyRasters += newId }
                                    }.toMutableList(),
                        )
                    } else {
                        FrameData(
                            id = nextFrameId++,
                            name = "Frame ${frameList.size + 1}",
                            durationMs = animationSettings.frameDurationMs,
                            layers = mutableListOf(backgroundLayer()),
                        )
                    }
                val insertAt = (activeFrame + 1).coerceIn(0, frameList.size)
                frameList.add(insertAt, frame)
                activeFrame = insertAt
                dirty = true
                syncTimeline()
                emit(CanvasInvalidationEvent.FrameChanged(activeFrame))
            }

        override suspend fun deleteFrame(index: Int): Boolean =
            withState {
                if (frameList.size <= 1 || index !in frameList.indices) return@withState false
                pushUndo()
                frameList.removeAt(index)
                activeFrame =
                    when {
                        index < activeFrame -> activeFrame - 1
                        index == activeFrame -> index.coerceAtMost(frameList.lastIndex)
                        else -> activeFrame
                    }.coerceIn(0, frameList.lastIndex)
                dirty = true
                syncTimeline()
                emit(CanvasInvalidationEvent.FrameChanged(activeFrame))
                true
            }

        override suspend fun moveFrame(
            from: Int,
            to: Int,
        ): Boolean =
            withState {
                if (from !in frameList.indices) return@withState false
                val target = to.coerceIn(0, frameList.lastIndex)
                if (from == target) return@withState true
                pushUndo()
                val frame = frameList.removeAt(from)
                frameList.add(target, frame)
                activeFrame = target
                dirty = true
                syncTimeline()
                emit(CanvasInvalidationEvent.FrameChanged(activeFrame))
                true
            }

        override suspend fun selectFrame(index: Int) =
            withState {
                val clamped = index.coerceIn(0, frameList.lastIndex.coerceAtLeast(0))
                if (clamped == activeFrame) return@withState
                activeFrame = clamped
                syncTimeline()
                emit(CanvasInvalidationEvent.FrameChanged(activeFrame))
            }

        override suspend fun setFrameDuration(
            index: Int,
            durationMs: Int,
        ): Boolean =
            withState {
                val frame = frameList.getOrNull(index) ?: return@withState false
                pushUndo()
                frame.durationMs =
                    durationMs.coerceIn(
                        AnimationFrame.MIN_DURATION_MS,
                        AnimationFrame.MAX_DURATION_MS,
                    )
                dirty = true
                syncTimeline()
                true
            }

        override suspend fun updateAnimationSettings(settings: AnimationSettings) =
            withState {
                pushUndo()
                val oldDuration = animationSettings.frameDurationMs
                val fpsChanged = settings.fps != animationSettings.fps
                animationSettings =
                    settings.copy(
                        fps = settings.fps.coerceIn(AnimationSettings.MIN_FPS, AnimationSettings.MAX_FPS),
                        onionSkinFrames = settings.onionSkinFrames.coerceIn(0, AnimationSettings.MAX_ONION_SKIN_FRAMES),
                        onionSkinOpacity = settings.onionSkinOpacity.coerceIn(0.05f, 1f),
                    )
                if (fpsChanged) {
                    frameList.filter { it.durationMs == oldDuration }.forEach {
                        it.durationMs =
                            animationSettings.frameDurationMs
                    }
                }
                dirty = true
                syncTimeline()
                emit(CanvasInvalidationEvent.Full)
            }

        override suspend fun compositeAllFrames(maxFrames: Int): List<PixelBuffer> {
            val snapshot = withState { currentSnapshot() to canvasSnapshot() }
            val frames = snapshot.first.frames
            require(frames.size <= maxFrames) { "Export would exceed the $maxFrames frame limit; no frames were exported" }
            val required = snapshot.second.width.toLong() * snapshot.second.height * 4L * frames.size
            require(required <= Runtime.getRuntime().maxMemory() / 4) { "Animation is too large to export at this size on this device" }
            return withContext(Dispatchers.Default) { frames.map { renderFrozen(it.layers, snapshot.second) } }
        }

        override suspend fun compositeFrame(
            index: Int,
            transparentBackground: Boolean,
        ): PixelBuffer? {
            val snapshot =
                withState {
                    val frame = frameList.getOrNull(index) ?: return@withState null
                    markRastersShared()
                    frame.layers.map { it.snapshotCopy() } to canvasSnapshot()
                } ?: return null
            return withContext(Dispatchers.Default) {
                renderFrozen(snapshot.first, snapshot.second, transparentBackground = transparentBackground)
            }
        }

        override suspend fun compositeBuffer(
            includeHidden: Boolean,
            applyAdjustments: Boolean,
        ): PixelBuffer? {
            val snapshot =
                withState {
                    markRastersShared()
                    currentLayers().map { it.snapshotCopy() } to canvasSnapshot()
                }
            return withContext(Dispatchers.Default) { renderFrozen(snapshot.first, snapshot.second, includeHidden, applyAdjustments) }
        }

        override suspend fun compositePreview(): PixelBuffer? {
            val snapshot =
                withState {
                    markRastersShared()
                    val layers =
                        currentLayers().map { layer ->
                            layer.snapshotCopy().also { copy ->
                                pendingEdits.values.firstOrNull { it.layer === layer }?.let { pending ->
                                    copy.raster = pending.session.buffer.copy()
                                }
                            }
                        }
                    layers to canvasSnapshot()
                }
            return withContext(Dispatchers.Default) {
                renderFrozen(snapshot.first, snapshot.second, transparentBackground = true)
            }
        }

        override suspend fun exportSnapshot(
            allFrames: Boolean,
            includeHidden: Boolean,
            includeLayers: Boolean,
        ): CanvasExportSnapshot {
            val frozen = withState { Triple(currentSnapshot(), canvasSnapshot(), activeSelection?.copy()) }
            val snapshot = frozen.first
            val frames = if (allFrames) snapshot.frames else listOf(snapshot.frames[snapshot.activeFrame])
            val selectedLayers = frames.first().layers.filter { !it.isReference && (includeHidden || it.isVisible) }
            val buffers = frames.size + (if (includeLayers) selectedLayers.size else 0)
            val bytes = snapshot.width.toLong() * snapshot.height * 4L * (buffers + 2)
            require(bytes <= Runtime.getRuntime().maxMemory() / 3) { "This export is too large for the available memory" }
            return withContext(Dispatchers.Default) {
                val renderer = Compositor(StrokeRasterizer())
                try {
                    val layerPixels =
                        if (!includeLayers) {
                            emptyList()
                        } else {
                            selectedLayers.mapIndexedNotNull { index, data ->
                                val layer = data.toDomain(index)
                                val input = Compositor.LayerInput(layer, data.raster, data.strokes.toList(), data.mask)
                                val pixels =
                                    renderer.renderLayerContent(input, snapshot.width, snapshot.height)
                                        ?: return@mapIndexedNotNull null
                                layer to pixels
                            }
                        }
                    CanvasExportSnapshot(
                        frames = frames.map { renderFrozen(it.layers, frozen.second, includeHidden, transparentBackground = true) },
                        delaysMs = frames.map { it.durationMs },
                        layers = layerPixels,
                        selection = frozen.third,
                        hasAdjustmentLayers = selectedLayers.any { it.adjustmentType != null },
                    )
                } finally {
                    renderer.release()
                }
            }
        }

        override suspend fun layerBuffers(): List<Pair<Layer, PixelBuffer>> =
            withState {
                markRastersShared()
                currentLayers().mapIndexedNotNull { index, data ->
                    val buffer =
                        data.raster?.copy() ?: run {
                            if (data.strokes.isEmpty()) return@mapIndexedNotNull null
                            strokeRasterizer().rasterize(data.strokes, canvasWidth, canvasHeight)
                        }
                    data.toDomain(index) to buffer
                }
            }

        private fun renderFrozen(
            layers: List<LayerData>,
            document: CanvasDocument,
            includeHidden: Boolean = false,
            applyAdjustments: Boolean = true,
            transparentBackground: Boolean = false,
        ): PixelBuffer {
            val localCompositor = Compositor(StrokeRasterizer())
            return try {
                localCompositor.composite(
                    layers.mapIndexed {
                            index,
                            layer,
                        ->
                        Compositor.LayerInput(layer.toDomain(index), layer.raster, layer.strokes.toList(), layer.mask)
                    },
                    document.width,
                    document.height,
                    if (transparentBackground) 0 else document.backgroundColor,
                    Compositor.Options(includeHiddenLayers = includeHidden, applyAdjustments = applyAdjustments),
                )
            } finally {
                localCompositor.release()
            }
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
            while (undoStack.size > 1 && rasterBytes(undoStack) > minOf(MAX_RASTER_HISTORY_BYTES, Runtime.getRuntime().maxMemory() / 4)) {
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
            val layers: List<LayerData>,
            val isKeyframe: Boolean,
        )

        private data class Snapshot(
            val frames: List<FrameSnapshot>,
            val activeFrame: Int,
            val activeLayerId: Long,
            val width: Int,
            val height: Int,
            val dpi: Int,
            val backgroundColor: Int,
            val animation: AnimationSettings,
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
        private fun currentSnapshot(): Snapshot =
            Snapshot(
                frames =
                    frameList.map { frame ->
                        FrameSnapshot(
                            id = frame.id,
                            name = frame.name,
                            durationMs = frame.durationMs,
                            activeLayerId = frame.activeLayerId,
                            layers = frame.layers.map { it.snapshotCopy() },
                            isKeyframe = frame.isKeyframe,
                        )
                    },
                activeFrame = activeFrame,
                activeLayerId = activeLayerId(),
                width = canvasWidth,
                height = canvasHeight,
                dpi = canvasDpi,
                backgroundColor = backgroundColor,
                animation = animationSettings,
            ).also { markRastersShared() }

        private fun restore(snapshot: Snapshot) {
            frameList =
                snapshot.frames
                    .map { frame ->
                        FrameData(
                            id = frame.id,
                            name = frame.name,
                            durationMs = frame.durationMs,
                            layers = frame.layers.map { it.snapshotCopy() }.toMutableList(),
                            activeLayerId = frame.activeLayerId,
                            isKeyframe = frame.isKeyframe,
                        )
                    }.toMutableList()
            activeFrame = snapshot.activeFrame.coerceIn(0, frameList.lastIndex.coerceAtLeast(0))
            setActiveLayerId(snapshot.activeLayerId)
            canvasWidth = snapshot.width
            canvasHeight = snapshot.height
            canvasDpi = snapshot.dpi
            backgroundColor = snapshot.backgroundColor
            animationSettings = snapshot.animation
            pendingEdits.clear()
            activeSelection = null
            activeStrokes.clear()
            strokeBrushParams.clear()
            strokeLayerIds.clear()
            strokeErasers.clear()
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
            applyAdjustments: Boolean = true,
        ): PixelBuffer {
            val inputs =
                layers.mapIndexed { index, data ->
                    Compositor.LayerInput(
                        layer = data.toDomain(index),
                        raster = data.raster,
                        strokes = data.strokes.toList(),
                        mask = data.mask,
                    )
                }
            return compositor.composite(
                inputs = inputs,
                width = canvasWidth,
                height = canvasHeight,
                backgroundColor = backgroundColor,
                options =
                    Compositor.Options(
                        includeHiddenLayers = includeHidden,
                        selection = null,
                        applyAdjustments = applyAdjustments,
                    ),
            )
        }

        private fun requireCanvasMemory(
            width: Int,
            height: Int,
        ) {
            val workingBytes = width.toLong() * height * 4L * 6L
            require(workingBytes <= Runtime.getRuntime().maxMemory() * 3 / 5) {
                "This canvas is too large for this device; choose smaller dimensions"
            }
        }

        private fun scaleDown(
            buffer: PixelBuffer,
            maxSize: Int,
        ): PixelBuffer {
            val scale =
                minOf(
                    maxSize.toFloat() / buffer.width.coerceAtLeast(1),
                    maxSize.toFloat() / buffer.height.coerceAtLeast(1),
                ).coerceAtMost(1f)
            if (scale >= 1f) return buffer
            return buffer.scaled(
                (buffer.width * scale).roundToInt().coerceAtLeast(1),
                (buffer.height * scale).roundToInt().coerceAtLeast(1),
            )
        }

        private fun currentLayers(): MutableList<LayerData> = frameList[activeFrame.coerceIn(0, frameList.lastIndex)].layers

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
            _timeline.value =
                AnimationTimeline.State(
                    frames = frames(),
                    activeIndex = activeFrame,
                    settings = animationSettings,
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
                hasUnsavedChanges = dirty,
            )
        }

        private fun canvasSnapshot(): CanvasDocument =
            CanvasDocument(
                width = canvasWidth,
                height = canvasHeight,
                dpi = canvasDpi,
                backgroundColor = backgroundColor,
                activeLayerId = activeLayerId(),
                nextLayerId = nextLayerId,
                layers = currentLayers().mapIndexed { index, data -> data.toDomain(index) },
                frames = frames(),
                activeFrameIndex = activeFrame,
                animation = animationSettings,
            )

        private fun emit(event: CanvasInvalidationEvent) {
            invalidationFlow.tryEmit(event)
        }

        private fun emitAsync(event: CanvasInvalidationEvent) {
            coroutineScope.launch { invalidationFlow.emit(event) }
        }

        override fun dispose() {
            compositor.release()
            pendingEdits.clear()
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
            var isInternal: Boolean = false,
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

            fun canPaint(): Boolean =
                isVisible &&
                    !isLocked &&
                    !isReference &&
                    adjustmentType == null &&
                    filterType == null

            /** Copy for an undo snapshot: shares pixel buffers (copy-on-write) and copies the list. */
            fun snapshotCopy(): LayerData =
                LayerData(
                    id = id,
                    name = name,
                    isVisible = isVisible,
                    opacity = opacity,
                    isLocked = isLocked,
                    blendMode = blendMode,
                    strokes = strokes.toMutableList(),
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
                    isInternal = isInternal,
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
            fun duplicate(
                newId: Long,
                newName: String,
                nextStrokeId: () -> Long,
            ): LayerData =
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
                    isInternal = isInternal,
                ).also { fresh ->
                    fresh.raster = raster
                    fresh.mask = mask
                    fresh.maskOwned = false
                }

            fun toDomain(index: Int): Layer =
                Layer(
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
                    isInternal = isInternal,
                    hasInMemoryMask = mask != null,
                )
        }

        /** One animation frame's in-memory state. */
        private class FrameData(
            val id: Long,
            var name: String,
            var durationMs: Int,
            val layers: MutableList<LayerData>,
            var isKeyframe: Boolean = false,
            var activeLayerId: Long = layers.firstOrNull()?.id ?: 0L,
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
