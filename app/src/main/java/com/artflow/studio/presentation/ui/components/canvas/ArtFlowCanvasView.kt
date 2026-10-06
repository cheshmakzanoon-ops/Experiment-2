package com.artflow.studio.presentation.ui.components.canvas

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Path
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.animation.DecelerateInterpolator
import androidx.core.math.MathUtils
import com.artflow.studio.core.animation.OnionSkin
import com.artflow.studio.core.canvas.MotionFilter
import com.artflow.studio.core.canvas.PointerGestureRouter
import com.artflow.studio.core.canvas.PointerPressure
import com.artflow.studio.core.canvas.PressureSmoother
import com.artflow.studio.core.canvas.QuickPinch
import com.artflow.studio.core.canvas.QuickShape
import com.artflow.studio.core.canvas.StrokePredictor
import com.artflow.studio.core.canvas.StrokeStabilizer
import com.artflow.studio.core.color.CmykProof
import com.artflow.studio.core.perspective.PerspectiveGuide
import com.artflow.studio.core.pixels.Channels
import com.artflow.studio.core.pixels.IntBounds
import com.artflow.studio.core.pixels.LayerTransform
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.core.pixels.Quad
import com.artflow.studio.core.pixels.RasterOverlay
import com.artflow.studio.core.pixels.SelectionMask
import com.artflow.studio.core.pixels.TransformQuad
import com.artflow.studio.core.pixels.WarpMesh
import com.artflow.studio.core.render.BrushPatch
import com.artflow.studio.core.symmetry.SymmetryEngine
import com.artflow.studio.core.text.TextLayout
import com.artflow.studio.core.tool.FillTool
import com.artflow.studio.core.tool.GradientTool
import com.artflow.studio.core.tool.LiquifyTool
import com.artflow.studio.core.tool.PixelBrushes
import com.artflow.studio.core.tool.ToolType
import com.artflow.studio.data.renderer.BitmapPixelBridge
import com.artflow.studio.data.renderer.opengl.OpenGLCanvasRenderer
import com.artflow.studio.domain.model.animation.AnimationSettings
import com.artflow.studio.domain.model.brush.BrushParams
import com.artflow.studio.domain.model.brush.PressureResponse
import com.artflow.studio.domain.model.brush.StrokeDestination
import com.artflow.studio.domain.repository.canvas.CanvasInvalidationEvent
import com.artflow.studio.domain.repository.canvas.CanvasRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
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
    /** Procreate's secondary colour: brushes with secondary colour dynamics blend toward it. */
    val secondaryColor: Int = 0xFFFFFFFF.toInt(),
    val eraserSize: Float = 48f,
    val symmetry: SymmetryEngine.Settings = SymmetryEngine.Settings(),
    val perspective: PerspectiveGuide.Settings = PerspectiveGuide.Settings(),
    val snapToGuides: Boolean = false,
    val gradient: GradientTool.Gradient = GradientTool.Presets.BLACK_TO_WHITE,
    val gradientType: GradientTool.GradientType = GradientTool.GradientType.LINEAR,
    val fillTolerance: Int = 32,
    val fillContiguous: Boolean = true,
    /** Gaps in line art up to this many pixels wide stop a fill, so it does not leak out. */
    val fillGapClose: Int = 0,
    val smudge: PixelBrushes.SmudgeSettings = PixelBrushes.SmudgeSettings(),
    val clone: PixelBrushes.CloneSettings = PixelBrushes.CloneSettings(),
    val healing: PixelBrushes.HealingSettings = PixelBrushes.HealingSettings(),
    val liquify: LiquifyTool.Settings = LiquifyTool.Settings(),
    val shapeKind: ShapeKind = ShapeKind.RECTANGLE,
    val shapeFilled: Boolean = true,
    val text: String = "ArtFlow",
    val textStyle: TextLayout.TextStyle = TextLayout.TextStyle(),
    val selectionMode: SelectionCombineMode = SelectionCombineMode.REPLACE,
    /** How a one-finger drag edits the active layer while the transform tool is selected. */
    val transformMode: TransformQuad.Mode = TransformQuad.Mode.FREEFORM,
    val transformInterpolation: TransformQuad.Interpolation = TransformQuad.Interpolation.BILINEAR,
    /** Transform Magnetics and Snapping. */
    val transformAssist: TransformQuad.Assist = TransformQuad.Assist(),
    /** Holding the pen still at the end of a stroke snaps it to a line or ellipse. */
    val quickShape: Boolean = true,
    /** A finger held still for a moment samples colour, like Procreate's touch-and-hold eyedropper. */
    val touchHoldEyedropper: Boolean = true,
    /** Whether a finger (rather than a stylus) may paint. */
    val fingerPainting: Boolean = true,
    /** Global pressure response exponent from Prefs > Pressure and Smoothing. */
    val pressureCurve: Float = 1f,
    /** Global stabilization from Prefs > Pressure and Smoothing (0..1). */
    val stabilization: Float = 0f,
    /** Prefs > Pressure and Smoothing: motion filtering, its expression, and pressure smoothing (0..1). */
    val motionFiltering: Float = 0f,
    val motionExpression: Float = 0.5f,
    val pressureSmoothing: Float = 0f,
    /** Pulled string from Prefs > Pressure and Smoothing (0..1 of 60 dp on screen). */
    val pulledString: Float = 0f,
    /** Prefs > Pressure and Smoothing curve, applied after [pressureCurve]. */
    val pressureResponse: PressureResponse = PressureResponse(),
    /** Settings > Haptics: a short buzz confirms QuickShape and the touch-and-hold eyedropper. */
    val haptics: Boolean = true,
    /** Settings > Reduce motion: the view jumps to fit instead of animating there. */
    val reduceMotion: Boolean = false,
    /** Outline the brush under a hovering stylus. */
    val brushCursor: Boolean = true,
    /** Gesture controls from Prefs; each can be switched off. */
    val gestures: GestureControls = GestureControls(),
    /** Prefs > Dynamic brush scaling: brush size follows the zoom so it looks the same on screen. */
    val dynamicBrushScaling: Boolean = false,
)

/** Which multi-finger shortcuts are active, from Prefs > Gesture controls. */
data class GestureControls(
    val scrubToClear: Boolean = true,
    val swipeCopyPaste: Boolean = true,
    val fourFingerFullScreen: Boolean = true,
    /** How long two or three resting fingers wait before undo or redo starts repeating. */
    val rapidUndoDelayMs: Int = 650,
)

/** Brush outline under a hovering stylus, in view pixels. */
data class BrushCursor(
    val x: Float,
    val y: Float,
    val radius: Float,
)

/** Procreate's eyedropper loupe: where the sample is (view pixels), the colour found and the one it replaces. */
data class EyedropperLoupe(
    val x: Float,
    val y: Float,
    val color: Int,
    val previous: Int,
)

/** Rubber-band geometry reported while a selection, shape or gradient drag is in progress. */
data class DragPreview(
    val tool: ToolType,
    val points: List<Pair<Float, Float>>,
    val closed: Boolean = false,
)

/** Edit Shape as the editor shows it: the shape's kind, its nodes in canvas pixels, and whether they are being edited. */
data class ShapeEditState(
    val label: String,
    val nodes: List<Pair<Float, Float>>,
    val editing: Boolean,
)

