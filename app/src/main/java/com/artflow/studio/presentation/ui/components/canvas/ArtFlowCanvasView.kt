package com.artflow.studio.presentation.ui.components.canvas

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.opengl.GLSurfaceView
import android.os.Build
import android.util.AttributeSet
import android.view.MotionEvent
import androidx.core.math.MathUtils
import com.artflow.studio.core.canvas.PointerGestureRouter
import com.artflow.studio.core.canvas.PointerPressure
import com.artflow.studio.core.perspective.PerspectiveGuide
import com.artflow.studio.core.pixels.Channels
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.core.pixels.RasterOverlay
import com.artflow.studio.core.pixels.SelectionMask
import com.artflow.studio.core.symmetry.SymmetryEngine
import com.artflow.studio.core.text.TextLayout
import com.artflow.studio.core.tool.FillTool
import com.artflow.studio.core.tool.GradientTool
import com.artflow.studio.core.tool.LiquifyTool
import com.artflow.studio.core.tool.PixelBrushes
import com.artflow.studio.core.tool.ToolType
import com.artflow.studio.data.renderer.BitmapPixelBridge
import com.artflow.studio.data.renderer.opengl.OpenGLCanvasRenderer
import com.artflow.studio.domain.model.brush.BrushParams
import com.artflow.studio.domain.model.brush.StrokeDestination
import com.artflow.studio.domain.repository.canvas.CanvasInvalidationEvent
import com.artflow.studio.domain.repository.canvas.CanvasRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Shape kinds the shape tool can draw. */
enum class ShapeKind(
    val displayName: String,
) {
    RECTANGLE("Rectangle"),
    ELLIPSE("Ellipse"),
    LINE("Line"),
    POLYGON("Polygon"),
}

/** How a new selection combines with the existing one. */
enum class SelectionCombineMode(
    val displayName: String,
) {
    REPLACE("Replace"),
    ADD("Add"),
    SUBTRACT("Subtract"),
    INTERSECT("Intersect"),
}

/**
 * Everything the canvas view needs in order to interpret a gesture.
 *
 * The view renders and routes input only: it owns no editor state that the rest of the app does not
 * already have, so the toolbar, the panels and the canvas cannot disagree about the active tool.
 */
data class EditorInput(
    val tool: ToolType = ToolType.BRUSH,
    val brushParams: BrushParams = BrushParams(),
    val strokeDestination: StrokeDestination = StrokeDestination.LAYER,
    val brushColor: Int = 0xFF1F2933.toInt(),
    val eraserSize: Float = 48f,
    val symmetry: SymmetryEngine.Settings = SymmetryEngine.Settings(),
    val perspective: PerspectiveGuide.Settings = PerspectiveGuide.Settings(),
    val snapToGuides: Boolean = false,
    val gradient: GradientTool.Gradient = GradientTool.Presets.BLACK_TO_WHITE,
    val gradientType: GradientTool.GradientType = GradientTool.GradientType.LINEAR,
    val fillTolerance: Int = 32,
    val fillContiguous: Boolean = true,
    val smudge: PixelBrushes.SmudgeSettings = PixelBrushes.SmudgeSettings(),
    val clone: PixelBrushes.CloneSettings = PixelBrushes.CloneSettings(),
    val healing: PixelBrushes.HealingSettings = PixelBrushes.HealingSettings(),
    val liquify: LiquifyTool.Settings = LiquifyTool.Settings(),
    val shapeKind: ShapeKind = ShapeKind.RECTANGLE,
    val shapeFilled: Boolean = true,
    val text: String = "ArtFlow",
    val textStyle: TextLayout.TextStyle = TextLayout.TextStyle(),
    val selectionMode: SelectionCombineMode = SelectionCombineMode.REPLACE,
    /** Whether a finger (rather than a stylus) may paint. */
    val fingerPainting: Boolean = true,
)

/** Rubber-band geometry reported while a selection, shape or gradient drag is in progress. */
data class DragPreview(
    val tool: ToolType,
    val points: List<Pair<Float, Float>>,
    val closed: Boolean = false,
)

/**
 * The drawing surface.
 *
 * - routes pointer input to the active tool (strokes, pixel tools, selection, fill, gradient,
 *   shapes, text, eyedropper, move),
 * - handles stylus pressure/tilt plus palm rejection,
 * - provides two-finger pan/zoom/rotate and gesture undo/redo,
 * - keeps the GPU texture in sync with the document by re-compositing whenever the repository
 *   reports a change.
 *
 * The pixel maths that this class depends on lives in `core`, which is where it is unit tested.
 */
