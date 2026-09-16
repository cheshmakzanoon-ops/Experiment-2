package com.artflow.studio.domain.model.layer

import com.artflow.studio.domain.model.brush.Stroke
import kotlinx.serialization.Serializable

/**
 * Domain model representing a layer in the canvas
 * Layers are stacked and composited to create the final image
 */
@Serializable
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
    val maskLayerId: Long? = null,     // Associated mask layer ID

    // --- Phase 17-24: pixel-backed layer content -------------------------------------------
    /**
     * Relative path (inside the project folder) of this layer's pixel data.
     *
     * Layers carry two kinds of content: immutable vector [strokes] and an optional raster base.
     * The raster base is what makes the pixel tools possible (fill, gradient, smudge, clone, heal,
     * liquify, text, imported images, applied filters) and it is stored as a PNG per *version* so
     * undo/redo can reference older versions without copying pixels around in memory.
     */
    val rasterFile: String? = null,

    // --- Phase 16 / 25-30: masks, adjustments, filters, smart objects ----------------------
    /** Grayscale mask image (relative path). The mask multiplies this layer's alpha. */
    val maskFile: String? = null,
    val maskEnabled: Boolean = true,
    val maskInverted: Boolean = false,
    val maskDensity: Float = 1f,
    val maskFeather: Float = 0f,

    /** Phase 25: non-destructive colour correction applied to everything below this layer. */
    val adjustmentType: AdjustmentType? = null,
    val adjustmentParameters: Map<String, Float> = emptyMap(),

    /** Phase 29: non-destructive filter applied to the layer's own pixels. */
    val filterType: FilterType? = null,
    val filterAmount: Float = 0f,

    /** Phase 27: reference layer, shown on a side panel and never composited into the artwork. */
    val isReference: Boolean = false,

    /** Phase 28: layers sharing a non-null link move and transform together. */
    val linkGroupId: Long? = null,

    /** Phase 30: embedded smart object instance instead of owned pixels. */
    val smartObjectId: String? = null,

    /** Hidden from the layer list but still composited (used by text layers before rasterising). */
    val isInternal: Boolean = false
) {
    /**
     * Check if this layer can be edited
     */
    fun canEdit(): Boolean = !isLocked && isVisible

    /** Layer kinds the UI shows differently. */
    fun type(): LayerType = when {
        isReference -> LayerType.REFERENCE
        adjustmentType != null -> LayerType.ADJUSTMENT
        smartObjectId != null -> LayerType.SMART_OBJECT
        filterType != null -> LayerType.FILTER
        else -> LayerType.PIXEL
    }

    /** True when the layer has pixel data that tools can modify. */
    fun hasRaster(): Boolean = rasterFile != null

    /** True when painting on this layer is allowed (visible, unlocked, not a reference/adjustment). */
    fun acceptsPaint(): Boolean =
        isVisible && !isLocked && !isReference && adjustmentType == null && smartObjectId == null

    /** True when the layer has an active mask that must be applied during compositing. */
    fun hasActiveMask(): Boolean = maskFile != null && maskEnabled

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
        maskLayerId: Long? = this.maskLayerId,
        rasterFile: String? = this.rasterFile,
        maskFile: String? = this.maskFile,
        maskEnabled: Boolean = this.maskEnabled,
        maskInverted: Boolean = this.maskInverted,
        maskDensity: Float = this.maskDensity,
        maskFeather: Float = this.maskFeather,
        adjustmentType: AdjustmentType? = this.adjustmentType,
        adjustmentParameters: Map<String, Float> = this.adjustmentParameters,
        filterType: FilterType? = this.filterType,
        filterAmount: Float = this.filterAmount,
        isReference: Boolean = this.isReference,
        linkGroupId: Long? = this.linkGroupId,
        smartObjectId: String? = this.smartObjectId,
        isInternal: Boolean = this.isInternal
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
        maskLayerId = maskLayerId,
        rasterFile = rasterFile,
        maskFile = maskFile,
        maskEnabled = maskEnabled,
        maskInverted = maskInverted,
        maskDensity = maskDensity,
        maskFeather = maskFeather,
        adjustmentType = adjustmentType,
        adjustmentParameters = adjustmentParameters,
        filterType = filterType,
        filterAmount = filterAmount,
        isReference = isReference,
        linkGroupId = linkGroupId,
        smartObjectId = smartObjectId,
        isInternal = isInternal
    )
}

