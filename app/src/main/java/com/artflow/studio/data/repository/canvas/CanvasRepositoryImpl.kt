package com.artflow.studio.data.repository.canvas

import android.graphics.Bitmap
import android.graphics.Color
import com.artflow.studio.domain.model.brush.BrushParams
import com.artflow.studio.domain.model.brush.Stroke
import com.artflow.studio.domain.model.brush.StrokePoint
import com.artflow.studio.domain.repository.canvas.CanvasInvalidationEvent
import com.artflow.studio.domain.repository.canvas.CanvasRepository
import com.artflow.studio.domain.repository.canvas.CanvasSize
import com.artflow.studio.domain.repository.canvas.CanvasState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implementation of CanvasRepository using OpenGL ES for rendering
 */
@Singleton
class CanvasRepositoryImpl @Inject constructor() : CanvasRepository {

    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val renderMutex = Mutex()
    
    // Current canvas state
    private var currentCanvasId: Long = 0
    private var canvasWidth = 1920
    private var canvasHeight = 1080
    private var canvasDpi = 72
    private var backgroundColor = Color.WHITE
    
    // Layer management
    private val layers = mutableMapOf<Long, LayerData>()
    private var activeLayerId = 1L
    private var nextLayerId = 1L
    
    // Stroke management
    private val activeStrokes = mutableMapOf<Long, MutableList<StrokePoint>>()
    private val strokeBrushParams = mutableMapOf<Long, BrushParams>()
    private val strokeLayerIds = mutableMapOf<Long, Long>()
    private var nextStrokeId = 1L
    
    // Viewport transformation
    private var zoom = 1f
    private var offsetX = 0f
    private var offsetY = 0f
    private var rotation = 0f
    
    // Invalidation events
    private val invalidationFlow = MutableSharedFlow<CanvasInvalidationEvent>(replay = 0)

    override suspend fun createCanvas(width: Int, height: Int, dpi: Int): Long {
        return renderMutex.withLock {
            currentCanvasId = System.nanoTime()
            canvasWidth = width
            canvasHeight = height
            canvasDpi = dpi
            
            // Create initial background layer
            val bgLayerId = nextLayerId++
            layers[bgLayerId] = LayerData(
                id = bgLayerId,
                name = "Background",
                isVisible = true,
                opacity = 1.0f,
                isLocked = false
            )
            activeLayerId = bgLayerId
            
            // Emit canvas created event
            invalidationFlow.emit(CanvasInvalidationEvent.Full)
            
            currentCanvasId
        }
    }

    override suspend fun loadCanvas(projectId: Long): CanvasState? {
        // TODO: Load from database/file storage
        // For now, return null to indicate no saved canvas exists
        return null
    }

    override suspend fun saveCanvas(projectId: Long) {
        // TODO: Save to database/file storage
        // This will serialize layers and strokes to disk
    }

    override fun beginStroke(
        x: Float,
        y: Float,
        pressure: Float,
        brushParams: BrushParams,
        layerId: Long
    ): Long {
        val strokeId = nextStrokeId++
        
        val startPoint = StrokePoint(
            x = applyZoomAndOffsetX(x),
            y = applyZoomAndOffsetY(y),
            pressure = pressure
        )
        
        activeStrokes[strokeId] = mutableListOf(startPoint)
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
        
        val point = StrokePoint(
            x = applyZoomAndOffsetX(x),
            y = applyZoomAndOffsetY(y),
            pressure = pressure,
            tiltX = tiltX,
            tiltY = tiltY
        )
        
        points.add(point)
        
        // Request partial redraw of affected region
        coroutineScope.launch {
            val bounds = calculateStrokeBounds(points)
            invalidationFlow.emit(
                CanvasInvalidationEvent.Region(
                    left = bounds.left - 50,
                    top = bounds.top - 50,
                    right = bounds.right + 50,
                    bottom = bounds.bottom + 50
                )
            )
        }
    }

    override fun endStroke(strokeId: Long) {
        val points = activeStrokes.remove(strokeId) ?: return
        val brushParams = strokeBrushParams.remove(strokeId) ?: return
        val layerId = strokeLayerIds.remove(strokeId) ?: return
        
        // Create completed stroke
        val stroke = Stroke(
            id = strokeId,
            points = points,
            brushParams = brushParams,
            layerId = layerId,
            color = points.firstOrNull()?.color ?: Color.BLACK
        )
        
        // Add to layer
        val layer = layers[layerId]
        layer?.strokes?.add(stroke)
        
        // Request full redraw (optimized rendering would only redraw affected area)
        coroutineScope.launch {
            invalidationFlow.emit(CanvasInvalidationEvent.StrokeCompleted(strokeId))
        }
    }

    override suspend fun renderStroke(stroke: Stroke) {
        // GPU rendering handled by OpenGLCanvasRenderer
        // This method is for persistence or special effects
        renderMutex.withLock {
            // Implementation will use OpenGL shaders to render the stroke
            // to the appropriate layer texture
        }
    }

    override suspend fun getCanvasBitmap(): ByteArray? {
        // TODO: Render all layers to bitmap and compress
        return null
    }

    override suspend fun clearCanvas(color: Int) {
        renderMutex.withLock {
            backgroundColor = color
            
            // Clear all layer strokes
            layers.values.forEach { it.strokes.clear() }
            
            invalidationFlow.emit(CanvasInvalidationEvent.Full)
        }
    }

    override fun setBackgroundColor(color: Int) {
        backgroundColor = color
    }

    override fun getCanvasSize(): CanvasSize {
        return CanvasSize(canvasWidth, canvasHeight, canvasDpi)
    }

    override fun observeCanvasInvalidation(): Flow<CanvasInvalidationEvent> {
        return invalidationFlow
    }

    override fun dispose() {
        coroutineScope.cancel()
        layers.clear()
        activeStrokes.clear()
        strokeBrushParams.clear()
        strokeLayerIds.clear()
    }

    /**
     * Apply zoom and offset transformation to X coordinate
     */
    private fun applyZoomAndOffsetX(x: Float): Float {
        return (x - offsetX) / zoom
    }

    /**
     * Apply zoom and offset transformation to Y coordinate
     */
    private fun applyZoomAndOffsetY(y: Float): Float {
        return (y - offsetY) / zoom
    }

    /**
     * Calculate bounding box of stroke points
     */
    private fun calculateStrokeBounds(points: List<StrokePoint>): RectF {
        if (points.isEmpty()) return RectF(0f, 0f, 0f, 0f)
        
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = Float.MIN_VALUE
        var maxY = Float.MIN_VALUE
        
        points.forEach { point ->
            minX = kotlin.math.min(minX, point.x)
            minY = kotlin.math.min(minY, point.y)
            maxX = kotlin.math.max(maxX, point.x)
            maxY = kotlin.math.max(maxY, point.y)
        }
        
        return RectF(minX, minY, maxX, maxY)
    }

    /**
     * Internal layer data structure
     */
    private data class LayerData(
        val id: Long,
        val name: String,
        val isVisible: Boolean,
        val opacity: Float,
        val isLocked: Boolean,
        val strokes: MutableList<Stroke> = mutableListOf()
    )

    /**
     * Simple rectangle for bounds calculation
     */
    private data class RectF(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float
    )
}
