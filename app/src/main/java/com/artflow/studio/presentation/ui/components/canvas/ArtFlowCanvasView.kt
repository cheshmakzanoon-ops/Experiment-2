package com.artflow.studio.presentation.ui.components.canvas

import android.content.Context
import android.opengl.GLSurfaceView
import android.util.AttributeSet
import android.view.MotionEvent
import com.artflow.studio.data.renderer.opengl.OpenGLCanvasRenderer
import com.artflow.studio.domain.model.brush.BrushParams
import dagger.hilt.android.AndroidEntryPoint
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

    // Current brush settings
    private var currentBrushParams = BrushParams()
    
    // Current drawing state
    private var isDrawing = false
    private var currentStrokeId = 0L
    
    // Active layer
    private var activeLayerId = 1L

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
        val x = event.x
        val y = event.y
        val pressure = getPressureFromEvent(event)
        
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                isDrawing = true
                // TODO: Begin stroke through repository
                // currentStrokeId = canvasRepository.beginStroke(x, y, pressure, currentBrushParams, activeLayerId)
                invalidate() // Request redraw
                true
            }
            
            MotionEvent.ACTION_MOVE -> {
                if (isDrawing) {
                    // TODO: Continue stroke through repository
                    // canvasRepository.continueStroke(currentStrokeId, x, y, pressure)
                    invalidate() // Request redraw
                }
                true
            }
            
            MotionEvent.ACTION_UP -> {
                if (isDrawing) {
                    // TODO: End stroke through repository
                    // canvasRepository.endStroke(currentStrokeId)
                    isDrawing = false
                }
                true
            }
            
            MotionEvent.ACTION_CANCEL -> {
                if (isDrawing) {
                    isDrawing = false
                }
                true
            }
            
            else -> false
        }
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
        renderer.setTransformation(scale, offsetX, offsetY, rotation)
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
    }
}
