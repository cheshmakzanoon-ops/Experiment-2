package com.artflow.studio.data.repository.canvas

import com.artflow.studio.core.animation.AnimationTimeline
import com.artflow.studio.core.canvas.CanvasOperations
import com.artflow.studio.core.pixels.AdjustmentProcessor
import com.artflow.studio.core.pixels.IntBounds
import com.artflow.studio.core.pixels.LayerMaskFactory
import com.artflow.studio.core.pixels.LayerMaskSource
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.core.pixels.SelectionMask
import com.artflow.studio.core.render.Compositor
import com.artflow.studio.core.render.LayerStrokeRenderer
import com.artflow.studio.core.render.StrokeRasterizer
import com.artflow.studio.core.symmetry.SymmetryEngine
import com.artflow.studio.data.local.CanvasDocument
import com.artflow.studio.data.local.ProjectStorage
import com.artflow.studio.data.renderer.BitmapPixelBridge
import com.artflow.studio.domain.model.animation.AnimationFrame
import com.artflow.studio.domain.model.animation.AnimationSettings
import com.artflow.studio.domain.model.brush.BrushParams
import com.artflow.studio.domain.model.brush.Stroke
import com.artflow.studio.domain.model.brush.StrokeDestination
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
        private val strokeDestinations = mutableMapOf<Long, StrokeDestination>()

        private var strokeColor: Int = 0xFF000000.toInt()
        private var symmetrySettings = SymmetryEngine.Settings()
        private var activeSelection: SelectionMask? = null

        private data class PendingSelection(
            val session: CanvasRepository.SelectionEditSession,
            val revision: Long,
        )

        private var pendingSelection: PendingSelection? = null

        private val _timeline = MutableStateFlow(AnimationTimeline.State())
        override val timeline: StateFlow<AnimationTimeline.State> = _timeline.asStateFlow()

        private val invalidationFlow = MutableSharedFlow<CanvasInvalidationEvent>(replay = 1, extraBufferCapacity = 63)

        private val undoStack = ArrayDeque<Snapshot>()
        private val redoStack = ArrayDeque<Snapshot>()

        private var editRevision = 0L
        override val contentRevision: Long get() = editRevision
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
            val originalStrokes: List<Stroke>,
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
            editRevision++

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
            pendingSelection = null
            activeSelection = null
            activeStrokes.clear()
            strokeBrushParams.clear()
            strokeLayerIds.clear()
            strokeErasers.clear()
            strokeDestinations.clear()
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
            editRevision++
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
            pendingSelection = null
            activeSelection = null
            activeStrokes.clear()
            strokeBrushParams.clear()
            strokeLayerIds.clear()
            strokeErasers.clear()
            strokeDestinations.clear()
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
            destination: StrokeDestination,
        ): Long {
            val strokeId = nextStrokeId++
            val layer = layerById(layerId)
            if (layer == null || !canReceiveStroke(layer, destination) || !validSample(x, y, pressure)) {
                Timber.w("Stroke rejected on unavailable or non-editable layer $layerId")
                return 0L
            }
            activeStrokes[strokeId] =
                mutableListOf(
                    StrokePoint(x = x, y = y, pressure = pressure, color = destination.color(strokeColor, layer.maskInverted)),
                )
            strokeBrushParams[strokeId] =
                if (destination.isMask) {
                    brushParams.copy(
                        hueJitter = 0f,
                        saturationJitter = 0f,
                        brightnessJitter = 0f,
                        colorPressure = false,
                        velocityToHue = 0f,
                        wetMix = 0f,
                    )
                } else {
                    brushParams
                }
            strokeLayerIds[strokeId] = layerId
            strokeErasers[strokeId] = isEraser && !destination.isMask
            strokeDestinations[strokeId] = destination
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
            if (!validSample(x, y, pressure) || !tiltX.isFinite() || !tiltY.isFinite()) return
            val points = activeStrokes[strokeId] ?: return
            points.add(
                StrokePoint(
                    x = x,
                    y = y,
                    pressure = pressure,
                    tiltX = tiltX,
                    tiltY = tiltY,
                    color = points.first().color,
                ),
            )
        }

        override fun endStroke(strokeId: Long) {
            val points = activeStrokes.remove(strokeId) ?: return
            val brushParams = strokeBrushParams.remove(strokeId) ?: return
            val layerId = strokeLayerIds.remove(strokeId) ?: return
            val isEraser = strokeErasers.remove(strokeId) ?: false
            val destination = strokeDestinations.remove(strokeId) ?: StrokeDestination.LAYER

            val layer =
                layerById(layerId) ?: run {
                    Timber.w("Dropping stroke for unknown layer $layerId")
                    return
                }
            if (!canReceiveStroke(layer, destination)) {
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
            // Preview and commit share this exact raw-pixel operation. Compute first so a failed
            // allocation or render cannot add an undo entry or modify the committed document.
            val incoming = SymmetryEngine.mirrorStroke(stroke, canvasWidth, canvasHeight, symmetrySettings)
            val base =
                LayerStrokeRenderer.render(
                    if (destination.isMask) layer.mask else layer.raster,
                    if (destination.isMask) emptyList() else layer.strokes.toList(),
                    incoming,
                    canvasWidth,
                    canvasHeight,
                    !destination.isMask && layer.isAlphaLocked,
                    activeSelection,
                )
            pushUndo()
            if (destination.isMask) {
                layer.mask = base
                layer.maskFile = null
                layer.maskOwned = true
            } else {
                layer.raster = base
                layer.strokes.clear()
                layer.rasterFile = null
            }
            dirtyRasters += layer.id
            dirty = true
            emitAsync(CanvasInvalidationEvent.Full)
        }

        private fun validSample(
            x: Float,
            y: Float,
            pressure: Float,
        ): Boolean = x.isFinite() && y.isFinite() && pressure.isFinite()

        private fun canReceiveStroke(
            layer: LayerData,
            destination: StrokeDestination,
        ): Boolean =
            if (destination.isMask) {
                layer.isVisible && !layer.isLocked && !layer.isReference && layer.mask != null && layer.maskEnabled
            } else {
                layer.canPaint()
            }

        override fun cancelStroke(strokeId: Long) {
            activeStrokes.remove(strokeId)
            strokeBrushParams.remove(strokeId)
            strokeLayerIds.remove(strokeId)
            strokeErasers.remove(strokeId)
            strokeDestinations.remove(strokeId)
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
                if (!layer.canPaint()) return@withState false
                val owned = strokes.map { it.copy(layerId = layerId, points = it.points.toList()) }
                pushUndo()
                layer.strokes.clear()
                layer.strokes.addAll(owned)
                dirty = true
                emit(CanvasInvalidationEvent.Full)
                true
            }

        // -----------------------------------------------------------------------------------------
        // Pixel editing
        // -----------------------------------------------------------------------------------------

        override suspend fun layerPixels(layerId: Long): PixelBuffer? =
            withState { layerById(layerId)?.let { rawLayerPixels(it, canvasWidth, canvasHeight)?.copy() } }

        override suspend fun beginRasterEdit(layerId: Long): CanvasRepository.RasterEditSession? =
            withState {
                val layer = layerById(layerId) ?: return@withState null
                if (!layer.canPaint() || pendingEdits.values.any { it.layer.id == layerId }) return@withState null
                val buffer = rawLayerPixels(layer, canvasWidth, canvasHeight)?.copy() ?: PixelBuffer(canvasWidth, canvasHeight)
                val session = CanvasRepository.RasterEditSession(layerId, buffer, ++rasterEditToken, editRevision, layer.isAlphaLocked)
                pendingEdits[session.snapshotToken] = PendingEdit(currentProjectId, layer, layer.raster, layer.strokes.toList(), session)
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
                if (layer.strokes != pending.originalStrokes) return@withState false
                if (layer.isAlphaLocked != session.alphaLocked) return@withState false
                if (session.buffer.width != canvasWidth || session.buffer.height != canvasHeight) return@withState false
                // A session is provisional until here: cancellation cannot remove someone else's undo
                // entry, and autosave/export can never publish half a drag or a failed tool operation.
                pushUndo()
                layer.raster = session.buffer.copy()
                layer.strokes.clear()
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
                layer.strokes.clear()
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

        override fun selection(): SelectionMask? = activeSelection?.copy()

        override fun setSelection(mask: SelectionMask?) {
            require(mask == null || (mask.width == canvasWidth && mask.height == canvasHeight)) {
                "Selection dimensions must match the canvas"
            }
            // Empty coverage means select nothing, not select everything. Only null deselects.
            // Own both sides of the boundary so an asynchronous tool cannot mutate a live mask.
            val owned = mask?.copy()
            pendingSelection = null
            activeSelection = owned
            emitAsync(CanvasInvalidationEvent.Full)
        }

        override fun clearSelection() = setSelection(null)

        override fun beginSelectionEdit(): CanvasRepository.SelectionEditSession {
            val session = CanvasRepository.SelectionEditSession(canvasWidth, canvasHeight, activeSelection?.copy())
            pendingSelection = PendingSelection(session, editRevision)
            return session
        }

        override fun commitSelectionEdit(
            session: CanvasRepository.SelectionEditSession,
            mask: SelectionMask,
        ): Boolean {
            val pending = pendingSelection ?: return false
            if (pending.session !== session) return false
            pendingSelection = null
            if (pending.revision != editRevision || mask.width != canvasWidth || mask.height != canvasHeight) return false
            setSelection(mask)
            return true
        }

        override fun cancelSelectionEdit(session: CanvasRepository.SelectionEditSession) {
            if (pendingSelection?.session === session) pendingSelection = null
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
                require(opacity.isFinite()) { "Layer opacity must be finite" }
                require(hasLayerCapacity(1)) { "Maximum project layer count reached" }
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
                if (position == -1 || !hasLayerCapacity(1)) return@withState null

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
                val layers = currentLayers()
                val source = layers.indexOfFirst { it.id == sourceLayerId }
                val target = layers.indexOfFirst { it.id == targetLayerId }
                // A non-adjacent merge changes intervening compositing order; never silently do so.
                if (source < 0 || target < 0 || kotlin.math.abs(source - target) != 1) return@withState false
                val selected = listOf(layers[source], layers[target])
                if (selected.any { !it.isVisible || it.isLocked || it.isReference }) return@withState false
                val lowerIndex = minOf(source, target)
                mergeStack(selected.map { it.id }.toSet(), targetLayerId, layers[target].name, lowerIndex) != null
            }

        override suspend fun mergeVisibleLayers(keepOriginals: Boolean): Long? =
            withState {
                val layers = currentLayers()
                val selected = layers.filter { it.isVisible && !it.isReference }
                if (selected.size < 2 || selected.any { it.isLocked }) return@withState null
                if (keepOriginals && !hasLayerCapacity(1)) return@withState null
                val merged = mergeStack(selected.map { it.id }.toSet(), nextLayerId, "Merged", layers.size, keepOriginals)
                if (merged != null) nextLayerId++
                merged
            }

        override suspend fun mergeLayerDown(layerId: Long): Boolean =
            withState {
                val layers = currentLayers()
                val index = layers.indexOfFirst { it.id == layerId }
                if (index <= 0) return@withState false
                mergeLayers(layerId, layers[index - 1].id)
            }

        override suspend fun flattenAllLayers(): Long? =
            withState {
                val layers = currentLayers()
                val selected = layers.filter { !it.isReference }
                if (selected.isEmpty() || selected.any { it.isLocked }) return@withState null
                val merged = mergeStack(selected.map { it.id }.toSet(), nextLayerId, "Flattened", layers.size)
                if (merged != null) nextLayerId++
                merged
            }

        /** Prepare off-thread, then atomically publish only if the captured document is still current. */
        private suspend fun mergeStack(
            selectedIds: Set<Long>,
            mergedId: Long,
            name: String,
            insertionIndex: Int,
            keepOriginals: Boolean = false,
        ): Long? {
            if (activeStrokes.isNotEmpty() || pendingEdits.isNotEmpty()) return null
            val live = currentLayers()
            val revision = editRevision
            val project = currentProjectId
            val document = canvasSnapshot()
            val frozen = live.map { it.snapshotCopy() }
            markRastersShared()
            val candidate =
                withContext(Dispatchers.Default) {
                    val selected = frozen.filter { it.id in selectedIds }
                    val baked = LayerData(id = mergedId, name = name)
                    // Raster content excludes the canvas background. All per-layer effects are already
                    // baked; the replacement must have neutral opacity, blend mode, mask and strokes.
                    baked.raster = renderFrozen(selected, document, transparentBackground = true)
                    val remaining = frozen.filter { keepOriginals || it.id !in selectedIds }.map { it.snapshotCopy() }.toMutableList()
                    if (keepOriginals) remaining.filter { it.id in selectedIds }.forEach { it.isVisible = false }
                    val position = frozen.take(insertionIndex).count { keepOriginals || it.id !in selectedIds }
                    remaining.add(position, baked)
                    // An isolated partial merge cannot always represent backdrop-dependent blend or
                    // clipping groups. Reject instead of changing the artwork or pretending success.
                    if (mergePreservesAppearance(frozen, remaining, document)) remaining else null
                } ?: return null
            if (currentProjectId != project || currentLayers() !== live || editRevision != revision) return null
            if (activeStrokes.isNotEmpty() || pendingEdits.isNotEmpty()) return null
            pushUndo()
            live.clear()
            live.addAll(candidate)
            setActiveLayerId(mergedId)
            dirtyRasters += mergedId
            dirty = true
            syncTimeline()
            emit(CanvasInvalidationEvent.LayersChanged)
            return mergedId
        }

        private fun mergePreservesAppearance(
            original: List<LayerData>,
            candidate: List<LayerData>,
            document: CanvasDocument,
        ): Boolean =
            listOf(true, false).all { transparent ->
                val before = renderFrozen(original, document, transparentBackground = transparent)
                val after = renderFrozen(candidate, document, transparentBackground = transparent)
                before.pixels.indices.all { index -> sameVisiblePixel(before.pixels[index], after.pixels[index]) }
            }

        private fun sameVisiblePixel(
            a: Int,
            b: Int,
        ): Boolean {
            // Source-over regrouping may differ by one 8-bit rounding unit. Ignore only RGB
            // under fully transparent pixels; alpha itself must always match within one unit.
            if (kotlin.math.abs((a ushr 24) - (b ushr 24)) > 1) return false
            if (a ushr 24 == 0 && b ushr 24 == 0) return true
            for (shift in 0..16 step 8) {
                if (kotlin.math.abs(((a ushr shift) and 255) - ((b ushr shift) and 255)) > 1) return false
            }
            return true
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
                if (!opacity.isFinite()) return@withState false
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
                if (layer.isLocked || layer.isReference || layer.mask != null) return@withState false
                if (fromSelection != null &&
                    (fromSelection.width != canvasWidth || fromSelection.height != canvasHeight)
                ) {
                    return@withState false
                }
                val mask = fromSelection?.toMaskBitmap() ?: PixelBuffer.filled(canvasWidth, canvasHeight, 0xFFFFFFFF.toInt())
                pushUndo()
                layer.mask = mask
                layer.maskEnabled = true
                layer.maskInverted = false
                layer.maskFile = null
                dirtyRasters += layer.id
                dirty = true
                emit(CanvasInvalidationEvent.Full)
                true
            }

        override suspend fun createLayerMask(
            layerId: Long,
            source: LayerMaskSource,
        ): Boolean =
            withState {
                val layer = layerById(layerId) ?: return@withState false
                if (layer.isLocked || layer.isReference || layer.mask != null) return@withState false
                val selection = activeSelection?.copy()
                if (source == LayerMaskSource.SELECTION && selection == null) return@withState false
                val revision = editRevision
                val width = canvasWidth
                val height = canvasHeight
                val project = currentProjectId
                markRastersShared()
                val frozen = layer.snapshotCopy()
                val mask =
                    withContext(Dispatchers.Default) {
                        val pixels =
                            if (source == LayerMaskSource.LAYER_ALPHA) {
                                LayerStrokeRenderer.render(
                                    frozen.raster,
                                    frozen.strokes.toList(),
                                    emptyList(),
                                    width,
                                    height,
                                    frozen.isAlphaLocked,
                                    null,
                                )
                            } else {
                                null
                            }
                        LayerMaskFactory.create(source, width, height, pixels, selection)
                    }
                if (project != currentProjectId || revision != editRevision || layerById(layerId) !== layer) return@withState false
                pushUndo()
                layer.mask = mask
                layer.maskEnabled = true
                layer.maskInverted = false
                layer.maskDensity = 1f
                layer.maskFeather = 0f
                layer.maskFile = null
                layer.maskOwned = true
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
                if (!density.isFinite()) return@withState false
                val clamped = density.coerceIn(0f, 1f)
                if (layer.maskDensity == clamped) return@withState true
                pushUndo()
                layer.maskDensity = clamped
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
                if (!radius.isFinite()) return@withState false
                val clamped = radius.coerceIn(0f, 64f)
                if (layer.maskFeather == clamped) return@withState true
                pushUndo()
                layer.maskFeather = clamped
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
                if (!radius.isFinite() || radius <= 0f) return@withState false
                val destination = if (reveal) StrokeDestination.MASK_REVEAL else StrokeDestination.MASK_HIDE
                val params = BrushParams(size = (radius * 2f).coerceAtMost(512f), pressureToSize = 0f, pressureToOpacity = 0f)
                val id = beginStroke(x, y, 1f, params, layerId, false, destination)
                if (id == 0L) return@withState false
                endStroke(id)
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
                if (!hasLayerCapacity(1)) return@withState null
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
        ): Boolean = setAdjustmentParameters(layerId, mapOf(key to value))

        override suspend fun setAdjustmentParameters(
            layerId: Long,
            values: Map<String, Float>,
        ): Boolean =
            withState {
                val layer = layerById(layerId) ?: return@withState false
                val type = layer.adjustmentType ?: return@withState false
                // Validate the entire batch before touching live state or history. Preparing
                // an owned map also prevents a caller's later changes from altering the edit.
                val replacement = layer.adjustmentParams.toMutableMap()
                for ((key, value) in values) {
                    val range = type.parameterRanges[key] ?: return@withState false
                    if (!value.isFinite()) return@withState false
                    replacement[key] = value.coerceIn(range)
                }
                if (replacement == layer.adjustmentParams) return@withState true
                pushUndo()
                layer.adjustmentParams = replacement
                dirty = true
                emit(CanvasInvalidationEvent.Full)
                true
            }

        override suspend fun resetAdjustment(layerId: Long): Boolean =
            withState {
                val layer = layerById(layerId) ?: return@withState false
                val type = layer.adjustmentType ?: return@withState false
                if (layer.adjustmentParams == type.defaultParameters) return@withState true
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
                if (!hasLayerCapacity(1)) return@withState null
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
                if (layer.filterType == null || !amount.isFinite()) return@withState false
                val clamped = amount.coerceIn(0f, 1f)
                if (layer.filterAmount == clamped) return@withState true
                pushUndo()
                layer.filterAmount = clamped
                dirty = true
                emit(CanvasInvalidationEvent.Full)
                true
            }

        override suspend fun rasterizeFilterLayer(layerId: Long): Boolean =
            withState {
                val layer = layerById(layerId) ?: return@withState false
                if (layer.filterType == null) return@withState false
                // Use the exact preview compositor, including opacity/masks and vector ink. A
                // context-dependent filter that cannot be represented by this pair is not baked.
                mergeLayerDown(layerId)
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
                if (width == canvasWidth && height == canvasHeight) return@withState true
                val properties = CanvasOperations.CanvasProperties(canvasWidth, canvasHeight, canvasDpi, backgroundColor)
                transformCanvas(width, height) { buffer, mask ->
                    if (resample) {
                        CanvasOperations.resample(buffer, width, height, properties).buffer
                    } else {
                        val fill = if (mask) 0xFF000000.toInt() else 0
                        CanvasOperations.resizeCanvas(buffer, width, height, anchor, properties, fillColor = fill).buffer
                    }
                }
            }

        override suspend fun cropCanvas(bounds: IntBounds): Boolean =
            withState {
                val clamped = bounds.intersect(IntBounds(0, 0, canvasWidth - 1, canvasHeight - 1))
                if (clamped.isEmpty) return@withState false
                if (clamped.width == canvasWidth && clamped.height == canvasHeight) return@withState true
                transformCanvas(clamped.width, clamped.height) { buffer, _ -> buffer.crop(clamped) }
            }

        override suspend fun rotateCanvas(degrees: Int): Boolean =
            withState {
                val normalized = ((degrees % 360) + 360) % 360
                if (normalized % 90 != 0) return@withState false
                if (normalized == 0) return@withState true
                val swap = normalized == 90 || normalized == 270
                val width = if (swap) canvasHeight else canvasWidth
                val height = if (swap) canvasWidth else canvasHeight
                transformCanvas(width, height) { buffer, _ -> buffer.rotated(normalized) }
            }

        override suspend fun flipCanvas(vertical: Boolean): Boolean =
            withState {
                val properties = CanvasOperations.CanvasProperties(canvasWidth, canvasHeight, canvasDpi, backgroundColor)
                val axis = if (vertical) CanvasOperations.FlipAxis.VERTICAL else CanvasOperations.FlipAxis.HORIZONTAL
                transformCanvas(canvasWidth, canvasHeight) { buffer, _ -> CanvasOperations.flip(buffer, axis, properties).buffer }
            }

        /** Rasterise legacy vectors BEFORE transforming, retaining masks/effects as independent data. */
        private suspend fun transformCanvas(
            width: Int,
            height: Int,
            transform: (PixelBuffer, Boolean) -> PixelBuffer,
        ): Boolean {
            if (activeStrokes.isNotEmpty() || pendingEdits.isNotEmpty()) return false
            requireCanvasMemory(width, height)
            val liveFrames = frameList
            val revision = editRevision
            val project = currentProjectId
            val snapshot = currentSnapshot()
            val planes =
                snapshot.frames.sumOf { frame ->
                    frame.layers.sumOf { layer ->
                        (if (layer.raster != null || layer.strokes.isNotEmpty()) 1L else 0L) + (if (layer.mask != null) 1L else 0L)
                    }
                }
            val required = width.toLong() * height * 4L * (planes + 6L)
            require(
                required <= Runtime.getRuntime().maxMemory() * 3 / 5,
            ) { "This transformation needs more memory than this device provides" }
            val transformed =
                withContext(Dispatchers.Default) {
                    snapshot.frames
                        .map { frame ->
                            val layers =
                                frame.layers
                                    .map { layer ->
                                        coroutineContext.ensureActive()
                                        layer.snapshotCopy().also { result ->
                                            result.raster =
                                                rawLayerPixels(layer, snapshot.width, snapshot.height)?.let { transform(it, false) }
                                            result.strokes.clear()
                                            result.rasterFile = null
                                            result.mask = layer.mask?.let { transform(it, true) }
                                            result.maskFile = null
                                        }
                                    }.toMutableList()
                            FrameData(frame.id, frame.name, frame.durationMs, layers, frame.isKeyframe, frame.activeLayerId)
                        }.toMutableList()
                }
            val documentChanged = currentProjectId != project || frameList !== liveFrames || editRevision != revision
            if (documentChanged || activeFrame != snapshot.activeFrame) return false
            if (activeStrokes.isNotEmpty() || pendingEdits.isNotEmpty()) return false
            pushUndo()
            frameList = transformed
            canvasWidth = width
            canvasHeight = height
            pendingSelection = null
            activeSelection = null
            dirtyRasters.addAll(allLayers().map { it.id })
            dirty = true
            syncTimeline()
            emit(CanvasInvalidationEvent.Full)
            return true
        }

        override suspend fun trimTransparent(): Boolean =
            withState {
                val composite = renderFrozen(currentLayers(), canvasSnapshot(), transparentBackground = true)
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
                if (activeStrokes.isNotEmpty() || pendingEdits.isNotEmpty()) return@withState false
                if (parameters.values.any { !it.isFinite() }) return@withState false
                val selection = activeSelection
                if (selection != null && !selection.isActive()) return@withState false
                val live = currentLayers()
                val revision = editRevision
                val project = currentProjectId
                val targets =
                    (if (toAllLayers) live else listOfNotNull(activeLayerData()))
                        .filter { it.canPaint() && (it.raster != null || it.strokes.isNotEmpty()) }
                        .map { it.snapshotCopy() }
                if (targets.isEmpty()) return@withState false
                val width = canvasWidth
                val height = canvasHeight
                val required = width.toLong() * height * 4L * (targets.size + 6L)
                require(required <= Runtime.getRuntime().maxMemory() * 3 / 5) {
                    "This adjustment needs more memory than this device provides"
                }
                val values = parameters.mapValues { (key, value) -> type.validateParameter(key, value) }
                markRastersShared()
                val changed =
                    withContext(Dispatchers.Default) {
                        targets.associate { layer ->
                            coroutineContext.ensureActive()
                            val raw = requireNotNull(rawLayerPixels(layer, width, height))
                            layer.id to AdjustmentProcessor.apply(raw, type, values, 1f, selection?.coverage)
                        }
                    }
                if (currentProjectId != project || currentLayers() !== live || editRevision != revision) return@withState false
                if (activeSelection !== selection || activeStrokes.isNotEmpty() || pendingEdits.isNotEmpty()) return@withState false
                pushUndo()
                live.filter { it.id in changed }.forEach { layer ->
                    layer.raster = changed.getValue(layer.id)
                    layer.strokes.clear()
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
                val source = frameList.getOrNull(activeFrame)
                val addedLayers = if (duplicateCurrent && source != null) source.layers.size else 1
                require(hasLayerCapacity(addedLayers)) { "Maximum project layer count reached" }
                require(nextFrameId in 1 until Long.MAX_VALUE) { "Frame identifiers are exhausted" }
                pushUndo()
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
                pendingSelection = null
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

        private data class PreviewSnapshot(
            val layers: List<LayerData>,
            val document: CanvasDocument,
            val strokes: List<Stroke>,
            val destinations: Map<Long, StrokeDestination>,
            val selection: SelectionMask?,
            val symmetry: SymmetryEngine.Settings,
        )

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
                    PreviewSnapshot(
                        layers,
                        canvasSnapshot(),
                        activeStrokes.keys.mapNotNull { activeStroke(it) },
                        strokeDestinations.toMap(),
                        activeSelection?.copy(),
                        symmetrySettings,
                    )
                }
            return withContext(Dispatchers.Default) {
                val strokesByLayer = snapshot.strokes.groupBy { it.layerId }
                for (layer in snapshot.layers) {
                    coroutineContext.ensureActive()
                    val active = strokesByLayer[layer.id] ?: continue
                    paintPreviewStrokes(layer, active, snapshot)
                }
                renderFrozen(snapshot.layers, snapshot.document, transparentBackground = true)
            }
        }

        private fun paintPreviewStrokes(
            layer: LayerData,
            active: List<Stroke>,
            snapshot: PreviewSnapshot,
        ) {
            for (stroke in active) {
                val destination = snapshot.destinations[stroke.id] ?: StrokeDestination.LAYER
                if (!canReceiveStroke(layer, destination)) continue
                val incoming = SymmetryEngine.mirrorStroke(stroke, snapshot.document.width, snapshot.document.height, snapshot.symmetry)
                val pixels =
                    LayerStrokeRenderer.render(
                        if (destination.isMask) layer.mask else layer.raster,
                        if (destination.isMask) emptyList() else layer.strokes.toList(),
                        incoming,
                        snapshot.document.width,
                        snapshot.document.height,
                        !destination.isMask && layer.isAlphaLocked,
                        snapshot.selection,
                    )
                if (destination.isMask) {
                    layer.mask = pixels
                } else {
                    layer.raster = pixels
                    layer.strokes.clear()
                }
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
                        hasAdjustmentLayers = selectedLayers.any { it.adjustmentType != null || it.filterType != null },
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
            pendingSelection = null
            activeSelection = null
            activeStrokes.clear()
            strokeBrushParams.clear()
            strokeLayerIds.clear()
            strokeErasers.clear()
            strokeDestinations.clear()
            dirtyRasters.addAll(allLayers().map { it.id })
            syncTimeline()
        }

        fun hasUnsavedChangesFor(projectId: Long): Boolean = dirty && currentProjectId == projectId

        // -----------------------------------------------------------------------------------------
        // Internals
        // -----------------------------------------------------------------------------------------

        private fun strokeRasterizer(): StrokeRasterizer = StrokeRasterizer()

        private fun rawLayerPixels(
            layer: LayerData,
            width: Int,
            height: Int,
        ): PixelBuffer? =
            if (layer.strokes.isEmpty()) {
                layer.raster
            } else {
                LayerStrokeRenderer.render(layer.raster, layer.strokes, emptyList(), width, height, layer.isAlphaLocked, null)
            }

        private fun hasLayerCapacity(additional: Int): Boolean =
            frameList.sumOf { it.layers.size } <= ProjectStorage.MAX_LAYERS - additional &&
                nextLayerId > 0 &&
                nextLayerId <= Long.MAX_VALUE - additional

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
            if (activeLayerId() != layerId) pendingSelection = null
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
            pendingSelection = null
            editRevision++
            compositor.release()
            pendingEdits.clear()
            activeStrokes.clear()
            strokeBrushParams.clear()
            strokeLayerIds.clear()
            strokeErasers.clear()
            strokeDestinations.clear()
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
