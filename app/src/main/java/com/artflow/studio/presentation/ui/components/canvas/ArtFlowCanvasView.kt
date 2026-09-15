package com.artflow.studio.presentation.ui.components.canvas

import android.content.Context
import android.opengl.GLSurfaceView
import android.util.AttributeSet
import android.view.MotionEvent
import androidx.core.math.MathUtils
import com.artflow.studio.data.renderer.opengl.OpenGLCanvasRenderer
import com.artflow.studio.domain.model.brush.BrushParams
import com.artflow.studio.domain.repository.canvas.CanvasRepository
import com.artflow.studio.domain.usecase.canvas.BeginStroke
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Custom GLSurfaceView for hardware-accelerated drawing canvas
 * Handles touch input and delegates rendering to OpenGLCanvasRenderer
 */
@AndroidEntryPoint
class ArtFlowCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : GLSurfaceView(context, attrs) {

    @Inject
    lateinit var renderer: OpenGLCanvasRenderer

    @Inject
    lateinit var canvasRepository: CanvasRepository

    @Inject
    lateinit var beginStroke: BeginStroke

    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // Current brush settings
    private var currentBrushParams = BrushParams()

    // Current drawing state
    private var isDrawing = false
    private var currentStrokeId = 0L

    // Active layer
    private var activeLayerId = 1L

    // Gesture handling for zoom/pan
    private var scale = 1f
    private var offsetX = 0f
    private var offsetY = 0f
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var activePointerId = INVALID_POINTER_ID
    private var isPanning = false
    
    // Pinch-to-zoom
    private var oldDist = 1f
    private var newDist = 1f
    private val minScale = 0.1f
    private val maxScale = 5f

    companion object {
        private const val INVALID_POINTER_ID = -1
        private const val TOUCH_TIMEOUT = 200 // ms before considering as pan instead of draw
    }

    init {
        // Set up OpenGL ES 2.0 context
        setEGLContextClientVersion(2)

        // Set the renderer
        setRenderer(renderer)

        // Render only when there's a change (better performance)
        renderMode = RENDERMODE_WHEN_DIRTY

        // Enable touch events
        isFocusableInTouchMode = true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val action = event.actionMasked
        val pointerIndex = event.actionIndex
        
        // Handle pinch-to-zoom for two-finger gestures
        if (event.pointerCount > 1) {
            when (action) {
                MotionEvent.ACTION_POINTER_DOWN -> {
                    oldDist = spacing(event)
                    isPanning = true
                    isDrawing = false
                }
                MotionEvent.ACTION_MOVE -> {
                    newDist = spacing(event)
                    if (oldDist > 10f) { // Avoid jitter
                        val deltaScale = newDist / oldDist
                        val newScale = MathUtils.clamp(scale * deltaScale, minScale, maxScale)
                        
                        // Zoom towards the center point between fingers
                        val midX = (event.getX(0) + event.getX(1)) / 2
                        val midY = (event.getY(0) + event.getY(1)) / 2
                        
                        // Adjust offset to zoom towards finger position
                        offsetX = midX - (midX - offsetX) * (newScale / scale)
                        offsetY = midY - (midY - offsetY) * (newScale / scale)
                        
                        scale = newScale
                        renderer.setTransformation(scale, offsetX, offsetY, 0f)
                        invalidate()
                    }
                    oldDist = newDist
                }
            }
            return true
        }

        // Single finger handling for drawing and panning
        val x = event.getX(pointerIndex)
        val y = event.getY(pointerIndex)

        when (action) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = x
                lastTouchY = y
                activePointerId = event.getPointerId(0)
                
                // Start in drawing mode, switch to pan if movement exceeds threshold
                isDrawing = true
                isPanning = false
                
                // Transform coordinates for canvas space
                val canvasX = (x - offsetX) / scale
                val canvasY = (y - offsetY) / scale
                
                currentStrokeId = beginStroke(canvasX, canvasY, getPressureFromEvent(event), currentBrushParams, activeLayerId)
                invalidate()
                true
            }

