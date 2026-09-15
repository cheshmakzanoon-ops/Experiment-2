package com.artflow.studio.domain.model.layer

import com.artflow.studio.domain.model.brush.Stroke

/**
 * Domain model representing a layer in the canvas
 * Layers are stacked and composited to create the final image
 */
data class Layer(
    val id: Long,
    val name: String,
    val index: Int,                    // Position in layer stack (0 = bottom)
    val isVisible: Boolean = true,     // Whether layer is visible
    val opacity: Float = 1.0f,         // Layer opacity (0.0 - 1.0)
    val isLocked: Boolean = false,     // Whether layer is locked from editing
    val blendMode: BlendMode = BlendMode.NORMAL,
    val strokes: List<Stroke> = emptyList(),
    val thumbnailPath: String? = null, // Path to layer thumbnail
    val isAlphaLocked: Boolean = false, // Alpha lock (paint only on existing pixels)
    val isClippingMask: Boolean = false, // Clip to layer below
    val parentGroupId: Long? = null,   // Parent group ID if nested
    val maskLayerId: Long? = null      // Associated mask layer ID
) {
    /**
     * Check if this layer can be edited
     */
    fun canEdit(): Boolean = !isLocked && isVisible

    /**
     * Create a copy of this layer with modified properties
     */
    fun copyWith(
        id: Long = this.id,
        name: String = this.name,
        index: Int = this.index,
        isVisible: Boolean = this.isVisible,
        opacity: Float = this.opacity,
        isLocked: Boolean = this.isLocked,
        blendMode: BlendMode = this.blendMode,
        strokes: List<Stroke> = this.strokes,
        thumbnailPath: String? = this.thumbnailPath,
        isAlphaLocked: Boolean = this.isAlphaLocked,
        isClippingMask: Boolean = this.isClippingMask,
        parentGroupId: Long? = this.parentGroupId,
        maskLayerId: Long? = this.maskLayerId
    ): Layer = Layer(
        id = id,
        name = name,
        index = index,
        isVisible = isVisible,
        opacity = opacity,
        isLocked = isLocked,
        blendMode = blendMode,
        strokes = strokes,
        thumbnailPath = thumbnailPath,
        isAlphaLocked = isAlphaLocked,
        isClippingMask = isClippingMask,
        parentGroupId = parentGroupId,
        maskLayerId = maskLayerId
    )
}

/**
 * Layer blend modes for compositing
 */
enum class BlendMode {
    NORMAL,
    MULTIPLY,
    SCREEN,
    OVERLAY,
    DARKEN,
    LIGHTEN,
    COLOR_DODGE,
    COLOR_BURN,
    HARD_LIGHT,
    SOFT_LIGHT,
    DIFFERENCE,
    EXCLUSION,
    HUE,
    SATURATION,
    COLOR,
    LUMINOSITY,
    PASS_THROUGH  // For layer groups
}

/**
 * Represents a layer group (folder) for organizing layers
 */
data class LayerGroup(
    val id: Long,
    val name: String,
    val index: Int,
    val layerIds: List<Long>,          // Ordered list of layer IDs in this group
    val subGroupIds: List<Long> = emptyList(), // Nested groups
    val isVisible: Boolean = true,
    val isExpanded: Boolean = true,    // UI state for group expansion
    val parentGroupId: Long? = null
) {
    /**
     * Total number of layers in this group (including nested)
     */
    fun getTotalLayerCount(groups: List<LayerGroup>): Int {
        var count = layerIds.size
        subGroupIds.forEach { subGroupId ->
            groups.find { it.id == subGroupId }?.let { subGroup ->
                count += subGroup.getTotalLayerCount(groups)
            }
        }
        return count
    }
}

/**
 * Layer type enumeration for different layer kinds
 */
enum class LayerType {
    PIXEL,           // Standard raster layer
    VECTOR,          // Vector-based layer (future)
    FILL,            // Solid color/pattern fill layer
    ADJUSTMENT,      // Adjustment layer for color corrections
    CLIPPING_MASK,   // Clipping mask layer
    MASK             // Layer mask (grayscale)
}

/**
 * Event types for layer operations
 */
sealed class LayerEvent {
    data class LayerAdded(val layer: Layer) : LayerEvent()
    data class LayerRemoved(val layerId: Long) : LayerEvent()
    data class LayerMoved(val layerId: Long, val newIndex: Int) : LayerEvent()
    data class LayerVisibilityChanged(val layerId: Long, val isVisible: Boolean) : LayerEvent()
    data class LayerOpacityChanged(val layerId: Long, val opacity: Float) : LayerEvent()
    data class LayerRenamed(val layerId: Long, val newName: String) : LayerEvent()
    data class LayerLockChanged(val layerId: Long, val isLocked: Boolean) : LayerEvent()
    data class LayerBlendModeChanged(val layerId: Long, val blendMode: BlendMode) : LayerEvent()
    data class LayerSelected(val layerId: Long) : LayerEvent()
    data class LayerDuplicated(val originalLayerId: Long, val newLayerId: Long) : LayerEvent()
    data class LayerMerged(val sourceLayerId: Long, val targetLayerId: Long) : LayerEvent()
    object LayersReordered : LayerEvent()
}
