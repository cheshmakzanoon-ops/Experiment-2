package com.artflow.studio.data.repository.canvas

import com.artflow.studio.core.animation.AnimationTimeline
import com.artflow.studio.core.canvas.CanvasOperations
import com.artflow.studio.core.color.ColorProfile
import com.artflow.studio.core.pixels.AdjustmentProcessor
import com.artflow.studio.core.pixels.IntBounds
import com.artflow.studio.core.pixels.LayerMaskFactory
import com.artflow.studio.core.pixels.LayerMaskSource
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.core.pixels.SelectionMask
import com.artflow.studio.core.render.Compositor
import com.artflow.studio.core.render.LayerStrokeRenderer
import com.artflow.studio.core.render.StrokeRasterizer
import com.artflow.studio.core.render.TileCache
import com.artflow.studio.core.symmetry.SymmetryEngine
import com.artflow.studio.core.text.TextLayerContent
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
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext
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
        private var colorProfile = ColorProfile.SRGB
        private val trackedMs = AtomicLong()
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
        private val strokeSecondaries = mutableMapOf<Long, Int>()
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
                if (value) {
                    editRevision++
                    // Anything may have changed; a stroke commit narrows this right after.
                    previewDamage = null
                }
                field = value
            }

        /** Document edits since the last preview frame; null when the whole canvas must be redrawn. */
        private var previewDamage: PreviewCache.Damage? = null

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

        /** Sessions whose every buffer change is reported as preview damage. */
        private val damageTrackedSessions = mutableSetOf<Long>()

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
            colorProfile = ColorProfile.SRGB
            trackedMs.set(0L)
            backgroundColor = 0xFFFFFFFF.toInt()
            animationSettings = AnimationSettings()
            pendingEdits.clear()
            damageTrackedSessions.clear()
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
            synchronized(liveLock) { liveStrokes.clear() }
            strokeBrushParams.clear()
            strokeLayerIds.clear()
            strokeErasers.clear()
            strokeSecondaries.clear()
            strokeDestinations.clear()
            undoStack.clear()
            historyMark++
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
            colorProfile = ColorProfile.from(document.colorProfile)
            trackedMs.set(document.trackedMs)
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
            damageTrackedSessions.clear()
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
            synchronized(liveLock) { liveStrokes.clear() }
            strokeBrushParams.clear()
            strokeLayerIds.clear()
            strokeErasers.clear()
            strokeSecondaries.clear()
            strokeDestinations.clear()
            undoStack.clear()
            historyMark++
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
                ).apply {
                    isGroup = layer.isGroup
                    parentGroupId = layer.parentGroupId
                    isFillReference = layer.isFillReference
                    drawingAssist = layer.drawingAssist
                    isPrivate = layer.isPrivate
                }
            data.raster = loadRaster(projectId, layer.rasterFile)
            data.text = layer.textContent
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
                // Tagged with the document's profile so viewers show Display P3 artwork correctly.
                val profile = ColorProfile.from(snapshot.document.colorProfile)
                val flattened = withContext(Dispatchers.Default) { BitmapPixelBridge.toPngBytes(composite, profile = profile) }
                storage.saveFlattened(projectId, flattened)
                val thumbnail =
                    withContext(Dispatchers.Default) { BitmapPixelBridge.toPngBytes(scaleDown(composite, 512), profile = profile) }
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

        override var historyMark: Long = 0L
            private set

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
                        secondaryPressure = 0f,
                        secondaryJitter = 0f,
                    )
                } else {
                    brushParams
                }
            strokeLayerIds[strokeId] = layerId
            strokeErasers[strokeId] = isEraser && !destination.isMask
            strokeSecondaries[strokeId] = secondaryStrokeColor
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
            val secondary = strokeSecondaries.remove(strokeId)
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
                    secondaryColor = secondary,
                )
            // Preview and commit share this exact raw-pixel operation. Compute first so a failed
            // allocation or render cannot add an undo entry or modify the committed document.
            val incoming = SymmetryEngine.mirrorStroke(stroke, canvasWidth, canvasHeight, symmetryFor(layer, symmetrySettings))
            val reach = incoming.map { PreviewCache.boundsOf(it, 0) }.reduce { a, b -> PreviewCache.union(a, b) }
            val area =
                IntBounds(max(0, reach.left), max(0, reach.top), min(canvasWidth - 1, reach.right), min(canvasHeight - 1, reach.bottom))
            // A live stroke already holds its dabs: the commit lays them down instead of redrawing.
            val lives = synchronized(liveLock) { liveStrokes.remove(strokeId) }
            val live = lives?.takeIf { destination == StrokeDestination.LAYER && it.size == incoming.size && layer.strokes.isEmpty() }
            val base =
                if (live != null) {
                    commitLive(layer, incoming, live)
                } else {
                    LayerStrokeRenderer.render(
                        if (destination.isMask) layer.mask else layer.raster,
                        if (destination.isMask) emptyList() else layer.strokes.toList(),
                        incoming,
                        canvasWidth,
                        canvasHeight,
                        !destination.isMask && layer.isAlphaLocked,
                        activeSelection,
                        region = area,
                    )
                }
            val damageBefore = previewDamage
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
            // The commit only changed this layer, inside the stroke's reach.
            previewDamage = damageBefore?.plus(area, layer.id)
            emitAsync(CanvasInvalidationEvent.Full)
        }

        /** [layer]'s pixels with the live [strokes] laid down, as a full redraw would paint them. */
        private fun commitLive(
            layer: LayerData,
            strokes: List<Stroke>,
            lives: List<StrokeRasterizer.LiveStroke>,
        ): PixelBuffer {
            val result = layer.raster?.copy() ?: PixelBuffer(canvasWidth, canvasHeight)
            val rasterizer = StrokeRasterizer()
            try {
                synchronized(liveLock) {
                    strokes.zip(lives).forEach { (stroke, live) ->
                        rasterizer.drawLive(result, live, stroke, layer.raster, layer.isAlphaLocked, activeSelection)
                    }
                }
            } finally {
                rasterizer.release()
            }
            return result
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
            synchronized(liveLock) { liveStrokes.remove(strokeId) }
            activeStrokes.remove(strokeId)
            strokeBrushParams.remove(strokeId)
            strokeLayerIds.remove(strokeId)
            strokeErasers.remove(strokeId)
            strokeSecondaries.remove(strokeId)
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
                secondaryColor = strokeSecondaries[strokeId],
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
        ): Boolean = commitRasterEdits(listOf(session), description)

        override suspend fun commitRasterEdits(
            sessions: List<CanvasRepository.RasterEditSession>,
            description: String,
        ): Boolean =
            withState {
                // Every session is consumed; one stale session discards the whole group.
                val layers = sessions.map { takeCommittable(it) }
                if (sessions.isEmpty() || layers.any { it == null }) return@withState false
                // A session is provisional until here: cancellation cannot remove someone else's undo
                // entry, and autosave/export can never publish half a drag or a failed tool operation.
                pushUndo()
                sessions.zip(layers.filterNotNull()).forEach { (session, layer) ->
                    layer.raster = session.buffer.copy()
                    layer.strokes.clear()
                    layer.rasterFile = null
                    dirtyRasters += layer.id
                }
                dirty = true
                emit(CanvasInvalidationEvent.Full)
                Timber.d("Committed pixel edit ($description) on ${sessions.size} layer(s)")
                true
            }

        /** Removes [session]'s pending edit and returns its layer when the edit may still be committed. */
        private fun takeCommittable(session: CanvasRepository.RasterEditSession): LayerData? {
            val pending = pendingEdits.remove(session.snapshotToken) ?: return null
            damageTrackedSessions -= session.snapshotToken
            previewDamage = null
            val layer = layerById(session.layerId) ?: return null
            val valid =
                pending.session === session &&
                    pending.projectId == currentProjectId &&
                    layer === pending.layer &&
                    layer.raster === pending.original &&
                    layer.canPaint() &&
                    layer.strokes == pending.originalStrokes &&
                    layer.isAlphaLocked == session.alphaLocked &&
                    session.buffer.width == canvasWidth &&
                    session.buffer.height == canvasHeight
            return layer.takeIf { valid }
        }

        override suspend fun cancelRasterEdit(session: CanvasRepository.RasterEditSession) =
            withState {
                if (pendingEdits[session.snapshotToken]?.session === session) pendingEdits.remove(session.snapshotToken)
                damageTrackedSessions -= session.snapshotToken
                // Everything the session showed must be redrawn from the layer again.
                previewDamage = null
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

        private var secondaryStrokeColor: Int = 0xFFFFFFFF.toInt()

        override fun setSecondaryColor(color: Int) {
            secondaryStrokeColor = color
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

        override suspend fun addTextLayer(
            text: TextLayerContent,
            pixels: PixelBuffer,
        ): Long? =
            withState {
                if (!hasLayerCapacity(1) || pixels.width != canvasWidth || pixels.height != canvasHeight) return@withState null
                pushUndo()
                val layerId = nextLayerId++
                val layers = currentLayers()
                val layer = LayerData(id = layerId, name = textLayerName(text))
                layer.raster = pixels.copy()
                layer.text = text
                val activeIndex = layers.indexOfFirst { it.id == activeLayerId() }
                layers.add((activeIndex + 1).coerceIn(0, layers.size), layer)
                setActiveLayerId(layerId)
                dirtyRasters += layerId
                dirty = true
                emit(CanvasInvalidationEvent.LayersChanged)
                layerId
            }

        override suspend fun setTextLayer(
            layerId: Long,
            text: TextLayerContent,
            pixels: PixelBuffer,
        ): Boolean =
            withState {
                val layer = layerById(layerId) ?: return@withState false
                if (!layer.canPaint() || pixels.width != canvasWidth || pixels.height != canvasHeight) return@withState false
                pushUndo()
                layer.raster = pixels.copy()
                layer.text = text
                layer.name = textLayerName(text)
                layer.strokes.clear()
                layer.rasterFile = null
                dirtyRasters += layer.id
                dirty = true
                emit(CanvasInvalidationEvent.LayersChanged)
                true
            }

        private fun textLayerName(text: TextLayerContent): String =
            text.text
                .lineSequence()
                .firstOrNull()
                .orEmpty()
                .trim()
                .take(TEXT_LAYER_NAME)
                .ifEmpty { "Text" }

        override suspend fun removeLayer(layerId: Long): Boolean =
            withState {
                val layers = currentLayers()
                val position = layers.indexOfFirst { it.id == layerId }
                if (position == -1) return@withState false
                val removing = layers[position]
                // A group header is removed by ungrouping; the artwork must keep one paintable layer.
                if (!removing.isGroup && layers.count { !it.isGroup } <= 1) return@withState false

                pushUndo()
                layers.removeAt(position)
                if (removing.isGroup) layers.forEach { if (it.parentGroupId == layerId) it.parentGroupId = removing.parentGroupId }
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
                // A group moves with everything inside it; [newIndex] is where its header lands.
                val moving = layers[from]
                val block = if (moving.isGroup) subtreeIds(layers, layerId) else setOf(layerId)
                val moved = layers.filter { it.id in block }
                layers.removeAll(moved)
                val insertAt = (to - moved.size + 1).coerceIn(0, layers.size)
                layers.addAll(insertAt, moved)
                moving.parentGroupId = groupAtGap(layers.getOrNull(insertAt - 1), layers.getOrNull(insertAt + moved.size), layers)
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
                // A group header has no pixels of its own; merging it would orphan the layers inside it.
                if (selected.any { it.isGroup }) return@withState false
                val lowerIndex = minOf(source, target)
                mergeStack(selected.map { it.id }.toSet(), targetLayerId, layers[target].name, lowerIndex) != null
            }

        private fun mergeable(layer: LayerData): Boolean = layer.isVisible && !layer.isLocked && !layer.isReference && !layer.isGroup

        override suspend fun mergeLayerRange(layerIds: List<Long>): Boolean =
            withState {
                val layers = currentLayers()
                val indices = layerIds.map { id -> layers.indexOfFirst { it.id == id } }.distinct().sorted()
                // Only a run of neighbouring layers merges, so nothing between them changes order.
                if (indices.size < 2 || indices.first() < 0 || indices.last() - indices.first() != indices.size - 1) return@withState false
                val selected = indices.map { layers[it] }
                if (!selected.all(::mergeable)) return@withState false
                val bottom = selected.first()
                mergeStack(selected.map { it.id }.toSet(), bottom.id, bottom.name, indices.first()) != null
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

        private data class OpacityRun(
            val layerId: Long,
            val pushes: Long,
            val at: Long,
        )

        private var opacityRun: OpacityRun? = null

        /** True when the last undo entry is an opacity change to [layerId] made moments ago. */
        private fun continuesOpacityRun(
            layerId: Long,
            now: Long,
        ): Boolean {
            val run = opacityRun ?: return false
            return run.layerId == layerId && run.pushes == undoPushes && now - run.at <= OPACITY_MERGE_MS
        }

        /** Counts undo entries ever pushed, so an opacity run can tell whether anything came between. */
        private var undoPushes = 0L

        override suspend fun setLayerOpacity(
            layerId: Long,
            opacity: Float,
        ): Boolean =
            withState {
                val layer = layerById(layerId) ?: return@withState false
                if (!opacity.isFinite()) return@withState false
                val clamped = opacity.coerceIn(0f, 1f)
                if (layer.opacity == clamped) return@withState true
                // A slider drag is one undo step: changes to the same layer in quick succession merge.
                val now = System.currentTimeMillis()
                if (!continuesOpacityRun(layerId, now)) pushUndo()
                opacityRun = OpacityRun(layerId, undoPushes, now)
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

        /** Symmetry mirrors strokes only on layers with Drawing Assist, as in Procreate. */
        private fun symmetryFor(
            layer: LayerData?,
            settings: SymmetryEngine.Settings,
        ): SymmetryEngine.Settings = if (layer?.drawingAssist == true) settings else NO_SYMMETRY

        override fun isDrawingAssisted(layerId: Long): Boolean = currentLayers().firstOrNull { it.id == layerId }?.drawingAssist == true

        override suspend fun setLayerDrawingAssist(
            layerId: Long,
            enabled: Boolean,
        ): Boolean =
            withState {
                val layer = layerById(layerId)?.takeIf { !it.isGroup } ?: return@withState false
                if (layer.drawingAssist == enabled) return@withState true
                pushUndo()
                layer.drawingAssist = enabled
                dirty = true
                emit(CanvasInvalidationEvent.LayersChanged)
                true
            }

        override suspend fun setLayerPrivate(
            layerId: Long,
            isPrivate: Boolean,
        ): Boolean =
            withState {
                val layer = layerById(layerId)?.takeIf { !it.isGroup } ?: return@withState false
                if (layer.isPrivate == isPrivate) return@withState true
                pushUndo()
                layer.isPrivate = isPrivate
                dirty = true
                emit(CanvasInvalidationEvent.LayersChanged)
                true
            }

        override suspend fun setLayerFillReference(
            layerId: Long,
            enabled: Boolean,
        ): Boolean =
            withState {
                val layer = layerById(layerId)?.takeIf { !it.isGroup } ?: return@withState false
                if (layer.isFillReference == enabled) return@withState true
                pushUndo()
                // One reference at a time, as in Procreate.
                if (enabled) currentLayers().forEach { it.isFillReference = false }
                layer.isFillReference = enabled
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

        override suspend fun groupLayers(layerIds: List<Long>): Long? =
            withState {
                val layers = currentLayers()
                // A chosen group comes with everything inside it, so groups nest.
                val ids =
                    layers
                        .filter { it.id in layerIds && !it.isInternal }
                        .flatMap { if (it.isGroup) subtreeIds(layers, it.id) else setOf(it.id) }
                        .toSet()
                val members = layers.filter { it.id in ids }
                val paintable = members.lastOrNull { !it.isGroup }
                if (paintable == null || !hasLayerCapacity(1)) return@withState null
                pushUndo()
                val groupId = nextLayerId++
                val roots = members.filter { it.parentGroupId !in ids }
                val group =
                    LayerData(id = groupId, name = "Group ${layers.count { it.isGroup } + 1}").apply {
                        isGroup = true
                        parentGroupId = roots.last().parentGroupId
                    }
                // Keep members contiguous, in stack order, where the topmost member used to be.
                val topIndex = layers.indexOf(members.last())
                layers.removeAll(members)
                val insertAt = (topIndex - members.size + 1).coerceIn(0, layers.size)
                roots.forEach { it.parentGroupId = groupId }
                layers.addAll(insertAt, members)
                layers.add(insertAt + members.size, group)
                setActiveLayerId(paintable.id)
                dirty = true
                emit(CanvasInvalidationEvent.LayersChanged)
                groupId
            }

        override suspend fun removeLayers(layerIds: List<Long>): Int =
            withState {
                val layers = currentLayers()
                val paintable = layers.filter { !it.isGroup && !it.isInternal }
                // Bottom first, so keeping one layer keeps the lowest of the chosen ones.
                val chosen = paintable.filter { it.id in layerIds }
                val removing = if (chosen.size >= paintable.size) chosen.drop(1) else chosen
                if (removing.isEmpty()) return@withState 0
                pushUndo()
                val ids = removing.map { it.id }.toSet()
                val position = layers.indexOfFirst { it.id == activeLayerId() }
                layers.removeAll { it.id in ids }
                if (activeLayerId() in ids) {
                    val below = layers.take(position.coerceAtMost(layers.size)).lastOrNull { !it.isGroup && !it.isInternal }
                    setActiveLayerId((below ?: layers.first { !it.isGroup && !it.isInternal }).id)
                }
                dirty = true
                emit(CanvasInvalidationEvent.LayersChanged)
                removing.size
            }

        /** [groupId] and every layer inside it, at any depth. */
        private fun subtreeIds(
            layers: List<LayerData>,
            groupId: Long,
        ): Set<Long> {
            val ids = mutableSetOf(groupId)
            do {
                val added = layers.filter { it.parentGroupId in ids && it.id !in ids }.map { it.id }
                ids += added
            } while (added.isNotEmpty())
            return ids
        }

        /**
         * The group holding the gap between [below] and [above]: the innermost group both sides are
         * inside, where the gap just beneath a group's header is inside that group.
         */
        private fun groupAtGap(
            below: LayerData?,
            above: LayerData?,
            layers: List<LayerData>,
        ): Long? {
            val byId = layers.filter { it.isGroup }.associateBy { it.id }

            fun chain(start: Long?): List<Long> = generateSequence(start) { byId[it]?.parentGroupId }.take(byId.size + 1).toList()
            val belowSide = chain(below?.parentGroupId).toSet()
            val aboveSide = chain(if (above?.isGroup == true) above.id else above?.parentGroupId)
            return aboveSide.firstOrNull { it in belowSide }
        }

        override suspend fun ungroupLayers(groupId: Long): Boolean =
            withState {
                val layers = currentLayers()
                val group = layers.firstOrNull { it.id == groupId && it.isGroup } ?: return@withState false
                pushUndo()
                layers.forEach { if (it.parentGroupId == groupId) it.parentGroupId = group.parentGroupId }
                layers.remove(group)
                if (activeLayerId() == groupId) setActiveLayerId(layers.last { !it.isGroup }.id)
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
                emit(CanvasInvalidationEvent.Full)
                true
            }

        override fun getColorProfile(): ColorProfile = colorProfile

        override suspend fun loadModel(): String? = currentProjectId.takeIf { it > 0 }?.let { storage.loadModel(it) }

        override suspend fun saveModel(objText: String): Boolean {
            val projectId = currentProjectId.takeIf { it > 0 } ?: return false
            storage.saveModel(projectId, objText)
            return true
        }

        override fun trackedTimeMs(): Long = trackedMs.get()

        override fun addTrackedTime(ms: Long) {
            trackedMs.addAndGet(ms.coerceAtLeast(0L))
        }

        override suspend fun setColorProfile(
            profile: ColorProfile,
            undoable: Boolean,
        ): Boolean =
            withState {
                if (profile == colorProfile) return@withState true
                if (undoable) pushUndo()
                colorProfile = profile
                dirty = true
                emit(CanvasInvalidationEvent.Full)
                true
            }

        override suspend fun setCanvasBackgroundColor(color: Int): Boolean =
            withState {
                if (color == backgroundColor) return@withState true
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
                val clamped = durationMs.coerceIn(AnimationFrame.MIN_DURATION_MS, AnimationFrame.MAX_DURATION_MS)
                if (clamped == frame.durationMs) return@withState true
                pushUndo()
                frame.durationMs = clamped
                dirty = true
                syncTimeline()
                emit(CanvasInvalidationEvent.Full)
                true
            }

        override suspend fun updateAnimationSettings(settings: AnimationSettings) =
            withState {
                require(settings.onionSkinOpacity.isFinite()) { "Onion-skin opacity must be finite" }
                val normalized =
                    settings.copy(
                        fps = settings.fps.coerceIn(AnimationSettings.MIN_FPS, AnimationSettings.MAX_FPS),
                        onionSkinFrames = settings.onionSkinFrames.coerceIn(0, AnimationSettings.MAX_ONION_SKIN_FRAMES),
                        onionSkinOpacity = settings.onionSkinOpacity.coerceIn(0.05f, 1f),
                    )
                if (normalized == animationSettings) return@withState
                pushUndo()
                val oldDuration = animationSettings.frameDurationMs
                val fpsChanged = normalized.fps != animationSettings.fps
                animationSettings = normalized
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
                    withPinnedFrames(index, frame.layers, frameList.map { it.layers }, animationSettings)
                        .map { it.snapshotCopy() } to canvasSnapshot()
                } ?: return null
            return withContext(Dispatchers.Default) {
                renderFrozen(snapshot.first, snapshot.second, transparentBackground = transparentBackground)
            }
        }

        override suspend fun compositeBuffer(
            includeHidden: Boolean,
            applyAdjustments: Boolean,
        ): PixelBuffer? = composite(includeHidden, applyAdjustments, includePrivate = true)

        override suspend fun compositeWithoutPrivateLayers(): PixelBuffer? =
            composite(includeHidden = false, applyAdjustments = true, includePrivate = false)

        private suspend fun composite(
            includeHidden: Boolean,
            applyAdjustments: Boolean,
            includePrivate: Boolean,
        ): PixelBuffer? {
            val snapshot =
                withState {
                    markRastersShared()
                    withPinnedFrames(activeFrame, currentLayers(), frameList.map { it.layers }, animationSettings)
                        .filter { includePrivate || !it.isPrivate }
                        .map { it.snapshotCopy() } to canvasSnapshot()
                }
            return withContext(Dispatchers.Default) { renderFrozen(snapshot.first, snapshot.second, includeHidden, applyAdjustments) }
        }

        /** [region] limits rendering to part of the canvas; its layers then hold cropped pixels. */
        private data class PreviewSnapshot(
            val layers: List<LayerData>,
            val document: CanvasDocument,
            val strokes: List<Stroke>,
            val destinations: Map<Long, StrokeDestination>,
            val selection: SelectionMask?,
            val symmetry: SymmetryEngine.Settings,
            val region: IntBounds? = null,
            /** Each layer's whole pixels in canvas coordinates, kept when [layers] are cropped. */
            val fullRasters: Map<Long, PixelBuffer?> = emptyMap(),
        )

        private val previewCache = PreviewCache()
        private val previewMutex = Mutex()

        /** Strokes being drawn live (one per mirrored copy), by stroke id; guarded by [liveLock]. */
        private val liveStrokes = HashMap<Long, List<StrokeRasterizer.LiveStroke>>()
        private val liveLock = Any()

        /** The layers below the one being painted, composited once per area while a stroke goes on. */
        private val belowCache = TileCache()

        private class PreviewRequest(
            val snapshot: PreviewSnapshot,
            val key: PreviewCache.Key?,
            val damage: PreviewCache.Damage?,
            val below: List<Any?>?,
        )

        /**
         * Identifies the layers below the one a single stroke in progress paints — or, with no stroke
         * in progress, below [changedLayer], the one layer just edited (a stroke being committed) —
         * or null when the preview cannot keep them aside: several strokes, mask strokes, a layer
         * inside a group or clipping to the layer below, or pinned animation frames. Starts with
         * the painted layer's id.
         */
        private fun belowKey(changedLayer: Long?): List<Any?>? {
            val stroke = activeStrokes.keys.singleOrNull()
            if (stroke == null) return changedLayer?.let { if (activeStrokes.isEmpty()) belowKeyFor(it) else null }
            if ((strokeDestinations[stroke] ?: StrokeDestination.LAYER) != StrokeDestination.LAYER) return null
            return strokeLayerIds[stroke]?.let(::belowKeyFor)
        }

        private fun belowKeyFor(layerId: Long): List<Any?>? {
            // The cache is a canvas-sized image; skip it where that would crowd the heap.
            val cacheBytes = canvasWidth.toLong() * canvasHeight * BYTES_PER_PIXEL
            if (cacheBytes > Runtime.getRuntime().maxMemory() / BELOW_CACHE_HEAP_SHARE) return null
            val layers = currentLayers()
            val index = layers.indexOfFirst { it.id == layerId }
            val layer = layers.getOrNull(index) ?: return null
            if (layer.parentGroupId != null || layer.isClippingMask) return null
            if (withPinnedFrames(activeFrame, layers, frameList.map { it.layers }, animationSettings).size != layers.size) return null

            fun id(value: Any?) = System.identityHashCode(value)
            return listOf(layerId, canvasWidth, canvasHeight, id(layers)) +
                layers.take(index).map { listOf(id(it), id(it.raster), id(it.mask), it.strokes.size) }
        }

        private fun takePreviewSnapshot(): PreviewSnapshot {
            markRastersShared()
            val current =
                currentLayers().map { layer ->
                    layer.snapshotCopy().also { copy ->
                        pendingEdits.values.firstOrNull { it.layer === layer }?.let { pending ->
                            copy.raster = pending.session.buffer.copy()
                        }
                    }
                }
            // Only the pinned background and foreground frames need copies of their own.
            val layers =
                withPinnedFrames(activeFrame, current, frameList.map { it.layers }, animationSettings).map { layer ->
                    if (current.any { it === layer }) layer else layer.snapshotCopy()
                }
            return PreviewSnapshot(
                layers,
                canvasSnapshot(),
                activeStrokes.keys.mapNotNull { activeStroke(it) },
                strokeDestinations.toMap(),
                activeSelection?.copy(),
                symmetrySettings,
                fullRasters = layers.associate { it.id to it.raster },
            )
        }

        /**
         * Identifies everything a preview shows except strokes in progress, or null when only a full
         * composite is exact: pixel sessions in progress, filter layers and feathered masks reach
         * beyond the pixels they cover.
         */
        private fun previewKey(): PreviewCache.Key? {
            val layers = currentLayers()
            val selection = activeSelection
            val local =
                pendingEdits.keys.all { it in damageTrackedSessions } &&
                    (selection == null || (selection.width == canvasWidth && selection.height == canvasHeight)) &&
                    layers.none { it.filterType != null || it.maskFeather > 0f } &&
                    layers.all { canvasSized(it.raster) && canvasSized(it.mask) }
            if (!local) return null

            fun id(value: Any?) = System.identityHashCode(value)

            // While a tracked session is open the preview shows its buffer, which changes only where reported.
            val sessionBuffers = pendingEdits.values.associate { it.layer.id to it.session.buffer }
            val document = listOf(id(layers), canvasWidth, canvasHeight, id(selection), symmetrySettings)
            return PreviewCache.Key(
                document,
                layers.associate { it.id to listOf(id(it), id(sessionBuffers[it.id] ?: it.raster), id(it.mask)) },
            )
        }

        private fun canvasSized(buffer: PixelBuffer?): Boolean =
            buffer == null || (buffer.width == canvasWidth && buffer.height == canvasHeight)

        override fun trackPreviewDamage(session: CanvasRepository.RasterEditSession) {
            if (pendingEdits[session.snapshotToken]?.session !== session) return
            damageTrackedSessions += session.snapshotToken
            // Show the whole session buffer once; after this only reported areas are redrawn.
            previewDamage = null
        }

        override fun markPreviewDamage(
            session: CanvasRepository.RasterEditSession,
            area: IntBounds,
        ) {
            if (session.snapshotToken !in damageTrackedSessions) return
            previewDamage = previewDamage?.plus(area, session.layerId)
        }

        override suspend fun compositePreview(): PixelBuffer? {
            val snapshot = withState { takePreviewSnapshot() }
            return withContext(Dispatchers.Default) { renderPreview(snapshot) }
        }

        override suspend fun compositePreviewFrame(): CanvasRepository.PreviewFrame? =
            previewMutex.withLock {
                val request =
                    withState {
                        val damage = previewDamage
                        previewDamage = PreviewCache.Damage.NONE
                        PreviewRequest(takePreviewSnapshot(), previewKey(), damage, belowKey(damage?.layers?.singleOrNull()))
                    }
                val snapshot = request.snapshot
                val key = request.key
                val damage = request.damage
                withContext(Dispatchers.Default) {
                    val drawn =
                        snapshot.strokes.flatMap { stroke ->
                            val layer = snapshot.layers.firstOrNull { it.id == stroke.layerId }
                            val symmetry = symmetryFor(layer, snapshot.symmetry)
                            SymmetryEngine.mirrorStroke(stroke, snapshot.document.width, snapshot.document.height, symmetry)
                        }
                    // A local preview where at most the painted layer changed: the layers below it come from the cache.
                    val split = request.below?.first()
                    val keepBelow = key != null && damage != null && damage.layers.all { it == split }
                    previewCache.frame(key, damage, drawn, snapshot.document.width, snapshot.document.height) { region ->
                        val below = request.below
                        if (region != null && below != null && keepBelow) {
                            renderOverBelow(snapshot, region, below)
                        } else {
                            belowCache.reset()
                            renderPreview(if (region == null) snapshot else cropPreview(snapshot, region))
                        }
                    }
                }
            }

        private suspend fun renderPreview(snapshot: PreviewSnapshot): PixelBuffer {
            val strokesByLayer = snapshot.strokes.groupBy { it.layerId }
            for (layer in snapshot.layers) {
                coroutineContext.ensureActive()
                val active = strokesByLayer[layer.id] ?: continue
                paintPreviewStrokes(layer, active, snapshot)
            }
            val region = snapshot.region ?: return renderFrozen(snapshot.layers, snapshot.document, transparentBackground = true)
            return renderFrozen(
                snapshot.layers,
                snapshot.document.copy(width = region.width, height = region.height),
                transparentBackground = true,
                origin = region,
            )
        }

        /**
         * [renderPreview] for [region], starting from the cached composite of the layers below the
         * painted one (first in [below]) and compositing only that layer and those above it.
         */
        private suspend fun renderOverBelow(
            snapshot: PreviewSnapshot,
            region: IntBounds,
            below: List<Any?>,
        ): PixelBuffer {
            val split = snapshot.layers.indexOfFirst { it.id == below.first() }
            if (split < 0) {
                belowCache.reset()
                return renderPreview(cropPreview(snapshot, region))
            }
            val document = snapshot.document
            val target =
                belowCache.read(below, document.width, document.height, region) { area ->
                    val lower = cropPreview(snapshot.copy(layers = snapshot.layers.subList(0, split), strokes = emptyList()), area)
                    renderFrozen(
                        lower.layers,
                        document.copy(width = area.width, height = area.height),
                        transparentBackground = true,
                        origin = area,
                    )
                }
            val upper = cropPreview(snapshot.copy(layers = snapshot.layers.subList(split, snapshot.layers.size)), region)
            val strokesByLayer = upper.strokes.groupBy { it.layerId }
            for (layer in upper.layers) {
                coroutineContext.ensureActive()
                val active = strokesByLayer[layer.id] ?: continue
                paintPreviewStrokes(layer, active, upper)
            }
            val compositor = Compositor(StrokeRasterizer(region.left, region.top))
            try {
                compositor.compositeOnto(
                    target,
                    upper.layers.mapIndexed { index, layer ->
                        Compositor.LayerInput(layer.toDomain(split + index), layer.raster, layer.strokes.toList(), layer.mask)
                    },
                )
            } finally {
                compositor.release()
            }
            return target
        }

        /** The same preview limited to [region]: layers hold cropped pixels and shifted strokes. */
        private fun cropPreview(
            snapshot: PreviewSnapshot,
            region: IntBounds,
        ): PreviewSnapshot {
            val dx = -region.left.toFloat()
            val dy = -region.top.toFloat()
            val layers =
                snapshot.layers.map { layer ->
                    layer.snapshotCopy().also { copy ->
                        copy.raster = layer.raster?.crop(region)
                        copy.mask = layer.mask?.crop(region)
                        copy.strokes.clear()
                        layer.strokes.mapTo(copy.strokes) { PreviewCache.translate(it, dx, dy) }
                    }
                }
            val selection =
                snapshot.selection?.let { mask ->
                    SelectionMask(region.width, region.height).also { cropped ->
                        for (y in 0 until region.height) {
                            val from = (region.top + y) * mask.width + region.left
                            System.arraycopy(mask.coverage, from, cropped.coverage, y * region.width, region.width)
                        }
                    }
                }
            return snapshot.copy(layers = layers, selection = selection, region = region)
        }

        /**
         * Paints [stroke] (with its [mirrors]) on [layer]'s preview pixels from its live state: only
         * dabs added since the last frame are stamped. False when the stroke must be redrawn whole.
         */
        private fun paintLive(
            layer: LayerData,
            stroke: Stroke,
            mirrors: List<Stroke>,
            snapshot: PreviewSnapshot,
        ): Boolean {
            val document = snapshot.document
            val region = snapshot.region
            synchronized(liveLock) {
                val lives = liveFor(stroke, mirrors, layer, document.width, document.height) ?: return false
                val target = layer.raster?.copy() ?: PixelBuffer(region?.width ?: document.width, region?.height ?: document.height)
                val rasterizer = StrokeRasterizer(region?.left ?: 0, region?.top ?: 0)
                try {
                    mirrors.zip(lives).forEach { (mirror, live) ->
                        rasterizer.drawLive(target, live, mirror, snapshot.fullRasters[layer.id], layer.isAlphaLocked, snapshot.selection)
                    }
                } finally {
                    rasterizer.release()
                }
                layer.raster = target
                layer.strokes.clear()
            }
            return true
        }

        /**
         * The live copies of [stroke] (one per mirror) on a [width] × [height] canvas, started on
         * first use; null when it has to be redrawn whole: a brush whose dabs depend on the finished
         * stroke, older vector strokes still on [layer], wet mix across mirrors, or too little memory.
         * Call with [liveLock] held.
         */
        private fun liveFor(
            stroke: Stroke,
            mirrors: List<Stroke>,
            layer: LayerData,
            width: Int,
            height: Int,
        ): List<StrokeRasterizer.LiveStroke>? {
            val eligible =
                StrokeRasterizer.canDrawLive(stroke) &&
                    layer.strokes.isEmpty() &&
                    (mirrors.size == 1 || stroke.brushParams.wetMix <= 0f)
            val bytes = width.toLong() * height * BYTES_PER_PIXEL * mirrors.size
            if (!eligible || bytes > Runtime.getRuntime().maxMemory() / LIVE_HEAP_SHARE) return null
            val lives =
                liveStrokes.getOrPut(stroke.id) {
                    val starter = StrokeRasterizer()
                    mirrors.map { starter.startLive(it, width, height) }
                }
            return lives.takeIf { it.size == mirrors.size }
        }

        private fun paintPreviewStrokes(
            layer: LayerData,
            active: List<Stroke>,
            snapshot: PreviewSnapshot,
        ) {
            val region = snapshot.region
            for (stroke in active) {
                val destination = snapshot.destinations[stroke.id] ?: StrokeDestination.LAYER
                val mirrors =
                    SymmetryEngine.mirrorStroke(
                        stroke,
                        snapshot.document.width,
                        snapshot.document.height,
                        symmetryFor(layer, snapshot.symmetry),
                    )
                val done =
                    !canReceiveStroke(layer, destination) ||
                        (destination == StrokeDestination.LAYER && paintLive(layer, stroke, mirrors, snapshot))
                if (done) continue
                // Shift the mirrored strokes into the region being rendered.
                val incoming =
                    mirrors.map { if (region == null) it else PreviewCache.translate(it, -region.left.toFloat(), -region.top.toFloat()) }
                val pixels =
                    LayerStrokeRenderer.render(
                        if (destination.isMask) layer.mask else layer.raster,
                        if (destination.isMask) emptyList() else layer.strokes.toList(),
                        incoming,
                        region?.width ?: snapshot.document.width,
                        region?.height ?: snapshot.document.height,
                        !destination.isMask && layer.isAlphaLocked,
                        snapshot.selection,
                        originX = region?.left ?: 0,
                        originY = region?.top ?: 0,
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
                        frames =
                            frames.mapIndexed { position, frame ->
                                val index = if (allFrames) position else snapshot.activeFrame
                                val shown = withPinnedFrames(index, frame.layers, snapshot.frames.map { it.layers }, snapshot.animation)
                                renderFrozen(shown, frozen.second, includeHidden, transparentBackground = true)
                            },
                        delaysMs = frames.map { it.durationMs },
                        layers = layerPixels,
                        selection = frozen.third,
                        hasAdjustmentLayers = selectedLayers.any { it.adjustmentType != null || it.filterType != null },
                        groups = frames.first().layers.mapIndexedNotNull { index, data -> data.takeIf { it.isGroup }?.toDomain(index) },
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

        /**
         * [layers] of frame [index] with Animation Assist's background frame below and foreground
         * frame above, as every other frame shows them during playback and in exports.
         */
        private fun withPinnedFrames(
            index: Int,
            layers: List<LayerData>,
            frames: List<List<LayerData>>,
            settings: AnimationSettings,
        ): List<LayerData> {
            if (frames.size < 2) return layers
            val below = if (settings.backgroundFrame && index != 0) frames.first() else emptyList()
            val above = if (settings.foregroundFrame && index != frames.lastIndex) frames.last() else emptyList()
            return below + layers + above
        }

        private fun renderFrozen(
            layers: List<LayerData>,
            document: CanvasDocument,
            includeHidden: Boolean = false,
            applyAdjustments: Boolean = true,
            transparentBackground: Boolean = false,
            origin: IntBounds? = null,
        ): PixelBuffer {
            val localCompositor = Compositor(StrokeRasterizer(origin?.left ?: 0, origin?.top ?: 0))
            return try {
                localCompositor.composite(
                    layers.mapIndexed { index, layer ->
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
            opacityRun = null
            val snapshot = undoStack.removeLastOrNull() ?: return false
            historyMark++
            redoStack.addLast(currentSnapshot())
            restore(snapshot)
            dirty = true
            emitAsync(CanvasInvalidationEvent.Full)
            return true
        }

        override fun redo(): Boolean {
            opacityRun = null
            val snapshot = redoStack.removeLastOrNull() ?: return false
            historyMark++
            undoStack.addLast(currentSnapshot())
            restore(snapshot)
            dirty = true
            emitAsync(CanvasInvalidationEvent.Full)
            return true
        }

        private fun pushUndo() {
            undoPushes++
            historyMark++
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
            val colorProfile: ColorProfile = ColorProfile.SRGB,
            /** Shared, not copied: the selection is replaced on change and never edited in place. */
            val selection: SelectionMask? = null,
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
                colorProfile = colorProfile,
                selection = activeSelection,
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
            colorProfile = snapshot.colorProfile
            backgroundColor = snapshot.backgroundColor
            animationSettings = snapshot.animation
            pendingEdits.clear()
            damageTrackedSessions.clear()
            pendingSelection = null
            activeSelection = snapshot.selection?.copy()
            activeStrokes.clear()
            synchronized(liveLock) { liveStrokes.clear() }
            strokeBrushParams.clear()
            strokeLayerIds.clear()
            strokeErasers.clear()
            strokeSecondaries.clear()
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
                colorProfile = colorProfile.name,
                trackedMs = trackedMs.get(),
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
            belowCache.release()
            pendingSelection = null
            editRevision++
            compositor.release()
            pendingEdits.clear()
            damageTrackedSessions.clear()
            activeStrokes.clear()
            synchronized(liveLock) { liveStrokes.clear() }
            strokeBrushParams.clear()
            strokeLayerIds.clear()
            strokeErasers.clear()
            strokeSecondaries.clear()
            strokeDestinations.clear()
            undoStack.clear()
            historyMark++
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
            var isGroup: Boolean = false
            var isFillReference: Boolean = false
            var drawingAssist: Boolean = false
            var isPrivate: Boolean = false
            var parentGroupId: Long? = null

            /** Editable text; set it after [raster], because any new pixels turn the text into pixels. */
            var text: TextLayerContent? = null
            var raster: PixelBuffer? = null
                set(value) {
                    field = value
                    text = null
                }
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
                    !isGroup &&
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
                ).apply {
                    isGroup = this@LayerData.isGroup
                    parentGroupId = this@LayerData.parentGroupId
                    isFillReference = this@LayerData.isFillReference
                    drawingAssist = this@LayerData.drawingAssist
                    isPrivate = this@LayerData.isPrivate
                }.also {
                    it.raster = raster
                    it.text = text
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
                ).apply {
                    isGroup = this@LayerData.isGroup
                    parentGroupId = this@LayerData.parentGroupId
                    isFillReference = this@LayerData.isFillReference
                    drawingAssist = this@LayerData.drawingAssist
                    isPrivate = this@LayerData.isPrivate
                }.also { fresh ->
                    fresh.raster = raster
                    fresh.text = text
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
                    isGroup = isGroup,
                    isFillReference = isFillReference,
                    drawingAssist = drawingAssist,
                    isPrivate = isPrivate,
                    parentGroupId = parentGroupId,
                    isInternal = isInternal,
                    textContent = text,
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
            private const val BYTES_PER_PIXEL = 4L
            private const val BELOW_CACHE_HEAP_SHARE = 8L
            private const val LIVE_HEAP_SHARE = 6L
            private const val MAX_HISTORY = 30
            private val NO_SYMMETRY = SymmetryEngine.Settings()
            private const val OPACITY_MERGE_MS = 1_500L
            private const val TEXT_LAYER_NAME = 32

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