            MotionEvent.ACTION_MOVE -> {
                val pointerIndex = event.findPointerIndex(activePointerId)
                if (pointerIndex == -1) return@onTouchEvent
                
                val currentX = event.getX(pointerIndex)
                val currentY = event.getY(pointerIndex)
                
                val dx = currentX - lastTouchX
                val dy = currentY - lastTouchY
                
                // Check if movement is significant enough to consider panning
                if (!isPanning && (kotlin.math.abs(dx) > 10f || kotlin.math.abs(dy) > 10f)) {
                    // If we were drawing, end the stroke and switch to panning
                    if (isDrawing) {
                        isDrawing = false
                        coroutineScope.launch {
                            canvasRepository.endStroke(currentStrokeId)
                        }
                        isPanning = true
                    }
                }
                
                if (isPanning) {
                    // Pan the canvas
                    offsetX += dx
                    offsetY += dy
                    renderer.setTransformation(scale, offsetX, offsetY, 0f)
                    invalidate()
                } else if (isDrawing) {
                    // Continue drawing stroke
                    val canvasX = (currentX - offsetX) / scale
                    val canvasY = (currentY - offsetY) / scale
                    val pressure = getPressureFromEvent(event)
                    
                    coroutineScope.launch {
                        canvasRepository.continueStroke(currentStrokeId, canvasX, canvasY, pressure)
                    }
                    invalidate()
                }
                
                lastTouchX = currentX
                lastTouchY = currentY
                true
            }

            MotionEvent.ACTION_UP -> {
                if (isDrawing) {
                    coroutineScope.launch {
                        canvasRepository.endStroke(currentStrokeId)
                    }
                    isDrawing = false
                }
                isPanning = false
                activePointerId = INVALID_POINTER_ID
                true
            }

            MotionEvent.ACTION_CANCEL -> {
                if (isDrawing) {
                    isDrawing = false
                    coroutineScope.launch {
                        canvasRepository.endStroke(currentStrokeId)
                    }
                }
                isPanning = false
                activePointerId = INVALID_POINTER_ID
                true
            }

            MotionEvent.ACTION_POINTER_UP -> {
                // Reset for single finger operation
                val pointerIndex = event.actionIndex
                val ptrId = event.getPointerId(pointerIndex)
                if (ptrId == activePointerId) {
                    val newPointerIndex = if (pointerIndex == 0) 1 else 0
                    if (newPointerIndex < event.pointerCount) {
                        activePointerId = event.getPointerId(newPointerIndex)
                        lastTouchX = event.getX(newPointerIndex)
                        lastTouchY = event.getY(newPointerIndex)
                    }
                }
                true
            }

            else -> false
        }
    }

    /**
     * Calculate the distance between the first two fingers
     */
    private fun spacing(event: MotionEvent): Float {
        if (event.pointerCount < 2) return 0f
        val x = event.getX(0) - event.getX(1)
        val y = event.getY(0) - event.getY(1)
        return kotlin.math.sqrt(x * x + y * y)
    }

    /**
     * Extract pressure from MotionEvent
     * Handles both stylus and finger input
     */
    private fun getPressureFromEvent(event: MotionEvent): Float {
        return when {
            event.toolType == MotionEvent.TOOL_TYPE_STYLUS ||
            event.toolType == MotionEvent.TOOL_TYPE_ERASER -> {
                // Use actual stylus pressure
                event.pressure
            }
            else -> {
                // For finger/touch, use size as proxy for pressure
                // This gives basic pressure-like behavior
                kotlin.math.min(1f, event.size * 2f)
            }
        }
    }

    /**
     * Update brush parameters
     */
    fun setBrushParams(params: BrushParams) {
        currentBrushParams = params
    }

    /**
     * Set the active layer ID for drawing
     */
    fun setActiveLayerId(layerId: Long) {
        activeLayerId = layerId
    }

    /**
     * Initialize canvas with specific dimensions
     */
    fun initializeCanvas(width: Int, height: Int, dpi: Int) {
        renderer.setCanvasSize(width, height, dpi)
    }

    /**
     * Set zoom and pan transformation
     */
    fun setTransformation(scale: Float, offsetX: Float, offsetY: Float, rotation: Float = 0f) {
        this.scale = scale
        this.offsetX = offsetX
        this.offsetY = offsetY
        renderer.setTransformation(scale, offsetX, offsetY, rotation)
    }

    /**
     * Get current transformation values
     */
    fun getTransformation(): TransformationData {
        return TransformationData(scale, offsetX, offsetY)
    }

    /**
     * Reset view to default transformation
     */
    fun resetTransformation() {
        scale = 1f
        offsetX = 0f
        offsetY = 0f
        renderer.setTransformation(scale, offsetX, offsetY, 0f)
        invalidate()
    }

    /**
     * Set background color
     */
    fun setBackgroundColor(r: Float, g: Float, b: Float, a: Float = 1f) {
        renderer.setBackgroundColor(r, g, b, a)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        renderer.dispose()
        coroutineScope.cancel()
    }
    
    /**
     * Data class holding transformation state
     */
    data class TransformationData(
        val scale: Float,
        val offsetX: Float,
        val offsetY: Float
    )
}
