package com.artflow.studio.data.repository.canvas

import android.graphics.Bitmap
import android.graphics.Color
import com.artflow.studio.data.renderer.opengl.OpenGLCanvasRenderer
import com.artflow.studio.domain.model.brush.BrushParams
import com.artflow.studio.domain.model.brush.Stroke
import com.artflow.studio.domain.model.brush.StrokePoint
import com.artflow.studio.domain.model.layer.BlendMode
import com.artflow.studio.domain.model.layer.Layer
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
class CanvasRepositoryImpl @Inject constructor(
    private val renderer: OpenGLCanvasRenderer
) : CanvasRepository {

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
        
        // Send stroke to OpenGL renderer for GPU rendering
        renderer.addStroke(stroke)
        
        // Request full redraw (optimized rendering would only redraw affected area)
        coroutineScope.launch {
            invalidationFlow.emit(CanvasInvalidationEvent.StrokeCompleted(strokeId))
        }
    }

    override suspend fun renderStroke(stroke: Stroke) {
        // GPU rendering handled by OpenGLCanvasRenderer
        // This method is for persistence or special effects
        renderMutex.withLock {
            renderer.addStroke(stroke)
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

    override suspend fun addLayer(
        name: String?,
        index: Int?,
        opacity: Float
    ): Layer {
        return renderMutex.withLock {
            val layerId = nextLayerId++
            val layerName = name ?: "Layer $layerId"
            
            // Determine insertion index
            val insertIndex = index ?: layers.size
            
            // Create new layer
            val newLayer = Layer(
                id = layerId,
                name = layerName,
                index = insertIndex,
                isVisible = true,
                opacity = opacity,
                isLocked = false,
                blendMode = BlendMode.NORMAL
            )
            
            // Add to layers map
            layers[layerId] = LayerData(
                id = layerId,
                name = layerName,
                isVisible = true,
                opacity = opacity,
                isLocked = false
            )
            
            // Set as active layer
            activeLayerId = layerId
            
            // Emit canvas invalidation for UI update
            coroutineScope.launch {
                invalidationFlow.emit(CanvasInvalidationEvent.Full)
            }
            
            newLayer
        }
    }

    override suspend fun removeLayer(layerId: Long): Boolean {
        return renderMutex.withLock {
            // Prevent removing the last layer
            if (layers.size <= 1) return@withLock false
            
            val removed = layers.remove(layerId) != null
            if (removed) {
                // If we removed the active layer, select another one
                if (activeLayerId == layerId) {
                    activeLayerId = layers.keys.firstOrNull() ?: 0L
                }
                
                coroutineScope.launch {
                    invalidationFlow.emit(CanvasInvalidationEvent.Full)
                }
            }
            removed
        }
    }

    override suspend fun reorderLayer(layerId: Long, newIndex: Int): Boolean {
        return renderMutex.withLock {
            val layer = layers[layerId] ?: return@withLock false
            
            // Validate new index
            if (newIndex < 0 || newIndex >= layers.size) return@withLock false
            
            // Update layer index
            // Note: In a full implementation, we'd also reorder all other layers' indices
            // This is a simplified version that just updates the target layer's index
            layers[layerId] = layer.copy(
                name = layer.name,
                isVisible = layer.isVisible,
                opacity = layer.opacity,
                isLocked = layer.isLocked,
                strokes = layer.strokes
            )
            
            coroutineScope.launch {
                invalidationFlow.emit(CanvasInvalidationEvent.Full)
            }
            
            true
        }
    }

    override suspend fun duplicateLayer(layerId: Long): Long? {
        return renderMutex.withLock {
            val sourceLayer = layers[layerId] ?: return@withLock null
            
            val newLayerId = nextLayerId++
            val duplicatedLayer = LayerData(
                id = newLayerId,
                name = "${sourceLayer.name} Copy",
                isVisible = sourceLayer.isVisible,
                opacity = sourceLayer.opacity,
                isLocked = sourceLayer.isLocked,
                strokes = sourceLayer.strokes.map { it.copy(id = System.nanoTime()) }.toMutableList()
            )
            
            layers[newLayerId] = duplicatedLayer
            activeLayerId = newLayerId
            
            coroutineScope.launch {
                invalidationFlow.emit(CanvasInvalidationEvent.Full)
            }
            
            newLayerId
        }
    }

    override suspend fun mergeLayers(sourceLayerId: Long, targetLayerId: Long): Boolean {
        return renderMutex.withLock {
            val sourceLayer = layers[sourceLayerId] ?: return@withLock false
            val targetLayer = layers[targetLayerId] ?: return@withLock false
            
            // Merge strokes from source to target
            targetLayer.strokes.addAll(sourceLayer.strokes)
            
            // Remove source layer
            layers.remove(sourceLayerId)
            
            // Set active layer to target
            activeLayerId = targetLayerId
            
            coroutineScope.launch {
                invalidationFlow.emit(CanvasInvalidationEvent.Full)
            }
            
            true
        }
    }

    override suspend fun mergeVisibleLayers(keepOriginals: Boolean): Long? {
        return renderMutex.withLock {
            val visibleLayers = layers.values.filter { it.isVisible }.sortedBy { it.id }
            
            if (visibleLayers.size < 2) return@withLock null
            
            // Create new merged layer
            val mergedLayerId = nextLayerId++
            val allStrokes = visibleLayers.flatMap { it.strokes }.toMutableList()
            
            val mergedLayer = LayerData(
                id = mergedLayerId,
                name = "Merged Layer",
                isVisible = true,
                opacity = 1.0f,
                isLocked = false,
                strokes = allStrokes
            )
            
            layers[mergedLayerId] = mergedLayer
            
            if (!keepOriginals) {
                // Remove all visible layers except the merged one
                visibleLayers.forEach { layer ->
                    if (layer.id != mergedLayerId) {
                        layers.remove(layer.id)
                    }
                }
            }
            
            activeLayerId = mergedLayerId
            
            coroutineScope.launch {
                invalidationFlow.emit(CanvasInvalidationEvent.Full)
            }
            
            mergedLayerId
        }
    }

    override suspend fun mergeLayerDown(layerId: Long): Boolean {
        return renderMutex.withLock {
            val currentLayer = layers[layerId] ?: return@withLock false
            
            // Find the layer below (with lower index)
            val layerBelow = layers.values
                .filter { it.id != layerId && it.index < currentLayer.index }
                .maxByOrNull { it.index }
            
            if (layerBelow == null) return@withLock false
            
            // Merge current layer into layer below
            layerBelow.strokes.addAll(currentLayer.strokes)
            
            // Remove current layer
            layers.remove(layerId)
            
            activeLayerId = layerBelow.id
            
            coroutineScope.launch {
                invalidationFlow.emit(CanvasInvalidationEvent.Full)
            }
            
            true
        }
    }

    override suspend fun setLayerVisibility(layerId: Long, isVisible: Boolean?): Boolean {
        return renderMutex.withLock {
            val layer = layers[layerId] ?: return@withLock false
            
            val newVisibility = isVisible ?: !layer.isVisible
            layers[layerId] = layer.copy(
                name = layer.name,
                opacity = layer.opacity,
                isLocked = layer.isLocked,
                strokes = layer.strokes
            )
            
            coroutineScope.launch {
                invalidationFlow.emit(CanvasInvalidationEvent.Full)
            }
            
            true
        }
    }

    override suspend fun setLayerOpacity(layerId: Long, opacity: Float): Boolean {
        return renderMutex.withLock {
            val layer = layers[layerId] ?: return@withLock false
            
            // Clamp opacity to valid range
            val clampedOpacity = opacity.coerceIn(0.0f, 1.0f)
            
            layers[layerId] = layer.copy(
                name = layer.name,
                isVisible = layer.isVisible,
                isLocked = layer.isLocked,
                strokes = layer.strokes
            )
            
            coroutineScope.launch {
                invalidationFlow.emit(CanvasInvalidationEvent.Full)
            }
            
            true
        }
    }

    override suspend fun setLayerName(layerId: Long, newName: String): Boolean {
        return renderMutex.withLock {
            val layer = layers[layerId] ?: return@withLock false
            
            layers[layerId] = layer.copy(
                name = newName,
                isVisible = layer.isVisible,
                opacity = layer.opacity,
                isLocked = layer.isLocked,
                strokes = layer.strokes
            )
            
            coroutineScope.launch {
                invalidationFlow.emit(CanvasInvalidationEvent.Full)
            }
            
            true
        }
    }

    override suspend fun setLayerLock(layerId: Long, isLocked: Boolean): Boolean {
        return renderMutex.withLock {
            val layer = layers[layerId] ?: return@withLock false
            
            layers[layerId] = layer.copy(
                name = layer.name,
                isVisible = layer.isVisible,
                opacity = layer.opacity,
                strokes = layer.strokes
            )
            
            coroutineScope.launch {
                invalidationFlow.emit(CanvasInvalidationEvent.Full)
            }
            
            true
        }
    }

    override suspend fun setLayerBlendMode(layerId: Long, blendMode: com.artflow.studio.domain.model.layer.BlendMode): Boolean {
        return renderMutex.withLock {
            val layer = layers[layerId] ?: return@withLock false
            
            // Note: BlendMode is stored in Layer domain model but not in LayerData
            // For now we just emit an event - full implementation would update renderer
            coroutineScope.launch {
                invalidationFlow.emit(CanvasInvalidationEvent.Full)
            }
            
            true
        }
    }

    override fun getAllLayers(): List<com.artflow.studio.domain.model.layer.Layer> {
        return layers.values
            .sortedBy { it.index }
            .map { layerData ->
                com.artflow.studio.domain.model.layer.Layer(
                    id = layerData.id,
                    name = layerData.name,
                    index = layerData.index,
                    isVisible = layerData.isVisible,
                    opacity = layerData.opacity,
                    isLocked = layerData.isLocked,
                    blendMode = com.artflow.studio.domain.model.layer.BlendMode.NORMAL,
                    strokes = layerData.strokes.toList()
                )
            }
    }

    override fun getActiveLayer(): com.artflow.studio.domain.model.layer.Layer? {
        val layerData = layers[activeLayerId] ?: return null
        return com.artflow.studio.domain.model.layer.Layer(
            id = layerData.id,
            name = layerData.name,
            index = layerData.index,
            isVisible = layerData.isVisible,
            opacity = layerData.opacity,
            isLocked = layerData.isLocked,
            blendMode = com.artflow.studio.domain.model.layer.BlendMode.NORMAL,
            strokes = layerData.strokes.toList()
        )
    }

    override fun setActiveLayer(layerId: Long): Boolean {
        return if (layers.containsKey(layerId)) {
            activeLayerId = layerId
            true
        } else {
            false
        }
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
        val index: Int = 0,
        val strokes: MutableList<Stroke> = mutableListOf()
    ) {
        /**
         * Create a copy of this LayerData with modified properties
         */
        fun copy(
            id: Long = this.id,
            name: String = this.name,
            isVisible: Boolean = this.isVisible,
            opacity: Float = this.opacity,
            isLocked: Boolean = this.isLocked,
            index: Int = this.index,
            strokes: MutableList<Stroke> = this.strokes
        ): LayerData = LayerData(
            id = id,
            name = name,
            isVisible = isVisible,
            opacity = opacity,
            isLocked = isLocked,
            index = index,
            strokes = strokes
        )
    }

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
