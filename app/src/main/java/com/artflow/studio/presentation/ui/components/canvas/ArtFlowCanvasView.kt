package com.artflow.studio.presentation.ui.components.canvas

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.opengl.GLSurfaceView
import android.util.AttributeSet
import android.view.MotionEvent
import androidx.core.math.MathUtils
import com.artflow.studio.core.perspective.PerspectiveGuide
import com.artflow.studio.core.pixels.Channels
import com.artflow.studio.core.pixels.PixelBuffer
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
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
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

        private var activePointerId = INVALID_POINTER_ID
        private var lastPointerX = 0f
        private var lastPointerY = 0f
        private var gestureStartTime = 0L
        private var gestureMoved = 0f
        private var gestureMaxPointers = 0
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
        private var pixelCommitDescription = ""

        private var smudgeSession: PixelBrushes.SmudgeSession? = null
        private var cloneSession: PixelBrushes.CloneSession? = null
        private var healingSession: PixelBrushes.HealingSession? = null
        private var liquifySession: LiquifyTool.Session? = null
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
            if (drawing) canvasRepository.cancelStroke(currentStrokeId)
            drawing = false
            pixelOpenJob?.cancel()
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
            onDragPreview?.invoke(null)
        }

        override fun onDetachedFromWindow() {
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
            if (destinationChanged && (drawing || pixelTool != null)) cancelActiveGesture()
            input = newInput
            canvasRepository.setStrokeColor(newInput.brushColor)
            canvasRepository.setSymmetry(newInput.symmetry)
        }

        fun setActiveLayerId(layerId: Long) {
            if (layerId != activeLayerId && (drawing || pixelTool != null)) cancelActiveGesture()
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
            canvasRepository.clearSelection()
            onSelectionChanged?.invoke(null, 0)
        }

        fun selectAll() {
            val mask = SelectionMask(canvasWidth, canvasHeight).apply { selectAll() }
            canvasRepository.setSelection(mask)
            onSelectionChanged?.invoke(mask, mask.selectedPixelCount())
        }

        fun invertSelection() {
            val mask =
                canvasRepository.selection()?.copy()
                    ?: SelectionMask(canvasWidth, canvasHeight).apply { selectAll() }
            mask.invert()
            canvasRepository.setSelection(mask)
            onSelectionChanged?.invoke(mask, mask.selectedPixelCount())
        }

        fun featherSelection(radius: Int) {
            val current = canvasRepository.selection() ?: return
            val feathered = current.feathered(radius)
            canvasRepository.setSelection(feathered)
            onSelectionChanged?.invoke(feathered, feathered.selectedPixelCount())
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
            coroutineScope.launch {
                val buffer =
                    withContext(Dispatchers.Default) {
                        rasterizeText(canvasWidth, canvasHeight, x, y, text, style, color)
                    }
                applyBufferEdit(buffer, "Text")
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
            coroutineScope.launch {
                val buffer =
                    withContext(Dispatchers.Default) {
                        buildShapeBuffer(
                            canvasWidth,
                            canvasHeight,
                            kind,
                            startX,
                            startY,
                            endX,
                            endY,
                            filled,
                            color,
                            strokeWidth,
                        )
                    }
                applyBufferEdit(buffer, "Shape")
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
                            if (event is CanvasInvalidationEvent.FrameChanged) onionDirty = true
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
            gestureMaxPointers = max(gestureMaxPointers, event.pointerCount)

            // Extra fingers during a stylus stroke are a resting palm, not a new gesture.
            if (drawing && event.pointerCount > 1) return true

            if (event.pointerCount > 1) {
                handleNavigation(event)
                return true
            }

            val stylusIndex = stylusPointerIndex(event)
            val isStylus = stylusIndex >= 0
            val index =
                if (stylusIndex >= 0) {
                    stylusIndex
                } else {
                    val tracked = activePointerIndex(event)
                    if (event.actionMasked == MotionEvent.ACTION_MOVE && tracked >= 0) tracked else 0
                }

            val x = event.getX(index)
            val y = event.getY(index)

            return when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> beginGesture(event, index, x, y, isStylus)
                MotionEvent.ACTION_MOVE -> continueGesture(event, index, x, y)
                MotionEvent.ACTION_UP -> {
                    endGesture(event, x, y, cancelled = false)
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    endGesture(event, x, y, cancelled = true)
                    true
                }
                MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> true
                else -> false
            }
        }

        private fun stylusPointerIndex(event: MotionEvent): Int {
            for (i in 0 until event.pointerCount) {
                val toolType = event.getToolType(i)
                if (toolType == MotionEvent.TOOL_TYPE_STYLUS || toolType == MotionEvent.TOOL_TYPE_ERASER) {
                    return i
                }
            }
            return -1
        }

        private fun activePointerIndex(event: MotionEvent): Int =
            if (activePointerId == INVALID_POINTER_ID) -1 else event.findPointerIndex(activePointerId)

        private fun beginGesture(
            event: MotionEvent,
            index: Int,
            x: Float,
            y: Float,
            isStylus: Boolean,
        ): Boolean {
            activePointerId = event.getPointerId(index)
            lastPointerX = x
            lastPointerY = y
            gestureStartTime = event.eventTime
            gestureMoved = 0f
            gestureMaxPointers = event.pointerCount

            val tool = input.tool
            // Palm rejection: a stylus always paints, fingers only when fingerprint painting is on.
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
                else -> Unit // Pant bucket, magic wand, text, eyedropper and zoom act on release.
            }
            return true
        }

        private fun continueGesture(
            event: MotionEvent,
            index: Int,
            x: Float,
            y: Float,
        ): Boolean {
            val dx = x - lastPointerX
            val dy = y - lastPointerY
            gestureMoved += sqrt(dx * dx + dy * dy)

            val tool = gestureTool ?: return true
            val (canvasX, canvasY) = snapped(event, x, y, index)
            val pressure = pressureOf(event, index)

            when (tool) {
                ToolType.BRUSH, ToolType.ERASER ->
                    if (drawing) {
                        canvasRepository.continueStroke(
                            strokeId = currentStrokeId,
                            x = canvasX,
                            y = canvasY,
                            pressure = pressure,
                            tiltX = tiltOf(event, index),
                            tiltY = orientationOf(event, index),
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
            val tool: ToolType =
                gestureTool ?: run {
                    activePointerId = INVALID_POINTER_ID
                    return
                }
            val (canvasX, canvasY) = viewToCanvas(x, y)
            val elapsed = event.eventTime - gestureStartTime
            val wasTap = gestureMoved <= TAP_SLOP && elapsed < TAP_TIMEOUT_MS

            activePointerId = INVALID_POINTER_ID
            gestureTool = null
            val previewedTool = tool
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
                    if (cloneSource == null && wasTap) {
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
                    if (!cancelled) commitSelectionDrag(previewedTool)
                    onDragPreview?.invoke(null)
                }
                ToolType.ZOOM -> Unit
                else -> Unit
            }
        }

        // -----------------------------------------------------------------------------------------
        // Two-finger navigation (pan / zoom / rotate / gesture undo-redo)
        // -----------------------------------------------------------------------------------------

        private fun handleNavigation(event: MotionEvent) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                    navPrevDistance = spacing(event)
                    navPrevAngle = angle(event)
                    navPrevMidX = midpointX(event)
                    navPrevMidY = midpointY(event)
                    navAnchor = viewToCanvas(navPrevMidX, navPrevMidY)
                    if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                        gestureStartTime = event.eventTime
                        gestureMoved = 0f
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    val distance = spacing(event)
                    val eventAngle = angle(event)
                    val midX = midpointX(event)
                    val midY = midpointY(event)
                    gestureMoved +=
                        sqrt(
                            (midX - navPrevMidX) * (midX - navPrevMidX) +
                                (midY - navPrevMidY) * (midY - navPrevMidY),
                        )

                    if (navPrevDistance > 1f && distance > 1f) {
                        val anchor = navAnchor
                        if (anchor != null) {
                            scale = MathUtils.clamp(scale * (distance / navPrevDistance), MIN_SCALE, MAX_SCALE)
                            rotationDegrees = normalizeDegrees(rotationDegrees + (eventAngle - navPrevAngle))
                            // Keep the canvas point that was under the fingers pinned to the fingers.
                            val radians = Math.toRadians(rotationDegrees.toDouble())
                            val cosR = cos(radians).toFloat()
                            val sinR = sin(radians).toFloat()
                            val u = scale * (anchor.first - canvasWidth / 2f)
                            val v = scale * (anchor.second - canvasHeight / 2f)
                            offsetX = midX - width / 2f - (u * cosR - v * sinR)
                            offsetY = midY - height / 2f - (u * sinR + v * cosR)
                            pushTransform()
                        }
                    } else {
                        offsetX += midX - navPrevMidX
                        offsetY += midY - navPrevMidY
                        pushTransform()
                    }

                    navPrevDistance = distance
                    navPrevAngle = eventAngle
                    navPrevMidX = midX
                    navPrevMidY = midY
                    navAnchor = viewToCanvas(midX, midY)
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    navPrevDistance = 0f
                    navAnchor = null
                }
                MotionEvent.ACTION_UP -> {
                    val elapsed = event.eventTime - gestureStartTime
                    if (gestureMoved <= TAP_SLOP && elapsed < TAP_TIMEOUT_MS) {
                        if (gestureMaxPointers >= 3) onRedoRequested?.invoke() else onUndoRequested?.invoke()
                    }
                    gestureMaxPointers = 1
                    navAnchor = null
                    navPrevDistance = 0f
                }
                MotionEvent.ACTION_CANCEL -> {
                    gestureMaxPointers = 1
                    navAnchor = null
                    navPrevDistance = 0f
                }
            }
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
         * Copy-on-write means the session's buffer *is* the layer's buffer, so the tools mutate live
         * pixels while the pre-gesture state stays safe in the undo snapshot. Samples that arrive
         * before the session is open are queued and replayed, which keeps fast taps from being lost.
         */
        private fun startPixelGesture(
            tool: ToolType,
            x: Float,
            y: Float,
            pressure: Float,
            description: String,
        ) {
            pixelTool = tool
            pixelCommitDescription = description
            cancelActiveGesture()
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
            pixelOpenJob =
                coroutineScope.launch {
                    val session = canvasRepository.beginRasterEdit(layerId)
                    if (session == null) {
                        pixelTool = null
                        onStatusMessage?.invoke("This layer cannot be edited")
                        return@launch
                    }
                    rasterSession = session
                    rasterBuffer = session.buffer
                    rasterBase = session.buffer.copy()
                    if (tool == ToolType.CLONE_STAMP || tool == ToolType.HEALING) {
                        rasterSource = withContext(Dispatchers.Default) { canvasRepository.compositeBuffer() }
                    }
                    initToolSession(tool, session.buffer, x, y)
                    drainPendingSamples()
                    if (pendingPixelCommit) commitPixelGesture(cancelled = pendingPixelCancel)
                }
        }

        private fun initToolSession(
            tool: ToolType,
            buffer: PixelBuffer,
            x: Float,
            y: Float,
        ) {
            val selection = canvasRepository.selection()
            when (tool) {
                ToolType.SMUDGE ->
                    smudgeSession =
                        PixelBrushes.beginSmudge(
                            x,
                            y,
                            input.smudge.copy(size = input.brushParams.size, mask = selection),
                        )
                ToolType.CLONE_STAMP -> {
                    val source = cloneSource ?: (x to y)
                    cloneSession =
                        PixelBrushes.beginCloneStamp(
                            targetX = x,
                            targetY = y,
                            sourceX = source.first,
                            sourceY = source.second,
                            settings = input.clone.copy(size = input.brushParams.size, mask = selection),
                        )
                }
                ToolType.HEALING -> {
                    val settings = input.healing.copy(size = input.brushParams.size, mask = selection)
                    val (sourceX, sourceY) = PixelBrushes.findSpotSource(buffer, x, y, settings)
                    healingSession = PixelBrushes.beginHealing(x, y, sourceX, sourceY, settings)
                }
                ToolType.LIQUIFY ->
                    liquifySession =
                        LiquifyTool.beginSession(
                            x = x,
                            y = y,
                            settings = input.liquify.copy(size = input.brushParams.size, mask = selection),
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
                    liquifySession?.dragTo(x, y)
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
            val preview = session.map.apply(base)
            preview.pixels.copyInto(buffer.pixels)
        }

        private fun moveOriginX(): Float = moveStart.first

        private fun moveOriginY(): Float = moveStart.second

        private var moveStart: Pair<Float, Float> = 0f to 0f

        private fun endPixelGesture(cancelled: Boolean) {
            if (pixelTool == null) return
            if (rasterSession == null) {
                // The session is still opening; it commits itself once it is ready.
                pendingPixelCommit = true
                pendingPixelCancel = cancelled
                return
            }
            commitPixelGesture(cancelled)
        }

        private fun commitPixelGesture(cancelled: Boolean) {
            val session = rasterSession ?: return
            val description = pixelCommitDescription
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
            coroutineScope.launch {
                if (cancelled) {
                    canvasRepository.cancelRasterEdit(session)
                } else {
                    if (!canvasRepository.commitRasterEdit(
                            session,
                            description,
                        )
                    ) {
                        onStatusMessage?.invoke("The layer changed; this gesture was discarded")
                    }
                    reportHistory()
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
            val layerId = activeLayerId
            coroutineScope.launch {
                val selection = canvasRepository.selection()
                val applied =
                    withContext(Dispatchers.Default) {
                        canvasRepository.applyRasterEdit(layerId, "Paint bucket") { target ->
                            FillTool.floodFill(
                                target = target,
                                startX = cx,
                                startY = cy,
                                color = input.brushColor,
                                settings =
                                    FillTool.Settings(
                                        tolerance = input.fillTolerance,
                                        contiguous = input.fillContiguous,
                                        mask = selection,
                                    ),
                            )
                        }
                    }
                if (applied) {
                    reportHistory()
                } else {
                    onStatusMessage?.invoke("Nothing to fill here")
                }
            }
        }

        private fun gradientFill(
            start: Pair<Float, Float>,
            end: Pair<Float, Float>,
        ) {
            val layerId = activeLayerId
            coroutineScope.launch {
                val selection = canvasRepository.selection()
                withContext(Dispatchers.Default) {
                    canvasRepository.applyRasterEdit(layerId, "Gradient") { target ->
                        GradientTool.draw(
                            target = target,
                            startX = start.first,
                            startY = start.second,
                            endX = end.first,
                            endY = end.second,
                            settings =
                                GradientTool.Settings(
                                    gradient = input.gradient.copy(type = input.gradientType),
                                    mask = selection,
                                ),
                        )
                    }
                }
                reportHistory()
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

        private fun commitSelectionDrag(tool: ToolType) {
            val points = previewPoints.toList()
            if (points.size < 2) return
            val width = canvasWidth
            val height = canvasHeight
            val mode = input.selectionMode
            val existing = canvasRepository.selection()
            coroutineScope.launch {
                val mask =
                    withContext(Dispatchers.Default) {
                        when (tool) {
                            ToolType.SELECT_RECTANGLE ->
                                SelectionMask.rectangle(
                                    width,
                                    height,
                                    points.first().first,
                                    points.first().second,
                                    points.last().first,
                                    points.last().second,
                                )
                            ToolType.SELECT_ELLIPSE ->
                                SelectionMask.ellipse(
                                    width,
                                    height,
                                    points.first().first,
                                    points.first().second,
                                    points.last().first,
                                    points.last().second,
                                )
                            ToolType.SELECT_LASSO -> SelectionMask.polygon(width, height, points)
                            else -> SelectionMask.fromStroke(width, height, points, radius = 12f)
                        }
                    }
                val combined = combineSelection(existing, mask, mode)
                canvasRepository.setSelection(combined)
                onSelectionChanged?.invoke(combined, combined.selectedPixelCount())
            }
        }

        private fun magicWandSelect(
            x: Float,
            y: Float,
        ) {
            val cx = x.roundToInt()
            val cy = y.roundToInt()
            val tolerance = input.fillTolerance
            val contiguous = input.fillContiguous
            val mode = input.selectionMode
            coroutineScope.launch {
                val existing = canvasRepository.selection()
                val mask =
                    withContext(Dispatchers.Default) {
                        val source = canvasRepository.compositeBuffer() ?: return@withContext null
                        SelectionMask.magicWand(
                            buffer = source,
                            startX = cx,
                            startY = cy,
                            tolerance = tolerance,
                            contiguous = contiguous,
                            respectExistingSelection = existing,
                        )
                    } ?: return@launch
                val combined = combineSelection(existing, mask, mode)
                canvasRepository.setSelection(combined)
                onSelectionChanged?.invoke(combined, combined.selectedPixelCount())
            }
        }

        private fun combineSelection(
            existing: SelectionMask?,
            added: SelectionMask,
            mode: SelectionCombineMode,
        ): SelectionMask {
            if (existing == null || mode == SelectionCombineMode.REPLACE) return added
            if (existing.width != added.width || existing.height != added.height) return added
            if (mode == SelectionCombineMode.INTERSECT) return SelectionMask.intersect(existing, added)

            val result = existing.copy()
            for (i in result.coverage.indices) {
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

        private fun applyBufferEdit(
            buffer: PixelBuffer,
            description: String,
        ) {
            val layerId = activeLayerId
            coroutineScope.launch {
                val selection = canvasRepository.selection()
                canvasRepository.applyRasterEdit(layerId, description) { target ->
                    if (selection == null) {
                        buffer.pixels.copyInto(target.pixels)
                    } else {
                        for (i in target.pixels.indices) {
                            val coverage = selection.alphaAt(i)
                            if (coverage <= 0f) continue
                            val source = buffer.pixels[i]
                            target.pixels[i] = if (coverage >= 1f) source else Channels.scaleAlpha(source, coverage)
                        }
                    }
                }
                reportHistory()
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
                val sourceY = y + dy
                if (sourceY < 0 || sourceY >= source.height) continue
                val targetRow = y * target.width
                val sourceRow = sourceY * source.width
                for (x in 0 until width) {
                    val sourceX = x + dx
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
                            val radius = max(abs(endX - startX), abs(endY - startY))
                            val cx = (startX + endX) / 2f
                            val cy = (startY + endY) / 2f
                            for (i in 0 until sides) {
                                val angle = (i / sides.toFloat()) * 2f * Math.PI.toFloat() - Math.PI.toFloat() / 2f
                                val px = cx + cos(angle) * radius
                                val py = cy + sin(angle) * radius
                                if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
                            }
                            path.close()
                        }
                    }
                },
                fillColor = if (filled) color else 0,
                strokeColor = if (!filled || kind == ShapeKind.LINE) color else 0,
                strokeWidth = if (kind == ShapeKind.LINE) max(1f, strokeWidth) else 0f,
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
        ): Float {
            val toolType = event.getToolType(index)
            return if (toolType == MotionEvent.TOOL_TYPE_STYLUS || toolType == MotionEvent.TOOL_TYPE_ERASER) {
                event.getPressure(index).coerceIn(MIN_PRESSURE, 1f)
            } else {
                // Fingers report contact size rather than pressure; use it as a gentle proxy.
                (0.35f + min(1f, event.getSize(index) * 3f) * 0.65f).coerceIn(MIN_PRESSURE, 1f)
            }
        }

        private fun tiltOf(
            event: MotionEvent,
            index: Int,
        ): Float {
            if (event.getToolType(index) != MotionEvent.TOOL_TYPE_STYLUS) return 0f
            return event.getAxisValue(MotionEvent.AXIS_TILT, index)
        }

        private fun orientationOf(
            event: MotionEvent,
            index: Int,
        ): Float {
            if (event.getToolType(index) != MotionEvent.TOOL_TYPE_STYLUS) return 0f
            return event.getAxisValue(MotionEvent.AXIS_ORIENTATION, index)
        }

        private fun spacing(event: MotionEvent): Float {
            if (event.pointerCount < 2) return 0f
            val dx = event.getX(0) - event.getX(1)
            val dy = event.getY(0) - event.getY(1)
            return sqrt(dx * dx + dy * dy)
        }

        private fun angle(event: MotionEvent): Float {
            if (event.pointerCount < 2) return 0f
            val dx = event.getX(0) - event.getX(1)
            val dy = event.getY(0) - event.getY(1)
            return Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
        }

        private fun midpointX(event: MotionEvent): Float {
            var sum = 0f
            for (i in 0 until event.pointerCount) sum += event.getX(i)
            return sum / event.pointerCount
        }

        private fun midpointY(event: MotionEvent): Float {
            var sum = 0f
            for (i in 0 until event.pointerCount) sum += event.getY(i)
            return sum / event.pointerCount
        }

        private fun normalizeDegrees(value: Float): Float {
            val wrapped = value % 360f
            return if (wrapped < 0f) wrapped + 360f else wrapped
        }

        companion object {
            private const val INVALID_POINTER_ID = -1
            private const val MIN_SCALE = 0.05f
            private const val MAX_SCALE = 32f
            private const val TAP_SLOP = 24f
            private const val TAP_TIMEOUT_MS = 320L
            private const val MIN_PRESSURE = 0.05f
            private const val SNAP_TOLERANCE = 12f
            private const val PREVIEW_INTERVAL_MS = 66L
            private const val LIQUIFY_PREVIEW_INTERVAL_MS = 140L
            private const val LIQUIFY_PREVIEW_MAX_PIXELS = 4_000_000
            private const val MAX_PENDING_SAMPLES = 512
        }
    }