@AndroidEntryPoint
class ArtFlowCanvasView
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
    ) : GLSurfaceView(context, attrs) {
        @Inject
        lateinit var renderer: OpenGLCanvasRenderer

        @Inject
        lateinit var canvasRepository: CanvasRepository

        private val toolErrors =
            CoroutineExceptionHandler { _, error ->
                Timber.e(error, "Canvas operation failed")
                onStatusMessage?.invoke(error.message ?: "The operation could not be completed")
            }
        private var coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + toolErrors)

        // --- Editor state -------------------------------------------------------------------------

        private var input = EditorInput()
        private var activeLayerId = 0L
        private var canvasWidth = 1920
        private var canvasHeight = 1080
        private var canvasDpi = 72

        // --- View transform -----------------------------------------------------------------------

        private var scale = 1f
        private var offsetX = 0f
        private var offsetY = 0f
        private var rotationDegrees = 0f

        // --- Gesture state ------------------------------------------------------------------------

        private val pointerRouter = PointerGestureRouter(TAP_SLOP, TAP_TIMEOUT_MS)
        private var lastPointerX = 0f
        private var lastPointerY = 0f
        private var gestureStartTime = 0L
        private var gestureMoved = 0f
        private var gestureTool: ToolType? = null

        private var drawing = false
        private var currentStrokeId = 0L

        // Navigation baseline
        private var navPrevDistance = 0f
        private var navPrevAngle = 0f
        private var navPrevMidX = 0f
        private var navPrevMidY = 0f
        private var navAnchor: Pair<Float, Float>? = null

        // Drag preview (selection marquee, lasso, shape, gradient)
        private var previewPoints = mutableListOf<Pair<Float, Float>>()
        private var shapeOrigin: Pair<Float, Float>? = null
        private var gradientOrigin: Pair<Float, Float>? = null

        // --- Tool sessions ------------------------------------------------------------------------

        private var pixelTool: ToolType? = null
        private var rasterSession: CanvasRepository.RasterEditSession? = null
        private var rasterBuffer: PixelBuffer? = null
        private var rasterSource: PixelBuffer? = null
        private var rasterBase: PixelBuffer? = null
        private val pendingSamples = mutableListOf<FloatArray>()
        private var pendingPixelCommit = false
        private var pendingPixelCancel = false
        private var pixelOpenJob: Job? = null
        private var selectionJob: Job? = null
        private var pixelCommitDescription = ""

        private var smudgeSession: PixelBrushes.SmudgeSession? = null
        private var cloneSession: PixelBrushes.CloneSession? = null
        private var healingSession: PixelBrushes.HealingSession? = null
        private var liquifySession: LiquifyTool.Session? = null

        private data class LiquifyReference(
            val projectId: Long,
            val layerId: Long,
            val frameId: Long?,
            val revision: Long,
            val contextVersion: Long,
            val original: PixelBuffer,
        )

        private var liquifyReference: LiquifyReference? = null
        private var liquifyContextVersion = 0L

        private fun resetLiquifyReference() {
            liquifyContextVersion++
            liquifyReference = null
        }

        private var gestureLiquifyReference: LiquifyReference? = null
        private var cloneSource: Pair<Float, Float>? = null
        private var cloneDragStarted = false
        private var lastPreviewRequest = 0L
        private var lastLiquifyPreview = 0L

        // --- Callbacks ----------------------------------------------------------------------------

        var onColorPicked: ((Int) -> Unit)? = null
        var onSelectionChanged: ((SelectionMask?, Int) -> Unit)? = null
        var onDragPreview: ((DragPreview?) -> Unit)? = null
        var onHistoryChanged: ((undo: Int, redo: Int) -> Unit)? = null
        var onUndoRequested: (() -> Unit)? = null
        var onRedoRequested: (() -> Unit)? = null
        var onViewChanged: ((scale: Float, offsetX: Float, offsetY: Float, rotation: Float) -> Unit)? = null
        var onTextPlacementRequested: ((x: Float, y: Float) -> Unit)? = null

        /** Invoked by accessibility clicks (see [performClick]); unused by direct touch. */
        var onToolStripToggle: (() -> Unit)? = null
        var onCloneSourceChanged: ((Pair<Float, Float>) -> Unit)? = null
        var onStatusMessage: ((String) -> Unit)? = null

        // --- Lifecycle ----------------------------------------------------------------------------

        private var rendererAttached = false
        private var observingInvalidations = false
        private var invalidateJob: Job? = null
        private var onionEnabled = false
        private var onionDirty = true

        init {
            setEGLContextClientVersion(2)
            isFocusableInTouchMode = true
            preserveEGLContextOnPause = true
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            if (!rendererAttached) {
                setRenderer(renderer)
                renderMode = RENDERMODE_WHEN_DIRTY
                rendererAttached = true
                renderer.setCanvasSize(canvasWidth, canvasHeight, canvasDpi)
                renderer.setBackgroundArgb(canvasRepository.getBackgroundColor())
            }
            coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + toolErrors)
            observingInvalidations = false
            startObservingInvalidations()
            requestRender()
        }

        fun pauseRendering() {
            cancelActiveGesture()
            if (rendererAttached) onPause()
        }

        fun resumeRendering() {
            if (rendererAttached) {
                onResume()
                requestRender()
            }
        }

        override fun requestRender() {
            // Compose configures the AndroidView before attachment. GLSurfaceView creates its GL
            // thread only in setRenderer; requestRender/setRenderMode before that would throw.
            if (rendererAttached) super.requestRender()
        }

        override fun onSizeChanged(
            width: Int,
            height: Int,
            oldWidth: Int,
            oldHeight: Int,
        ) {
            super.onSizeChanged(width, height, oldWidth, oldHeight)
            val isFirstSize = oldWidth == 0 || oldHeight == 0
            if (width > 0 && height > 0 && isFirstSize) post { fitToView() }
        }

        fun cancelActiveGesture() {
            pointerRouter.suppress()
            resetNavigation()
            cancelToolInteraction()
        }

        private fun cancelToolInteraction() {
            gestureTool = null
            previewPoints.clear()
            shapeOrigin = null
            gradientOrigin = null
            if (drawing) canvasRepository.cancelStroke(currentStrokeId)
            drawing = false
            cancelPixelInteraction()
            selectionJob?.cancel()
            onDragPreview?.invoke(null)
        }

        private fun cancelPixelInteraction() {
            pixelOpenJob?.cancel()
            pixelOpenJob = null
            rasterSession?.let { session ->
                coroutineScope.launch(NonCancellable, start = CoroutineStart.UNDISPATCHED) { canvasRepository.cancelRasterEdit(session) }
            }
            rasterSession = null
            rasterBuffer = null
            rasterSource = null
            rasterBase = null
            pixelTool = null
            pendingSamples.clear()
            pendingPixelCommit = false
            pendingPixelCancel = false
            smudgeSession = null
            cloneSession = null
            healingSession = null
            liquifySession = null
            gestureLiquifyReference = null
        }

        override fun onDetachedFromWindow() {
            resetLiquifyReference()
            cancelActiveGesture()
            coroutineScope.cancel()
            observingInvalidations = false
            super.onDetachedFromWindow() // waits for the GL thread to stop
            renderer.dispose()
        }

        // -----------------------------------------------------------------------------------------
        // Public API used by the editor UI
        // -----------------------------------------------------------------------------------------

        /** Pushes the whole editor state; cheap enough to call on every recomposition. */
        fun setEditorInput(newInput: EditorInput) {
            val destinationChanged = newInput.tool != input.tool || newInput.strokeDestination != input.strokeDestination
            if (destinationChanged || newInput.fingerPainting != input.fingerPainting) cancelActiveGesture()
            if (destinationChanged) resetLiquifyReference()
            input = newInput
            canvasRepository.setStrokeColor(newInput.brushColor)
            canvasRepository.setSymmetry(newInput.symmetry)
        }

        fun setActiveLayerId(layerId: Long) {
            if (layerId != activeLayerId) {
                resetLiquifyReference()
                cancelActiveGesture()
            }
            activeLayerId = layerId
        }

        /** Called once the document is loaded so the view knows the real canvas size. */
        fun attachToCanvas(
            width: Int,
            height: Int,
            dpi: Int,
            backgroundColor: Int,
        ) {
            val dimensionsChanged = width != canvasWidth || height != canvasHeight
            if (dimensionsChanged) {
                resetLiquifyReference()
                cancelActiveGesture()
            }
            canvasWidth = max(1, width)
            canvasHeight = max(1, height)
            canvasDpi = dpi
            renderer.setCanvasSize(canvasWidth, canvasHeight, canvasDpi)
            renderer.setBackgroundArgb(backgroundColor)
            if (dimensionsChanged) fitToView()
            requestRender()
        }

        fun setCanvasBackgroundColor(argb: Int) {
            renderer.setBackgroundArgb(argb)
            requestRender()
        }

        fun setCheckerboardVisible(visible: Boolean) {
            renderer.setCheckerboardVisible(visible)
            requestRender()
        }

        fun setOnionSkinEnabled(enabled: Boolean) {
            if (enabled == onionEnabled) return
            onionEnabled = enabled
            onionDirty = true
            if (rendererAttached) refreshOnionSkins()
            requestRender()
        }

        /** Refreshes the ghost frames; called when onion skinning is switched on or the frame changes. */
        fun invalidateOnionSkins() {
            onionDirty = true
            refreshOnionSkins()
        }

        fun fitToView() {
            if (width == 0 || height == 0) return
            scale =
                (min(width.toFloat() / canvasWidth, height.toFloat() / canvasHeight) * 0.92f)
                    .coerceIn(MIN_SCALE, MAX_SCALE)
            offsetX = 0f
            offsetY = 0f
            rotationDegrees = 0f
            pushTransform()
        }

        fun resetView() {
            scale = 1f
            offsetX = 0f
            offsetY = 0f
            rotationDegrees = 0f
            pushTransform()
        }

        fun zoomBy(factor: Float) {
            scale = (scale * factor).coerceIn(MIN_SCALE, MAX_SCALE)
            pushTransform()
        }

        fun rotateView(degrees: Float) {
            rotationDegrees = normalizeDegrees(rotationDegrees + degrees)
            pushTransform()
        }

        fun currentScale(): Float = scale

        fun currentRotation(): Float = rotationDegrees

        /** Screen (view) position of a canvas point, for overlays drawn in Compose. */
        fun canvasToView(
            x: Float,
            y: Float,
        ): Pair<Float, Float> {
            val radians = Math.toRadians(rotationDegrees.toDouble())
            val cosR = cos(radians).toFloat()
            val sinR = sin(radians).toFloat()
            val u = scale * (x - canvasWidth / 2f)
            val v = scale * (y - canvasHeight / 2f)
            return (u * cosR - v * sinR + width / 2f + offsetX) to
                (u * sinR + v * cosR + height / 2f + offsetY)
        }

        /** Canvas position under a screen point. */
        fun viewToCanvas(
            x: Float,
            y: Float,
        ): Pair<Float, Float> {
            val radians = Math.toRadians(rotationDegrees.toDouble())
            val cosR = cos(radians).toFloat()
            val sinR = sin(radians).toFloat()
            val rx = x - width / 2f - offsetX
            val ry = y - height / 2f - offsetY
            val u = rx * cosR + ry * sinR
            val v = -rx * sinR + ry * cosR
            return (u / scale + canvasWidth / 2f) to (v / scale + canvasHeight / 2f)
        }

        fun undo() {
            if (canvasRepository.undo()) {
                onionDirty = true
                reportHistory()
            }
        }

        fun redo() {
            if (canvasRepository.redo()) {
                onionDirty = true
                reportHistory()
            }
        }

        fun clearSelection() {
            selectionJob?.cancel()
            canvasRepository.clearSelection()
            onSelectionChanged?.invoke(null, 0)
        }

        fun selectAll() {
            selectionJob?.cancel()
            val mask = SelectionMask(canvasWidth, canvasHeight).apply { selectAll() }
            canvasRepository.setSelection(mask)
            onSelectionChanged?.invoke(mask, mask.selectedPixelCount())
        }

        fun invertSelection() {
            selectionJob?.cancel()
            val mask =
                canvasRepository.selection()
                    ?: SelectionMask(canvasWidth, canvasHeight).apply { selectAll() }
            mask.invert()
            canvasRepository.setSelection(mask)
            onSelectionChanged?.invoke(mask, mask.selectedPixelCount())
        }

        fun featherSelection(radius: Int) {
            runSelectionEdit(SelectionCombineMode.REPLACE) { session ->
                val mask = session.original ?: return@runSelectionEdit null
                withContext(Dispatchers.Default) { mask.feathered(radius) { ensureActive() } }
            }
        }

        /** Bakes a text run into the active layer at a canvas position. */
        fun placeText(
            x: Float,
            y: Float,
            text: String,
            style: TextLayout.TextStyle,
            color: Int,
        ) {
            if (text.isBlank()) return
            applyOverlayEdit("Text") { width, height ->
                rasterizeText(width, height, x, y, text, style, color)
            }
        }

        fun placeShape(
            kind: ShapeKind,
            startX: Float,
            startY: Float,
            endX: Float,
            endY: Float,
            filled: Boolean,
            color: Int,
            strokeWidth: Float,
        ) {
            applyOverlayEdit("Shape") { width, height ->
                buildShapeBuffer(width, height, kind, startX, startY, endX, endY, filled, color, strokeWidth)
            }
        }

        /** Moves the active layer's pixels by a canvas-space offset. */
        fun moveActiveLayer(
            dx: Float,
            dy: Float,
        ) {
            if (dx == 0f && dy == 0f) return
            val layerId = activeLayerId
            coroutineScope.launch {
                val base = canvasRepository.layerPixels(layerId) ?: return@launch
                canvasRepository.applyRasterEdit(layerId, "Move layer") { target ->
                    target.clear()
                    drawShifted(target, base, dx.roundToInt(), dy.roundToInt())
                }
                reportHistory()
            }
        }

        /**
         * Bakes the active layer through a free transform about ([pivotX], [pivotY]).
         *
         * Scaling and rotation follow the canvas centre because the layer buffer is canvas sized; the
         * pivot only decides where the transformed result is placed.
         */
        fun transformActiveLayer(
            pivotX: Float,
            pivotY: Float,
            scaleFactor: Float,
            rotation: Float,
            flipHorizontal: Boolean,
            flipVertical: Boolean,
        ) {
            val layerId = activeLayerId
            coroutineScope.launch {
                val base = canvasRepository.layerPixels(layerId) ?: return@launch
                val transformed =
                    withContext(Dispatchers.Default) {
                        var buffer = base
                        if (flipHorizontal) buffer = buffer.flippedHorizontally()
                        if (flipVertical) buffer = buffer.flippedVertically()
                        val degrees = rotation.roundToInt()
                        if (degrees % 360 != 0) buffer = buffer.rotated(degrees)
                        if (abs(scaleFactor - 1f) > 0.001f) {
                            val targetWidth = (buffer.width * scaleFactor).roundToInt().coerceAtLeast(1)
                            val targetHeight = (buffer.height * scaleFactor).roundToInt().coerceAtLeast(1)
                            buffer = buffer.scaled(targetWidth, targetHeight)
                        }
                        buffer
                    }
                canvasRepository.applyRasterEdit(layerId, "Transform layer") { target ->
                    target.clear()
                    val dx =
                        (pivotX - transformed.width / 2f).roundToInt() +
                            (canvasWidth - transformed.width) / 2
                    val dy =
                        (pivotY - transformed.height / 2f).roundToInt() +
                            (canvasHeight - transformed.height) / 2
                    drawShifted(target, transformed, dx, dy)
                }
                reportHistory()
            }
        }

        // -----------------------------------------------------------------------------------------
        // Rendering pipeline
        // -----------------------------------------------------------------------------------------

        private fun startObservingInvalidations() {
            if (observingInvalidations) return
            observingInvalidations = true
            invalidateJob =
                coroutineScope.launch {
                    canvasRepository
                        .observeCanvasInvalidation()
                        .onEach { event ->
                            if (event is CanvasInvalidationEvent.FrameChanged) {
                                resetLiquifyReference()
                                cancelActiveGesture()
                                onionDirty = true
                            }
                        }.conflate()
                        .collect {
                            // A busy renderer must complete frames instead of cancelling each one
                            // when another pointer sample arrives. Only the latest queued request is kept.
                            refreshComposite()
                            if (onionDirty) refreshOnionSkins()
                        }
                }
        }

        private suspend fun refreshComposite() {
            val size = canvasRepository.getCanvasSize()
            attachToCanvas(size.width, size.height, size.dpi, canvasRepository.getBackgroundColor())
            try {
                val buffer = canvasRepository.compositePreview()
                if (buffer != null) renderer.setComposite(buffer)
                requestRender()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: IllegalArgumentException) {
                reportCompositeFailure(error)
            } catch (error: IllegalStateException) {
                reportCompositeFailure(error)
            } catch (error: OutOfMemoryError) {
                Timber.e(error, "Insufficient memory for canvas preview")
                onStatusMessage?.invoke("Not enough memory to render this canvas. Save your artwork and reduce its size.")
            }
        }

        private fun reportCompositeFailure(error: Exception) {
            Timber.e(error, "Composite failed")
            onStatusMessage?.invoke("Could not update the canvas: ${error.message}")
        }

        private var onionJob: Job? = null

        private fun refreshOnionSkins() {
            if (onionEnabled && !onionDirty) return
            onionJob?.cancel()
            if (!onionEnabled) {
                onionDirty = false
                renderer.clearOnionSkins()
                requestRender()
                return
            }
            onionDirty = false
            onionJob =
                coroutineScope.launch {
                    val state = canvasRepository.timeline.value
                    val active = state.activeIndex
                    val range = state.settings.onionSkinFrames.coerceIn(0, 5)
                    val opacity = state.settings.onionSkinOpacity
                    val ghosts = mutableListOf<Pair<PixelBuffer, Float>>()
                    for (offset in -range..range) {
                        if (offset == 0) continue
                        val index = active + offset
                        if (index !in state.frames.indices) continue
                        val frame = canvasRepository.compositeFrame(index, transparentBackground = true) ?: continue
                        val ratio = (1024f / maxOf(frame.width, frame.height)).coerceAtMost(1f)
                        val preview =
                            if (ratio < 1f) {
                                withContext(Dispatchers.Default) {
                                    frame.scaled(
                                        (frame.width * ratio).toInt().coerceAtLeast(1),
                                        (frame.height * ratio).toInt().coerceAtLeast(1),
                                    )
                                }
                            } else {
                                frame
                            }
                        ghosts += preview to opacity / abs(offset).toFloat()
                    }
                    renderer.setOnionSkins(ghosts)
                    requestRender()
                }
        }

        private fun reportHistory() {
            onHistoryChanged?.invoke(canvasRepository.undoDepth, canvasRepository.redoDepth)
        }

        private fun pushTransform() {
            renderer.setTransformation(scale, offsetX, offsetY, rotationDegrees)
            onViewChanged?.invoke(scale, offsetX, offsetY, rotationDegrees)
            requestRender()
        }

        // -----------------------------------------------------------------------------------------
        // Pointer handling
        // -----------------------------------------------------------------------------------------

        /**
         * Accessibility click: tapping the canvas toggles the tool strip so switcher-style
         * accessibility services can reach it. The editor's real interaction is drawing, which
         * [onTouchEvent] handles.
         */
        override fun performClick(): Boolean {
            super.performClick()
            onToolStripToggle?.invoke()
            return true
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            val action =
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> PointerGestureRouter.Event.DOWN
                    MotionEvent.ACTION_POINTER_DOWN -> PointerGestureRouter.Event.POINTER_DOWN
                    MotionEvent.ACTION_MOVE -> PointerGestureRouter.Event.MOVE
                    MotionEvent.ACTION_POINTER_UP -> PointerGestureRouter.Event.POINTER_UP
                    MotionEvent.ACTION_UP -> PointerGestureRouter.Event.UP
                    MotionEvent.ACTION_CANCEL -> PointerGestureRouter.Event.CANCEL
                    else -> return false
                }
            if (action == PointerGestureRouter.Event.MOVE) {
                for (history in 0 until event.historySize) pointerRouter.observe(pointers(event, history))
            }
            val samples = pointers(event)
            val cancelled = Build.VERSION.SDK_INT >= 33 && (event.flags and MotionEvent.FLAG_CANCELED) != 0
            val route = pointerRouter.route(action, samples, event.actionIndex, event.eventTime, cancelled)
            if (route.cancelTool) cancelToolInteraction()
            when (route.action) {
                PointerGestureRouter.Action.START_TOOL -> {
                    resetNavigation()
                    val pointer = samples[route.index]
                    beginGesture(event, route.index, pointer.x, pointer.y, pointer.stylus)
                }
                PointerGestureRouter.Action.MOVE_TOOL -> {
                    for (history in 0 until event.historySize) {
                        continueGesture(
                            event,
                            route.index,
                            event.getHistoricalX(route.index, history),
                            event.getHistoricalY(route.index, history),
                            history,
                        )
                    }
                    continueGesture(event, route.index, event.getX(route.index), event.getY(route.index))
                }
                PointerGestureRouter.Action.END_TOOL -> {
                    val x = event.getX(route.index)
                    val y = event.getY(route.index)
                    // Pointer-up can carry the final segment without an intervening MOVE.
                    if (x != lastPointerX || y != lastPointerY) continueGesture(event, route.index, x, y)
                    endGesture(event, x, y, cancelled = false)
                }
                PointerGestureRouter.Action.CANCEL -> cancelActiveGesture()
                PointerGestureRouter.Action.REBASE_NAVIGATION -> {
                    val remaining =
                        if (action ==
                            PointerGestureRouter.Event.POINTER_UP
                        ) {
                            samples.filterIndexed { i, _ -> i != event.actionIndex }
                        } else {
                            samples
                        }
                    rebaseNavigation(remaining)
                }
                PointerGestureRouter.Action.NAVIGATE -> navigate(samples)
                PointerGestureRouter.Action.FINISH_NAVIGATION -> {
                    resetNavigation()
                    when (route.historyPointers) {
                        2 -> onUndoRequested?.invoke()
                        3 -> onRedoRequested?.invoke()
                    }
                }
                PointerGestureRouter.Action.IGNORE -> Unit
            }
            return true
        }

        private fun pointers(
            event: MotionEvent,
            history: Int = -1,
        ): List<PointerGestureRouter.Pointer> =
            List(event.pointerCount) { index ->
                val type = event.getToolType(index)
                PointerGestureRouter.Pointer(
                    event.getPointerId(index),
                    if (history < 0) event.getX(index) else event.getHistoricalX(index, history),
                    if (history < 0) event.getY(index) else event.getHistoricalY(index, history),
                    type == MotionEvent.TOOL_TYPE_STYLUS || type == MotionEvent.TOOL_TYPE_ERASER,
                )
            }

        private fun beginGesture(
            event: MotionEvent,
            index: Int,
            x: Float,
            y: Float,
            isStylus: Boolean,
        ): Boolean {
            lastPointerX = x
            lastPointerY = y
            gestureStartTime = event.eventTime
            gestureMoved = 0f

            val tool = if (event.getToolType(index) == MotionEvent.TOOL_TYPE_ERASER) ToolType.ERASER else input.tool
            // Palm rejection: a stylus always paints, fingers only when finger painting is on.
            if (!isStylus && !input.fingerPainting) {
                gestureTool = null
                return true
            }

            gestureTool = tool
            val (canvasX, canvasY) = snapped(event, x, y, index)
            val pressure = pressureOf(event, index)

            when (tool) {
                ToolType.BRUSH, ToolType.ERASER -> startStroke(canvasX, canvasY, pressure, tool)
                ToolType.SMUDGE -> startPixelGesture(tool, canvasX, canvasY, pressure, "Smudge")
                ToolType.CLONE_STAMP -> {
                    cloneDragStarted = false
                    if (cloneSource == null) {
                        onStatusMessage?.invoke("Tap to set the clone source")
                    } else {
                        startPixelGesture(tool, canvasX, canvasY, pressure, "Clone stamp")
                    }
                }
                ToolType.HEALING -> startPixelGesture(tool, canvasX, canvasY, pressure, "Healing")
                ToolType.LIQUIFY -> startPixelGesture(tool, canvasX, canvasY, pressure, "Liquify")
                ToolType.MOVE, ToolType.TRANSFORM -> startPixelGesture(tool, canvasX, canvasY, pressure, "Move")
                ToolType.GRADIENT -> {
                    gradientOrigin = canvasX to canvasY
                    previewPoints = mutableListOf(canvasX to canvasY)
                }
                ToolType.SHAPE -> {
                    shapeOrigin = canvasX to canvasY
                    previewPoints = mutableListOf(canvasX to canvasY)
                }
                ToolType.SELECT_RECTANGLE, ToolType.SELECT_ELLIPSE,
                ToolType.SELECT_LASSO, ToolType.SELECT_FREEHAND,
                ->
                    previewPoints = mutableListOf(canvasX to canvasY)
                else -> Unit // Paint bucket, magic wand, text, eyedropper and zoom act on release.
            }
            return true
        }

        private fun continueGesture(
            event: MotionEvent,
            index: Int,
            x: Float,
            y: Float,
            history: Int = -1,
        ): Boolean {
            val dx = x - lastPointerX
            val dy = y - lastPointerY
            gestureMoved += sqrt(dx * dx + dy * dy)

            lastPointerX = x
            lastPointerY = y
            val tool = gestureTool ?: return true
            val (canvasX, canvasY) = snapped(event, x, y, index)
            val pressure = pressureOf(event, index, history)

            when (tool) {
                ToolType.BRUSH, ToolType.ERASER ->
                    if (drawing) {
                        canvasRepository.continueStroke(
                            strokeId = currentStrokeId,
                            x = canvasX,
                            y = canvasY,
                            pressure = pressure,
                            tiltX = axisOf(event, index, MotionEvent.AXIS_TILT, history),
                            tiltY = axisOf(event, index, MotionEvent.AXIS_ORIENTATION, history),
                        )
                        updateLiveStroke()
                    }
                ToolType.SMUDGE, ToolType.CLONE_STAMP, ToolType.HEALING,
                ToolType.LIQUIFY, ToolType.MOVE, ToolType.TRANSFORM,
                -> {
                    if (tool == ToolType.CLONE_STAMP && gestureMoved > TAP_SLOP) cloneDragStarted = true
                    queuePixelSample(canvasX, canvasY, pressure)
                }
                ToolType.GRADIENT, ToolType.SHAPE,
                ToolType.SELECT_RECTANGLE, ToolType.SELECT_ELLIPSE,
                -> {
                    if (previewPoints.isEmpty()) previewPoints.add(canvasX to canvasY)
                    if (previewPoints.size == 1) {
                        previewPoints.add(canvasX to canvasY)
                    } else {
                        previewPoints[previewPoints.lastIndex] = canvasX to canvasY
                    }
                    emitDragPreview(tool)
                }
                ToolType.SELECT_FREEHAND, ToolType.SELECT_LASSO -> {
                    previewPoints.add(canvasX to canvasY)
                    emitDragPreview(tool, closed = tool == ToolType.SELECT_LASSO)
                }
                else -> Unit
            }

            lastPointerX = x
            lastPointerY = y
            return true
        }

        private fun endGesture(
            event: MotionEvent,
            x: Float,
            y: Float,
            cancelled: Boolean,
        ) {
            val tool = gestureTool ?: return
            val (canvasX, canvasY) = viewToCanvas(x, y)
            val elapsed = event.eventTime - gestureStartTime
            val wasTap = gestureMoved <= TAP_SLOP && elapsed < TAP_TIMEOUT_MS

            gestureTool = null
            val selectionPoints = previewPoints.toList()
            val shapeStart = shapeOrigin
            val gradientStart = gradientOrigin
            previewPoints = mutableListOf()
            shapeOrigin = null
            gradientOrigin = null

            when (tool) {
                ToolType.BRUSH, ToolType.ERASER -> {
                    if (drawing) {
                        if (cancelled) canvasRepository.cancelStroke(currentStrokeId) else canvasRepository.endStroke(currentStrokeId)
                    }
                    drawing = false
                    reportHistory()
                }
                ToolType.SMUDGE, ToolType.HEALING, ToolType.LIQUIFY,
                ToolType.MOVE, ToolType.TRANSFORM,
                -> endPixelGesture(cancelled)
                ToolType.CLONE_STAMP -> {
                    if (!cancelled && cloneSource == null && wasTap) {
                        cloneSource = canvasX to canvasY
                        onCloneSourceChanged?.invoke(canvasX to canvasY)
                        onStatusMessage?.invoke("Clone source set — drag to stamp")
                    } else {
                        endPixelGesture(cancelled)
                    }
                }
                ToolType.PAINT_BUCKET -> if (!cancelled && wasTap) bucketFill(canvasX, canvasY)
                ToolType.GRADIENT -> {
                    if (!cancelled && gradientStart != null && gestureMoved > TAP_SLOP) {
                        gradientFill(gradientStart, canvasX to canvasY)
                    }
                    onDragPreview?.invoke(null)
                }
                ToolType.SHAPE -> {
                    if (!cancelled && shapeStart != null && gestureMoved > TAP_SLOP) {
                        placeShape(
                            kind = input.shapeKind,
                            startX = shapeStart.first,
                            startY = shapeStart.second,
                            endX = canvasX,
                            endY = canvasY,
                            filled = input.shapeFilled,
                            color = input.brushColor,
                            strokeWidth = max(1f, input.brushParams.size / 4f),
                        )
                    }
                    onDragPreview?.invoke(null)
                }
                ToolType.TEXT -> if (!cancelled && wasTap) onTextPlacementRequested?.invoke(canvasX, canvasY)
                ToolType.EYEDROPPER -> if (!cancelled) pickColor(canvasX, canvasY)
                ToolType.SELECT_MAGIC_WAND -> if (!cancelled && wasTap) magicWandSelect(canvasX, canvasY)
                ToolType.SELECT_RECTANGLE, ToolType.SELECT_ELLIPSE,
                ToolType.SELECT_FREEHAND, ToolType.SELECT_LASSO,
                -> {
                    if (!cancelled) commitSelectionDrag(tool, selectionPoints)
                    onDragPreview?.invoke(null)
                }
                ToolType.ZOOM -> Unit
                else -> Unit
            }
        }

        // -----------------------------------------------------------------------------------------
        // Two-finger navigation (pan / zoom / rotate / gesture undo-redo)
        // -----------------------------------------------------------------------------------------

        private fun resetNavigation() {
            navAnchor = null
            navPrevDistance = 0f
        }

        private fun rebaseNavigation(pointers: List<PointerGestureRouter.Pointer>) {
            resetNavigation()
            if (pointers.size < 2) return
            val ordered = pointers.sortedBy { it.id }
            navPrevDistance = spacing(ordered)
            navPrevAngle = angle(ordered)
            navPrevMidX = ordered.map { it.x }.average().toFloat()
            navPrevMidY = ordered.map { it.y }.average().toFloat()
            navAnchor = viewToCanvas(navPrevMidX, navPrevMidY)
        }

        private fun navigate(pointers: List<PointerGestureRouter.Pointer>) {
            if (pointers.size < 2) return // Do not turn a trailing navigation finger into a brush.
            val ordered = pointers.sortedBy { it.id }
            val anchor =
                navAnchor ?: run {
                    rebaseNavigation(ordered)
                    return
                }
            val distance = spacing(ordered)
            val eventAngle = angle(ordered)
            val midX = ordered.map { it.x }.average().toFloat()
            val midY = ordered.map { it.y }.average().toFloat()
            if (navPrevDistance > 1f && distance > 1f) {
                scale = MathUtils.clamp(scale * (distance / navPrevDistance), MIN_SCALE, MAX_SCALE)
                rotationDegrees = normalizeDegrees(rotationDegrees + (eventAngle - navPrevAngle))
                val radians = Math.toRadians(rotationDegrees.toDouble())
                val cosR = cos(radians).toFloat()
                val sinR = sin(radians).toFloat()
                val u = scale * (anchor.first - canvasWidth / 2f)
                val v = scale * (anchor.second - canvasHeight / 2f)
                offsetX = midX - width / 2f - (u * cosR - v * sinR)
                offsetY = midY - height / 2f - (u * sinR + v * cosR)
            } else {
                offsetX += midX - navPrevMidX
                offsetY += midY - navPrevMidY
            }
            pushTransform()
            rebaseNavigation(ordered)
        }

        // -----------------------------------------------------------------------------------------
        // Brush strokes
        // -----------------------------------------------------------------------------------------

        private fun startStroke(
            x: Float,
            y: Float,
            pressure: Float,
            tool: ToolType,
        ) {
            val params =
                if (tool == ToolType.ERASER) {
                    input.brushParams.copy(size = input.eraserSize)
                } else {
                    input.brushParams
                }
            currentStrokeId =
                canvasRepository.beginStroke(
                    x = x,
                    y = y,
                    pressure = pressure,
                    brushParams = params,
                    layerId = activeLayerId,
                    isEraser = tool == ToolType.ERASER,
                    destination = input.strokeDestination,
                )
            drawing = currentStrokeId != 0L
            if (!drawing) onStatusMessage?.invoke("Choose an unlocked, visible layer with an editable destination")
            updateLiveStroke()
        }

        private fun updateLiveStroke() {
            // The repository snapshots in-flight strokes into their real layer. A topmost GL
            // overlay cannot represent erasing, clipping, layer opacity, masks or textured paint.
            requestPreviewRefresh()
        }

        // -----------------------------------------------------------------------------------------
        // Pixel tools (smudge, clone, heal, liquify, move)
        // -----------------------------------------------------------------------------------------

        /**
         * Opens one raster edit session for the whole gesture.
         *
         * The buffer is provisional: previews can show it, but save/export only see committed
         * artwork. The repository takes an undo snapshot only at a valid commit. Samples that arrive
         * before the session is open are queued and replayed, which keeps fast taps from being lost.
         */
        private fun startPixelGesture(
            tool: ToolType,
            x: Float,
            y: Float,
            pressure: Float,
            description: String,
        ) {
            cancelPixelInteraction()
            pixelTool = tool
            pixelCommitDescription = description
            pendingPixelCommit = false
            pendingPixelCancel = false
            pendingSamples.clear()
            rasterSession = null
            rasterBuffer = null
            rasterSource = null
            rasterBase = null
            smudgeSession = null
            cloneSession = null
            healingSession = null
            liquifySession = null
            moveStart = x to y

            val layerId = activeLayerId
            val gestureInput = input
            val gestureSelection = canvasRepository.selection()
            pixelOpenJob =
                coroutineScope.launch(start = CoroutineStart.UNDISPATCHED) {
                    val session = canvasRepository.beginRasterEdit(layerId)
                    if (session == null) {
                        pixelTool = null
                        onStatusMessage?.invoke("This layer cannot be edited")
                        return@launch
                    }
                    rasterSession = session
                    rasterBase = session.buffer.copy()
                    if (tool == ToolType.LIQUIFY && !prepareLiquifyReference(session, gestureInput)) {
                        canvasRepository.cancelRasterEdit(session)
                        rasterSession = null
                        rasterBuffer = null
                        rasterBase = null
                        pixelTool = null
                        return@launch
                    }
                    if (tool == ToolType.CLONE_STAMP || tool == ToolType.HEALING) {
                        rasterSource =
                            if (tool == ToolType.CLONE_STAMP && gestureInput.clone.sampleAllLayers) {
                                withContext(Dispatchers.Default) { canvasRepository.compositeBuffer() }
                            } else {
                                rasterBase
                            }
                    }
                    initToolSession(tool, session.buffer, x, y, pressure, gestureInput, gestureSelection)
                    rasterBuffer = session.buffer
                    drainPendingSamples()
                    if (pendingPixelCommit) commitPixelGesture(cancelled = pendingPixelCancel)
                }
        }

        private fun prepareLiquifyReference(
            session: CanvasRepository.RasterEditSession,
            gestureInput: EditorInput,
        ): Boolean {
            val frameId =
                canvasRepository.timeline.value.activeFrame
                    ?.id
            val valid =
                liquifyReference?.takeIf {
                    it.projectId == canvasRepository.projectId() &&
                        it.layerId == session.layerId &&
                        it.frameId == frameId &&
                        it.revision == session.contentRevision &&
                        it.contextVersion == liquifyContextVersion
                }
            liquifyReference = valid
            if (gestureInput.liquify.mode == LiquifyTool.Mode.RECONSTRUCT && valid == null) {
                onStatusMessage?.invoke(
                    "Reconstruct needs a previous liquify gesture on this layer. " +
                        "Other edits, undo or reopening reset it.",
                )
                return false
            }
            gestureLiquifyReference = valid ?: LiquifyReference(
                canvasRepository.projectId(),
                session.layerId,
                frameId,
                session.contentRevision,
                liquifyContextVersion,
                requireNotNull(rasterBase),
            )
            return true
        }

        private fun initToolSession(
            tool: ToolType,
            buffer: PixelBuffer,
            x: Float,
            y: Float,
            pressure: Float,
            gestureInput: EditorInput,
            selection: SelectionMask?,
        ) {
            val alphaLocked = rasterSession?.alphaLocked == true
            when (tool) {
                ToolType.SMUDGE ->
                    smudgeSession =
                        PixelBrushes.beginSmudge(
                            x,
                            y,
                            gestureInput.smudge.copy(size = gestureInput.brushParams.size, mask = selection, alphaLock = alphaLocked),
                        )
                ToolType.CLONE_STAMP -> {
                    val source = cloneSource ?: (x to y)
                    cloneSession =
                        PixelBrushes.beginCloneStamp(
                            targetX = x,
                            targetY = y,
                            sourceX = source.first,
                            sourceY = source.second,
                            settings =
                                gestureInput.clone.copy(
                                    size = gestureInput.brushParams.size,
                                    mask = selection,
                                    alphaLock = alphaLocked,
                                ),
                        )
                }
                ToolType.HEALING -> {
                    val settings =
                        gestureInput.healing.copy(
                            size = gestureInput.brushParams.size,
                            mask = selection,
                            alphaLock = alphaLocked,
                        )
                    val (sourceX, sourceY) = PixelBrushes.findSpotSource(buffer, x, y, settings)
                    healingSession = PixelBrushes.beginHealing(x, y, sourceX, sourceY, settings)
                }
                ToolType.LIQUIFY ->
                    liquifySession =
                        LiquifyTool.beginSession(
                            x = x,
                            y = y,
                            settings =
                                gestureInput.liquify.copy(
                                    size = gestureInput.brushParams.size,
                                    pressure = pressure,
                                    mask = selection,
                                    alphaLock = alphaLocked,
                                ),
                            width = buffer.width,
                            height = buffer.height,
                        )
                else -> Unit
            }
        }

        private fun queuePixelSample(
            x: Float,
            y: Float,
            pressure: Float,
        ) {
            if (rasterBuffer == null) {
                if (pendingSamples.size < MAX_PENDING_SAMPLES) {
                    pendingSamples.add(floatArrayOf(x, y, pressure))
                }
                return
            }
            applyPixelSample(x, y, pressure)
        }

        private fun drainPendingSamples() {
            val samples = pendingSamples.toList()
            pendingSamples.clear()
            samples.forEach { applyPixelSample(it[0], it[1], it[2]) }
        }

        private fun applyPixelSample(
            x: Float,
            y: Float,
            pressure: Float,
        ) {
            val buffer = rasterBuffer ?: return
            when (pixelTool) {
                ToolType.SMUDGE -> smudgeSession?.dragTo(x, y, buffer)
                ToolType.CLONE_STAMP -> cloneSession?.dragTo(x, y, buffer, rasterSource ?: buffer)
                ToolType.HEALING -> healingSession?.dragTo(x, y, buffer, rasterSource ?: buffer)
                ToolType.LIQUIFY -> {
                    liquifySession?.dragTo(x, y, pressure)
                    previewLiquify(buffer)
                }
                ToolType.MOVE, ToolType.TRANSFORM -> {
                    val base = rasterBase ?: return
                    buffer.clear()
                    drawShifted(
                        buffer,
                        base,
                        (x - moveOriginX()).roundToInt(),
                        (y - moveOriginY()).roundToInt(),
                    )
                }
                else -> Unit
            }
            requestPreviewRefresh()
        }

        /** Full-canvas resample of the liquify map, throttled so dragging stays responsive. */
        private fun previewLiquify(buffer: PixelBuffer) {
            val session = liquifySession ?: return
            val base = rasterBase ?: return
            val now = System.currentTimeMillis()
            if (now - lastLiquifyPreview < LIQUIFY_PREVIEW_INTERVAL_MS) return
            lastLiquifyPreview = now
            if (buffer.width * buffer.height > LIQUIFY_PREVIEW_MAX_PIXELS) return
            val preview = session.render(base, gestureLiquifyReference?.original)
            preview.pixels.copyInto(buffer.pixels)
        }

        private fun moveOriginX(): Float = moveStart.first

        private fun moveOriginY(): Float = moveStart.second

        private var moveStart: Pair<Float, Float> = 0f to 0f

        private fun endPixelGesture(cancelled: Boolean) {
            if (pixelTool == null) return
            if (rasterBuffer == null) {
                // Source snapshotting may still be opening the tool after beginRasterEdit.
                // Keep the final samples and release/cancel decision until it is ready.
                pendingPixelCommit = true
                pendingPixelCancel = cancelled
                return
            }
            commitPixelGesture(cancelled)
        }

        private fun commitPixelGesture(cancelled: Boolean) {
            val session = rasterSession ?: return
            val description = pixelCommitDescription
            val finalLiquify = liquifySession
            val reference = gestureLiquifyReference
            val original = rasterBase
            rasterSession = null
            rasterBuffer = null
            rasterSource = null
            rasterBase = null
            pixelTool = null
            pendingPixelCommit = false
            pendingSamples.clear()
            smudgeSession = null
            cloneSession = null
            healingSession = null
            liquifySession = null
            gestureLiquifyReference = null
            coroutineScope.launch {
                try {
                    if (!cancelled) {
                        // The preview is throttled and may omit the final drag sample or a large canvas.
                        // Commit the complete map off the UI thread, never the last partial preview.
                        if (finalLiquify != null && original != null) {
                            withContext(Dispatchers.Default) {
                                finalLiquify
                                    .render(original, reference?.original)
                                    .pixels
                                    .copyInto(session.buffer.pixels)
                            }
                        }
                        val changed =
                            original == null || withContext(Dispatchers.Default) { !original.pixels.contentEquals(session.buffer.pixels) }
                        if (changed) {
                            if (canvasRepository.commitRasterEdit(session, description)) {
                                if (finalLiquify != null && reference != null && reference.contextVersion == liquifyContextVersion) {
                                    liquifyReference = reference.copy(revision = canvasRepository.contentRevision)
                                }
                                reportHistory()
                            } else {
                                onStatusMessage?.invoke("The layer changed; this gesture was discarded")
                            }
                        }
                    }
                } finally {
                    withContext(NonCancellable) { canvasRepository.cancelRasterEdit(session) }
                }
            }
        }

        private fun requestPreviewRefresh() {
            val now = System.currentTimeMillis()
            if (now - lastPreviewRequest < PREVIEW_INTERVAL_MS) return
            lastPreviewRequest = now
            canvasRepository.requestPreviewRefresh()
        }

        private fun bucketFill(
            x: Float,
            y: Float,
        ) {
            val cx = x.roundToInt()
            val cy = y.roundToInt()
            val captured = input
            applyFillEdit("Paint bucket") { target, selection, alphaLocked ->
                FillTool
                    .floodFill(
                        target = target,
                        startX = cx,
                        startY = cy,
                        color = captured.brushColor,
                        settings =
                            FillTool.Settings(
                                tolerance = captured.fillTolerance,
                                contiguous = captured.fillContiguous,
                                mask = selection,
                                alphaLock = alphaLocked,
                            ),
                    ).changed
            }
        }

        private fun gradientFill(
            start: Pair<Float, Float>,
            end: Pair<Float, Float>,
        ) {
            val gradient = input.gradient.copy(type = input.gradientType, stops = input.gradient.stops.toList())
            applyFillEdit("Gradient") { target, selection, alphaLocked ->
                GradientTool
                    .draw(
                        target = target,
                        startX = start.first,
                        startY = start.second,
                        endX = end.first,
                        endY = end.second,
                        settings = GradientTool.Settings(gradient = gradient, mask = selection, alphaLock = alphaLocked),
                    ).changed
            }
        }

        /** Capture the destination and policy before yielding; a no-op never consumes history. */
        private fun applyFillEdit(
            description: String,
            edit: (PixelBuffer, SelectionMask?, Boolean) -> Boolean,
        ) {
            val layerId = activeLayerId
            val selection = canvasRepository.selection()
            coroutineScope.launch(start = CoroutineStart.UNDISPATCHED) {
                val session = canvasRepository.beginRasterEdit(layerId)
                if (session == null) {
                    onStatusMessage?.invoke("This layer cannot be edited right now")
                    return@launch
                }
                try {
                    val changed = withContext(Dispatchers.Default) { edit(session.buffer, selection, session.alphaLocked) }
                    if (!changed) {
                        onStatusMessage?.invoke("$description did not change any pixels")
                    } else if (canvasRepository.commitRasterEdit(session, description)) {
                        reportHistory()
                    } else {
                        onStatusMessage?.invoke("The document changed before $description could be applied")
                    }
                } finally {
                    withContext(NonCancellable) { canvasRepository.cancelRasterEdit(session) }
                }
            }
        }

        private fun pickColor(
            x: Float,
            y: Float,
        ) {
            coroutineScope.launch {
                val color =
                    withContext(Dispatchers.Default) {
                        val buffer = canvasRepository.compositeBuffer()
                        val px = x.roundToInt()
                        val py = y.roundToInt()
                        if (buffer == null || !buffer.contains(px, py)) {
                            null
                        } else {
                            buffer.getSafe(px, py).takeIf { (it ushr 24) != 0 }
                        }
                    }
                if (color != null) {
                    onColorPicked?.invoke(Channels.withAlpha(color, 255))
                } else {
                    onStatusMessage?.invoke("Nothing to sample here")
                }
            }
        }

        // -----------------------------------------------------------------------------------------
        // Selection
        // -----------------------------------------------------------------------------------------

        private fun commitSelectionDrag(
            tool: ToolType,
            points: List<Pair<Float, Float>>,
        ) {
            if (points.size < 2) return
            val mode = input.selectionMode
            runSelectionEdit(mode) { session ->
                withContext(Dispatchers.Default) {
                    when (tool) {
                        ToolType.SELECT_RECTANGLE ->
                            SelectionMask.rectangle(
                                session.width,
                                session.height,
                                points.first().first,
                                points.first().second,
                                points.last().first,
                                points.last().second,
                                checkActive = { ensureActive() },
                            )
                        ToolType.SELECT_ELLIPSE ->
                            SelectionMask.ellipse(
                                session.width,
                                session.height,
                                points.first().first,
                                points.first().second,
                                points.last().first,
                                points.last().second,
                                checkActive = { ensureActive() },
                            )
                        ToolType.SELECT_LASSO ->
                            SelectionMask.polygon(session.width, session.height, points, checkActive = { ensureActive() })
                        else ->
                            SelectionMask.fromStroke(session.width, session.height, points, radius = 12f, checkActive = { ensureActive() })
                    }
                }
            }
        }

        private fun magicWandSelect(
            x: Float,
            y: Float,
        ) {
            if (!x.isFinite() || !y.isFinite()) return
            val cx = floor(x).toInt()
            val cy = floor(y).toInt()
            val tolerance = input.fillTolerance
            val contiguous = input.fillContiguous
            val mode = input.selectionMode
            runSelectionEdit(mode) {
                val source = canvasRepository.compositeBuffer() ?: return@runSelectionEdit null
                withContext(Dispatchers.Default) {
                    SelectionMask.magicWand(
                        buffer = source,
                        startX = cx,
                        startY = cy,
                        tolerance = tolerance,
                        contiguous = contiguous,
                        checkActive = { ensureActive() },
                    )
                }
            }
        }

        private fun runSelectionEdit(
            mode: SelectionCombineMode,
            compute: suspend (CanvasRepository.SelectionEditSession) -> SelectionMask?,
        ) {
            selectionJob?.cancel()
            val session = canvasRepository.beginSelectionEdit()
            selectionJob =
                coroutineScope.launch(start = CoroutineStart.UNDISPATCHED) {
                    try {
                        val mask = compute(session) ?: return@launch
                        val combined =
                            withContext(Dispatchers.Default) {
                                combineSelection(session.original, mask, mode) { ensureActive() }
                            }
                        if (canvasRepository.commitSelectionEdit(session, combined)) {
                            onSelectionChanged?.invoke(combined, combined.selectedPixelCount())
                        }
                    } finally {
                        canvasRepository.cancelSelectionEdit(session)
                    }
                }
        }

        private fun combineSelection(
            existing: SelectionMask?,
            added: SelectionMask,
            mode: SelectionCombineMode,
            checkActive: () -> Unit,
        ): SelectionMask {
            checkActive()
            if (existing == null || mode == SelectionCombineMode.REPLACE) return added
            if (existing.width != added.width || existing.height != added.height) return added
            if (mode == SelectionCombineMode.INTERSECT) return SelectionMask.intersect(existing, added, checkActive)

            val result = existing.copy()
            for (i in result.coverage.indices) {
                if (i % 8192 == 0) checkActive()
                val a = result.coverage[i].toInt() and 0xFF
                val b = added.coverage[i].toInt() and 0xFF
                val value = if (mode == SelectionCombineMode.ADD) max(a, b) else max(0, a - b)
                result.coverage[i] = value.toByte()
            }
            return result
        }

        private fun emitDragPreview(
            tool: ToolType,
            closed: Boolean = false,
        ) {
            val points = previewPoints.toList()
            if (points.isEmpty()) {
                onDragPreview?.invoke(null)
                return
            }
            onDragPreview?.invoke(DragPreview(tool = tool, points = points, closed = closed))
        }

        // -----------------------------------------------------------------------------------------
        // Buffer helpers
        // -----------------------------------------------------------------------------------------

        /** Text and shapes are overlays, never replacements for the layer's existing pixels. */
        private fun applyOverlayEdit(
            description: String,
            render: (Int, Int) -> PixelBuffer,
        ) {
            val layerId = activeLayerId
            val selection = canvasRepository.selection()
            val alphaLocked = canvasRepository.getAllLayers().firstOrNull { it.id == layerId }?.isAlphaLocked ?: false
            // Bind the transaction before rasterization yields. Changing layers, frames, documents
            // or dimensions must not redirect completed work into a different document snapshot.
            coroutineScope.launch(start = CoroutineStart.UNDISPATCHED) {
                val session = canvasRepository.beginRasterEdit(layerId)
                if (session == null) {
                    onStatusMessage?.invoke("This layer cannot be edited right now")
                    return@launch
                }
                try {
                    val changed =
                        withContext(Dispatchers.Default) {
                            RasterOverlay.draw(
                                session.buffer,
                                render(session.buffer.width, session.buffer.height),
                                selection,
                                alphaLocked,
                            )
                        }
                    if (changed) {
                        if (canvasRepository.commitRasterEdit(session, description)) {
                            reportHistory()
                        } else {
                            onStatusMessage?.invoke("The document changed before $description could be applied")
                        }
                    }
                } finally {
                    withContext(NonCancellable) { canvasRepository.cancelRasterEdit(session) }
                }
            }
        }

        /** Draws [source] into [target] shifted by ([dx], [dy]) canvas pixels. */
        private fun drawShifted(
            target: PixelBuffer,
            source: PixelBuffer,
            dx: Int,
            dy: Int,
        ) {
            val height = min(target.height, source.height)
            val width = min(target.width, source.width)
            for (y in 0 until height) {
                val sourceY = y - dy
                if (sourceY < 0 || sourceY >= source.height) continue
                val targetRow = y * target.width
                val sourceRow = sourceY * source.width
                for (x in 0 until width) {
                    val sourceX = x - dx
                    if (sourceX < 0 || sourceX >= source.width) continue
                    target.pixels[targetRow + x] = source.pixels[sourceRow + sourceX]
                }
            }
        }

        private fun rasterizeText(
            width: Int,
            height: Int,
            x: Float,
            y: Float,
            text: String,
            style: TextLayout.TextStyle,
            color: Int,
        ): PixelBuffer {
            val buffer = PixelBuffer(width, height)
            val bitmap = BitmapPixelBridge.toBitmap(buffer)
            val canvas = Canvas(bitmap)
            val paint =
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    this.color = color
                    textSize = style.fontSize
                    typeface =
                        Typeface.create(
                            TextLayout.fallbackFamily(style.fontFamily),
                            when {
                                style.bold && style.italic -> Typeface.BOLD_ITALIC
                                style.bold -> Typeface.BOLD
                                style.italic -> Typeface.ITALIC
                                else -> Typeface.NORMAL
                            },
                        )
                    textAlign = Paint.Align.LEFT
                    if (style.letterSpacing != 0f && style.fontSize > 0f) {
                        letterSpacing = style.letterSpacing / style.fontSize
                    }
                    if (style.underline) isUnderlineText = true
                    if (style.strikeThrough) isStrikeThruText = true
                }

            val maxWidth = style.maxWidth ?: (width - x - 8f)
            val lines = mutableListOf<String>()
            text.split('\n').forEach { paragraph ->
                lines += TextLayout.wrap(paragraph, maxWidth, style) { candidate -> paint.measureText(candidate) }
            }

            var baseline = y + TextLayout.estimatedAscent(style)
            lines.forEach { line ->
                val measured = paint.measureText(line)
                val startX =
                    when (style.alignment) {
                        TextLayout.Alignment.CENTER -> x + (maxWidth - measured) / 2f
                        TextLayout.Alignment.RIGHT -> x + maxWidth - measured
                        else -> x
                    }
                canvas.drawText(line, startX, baseline, paint)
                baseline += style.lineHeightPx
            }
            val result = BitmapPixelBridge.fromBitmap(bitmap)
            bitmap.recycle()
            return result
        }

        private fun buildShapeBuffer(
            width: Int,
            height: Int,
            kind: ShapeKind,
            startX: Float,
            startY: Float,
            endX: Float,
            endY: Float,
            filled: Boolean,
            color: Int,
            strokeWidth: Float,
        ): PixelBuffer =
            BitmapPixelBridge.rasterizePath(
                width = width,
                height = height,
                build = { path ->
                    when (kind) {
                        ShapeKind.RECTANGLE ->
                            path.addRect(
                                min(startX, endX),
                                min(startY, endY),
                                max(startX, endX),
                                max(startY, endY),
                                Path.Direction.CW,
                            )
                        ShapeKind.ELLIPSE ->
                            path.addOval(
                                android.graphics.RectF(
                                    min(startX, endX),
                                    min(startY, endY),
                                    max(startX, endX),
                                    max(startY, endY),
                                ),
                                Path.Direction.CW,
                            )
                        ShapeKind.LINE -> {
                            path.moveTo(startX, startY)
                            path.lineTo(endX, endY)
                        }
                        ShapeKind.POLYGON -> {
                            val sides = 6
                            val radiusX = abs(endX - startX) / 2f
                            val radiusY = abs(endY - startY) / 2f
                            val cx = (startX + endX) / 2f
                            val cy = (startY + endY) / 2f
                            for (i in 0 until sides) {
                                val angle = (i / sides.toFloat()) * 2f * Math.PI.toFloat() - Math.PI.toFloat() / 2f
                                val px = cx + cos(angle) * radiusX
                                val py = cy + sin(angle) * radiusY
                                if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
                            }
                            path.close()
                        }
                    }
                },
                fillColor = if (filled) color else 0,
                strokeColor = if (!filled || kind == ShapeKind.LINE) color else 0,
                strokeWidth = if (!filled || kind == ShapeKind.LINE) max(1f, strokeWidth) else 0f,
            )

        // -----------------------------------------------------------------------------------------
        // Input helpers
        // -----------------------------------------------------------------------------------------

        private fun snapped(
            event: MotionEvent,
            x: Float,
            y: Float,
            index: Int,
        ): Pair<Float, Float> {
            var (canvasX, canvasY) = viewToCanvas(x, y)
            if (!input.snapToGuides) return canvasX to canvasY

            if (input.symmetry.isActive()) {
                val snappedPoint =
                    SymmetryEngine.snapToAxis(
                        canvasX,
                        canvasY,
                        canvasWidth,
                        canvasHeight,
                        input.symmetry,
                        SNAP_TOLERANCE,
                    )
                canvasX = snappedPoint.first
                canvasY = snappedPoint.second
            }
            if (input.perspective.isActive() && input.perspective.snapEnabled) {
                val snappedPoint =
                    PerspectiveGuide.snap(
                        canvasX,
                        canvasY,
                        input.perspective,
                        canvasWidth,
                        canvasHeight,
                    )
                canvasX = snappedPoint.first
                canvasY = snappedPoint.second
            }
            return canvasX to canvasY
        }

        private fun pressureOf(
            event: MotionEvent,
            index: Int,
            history: Int = -1,
        ): Float {
            val toolType = event.getToolType(index)
            val pressure = if (history < 0) event.getPressure(index) else event.getHistoricalPressure(index, history)
            val size = if (history < 0) event.getSize(index) else event.getHistoricalSize(index, history)
            val stylus = toolType == MotionEvent.TOOL_TYPE_STYLUS || toolType == MotionEvent.TOOL_TYPE_ERASER
            return PointerPressure.normalize(stylus, pressure, size)
        }

        private fun axisOf(
            event: MotionEvent,
            index: Int,
            axis: Int,
            history: Int,
        ): Float {
            val type = event.getToolType(index)
            if (type != MotionEvent.TOOL_TYPE_STYLUS && type != MotionEvent.TOOL_TYPE_ERASER) return 0f
            val value = if (history < 0) event.getAxisValue(axis, index) else event.getHistoricalAxisValue(axis, index, history)
            return if (value.isFinite()) value else 0f
        }

        private fun spacing(pointers: List<PointerGestureRouter.Pointer>): Float {
            val dx = pointers[0].x - pointers[1].x
            val dy = pointers[0].y - pointers[1].y
            return sqrt(dx * dx + dy * dy)
        }

        private fun angle(pointers: List<PointerGestureRouter.Pointer>): Float {
            val dx = pointers[0].x - pointers[1].x
            val dy = pointers[0].y - pointers[1].y
            return Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
        }

        private fun normalizeDegrees(value: Float): Float {
            val wrapped = value % 360f
            return if (wrapped < 0f) wrapped + 360f else wrapped
        }

        companion object {
            private const val MIN_SCALE = 0.05f
            private const val MAX_SCALE = 32f
            private const val TAP_SLOP = 24f
            private const val TAP_TIMEOUT_MS = 320L
            private const val SNAP_TOLERANCE = 12f
            private const val PREVIEW_INTERVAL_MS = 66L
            private const val LIQUIFY_PREVIEW_INTERVAL_MS = 140L
            private const val LIQUIFY_PREVIEW_MAX_PIXELS = 4_000_000
            private const val MAX_PENDING_SAMPLES = 512
        }
    }
