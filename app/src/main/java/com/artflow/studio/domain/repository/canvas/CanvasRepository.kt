package com.artflow.studio.domain.repository.canvas

import com.artflow.studio.domain.model.brush.BrushParams
import com.artflow.studio.domain.model.brush.Stroke
import com.artflow.studio.domain.model.brush.StrokePoint
import kotlinx.coroutines.flow.Flow

/**
 * Repository interface for canvas operations
 * Defines the contract for canvas rendering and layer management
 */
interface CanvasRepository {

    /**
     * Initialize a new canvas with specified dimensions
     * @param width Canvas width in pixels
     * @param height Canvas height in pixels
     * @param dpi Canvas DPI
     * @return Canvas ID
     */
    suspend fun createCanvas(width: Int, height: Int, dpi: Int): Long

    /**
     * Load an existing canvas from storage
     * @param projectId Project ID to load
     * @return Canvas state or null if not found
     */
    suspend fun loadCanvas(projectId: Long): CanvasState?

    /**
     * Save the current canvas state
     * @param projectId Project ID to save to
     */
    suspend fun saveCanvas(projectId: Long)

    /**
     * Begin a new stroke
     * @param x Starting X coordinate
     * @param y Starting Y coordinate
     * @param pressure Initial pressure value
     * @param brushParams Current brush parameters
     * @param layerId Target layer ID
     * @return Stroke ID
     */
    fun beginStroke(
        x: Float,
        y: Float,
        pressure: Float,
        brushParams: BrushParams,
        layerId: Long
    ): Long

    /**
     * Continue an existing stroke with a new point
     * @param strokeId Stroke to continue
     * @param x X coordinate
     * @param y Y coordinate
     * @param pressure Pressure value
     * @param tiltX Tilt angle on X axis
     * @param tiltY Tilt angle on Y axis
     */
    fun continueStroke(
        strokeId: Long,
        x: Float,
        y: Float,
        pressure: Float,
        tiltX: Float = 0f,
        tiltY: Float = 0f
    )

    /**
     * End the current stroke
     * @param strokeId Stroke to end
     */
    fun endStroke(strokeId: Long)

    /**
     * Render a complete stroke to the canvas texture
     * @param stroke The stroke to render
     */
    suspend fun renderStroke(stroke: Stroke)

    /**
     * Get the current canvas as a bitmap (for export/thumbnail)
     * @return Bitmap data or null
     */
    suspend fun getCanvasBitmap(): ByteArray?

    /**
     * Clear the entire canvas
     * @param color Color to fill with (default white)
     */
    suspend fun clearCanvas(color: Int = -1)

    /**
     * Set the background color of the canvas
     * @param color ARGB color value
     */
    fun setBackgroundColor(color: Int)

    /**
     * Get current canvas dimensions
     */
    fun getCanvasSize(): CanvasSize

    /**
     * Observe canvas invalidation events for rendering
     */
    fun observeCanvasInvalidation(): Flow<CanvasInvalidationEvent>

    /**
     * Dispose of canvas resources
     */
    fun dispose()

    /**
     * Add a new layer to the canvas
     * @param name Layer name (optional, will auto-generate if null)
     * @param index Position in layer stack (null = above active layer)
     * @param opacity Initial opacity (0.0 - 1.0)
     * @return Created Layer object
     */
    suspend fun addLayer(
        name: String? = null,
        index: Int? = null,
        opacity: Float = 1.0f
    ): com.artflow.studio.domain.model.layer.Layer

    /**
     * Remove a layer from the canvas
     * @param layerId ID of the layer to remove
     * @return True if removed successfully, false otherwise
     */
    suspend fun removeLayer(layerId: Long): Boolean

    /**
     * Reorder a layer in the layer stack
     * @param layerId ID of the layer to move
     * @param newIndex New position in the layer stack
     * @return True if reordered successfully, false otherwise
     */
    suspend fun reorderLayer(layerId: Long, newIndex: Int): Boolean

    /**
     * Duplicate an existing layer
     * @param layerId ID of the layer to duplicate
     * @return ID of the newly created duplicated layer, or null if failed
     */
    suspend fun duplicateLayer(layerId: Long): Long?

    /**
     * Merge two layers (source into target)
     * @param sourceLayerId ID of the source layer (will be removed)
     * @param targetLayerId ID of the target layer (will contain merged content)
     * @return True if merged successfully, false otherwise
     */
    suspend fun mergeLayers(sourceLayerId: Long, targetLayerId: Long): Boolean

    /**
     * Merge all visible layers into a single layer
     * @param keepOriginals Whether to keep original layers after merge
     * @return ID of the merged layer
     */
    suspend fun mergeVisibleLayers(keepOriginals: Boolean = false): Long?

    /**
     * Merge down - merge current layer with the layer below it
     * @param layerId ID of the layer to merge down
     * @return true if merge was successful
     */
    suspend fun mergeLayerDown(layerId: Long): Boolean
}

/**
 * Represents the complete state of a canvas
 */
data class CanvasState(
    val id: Long,
    val width: Int,
    val height: Int,
    val dpi: Int,
    val backgroundColor: Int,
    val layerIds: List<Long>,
    val activeLayerId: Long,
    val zoom: Float,
    val offsetX: Float,
    val offsetY: Float,
    val rotation: Float
)

/**
 * Canvas dimensions
 */
data class CanvasSize(
    val width: Int,
    val height: Int,
    val dpi: Int
)

/**
 * Event indicating what part of the canvas needs redrawing
 */
sealed class CanvasInvalidationEvent {
    /**
     * Full canvas redraw needed
     */
    object Full : CanvasInvalidationEvent()

    /**
     * Partial redraw needed for specific region
     */
    data class Region(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float
    ) : CanvasInvalidationEvent()

    /**
     * A stroke was completed
     */
    data class StrokeCompleted(val strokeId: Long) : CanvasInvalidationEvent()
}