/** What Edit Shape needs to redraw a QuickShape stroke, and the history step that stroke made. */
private class ShapeEdit(
    val shape: QuickShape.Result,
    val pressure: Float,
    val tool: ToolType,
    val layerId: Long,
    val mark: Long,
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

        /** Holding two fingers still keeps undoing, three keeps redoing, as in Procreate. */
        private val rapidHistory =
            object : Runnable {
                override fun run() {
                    val fingers = pointerRouter.heldFingers()
                    if (fingers !in 2..3) return
                    pointerRouter.consumeTap()
                    if (fingers == 2) onUndoRequested?.invoke() else onRedoRequested?.invoke()
                    postDelayed(this, RAPID_HISTORY_REPEAT_MS)
                }
            }
        private var lastPointerX = 0f
        private var lastPointerY = 0f
        private var gestureStartTime = 0L
        private var gestureMoved = 0f
        private var gestureStartView = 0f to 0f

        /** Reports a new Automatic-selection threshold chosen by sliding (0-255). */
        var onFillToleranceChanged: ((Int) -> Unit)? = null

        /** Reports the brush outline under a hovering stylus, or null when it leaves. */
        var onBrushCursorChanged: ((BrushCursor?) -> Unit)? = null

        override fun onHoverEvent(event: MotionEvent): Boolean {
            val type = event.getToolType(0)
            if (type != MotionEvent.TOOL_TYPE_STYLUS && type != MotionEvent.TOOL_TYPE_ERASER) return super.onHoverEvent(event)
            val tool = if (type == MotionEvent.TOOL_TYPE_ERASER) ToolType.ERASER else input.tool
            val showing = input.brushCursor && tool in CURSOR_TOOLS && event.actionMasked != MotionEvent.ACTION_HOVER_EXIT
            val size = if (tool == ToolType.ERASER) input.eraserSize else input.brushParams.size
            // With dynamic brush scaling the brush keeps its size on screen, whatever the zoom.
            val onScreen = if (input.dynamicBrushScaling) size / 2f else size / 2f * scale
            onBrushCursorChanged?.invoke(if (showing) BrushCursor(event.x, event.y, onScreen) else null)
            return true
        }

        private var gestureTool: ToolType? = null

        private var drawing = false
        private var currentStrokeId = 0L
        private var stabilizer: StrokeStabilizer? = null
        private var motionFilter: MotionFilter? = null
        private var pressureSmoother: PressureSmoother? = null
        private val predictor = StrokePredictor()
        private val strokeRawPoints = mutableListOf<Pair<Float, Float>>()
        private var strokePressureSum = 0f
        private var strokeLastPressure = 1f
        private var quickShapeApplied = false
        private var quickShapeResult: QuickShape.Result? = null
        private var quickShapeAnchor = 0f to 0f
        private var quickShapePressure = 1f
        private var quickShapeShown: QuickShape.Result? = null
        private var quickShapeTool = ToolType.BRUSH
        private var shapeEdit: ShapeEdit? = null
        private var editingShape = false
        private var draggedNode = -1
        private var shapeStrokeOpen = false
        private var holdAnchorX = 0f
        private var holdAnchorY = 0f
        private val quickShapeCheck = Runnable { applyQuickShape() }

        // Navigation baseline
        private var navPrevDistance = 0f
        private var navPrevAngle = 0f
        private var navPrevMidX = 0f
        private var navPrevMidY = 0f
        private var navAnchor: Pair<Float, Float>? = null
        private var pinchStartScale = 0f
        private var pinchStartTime = 0L
        private var viewAnimator: ValueAnimator? = null

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

        /** The eyedropper loupe while colour is being sampled, then null. */
        var onEyedropperChanged: ((EyedropperLoupe?) -> Unit)? = null
        var onSelectionChanged: ((SelectionMask?, Int) -> Unit)? = null
        var onDragPreview: ((DragPreview?) -> Unit)? = null
        var onHistoryChanged: ((undo: Int, redo: Int) -> Unit)? = null
        var onUndoRequested: (() -> Unit)? = null
        var onRedoRequested: (() -> Unit)? = null
        var onFullscreenRequested: (() -> Unit)? = null
        var onCopyPasteMenuRequested: (() -> Unit)? = null
        var onClearLayerRequested: (() -> Unit)? = null
        var onViewChanged: ((scale: Float, offsetX: Float, offsetY: Float, rotation: Float) -> Unit)? = null
        var onTextPlacementRequested: ((x: Float, y: Float) -> Unit)? = null

        /** Invoked by accessibility clicks (see [performClick]); unused by direct touch. */
        var onToolStripToggle: (() -> Unit)? = null
        var onCloneSourceChanged: ((Pair<Float, Float>) -> Unit)? = null
        var onStatusMessage: ((String) -> Unit)? = null

        // --- Lifecycle ----------------------------------------------------------------------------

        private var rendererAttached = false

        /** Set when the renderer has no image to patch, so the next frame publishes the whole composite. */
        private var fullCompositeNeeded = true
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
            // Detaching disposed the renderer's image, so the next frame must carry the whole canvas.
            fullCompositeNeeded = true
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
            clearPrediction()
            pointerRouter.suppress()
            resetNavigation()
            pinchStartScale = 0f
            cancelToolInteraction()
        }

        private fun cancelToolInteraction() {
            // A node drag keeps the shape it has reached rather than losing the stroke.
            if (draggedNode >= 0) dropShapeNode()
            gestureTool = null
            previewPoints.clear()
            shapeOrigin = null
            gradientOrigin = null
            if (drawing) canvasRepository.cancelStroke(currentStrokeId)
            drawing = false
            removeCallbacks(quickShapeCheck)
            removeCallbacks(holdEyedropper)
            endSampling()
            fillingTap = false
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
            coroutineScope.launch(NonCancellable, start = CoroutineStart.UNDISPATCHED) { companions.cancel(canvasRepository) }
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
            removeCallbacks(rapidHistory)
            viewAnimator?.cancel()
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
            val enteringTransform = newInput.tool == ToolType.TRANSFORM && input.tool != ToolType.TRANSFORM
            val leavingTransform = newInput.tool != ToolType.TRANSFORM && input.tool == ToolType.TRANSFORM
            val warpToggled = (newInput.transformMode == TransformQuad.Mode.WARP) != (input.transformMode == TransformQuad.Mode.WARP)
            input = newInput
            if (enteringTransform) ensureTransformSession()
            if (leavingTransform) clearTransformSession()
            // Entering or leaving Warp bakes the current placement so each mode edits what is on the layer.
            if (warpToggled && newInput.tool == ToolType.TRANSFORM && transformSession != null) {
                clearTransformSession()
                ensureTransformSession()
            }
            canvasRepository.setStrokeColor(newInput.brushColor)
            canvasRepository.setSecondaryColor(newInput.secondaryColor)
            canvasRepository.setSymmetry(newInput.symmetry)
        }

        fun setActiveLayerId(layerId: Long) {
            val changed = layerId != activeLayerId
            if (changed) {
                resetLiquifyReference()
                cancelActiveGesture()
            }
            activeLayerId = layerId
            if (changed && input.tool == ToolType.TRANSFORM) {
                clearTransformSession()
                ensureTransformSession()
            }
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

        fun setWideColor(enabled: Boolean) {
            renderer.setWideColor(enabled)
            requestRender()
        }

        fun setProof(mode: CmykProof.Mode) {
            renderer.setProof(mode.ordinal)
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

        private fun fitScale(): Float =
            (min(width.toFloat() / canvasWidth, height.toFloat() / canvasHeight) * 0.92f).coerceIn(MIN_SCALE, MAX_SCALE)

        /** Eases the view back to fit the screen, unrotated, as after Procreate's quick pinch. */
        fun animateFitToView() {
            if (width == 0 || height == 0) return
            if (input.reduceMotion) return fitToView()
            val fromScale = scale
            val toScale = fitScale()
            val fromX = offsetX
            val fromY = offsetY
            val fromRotation = if (rotationDegrees > 180f) rotationDegrees - 360f else rotationDegrees
            viewAnimator?.cancel()
            viewAnimator =
                ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = FIT_ANIMATION_MS
                    interpolator = DecelerateInterpolator()
                    addUpdateListener { animator ->
                        val t = animator.animatedValue as Float
                        scale = fromScale + (toScale - fromScale) * t
                        offsetX = fromX * (1f - t)
                        offsetY = fromY * (1f - t)
                        rotationDegrees = normalizeDegrees(fromRotation * (1f - t))
                        pushTransform()
                    }
                    start()
                }
        }

        fun fitToView() {
            if (width == 0 || height == 0) return
            viewAnimator?.cancel()
            scale = fitScale()
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
            dismissShapeEdit()
            if (canvasRepository.undo()) {
                onionDirty = true
                reportHistory()
            }
        }

        fun redo() {
            dismissShapeEdit()
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

        /**
         * ColorDrop: flood-fills the region under a window-space drop point with the current
         * colour. Returns false when the point is outside the artwork.
         */
        fun colorDrop(
            windowX: Float,
            windowY: Float,
        ): Boolean {
            val origin = IntArray(2)
            getLocationInWindow(origin)
            val localX = windowX - origin[0]
            val localY = windowY - origin[1]
            if (localX !in 0f..width.toFloat() || localY !in 0f..height.toFloat()) return false
            val (x, y) = viewToCanvas(localX, localY)
            if (floor(x).toInt() !in 0 until canvasWidth || floor(y).toInt() !in 0 until canvasHeight) return false
            cancelActiveGesture()
            lastColorDrop = null
            colorDropTolerance = input.fillTolerance
            bucketFill(x, y, colorDropTolerance) { depth -> lastColorDrop = ColorDropFill(x, y, depth) }
            return true
        }

        /**
         * ColorDrop's Continue Filling: while on, each tap on the canvas fills there with the current
         * colour at the threshold last set, and becomes the fill the threshold slider adjusts.
         */
        var continueFilling = false

        private var colorDropTolerance = 0
        private var fillingTap = false

        private fun continueFill(
            viewX: Float,
            viewY: Float,
        ) {
            val (x, y) = viewToCanvas(viewX, viewY)
            if (floor(x).toInt() !in 0 until canvasWidth || floor(y).toInt() !in 0 until canvasHeight) return
            lastColorDrop = null
            bucketFill(x, y, colorDropTolerance) { depth -> lastColorDrop = ColorDropFill(x, y, depth) }
        }

        /** Where the last ColorDrop landed and the history depth its fill produced. */
        private class ColorDropFill(
            val x: Float,
            val y: Float,
            val depth: Int,
        )

        private var lastColorDrop: ColorDropFill? = null

        /**
         * Procreate's ColorDrop threshold: replaces the last drop's fill with one at [tolerance]
         * (0-255). Ignored once anything else has been done since the drop.
         */
        fun adjustColorDrop(tolerance: Int) {
            colorDropTolerance = tolerance.coerceIn(0, 255)
            val drop = lastColorDrop ?: return
            coroutineScope.launch {
                if (canvasRepository.undoDepth != drop.depth || !canvasRepository.undo()) {
                    lastColorDrop = null
                    return@launch
                }
                reportHistory()
                bucketFill(drop.x, drop.y, tolerance.coerceIn(0, 255)) { depth -> lastColorDrop = ColorDropFill(drop.x, drop.y, depth) }
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

        /** Flips, rotates and scales the transform box by a fixed amount (toolbar buttons). */
        fun transformActiveLayer(
            scaleFactor: Float = 1f,
            rotation: Float = 0f,
            flipHorizontal: Boolean = false,
            flipVertical: Boolean = false,
        ) = applyTransformQuad(
            { session ->
                var quad = session.quad
                if (flipHorizontal) quad = TransformQuad.flip(quad, horizontal = true)
                if (flipVertical) quad = TransformQuad.flip(quad, horizontal = false)
                if (rotation != 0f) quad = TransformQuad.rotate(quad, rotation)
                if (scaleFactor != 1f) quad = TransformQuad.scale(quad, scaleFactor)
                quad
            },
            { start ->
                var mesh = start
                if (flipHorizontal) mesh = mesh.flip(horizontal = true)
                if (flipVertical) mesh = mesh.flip(horizontal = false)
                if (rotation != 0f) mesh = mesh.rotate(rotation)
                if (scaleFactor != 1f) mesh = mesh.scale(scaleFactor)
                mesh
            },
        )

        /** Fit to Screen: the largest centred placement inside the canvas. */
        fun fitTransformToCanvas() =
            applyTransformQuad({ TransformQuad.fitTo(it.quad, canvasWidth, canvasHeight) }, { it.fitTo(canvasWidth, canvasHeight) })

        /** Reset: put the content back where the transform session started. */
        fun resetTransform() = applyTransformQuad({ Quad.fromBounds(it.bounds) }, { null })

        /** Pixels and placement for an ongoing transform; every edit resamples from [base]. */
        private class TransformSession(
            val layerId: Long,
            val base: PixelBuffer,
            val selection: SelectionMask?,
            val bounds: IntBounds,
            var quad: Quad,
            var revision: Long,
            /** Layers multi-selected in the Layers panel, moving with this one. */
            val companions: List<TransformCompanions.Layer> = emptyList(),
        ) {
            /** Control points while warping; null keeps the plain [quad] placement. */
            var mesh: WarpMesh? = null

            fun meshOrBox(): WarpMesh = mesh ?: WarpMesh.fromBounds(bounds)
        }

        private var transformSession: TransformSession? = null

        private val companions = TransformCompanions()

        /** Layers multi-selected in the Layers panel transform together with the active layer. */
        fun setTransformCompanions(ids: Set<Long>) {
            if (companions.ids == ids) return
            companions.ids = ids
            if (pixelTool != null || transformCommitting) return
            clearTransformSession()
            if (input.tool == ToolType.TRANSFORM) ensureTransformSession()
        }

        private var transformTarget: TransformQuad.Target = TransformQuad.Target.Body
        private var transformStartQuad: Quad? = null
        private var transformPreviewQuad: Quad? = null
        private var warpTarget: WarpMesh.Target = WarpMesh.Target.Body
        private var warpStartMesh: WarpMesh? = null
        private var transformPreviewMesh: WarpMesh? = null
        private var transformCommitting = false
        private var lastTransformPreview = 0L

        /** Reports the box to draw (canvas coordinates), or null when no transform is active. */
        var onTransformQuadChanged: ((Quad?) -> Unit)? = null

        /** Edit Shape: offered after a QuickShape stroke, with its nodes while they are being edited. */
        var onShapeEditChanged: ((ShapeEditState?) -> Unit)? = null

        /** Reports the warp mesh to draw while Warp is the transform mode, or null. */
        var onWarpMeshChanged: ((WarpMesh?) -> Unit)? = null

        private val warping: Boolean get() = input.transformMode == TransformQuad.Mode.WARP

        private fun publishTransformQuad() {
            val session = transformSession
            onTransformQuadChanged?.invoke(session?.takeUnless { warping }?.let { transformPreviewQuad ?: it.quad })
            onWarpMeshChanged?.invoke(session?.takeIf { warping }?.let { transformPreviewMesh ?: it.meshOrBox() })
        }

        private fun clearTransformSession() {
            transformSession = null
            transformPreviewQuad = null
            transformPreviewMesh = null
            publishTransformQuad()
        }

        private fun sameSelection(
            a: SelectionMask?,
            b: SelectionMask?,
        ): Boolean = if (a == null || b == null) a == b else a.coverage.contentEquals(b.coverage)

        private fun TransformSession.isCurrent(
            layerId: Long,
            selection: SelectionMask?,
        ): Boolean = this.layerId == layerId && revision == canvasRepository.contentRevision && sameSelection(this.selection, selection)

        /** Shows the box as soon as Transform is chosen, and rebuilds it after undo or other edits. */
        private fun ensureTransformSession(onReady: ((TransformSession) -> Unit)? = null) {
            val layerId = activeLayerId
            val selection = canvasRepository.selection()
            val current = transformSession?.takeIf { it.isCurrent(layerId, selection) }
            if (current != null) {
                onReady?.invoke(current)
                return
            }
            coroutineScope.launch {
                val pixels = canvasRepository.layerPixels(layerId) ?: return@launch
                val revision = canvasRepository.contentRevision
                val extra = companions.load(canvasRepository, layerId, selection)
                val bounds =
                    withContext(Dispatchers.Default) {
                        TransformCompanions.bounds(LayerTransform.floatingBounds(pixels, selection), extra)
                    }
                if (bounds == null) {
                    clearTransformSession()
                    onStatusMessage?.invoke("Nothing to transform on this layer")
                    return@launch
                }
                val session = TransformSession(layerId, pixels, selection, bounds, Quad.fromBounds(bounds), revision, extra)
                transformSession = session
                publishTransformQuad()
                onReady?.invoke(session)
            }
        }

        /** Chooses what the touch grabbed; false when there is nothing to transform. */
        private suspend fun beginTransformGesture(
            layerId: Long,
            x: Float,
            y: Float,
            selection: SelectionMask?,
        ): Boolean {
            var current = transformSession?.takeIf { it.isCurrent(layerId, selection) }
            if (current == null) {
                val base = rasterBase ?: return false
                val extra = companions.load(canvasRepository, layerId, selection)
                val bounds =
                    withContext(Dispatchers.Default) {
                        TransformCompanions.bounds(LayerTransform.floatingBounds(base, selection), extra)
                    }
                if (bounds == null) {
                    onStatusMessage?.invoke("Nothing to transform on this layer")
                    return false
                }
                val revision = canvasRepository.contentRevision
                current = TransformSession(layerId, base, selection, bounds, Quad.fromBounds(bounds), revision, extra)
                transformSession = current
            }
            companions.open(canvasRepository, current.companions)
            transformStartQuad = current.quad
            if (warping) {
                val mesh = current.meshOrBox()
                warpStartMesh = mesh
                warpTarget = mesh.hit(x, y, HANDLE_TOUCH_PX / scale)
            } else {
                transformTarget = TransformQuad.hit(current.quad, x, y, HANDLE_TOUCH_PX / scale, KNOB_DISTANCE_PX / scale)
            }
            return true
        }

        private fun previewTransform(
            buffer: PixelBuffer,
            x: Float,
            y: Float,
        ) {
            val session = transformSession ?: return
            if (warping) {
                transformPreviewMesh = (warpStartMesh ?: return).drag(warpTarget, x - moveOriginX(), y - moveOriginY())
            } else {
                val start = transformStartQuad ?: return
                val area = TransformQuad.SnapArea(session.base.width, session.base.height, SNAP_DISTANCE_PX / scale)
                transformPreviewQuad =
                    TransformQuad.drag(
                        start,
                        transformTarget,
                        input.transformMode,
                        moveOriginX() to moveOriginY(),
                        x to y,
                        input.transformAssist,
                        area,
                    )
            }
            publishTransformQuad()
            val now = System.currentTimeMillis()
            if (now - lastTransformPreview < TRANSFORM_PREVIEW_INTERVAL_MS) return
            lastTransformPreview = now
            val quad = transformPreviewQuad ?: session.quad
            val mesh = transformPreviewMesh
            TransformQuad.renderShape(session.base, buffer, session.bounds, quad, mesh, session.selection, highQuality = false)
            companions.render(session.bounds, quad, mesh, highQuality = false)
        }

        private fun applyTransformQuad(
            change: (TransformSession) -> Quad,
            changeMesh: (WarpMesh) -> WarpMesh?,
        ) {
            ensureTransformSession { session ->
                val quad = if (warping) session.quad else change(session)
                val mesh = if (warping) changeMesh(session.meshOrBox()) else null
                val highQuality = input.transformInterpolation == TransformQuad.Interpolation.BILINEAR
                transformCommitting = true
                coroutineScope.launch {
                    try {
                        val ids = listOf(session.layerId) + session.companions.map { it.layerId }
                        val placed =
                            canvasRepository.applyRasterEdits(ids, "Transform") { id, buffer ->
                                val companion = session.companions.firstOrNull { it.layerId == id }
                                val source = companion?.base ?: session.base
                                val selection = if (companion == null) session.selection else null
                                TransformQuad.renderShape(source, buffer, session.bounds, quad, mesh, selection, highQuality)
                            }
                        if (placed) {
                            session.quad = quad
                            session.mesh = mesh
                            session.revision = canvasRepository.contentRevision
                            reportHistory()
                        }
                    } finally {
                        transformCommitting = false
                        publishTransformQuad()
                    }
                }
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
                            // Ghost count, opacity and tints live in the animation settings.
                            if (onionEnabled && canvasRepository.timeline.value.settings != onionSettings) onionDirty = true
                        }.conflate()
                        .collect {
                            // A busy renderer must complete frames instead of cancelling each one
                            // when another pointer sample arrives. Only the latest queued request is kept.
                            refreshComposite()
                            if (onionDirty) refreshOnionSkins()
                            if (input.tool == ToolType.TRANSFORM && pixelTool == null && !transformCommitting) ensureTransformSession()
                        }
                }
        }

        private suspend fun refreshComposite() {
            val size = canvasRepository.getCanvasSize()
            attachToCanvas(size.width, size.height, size.dpi, canvasRepository.getBackgroundColor())
            try {
                val frame = canvasRepository.compositePreviewFrame()
                val dirty = frame?.dirty
                when {
                    frame == null -> {
                        Unit
                    }

                    dirty == null || fullCompositeNeeded -> {
                        renderer.setComposite(frame.buffer)
                        fullCompositeNeeded = false
                    }

                    else -> {
                        renderer.setCompositeRegion(frame.buffer, dirty)
                    }
                }
                requestRender()
            } catch (cancelled: CancellationException) {
                // The repository may already have handed out this frame's damage; resend everything.
                fullCompositeNeeded = true
                throw cancelled
            } catch (error: IllegalArgumentException) {
                fullCompositeNeeded = true
                reportCompositeFailure(error)
            } catch (error: IllegalStateException) {
                fullCompositeNeeded = true
                reportCompositeFailure(error)
            } catch (error: OutOfMemoryError) {
                fullCompositeNeeded = true
                Timber.e(error, "Insufficient memory for canvas preview")
                onStatusMessage?.invoke("Not enough memory to render this canvas. Save your artwork and reduce its size.")
            }
        }

        private fun reportCompositeFailure(error: Exception) {
            Timber.e(error, "Composite failed")
            onStatusMessage?.invoke("Could not update the canvas: ${error.message}")
        }

        private var onionJob: Job? = null

        /** The animation settings the current ghosts were made with. */
        private var onionSettings: AnimationSettings? = null

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
                    onionSettings = state.settings
                    val active = state.activeIndex
                    val range = state.settings.onionSkinFrames.coerceIn(0, 5)
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
                        // Colour Secondary Frames: earlier frames in one tint, later frames in another.
                        val ghost =
                            OnionSkin.tint(state.settings, offset)?.let { tint ->
                                withContext(Dispatchers.Default) {
                                    OnionSkin.tinted(if (preview === frame) frame.copy() else preview, tint)
                                }
                            } ?: preview
                        ghosts += ghost to OnionSkin.opacity(state.settings, offset)
                    }
                    renderer.setOnionSkins(ghosts, OnionSkin.primaryOpacity(state.settings))
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

                PointerGestureRouter.Action.CANCEL -> {
                    removeCallbacks(rapidHistory)
                    cancelActiveGesture()
                }

                PointerGestureRouter.Action.REBASE_NAVIGATION -> {
                    removeCallbacks(rapidHistory)
                    postDelayed(rapidHistory, input.gestures.rapidUndoDelayMs.toLong())
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

                PointerGestureRouter.Action.NAVIGATE -> {
                    navigate(samples)
                }

                PointerGestureRouter.Action.FINISH_NAVIGATION -> {
                    removeCallbacks(rapidHistory)
                    finishNavigation(route.historyPointers, event.eventTime)
                }

                PointerGestureRouter.Action.THREE_FINGER_SWIPE_DOWN -> {
                    resetNavigation()
                    if (input.gestures.swipeCopyPaste) onCopyPasteMenuRequested?.invoke()
                }

                PointerGestureRouter.Action.THREE_FINGER_SCRUB -> {
                    resetNavigation()
                    if (input.gestures.scrubToClear) onClearLayerRequested?.invoke()
                }

                PointerGestureRouter.Action.IGNORE -> {
                    Unit
                }
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
            gestureStartView = x to y

            onBrushCursorChanged?.invoke(null)
            if (shapeEdit != null && grabShapeNode(x, y)) {
                gestureTool = null
                return true
            }
            if (continueFilling) {
                // Fills on release, so a pinch or pan that starts with one finger never fills.
                fillingTap = true
                gestureTool = null
                return true
            }
            // The stylus side button samples colour, the usual Android pen shortcut.
            if (isStylus && (event.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0) {
                val (canvasX, canvasY) = viewToCanvas(x, y)
                startSampling()
                pickColor(canvasX, canvasY)
                gestureTool = null
                return true
            }
            val tool = if (event.getToolType(index) == MotionEvent.TOOL_TYPE_ERASER) ToolType.ERASER else input.tool
            armHoldEyedropper(isStylus, x, y)
            // Palm rejection: a stylus always paints, fingers only when finger painting is on.
            if (!isStylus && !input.fingerPainting) {
                gestureTool = null
                return true
            }

            gestureTool = tool
            val (canvasX, canvasY) = snapped(event, x, y, index)
            val pressure = pressureOf(event, index)
            if (tool == ToolType.EYEDROPPER) {
                // The eyedropper samples while it is dragged, showing the loupe; lifting keeps the colour.
                startSampling()
                pickColor(canvasX, canvasY)
                gestureTool = null
                return true
            }

            when (tool) {
                ToolType.BRUSH, ToolType.ERASER -> {
                    startStroke(canvasX, canvasY, pressure, tool)
                }

                ToolType.SMUDGE -> {
                    startPixelGesture(tool, canvasX, canvasY, pressure, "Smudge")
                }

                ToolType.CLONE_STAMP -> {
                    cloneDragStarted = false
                    if (cloneSource == null) {
                        onStatusMessage?.invoke("Tap to set the clone source")
                    } else {
                        startPixelGesture(tool, canvasX, canvasY, pressure, "Clone stamp")
                    }
                }

                ToolType.HEALING -> {
                    startPixelGesture(tool, canvasX, canvasY, pressure, "Healing")
                }

                ToolType.LIQUIFY -> {
                    startPixelGesture(tool, canvasX, canvasY, pressure, "Liquify")
                }

                ToolType.MOVE -> {
                    startPixelGesture(tool, canvasX, canvasY, pressure, "Move")
                }

                ToolType.TRANSFORM -> {
                    startPixelGesture(tool, canvasX, canvasY, pressure, "Transform")
                }

                ToolType.GRADIENT -> {
                    gradientOrigin = canvasX to canvasY
                    previewPoints = mutableListOf(canvasX to canvasY)
                }

                ToolType.SHAPE -> {
                    shapeOrigin = canvasX to canvasY
                    previewPoints = mutableListOf(canvasX to canvasY)
                }

                ToolType.SELECT_RECTANGLE, ToolType.SELECT_ELLIPSE,
                ToolType.SELECT_LASSO, ToolType.SELECT_FREEHAND, ToolType.LASSO_FILL,
                -> {
                    previewPoints = mutableListOf(canvasX to canvasY)
                }

                else -> {
                    Unit
                } // Paint bucket, magic wand, text, eyedropper and zoom act on release.
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
            if (draggedNode >= 0) {
                // Each redraw replaces the whole shape, so only the latest sample matters.
                if (history < 0) dragShapeNode(x, y)
                return true
            }
            if (holdSampling) {
                val (sampleX, sampleY) = viewToCanvas(x, y)
                pickColor(sampleX, sampleY)
                return true
            }
            if (gestureMoved > TAP_SLOP) removeCallbacks(holdEyedropper)
            val tool = gestureTool ?: return true
            val (canvasX, canvasY) = snapped(event, x, y, index)
            val pressure = pressureOf(event, index, history)

            when (tool) {
                ToolType.BRUSH, ToolType.ERASER -> {
                    if (drawing && quickShapeApplied) {
                        adjustQuickShape(canvasX, canvasY)
                    } else if (drawing) {
                        trackQuickShapeHold(x, y, canvasX, canvasY, pressure)
                        val time = if (history >= 0) event.getHistoricalEventTime(history) else event.eventTime
                        // Prefs > Pressure and Smoothing first, then the brush's own steadying.
                        val (filteredX, filteredY) = motionFilter?.add(canvasX, canvasY, time) ?: (canvasX to canvasY)
                        val strokePressure = pressureSmoother?.add(pressure) ?: pressure
                        val (smoothX, smoothY) = stabilizer?.add(filteredX, filteredY) ?: (filteredX to filteredY)
                        canvasRepository.continueStroke(
                            strokeId = currentStrokeId,
                            x = smoothX,
                            y = smoothY,
                            pressure = strokePressure,
                            tiltX = axisOf(event, index, MotionEvent.AXIS_TILT, history),
                            tiltY = axisOf(event, index, MotionEvent.AXIS_ORIENTATION, history),
                        )
                        predict(tool, smoothX, smoothY, strokePressure, time)
                        updateLiveStroke()
                    }
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

                ToolType.SELECT_FREEHAND, ToolType.SELECT_LASSO, ToolType.LASSO_FILL -> {
                    previewPoints.add(canvasX to canvasY)
                    emitDragPreview(tool, closed = tool != ToolType.SELECT_FREEHAND)
                }

                else -> {
                    Unit
                }
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
            removeCallbacks(holdEyedropper)
            if (holdSampling) {
                endSampling()
                gestureTool = null
                return
            }
            if (fillingTap) {
                fillingTap = false
                if (!cancelled && gestureMoved <= TAP_SLOP && event.eventTime - gestureStartTime < TAP_TIMEOUT_MS) continueFill(x, y)
                return
            }
            if (draggedNode >= 0) {
                dropShapeNode()
                return
            }
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
                    removeCallbacks(quickShapeCheck)
                    if (drawing) finishStroke(canvasX, canvasY, cancelled)
                    drawing = false
                    reportHistory()
                }

                ToolType.SMUDGE, ToolType.HEALING, ToolType.LIQUIFY,
                ToolType.MOVE, ToolType.TRANSFORM,
                -> {
                    endPixelGesture(cancelled)
                }

                ToolType.CLONE_STAMP -> {
                    if (!cancelled && cloneSource == null && wasTap) {
                        cloneSource = canvasX to canvasY
                        onCloneSourceChanged?.invoke(canvasX to canvasY)
                        onStatusMessage?.invoke("Clone source set — drag to stamp")
                    } else {
                        endPixelGesture(cancelled)
                    }
                }

                ToolType.PAINT_BUCKET -> {
                    if (!cancelled && wasTap) bucketFill(canvasX, canvasY)
                }

                ToolType.LASSO_FILL -> {
                    onDragPreview?.invoke(null)
                    if (!cancelled && selectionPoints.size >= 3) lassoFill(selectionPoints)
                }

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

                ToolType.TEXT -> {
                    if (!cancelled && wasTap) onTextPlacementRequested?.invoke(canvasX, canvasY)
                }

                ToolType.EYEDROPPER -> {
                    if (!cancelled) pickColor(canvasX, canvasY)
                }

                ToolType.SELECT_MAGIC_WAND -> {
                    if (!cancelled) automaticSelect(x, wasTap)
                }

                ToolType.SELECT_RECTANGLE, ToolType.SELECT_ELLIPSE,
                ToolType.SELECT_FREEHAND, ToolType.SELECT_LASSO,
                -> {
                    if (!cancelled) commitSelectionDrag(tool, selectionPoints)
                    onDragPreview?.invoke(null)
                }

                ToolType.ZOOM -> {
                    Unit
                }

                else -> {
                    Unit
                }
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
            viewAnimator?.cancel()
            if (pinchStartScale == 0f) {
                pinchStartScale = scale
                pinchStartTime = SystemClock.uptimeMillis()
            }
            val ordered = pointers.sortedBy { it.id }
            navPrevDistance = spacing(ordered)
            navPrevAngle = angle(ordered)
            navPrevMidX = ordered.map { it.x }.average().toFloat()
            navPrevMidY = ordered.map { it.y }.average().toFloat()
            navAnchor = viewToCanvas(navPrevMidX, navPrevMidY)
        }

        /** Taps of two, three or four fingers undo, redo or toggle full screen; a quick pinch fits the canvas. */
        private fun finishNavigation(
            tapFingers: Int,
            timeMillis: Long,
        ) {
            resetNavigation()
            val quickPinch = QuickPinch.isQuickPinch(pinchStartScale, scale, timeMillis - pinchStartTime)
            pinchStartScale = 0f
            when {
                tapFingers == 2 -> onUndoRequested?.invoke()
                tapFingers == 3 -> onRedoRequested?.invoke()
                tapFingers == 4 -> if (input.gestures.fourFingerFullScreen) onFullscreenRequested?.invoke()
                quickPinch -> animateFitToView()
            }
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
            val chosen =
                if (tool == ToolType.ERASER) {
                    input.brushParams.copy(size = input.eraserSize)
                } else {
                    input.brushParams
                }
            // Dynamic brush scaling keeps the brush the same size on screen at any zoom, as in Procreate.
            val params =
                if (input.dynamicBrushScaling && scale > 0f) {
                    chosen.copy(size = (chosen.size / scale).coerceIn(MIN_SCALED_BRUSH, MAX_SCALED_BRUSH))
                } else {
                    chosen
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
            clearPrediction()
            if (!drawing) onStatusMessage?.invoke("Choose an unlocked, visible layer with an editable destination")
            // The string keeps the same length on screen whatever the zoom.
            val string = input.pulledString.coerceIn(0f, 1f) * MAX_STRING_DP * resources.displayMetrics.density / scale
            stabilizer =
                StrokeStabilizer(max(params.smoothing, input.stabilization), string)
                    .takeIf { it.isActive }
                    ?.also { it.start(x, y) }
            motionFilter =
                MotionFilter(input.motionFiltering, input.motionExpression)
                    .takeIf { it.isActive }
                    ?.also { it.start(x, y, SystemClock.uptimeMillis()) }
            pressureSmoother =
                PressureSmoother(input.pressureSmoothing).takeIf { input.pressureSmoothing > 0f }?.also { it.start(pressure) }
            strokeRawPoints.clear()
            strokeRawPoints.add(x to y)
            strokePressureSum = pressure
            strokeLastPressure = pressure
            quickShapeApplied = false
            removeCallbacks(quickShapeCheck)
            updateLiveStroke()
        }

        private var holdSampling = false
        private var holdCanvasX = 0f
        private var holdCanvasY = 0f
        private val holdEyedropper = Runnable { startHoldEyedropper() }

        /** Fingers (not the stylus) that stay still for a moment switch to colour sampling. */
        private fun armHoldEyedropper(
            isStylus: Boolean,
            x: Float,
            y: Float,
        ) {
            removeCallbacks(holdEyedropper)
            holdSampling = false
            if (isStylus || !input.touchHoldEyedropper) return
            val (canvasX, canvasY) = viewToCanvas(x, y)
            holdCanvasX = canvasX
            holdCanvasY = canvasY
            postDelayed(holdEyedropper, HOLD_EYEDROPPER_MS)
        }

        private fun startHoldEyedropper() {
            if (gestureMoved > TAP_SLOP) return
            if (drawing) canvasRepository.cancelStroke(currentStrokeId)
            drawing = false
            removeCallbacks(quickShapeCheck)
            cancelPixelInteraction()
            previewPoints.clear()
            onDragPreview?.invoke(null)
            startSampling()
            if (input.haptics) performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            pickColor(holdCanvasX, holdCanvasY)
        }

        /**
         * Draws where the pen is heading as a light overlay ahead of the brush stroke, so the line keeps
         * up with the pen. Stabilized strokes lag on purpose and get no prediction; nothing is painted.
         */
        private fun predict(
            tool: ToolType,
            x: Float,
            y: Float,
            pressure: Float,
            timeMs: Long,
        ) {
            if (tool != ToolType.BRUSH || stabilizer != null) return
            predictor.add(x, y, timeMs)
            val ahead = predictor.predict()
            val points = FloatArray(ahead.size * 2)
            ahead.forEachIndexed { index, (px, py) ->
                points[index * 2] = px
                points[index * 2 + 1] = py
            }
            val radius = input.brushParams.size * 0.5f * pressure.coerceIn(MIN_PREDICTED_PRESSURE, 1f)
            val alpha = (input.brushParams.opacity.coerceIn(0f, 1f) * 255f).toInt()
            renderer.setPrediction(points, radius, (input.brushColor and 0x00FFFFFF) or (alpha shl 24))
            requestRender()
        }

        private fun clearPrediction() {
            predictor.reset()
            renderer.setPrediction(null, 0f, 0)
        }

        /** Lets the stabilised line catch up to the lift point, then commits or cancels the stroke. */
        private fun finishStroke(
            x: Float,
            y: Float,
            cancelled: Boolean,
        ) {
            clearPrediction()
            if (cancelled) {
                canvasRepository.cancelStroke(currentStrokeId)
                return
            }
            if (!quickShapeApplied) {
                stabilizer?.finish(x, y)?.forEach { (px, py) ->
                    canvasRepository.continueStroke(currentStrokeId, px, py, strokeLastPressure)
                }
            }
            val markBefore = canvasRepository.historyMark
            canvasRepository.endStroke(currentStrokeId)
            val shape = quickShapeShown?.takeIf { quickShapeApplied && it.nodes.isNotEmpty() }
            if (shape != null && canvasRepository.historyMark != markBefore) {
                shapeEdit = ShapeEdit(shape, quickShapePressure, quickShapeTool, activeLayerId, canvasRepository.historyMark)
                editingShape = false
                reportShapeEdit()
            }
        }

        /** Edit Shape: shows the last QuickShape stroke's nodes so they can be dragged. */
        fun beginShapeEdit() {
            if (shapeEdit?.isLatest() != true) return dismissShapeEdit()
            editingShape = true
            reportShapeEdit()
        }

        /** Closes Edit Shape, or withdraws the offer, keeping the shape as drawn. */
        fun dismissShapeEdit() {
            if (draggedNode >= 0) dropShapeNode()
            if (shapeEdit == null) return
            shapeEdit = null
            editingShape = false
            onShapeEditChanged?.invoke(null)
        }

        private fun ShapeEdit.isLatest(): Boolean = mark == canvasRepository.historyMark && layerId == activeLayerId

        private fun reportShapeEdit() {
            val edit = shapeEdit ?: return
            onShapeEditChanged?.invoke(ShapeEditState(edit.shape.kind.label, edit.shape.nodes, editingShape))
        }

        /**
         * While Edit Shape is open, a touch on a node starts dragging it and a touch elsewhere
         * closes it without drawing; otherwise any touch withdraws the offer. Returns whether the
         * touch was used here.
         */
        private fun grabShapeNode(
            viewX: Float,
            viewY: Float,
        ): Boolean {
            val edit = shapeEdit ?: return false
            val editing = editingShape
            val reach = NODE_REACH_DP * resources.displayMetrics.density / scale
            val node = if (editing && edit.isLatest()) QuickShape.nodeAt(edit.shape, viewToCanvas(viewX, viewY), reach) else null
            if (node == null) {
                dismissShapeEdit()
                return editing
            }
            draggedNode = node
            return true
        }

        /** Replaces the shape's stroke with one through the moved node; the first move takes the old stroke off. */
        private fun dragShapeNode(
            viewX: Float,
            viewY: Float,
        ) {
            val edit = shapeEdit ?: return
            if (shapeStrokeOpen) {
                canvasRepository.cancelStroke(currentStrokeId)
            } else if (!edit.isLatest() || !canvasRepository.undo()) {
                draggedNode = -1
                return dismissShapeEdit()
            }
            val moved = QuickShape.moveNode(edit.shape, draggedNode, viewToCanvas(viewX, viewY))
            shapeEdit = ShapeEdit(moved, edit.pressure, edit.tool, edit.layerId, edit.mark)
            shapeStrokeOpen = drawShape(moved, edit.pressure, edit.tool)
            if (!shapeStrokeOpen) {
                // The layer can no longer take the stroke: put the original back.
                canvasRepository.redo()
                draggedNode = -1
                dismissShapeEdit()
            }
            reportShapeEdit()
            reportHistory()
        }

        private fun drawShape(
            shape: QuickShape.Result,
            pressure: Float,
            tool: ToolType,
        ): Boolean {
            val (x, y) = shape.points.firstOrNull() ?: return false
            startStroke(x, y, pressure, tool)
            if (!drawing) return false
            shape.points.drop(1).forEach { (px, py) -> canvasRepository.continueStroke(currentStrokeId, px, py, pressure) }
            updateLiveStroke()
            return true
        }

        /** Commits the reshaped stroke, which then becomes the one Edit Shape follows. */
        private fun dropShapeNode() {
            draggedNode = -1
            if (!shapeStrokeOpen) return
            shapeStrokeOpen = false
            drawing = false
            canvasRepository.endStroke(currentStrokeId)
            shapeEdit = shapeEdit?.let { ShapeEdit(it.shape, it.pressure, it.tool, it.layerId, canvasRepository.historyMark) }
            onionDirty = true
            reportHistory()
            reportShapeEdit()
        }

        /** Restarts the QuickShape hold timer whenever the pen moves beyond a small radius. */
        private fun trackQuickShapeHold(
            viewX: Float,
            viewY: Float,
            canvasX: Float,
            canvasY: Float,
            pressure: Float,
        ) {
            strokeRawPoints.add(canvasX to canvasY)
            strokePressureSum += pressure
            strokeLastPressure = pressure
            if (!input.quickShape) return
            if (strokeRawPoints.size == 2 || kotlin.math.hypot(viewX - holdAnchorX, viewY - holdAnchorY) > QUICKSHAPE_HOLD_SLOP) {
                holdAnchorX = viewX
                holdAnchorY = viewY
                removeCallbacks(quickShapeCheck)
                postDelayed(quickShapeCheck, QUICKSHAPE_HOLD_MS)
            }
        }

        /** Replaces the live stroke with the recognised clean shape. */
        private fun applyQuickShape() {
            if (!drawing || quickShapeApplied || !input.quickShape) return
            val shape = QuickShape.recognize(strokeRawPoints.toList()) ?: return
            val tool = gestureTool ?: return
            val lastRaw = strokeRawPoints.last()
            val pressure = (strokePressureSum / strokeRawPoints.size).coerceIn(0.05f, 1f)
            canvasRepository.cancelStroke(currentStrokeId)
            val (startX, startY) = shape.points.first()
            startStroke(startX, startY, pressure, tool)
            if (!drawing) return
            quickShapeApplied = true
            quickShapeResult = shape
            quickShapeShown = shape
            quickShapeTool = tool
            quickShapeAnchor = lastRaw
            quickShapePressure = pressure
            shape.points.drop(1).forEach { (px, py) -> canvasRepository.continueStroke(currentStrokeId, px, py, pressure) }
            updateLiveStroke()
            if (input.haptics) performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            onStatusMessage?.invoke("QuickShape: ${shape.kind.label} — keep holding and drag to adjust")
        }

        /** After QuickShape snaps, keeping the pen down reshapes it, as in Procreate. */
        private fun adjustQuickShape(
            x: Float,
            y: Float,
        ) {
            val shape = quickShapeResult ?: return
            val tool = gestureTool ?: return
            val adjusted = QuickShape.adjust(shape, quickShapeAnchor, x to y)
            canvasRepository.cancelStroke(currentStrokeId)
            val (startX, startY) = adjusted.points.first()
            startStroke(startX, startY, quickShapePressure, tool)
            if (!drawing) return
            quickShapeApplied = true
            quickShapeShown = adjusted
            adjusted.points.drop(1).forEach { (px, py) -> canvasRepository.continueStroke(currentStrokeId, px, py, quickShapePressure) }
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
            transformPreviewQuad = null

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
                    if (tool == ToolType.TRANSFORM && !beginTransformGesture(session.layerId, x, y, gestureSelection)) {
                        canvasRepository.cancelRasterEdit(session)
                        rasterSession = null
                        rasterBase = null
                        pixelTool = null
                        return@launch
                    }
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
                    // These tools report exactly what each sample changed, so previews redraw only that.
                    if (tool in DAMAGE_TRACKED_TOOLS) canvasRepository.trackPreviewDamage(session)
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
                ToolType.SMUDGE -> {
                    smudgeSession =
                        PixelBrushes.beginSmudge(
                            x,
                            y,
                            gestureInput.smudge.copy(
                                size = gestureInput.brushParams.size,
                                mask = selection,
                                alphaLock = alphaLocked,
                                texture = BrushPatch.texture(gestureInput.brushParams),
                            ),
                        )
                }

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

                ToolType.LIQUIFY -> {
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
                }

                else -> {
                    Unit
                }
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
            val changed =
                when (pixelTool) {
                    ToolType.SMUDGE -> smudgeSession?.dragTo(x, y, buffer)
                    ToolType.CLONE_STAMP -> cloneSession?.dragTo(x, y, buffer, rasterSource ?: buffer)
                    ToolType.HEALING -> healingSession?.dragTo(x, y, buffer, rasterSource ?: buffer)
                    else -> null
                }
            val session = rasterSession
            if (changed != null && session != null) canvasRepository.markPreviewDamage(session, changed)
            when (pixelTool) {
                ToolType.LIQUIFY -> {
                    liquifySession?.dragTo(x, y, pressure)
                    previewLiquify(buffer)
                }

                ToolType.TRANSFORM -> {
                    previewTransform(buffer, x, y)
                }

                ToolType.MOVE -> {
                    val base = rasterBase ?: return
                    buffer.clear()
                    drawShifted(
                        buffer,
                        base,
                        (x - moveOriginX()).roundToInt(),
                        (y - moveOriginY()).roundToInt(),
                    )
                }

                else -> {
                    Unit
                }
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
            val finalQuad = transformPreviewQuad.takeIf { pixelTool == ToolType.TRANSFORM }
            val finalMesh = transformPreviewMesh.takeIf { pixelTool == ToolType.TRANSFORM }
            val transform = transformSession.takeIf { finalQuad != null || finalMesh != null }
            val interpolation = input.transformInterpolation
            transformPreviewQuad = null
            transformPreviewMesh = null
            if (transform != null) transformCommitting = true
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
                        if (transform != null) {
                            // Previews are throttled and nearest-neighbour; commit the exact final placement.
                            withContext(Dispatchers.Default) {
                                val highQuality = interpolation == TransformQuad.Interpolation.BILINEAR
                                val quad = finalQuad ?: transform.quad
                                TransformQuad.renderShape(
                                    transform.base,
                                    session.buffer,
                                    transform.bounds,
                                    quad,
                                    finalMesh,
                                    transform.selection,
                                    highQuality,
                                )
                                companions.render(transform.bounds, quad, finalMesh, highQuality)
                            }
                        }
                        if (finalLiquify != null && original != null) {
                            withContext(Dispatchers.Default) {
                                // Momentum carries the distortion on after the pen lifts.
                                finalLiquify.release()
                                finalLiquify
                                    .render(original, reference?.original)
                                    .pixels
                                    .copyInto(session.buffer.pixels)
                            }
                        }
                        val group = listOf(session) + companions.sessions
                        val changed =
                            original == null ||
                                group.size > 1 ||
                                withContext(Dispatchers.Default) { !original.pixels.contentEquals(session.buffer.pixels) }
                        if (changed) {
                            if (canvasRepository.commitRasterEdits(group, description)) {
                                if (transform != null) {
                                    transform.quad = finalQuad ?: transform.quad
                                    transform.mesh = finalMesh
                                    transform.revision = canvasRepository.contentRevision
                                }
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
                    withContext(NonCancellable) {
                        canvasRepository.cancelRasterEdit(session)
                        companions.cancel(canvasRepository)
                    }
                    if (transform != null) {
                        transformCommitting = false
                        publishTransformQuad()
                    }
                }
            }
        }

        private fun requestPreviewRefresh() {
            val now = System.currentTimeMillis()
            if (now - lastPreviewRequest < PREVIEW_INTERVAL_MS) return
            lastPreviewRequest = now
            canvasRepository.requestPreviewRefresh()
        }

        /** Fills the shape just traced with the brush colour, as one undo step. */
        private fun lassoFill(shape: List<Pair<Float, Float>>) {
            val color = input.brushColor
            applyFillEdit("Lasso fill") { target, selection, alphaLocked ->
                FillTool.lassoFill(target, shape, color, FillTool.Settings(mask = selection, alphaLock = alphaLocked)).changed
            }
        }

        private fun bucketFill(
            x: Float,
            y: Float,
            tolerance: Int = input.fillTolerance,
            onCommitted: ((Int) -> Unit)? = null,
        ) {
            val cx = x.roundToInt()
            val cy = y.roundToInt()
            val captured = input
            // With a Reference layer, the fill stops at its lines instead of the active layer's pixels.
            val referenceId =
                canvasRepository
                    .getAllLayers()
                    .firstOrNull { it.isFillReference && it.isVisible && it.id != activeLayerId }
                    ?.id
            coroutineScope.launch(start = CoroutineStart.UNDISPATCHED) {
                val reference = referenceId?.let { canvasRepository.layerPixels(it) }
                applyFillEdit("Paint bucket", onCommitted) { target, selection, alphaLocked ->
                    FillTool
                        .floodFill(
                            target = target,
                            startX = cx,
                            startY = cy,
                            color = captured.brushColor,
                            settings =
                                FillTool.Settings(
                                    tolerance = tolerance,
                                    contiguous = captured.fillContiguous,
                                    gapClose = captured.fillGapClose,
                                    mask = selection,
                                    alphaLock = alphaLocked,
                                ),
                            source = reference ?: target,
                        ).changed
                }
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
            onCommitted: ((Int) -> Unit)? = null,
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
                        onCommitted?.invoke(canvasRepository.undoDepth)
                    } else {
                        onStatusMessage?.invoke("The document changed before $description could be applied")
                    }
                } finally {
                    withContext(NonCancellable) { canvasRepository.cancelRasterEdit(session) }
                }
            }
        }

        /** The flattened artwork for the sampling gesture under way, read once rather than per sample. */
        private var samplingSource: Deferred<PixelBuffer?>? = null
        private var samplingPrevious = 0

        private fun startSampling() {
            holdSampling = true
            samplingPrevious = input.brushColor
            samplingSource = coroutineScope.async(Dispatchers.Default) { canvasRepository.compositeBuffer() }
        }

        private fun endSampling() {
            holdSampling = false
            samplingSource = null
            onEyedropperChanged?.invoke(null)
        }

        private fun pickColor(
            x: Float,
            y: Float,
        ) {
            val source = samplingSource
            val (viewX, viewY) = canvasToView(x, y)
            coroutineScope.launch {
                val color =
                    withContext(Dispatchers.Default) {
                        val buffer = source?.await() ?: canvasRepository.compositeBuffer()
                        val px = x.roundToInt()
                        val py = y.roundToInt()
                        if (buffer == null || !buffer.contains(px, py)) {
                            null
                        } else {
                            buffer.getSafe(px, py).takeIf { (it ushr 24) != 0 }
                        }
                    }
                if (color != null) {
                    val opaque = Channels.withAlpha(color, 255)
                    if (source != null && source === samplingSource) {
                        onEyedropperChanged?.invoke(EyedropperLoupe(viewX, viewY, opaque, samplingPrevious))
                    }
                    onColorPicked?.invoke(opaque)
                } else if (source == null) {
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
                        ToolType.SELECT_RECTANGLE -> {
                            SelectionMask.rectangle(
                                session.width,
                                session.height,
                                points.first().first,
                                points.first().second,
                                points.last().first,
                                points.last().second,
                                checkActive = { ensureActive() },
                            )
                        }

                        ToolType.SELECT_ELLIPSE -> {
                            SelectionMask.ellipse(
                                session.width,
                                session.height,
                                points.first().first,
                                points.first().second,
                                points.last().first,
                                points.last().second,
                                checkActive = { ensureActive() },
                            )
                        }

                        ToolType.SELECT_LASSO -> {
                            SelectionMask.polygon(session.width, session.height, points, checkActive = { ensureActive() })
                        }

                        else -> {
                            SelectionMask.fromStroke(session.width, session.height, points, radius = 12f, checkActive = { ensureActive() })
                        }
                    }
                }
            }
        }

        /**
         * Procreate's Automatic selection: tap to select similar colour, or touch and slide sideways
         * to raise or lower the threshold before the selection is made.
         */
        private fun automaticSelect(
            endViewX: Float,
            wasTap: Boolean,
        ) {
            val (startX, startY) = gestureStartView
            val (canvasX, canvasY) = viewToCanvas(startX, startY)
            var tolerance = input.fillTolerance
            if (!wasTap && width > 0) {
                tolerance = (tolerance + ((endViewX - startX) / width * 255f).roundToInt()).coerceIn(0, 255)
                onFillToleranceChanged?.invoke(tolerance)
                onStatusMessage?.invoke("Selection threshold ${tolerance * 100 / 255}%")
            }
            magicWandSelect(canvasX, canvasY, tolerance)
        }

        private fun magicWandSelect(
            x: Float,
            y: Float,
            tolerance: Int,
        ) {
            if (!x.isFinite() || !y.isFinite()) return
            val cx = floor(x).toInt()
            val cy = floor(y).toInt()
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
                        ShapeKind.RECTANGLE -> {
                            path.addRect(
                                min(startX, endX),
                                min(startY, endY),
                                max(startX, endX),
                                max(startY, endY),
                                Path.Direction.CW,
                            )
                        }

                        ShapeKind.ELLIPSE -> {
                            path.addOval(
                                android.graphics.RectF(
                                    min(startX, endX),
                                    min(startY, endY),
                                    max(startX, endX),
                                    max(startY, endY),
                                ),
                                Path.Direction.CW,
                            )
                        }

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
            // Guides pull strokes only on layers with Drawing Assist, as in Procreate.
            if (!input.snapToGuides || !canvasRepository.isDrawingAssisted(activeLayerId)) return canvasX to canvasY

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
            return input.pressureResponse.map(PointerPressure.curve(PointerPressure.normalize(stylus, pressure, size), input.pressureCurve))
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
            private const val MIN_PREDICTED_PRESSURE = 0.3f
            private val DAMAGE_TRACKED_TOOLS = setOf(ToolType.SMUDGE, ToolType.CLONE_STAMP, ToolType.HEALING)
            private val CURSOR_TOOLS =
                setOf(ToolType.BRUSH, ToolType.ERASER, ToolType.SMUDGE, ToolType.CLONE_STAMP, ToolType.HEALING, ToolType.LIQUIFY)
            private const val FIT_ANIMATION_MS = 260L
            private const val MAX_SCALE = 32f
            private const val TAP_SLOP = 24f
            private const val QUICKSHAPE_HOLD_SLOP = 10f
            private const val QUICKSHAPE_HOLD_MS = 650L
            private const val NODE_REACH_DP = 28f
            private const val HOLD_EYEDROPPER_MS = 500L
            private const val HANDLE_TOUCH_PX = 36f
            private const val MIN_SCALED_BRUSH = 0.5f
            private const val MAX_SCALED_BRUSH = 1_000f
            private const val RAPID_HISTORY_REPEAT_MS = 220L
            private const val MAX_STRING_DP = 60f
            private const val KNOB_DISTANCE_PX = 48f
            private const val SNAP_DISTANCE_PX = 12f
            private const val TRANSFORM_PREVIEW_INTERVAL_MS = 33L
            private const val TAP_TIMEOUT_MS = 320L
            private const val SNAP_TOLERANCE = 12f
            private const val PREVIEW_INTERVAL_MS = 66L
            private const val LIQUIFY_PREVIEW_INTERVAL_MS = 140L
            private const val LIQUIFY_PREVIEW_MAX_PIXELS = 4_000_000
            private const val MAX_PENDING_SAMPLES = 512
        }
    }