/**
 * Filter types available to filter layers (Phase 29).
 * Kept next to [BlendMode] because both are layer-compositing concerns.
 */
@Serializable
enum class FilterType(
    val displayName: String,
    /** Default strength (`0..1`) applied when the filter layer is created. */
    val defaultAmount: Float
) {
    GAUSSIAN_BLUR("Gaussian Blur", 0.4f),
    MOTION_BLUR("Motion Blur", 0.4f),
    SHARPEN("Sharpen", 0.5f),
    NOISE("Noise", 0.2f),
    CHROMATIC_ABERRATION("Chromatic Aberration", 0.3f),
    VIGNETTE("Vignette", 0.5f),
    FIND_EDGES("Find Edges", 1f),
    EMBOSS("Emboss", 0.5f),
    TILT_SHIFT("Tilt Shift", 0.5f);

    companion object {
        fun byName(name: String): FilterType? = entries.firstOrNull { it.name == name }
    }
}

/**
 * Layer blend modes for compositing
 * Implements Phase 12: Blend Modes
 */
@Serializable
enum class BlendMode(val displayName: String) {
    NORMAL("Normal"),
    MULTIPLY("Multiply"),
    SCREEN("Screen"),
    OVERLAY("Overlay"),
    DARKEN("Darken"),
    LIGHTEN("Lighten"),
    COLOR_DODGE("Color Dodge"),
    COLOR_BURN("Color Burn"),
    HARD_LIGHT("Hard Light"),
    SOFT_LIGHT("Soft Light"),
    DIFFERENCE("Difference"),
    EXCLUSION("Exclusion"),
    HUE("Hue"),
    SATURATION("Saturation"),
    COLOR("Color"),
    LUMINOSITY("Luminosity"),
    PASS_THROUGH("Pass Through");  // For layer groups
    
    companion object {
        /**
         * Get all blend modes available for regular layers (excludes PASS_THROUGH)
         */
        fun getLayerBlendModes(): List<BlendMode> = entries.filter { it != PASS_THROUGH }
    }
}

/**
 * Represents a layer group (folder) for organizing layers
 */
@Serializable
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
     * Create a copy of this group with modified properties.
     * Used by LayerGroupManager for immutable updates.
     */
    fun copyWith(
        id: Long = this.id,
        name: String = this.name,
        index: Int = this.index,
        layerIds: List<Long> = this.layerIds,
        subGroupIds: List<Long> = this.subGroupIds,
        isVisible: Boolean = this.isVisible,
        isExpanded: Boolean = this.isExpanded,
        parentGroupId: Long? = this.parentGroupId
    ): LayerGroup = LayerGroup(
        id = id,
        name = name,
        index = index,
        layerIds = layerIds,
        subGroupIds = subGroupIds,
        isVisible = isVisible,
        isExpanded = isExpanded,
        parentGroupId = parentGroupId
    )

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
enum class LayerType(val displayName: String) {
    PIXEL("Pixel"),              // Standard raster layer
    VECTOR("Vector"),            // Vector-based layer
    FILL("Fill"),                // Solid colour / pattern fill layer
    ADJUSTMENT("Adjustment"),    // Adjustment layer for colour corrections
    CLIPPING_MASK("Clipping"),   // Clipping mask layer
    MASK("Mask"),                // Layer mask (grayscale)
    REFERENCE("Reference"),      // Reference image, never composited
    FILTER("Filter"),            // Non-destructive filter layer
    SMART_OBJECT("Smart Object") // Embedded reusable content
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
