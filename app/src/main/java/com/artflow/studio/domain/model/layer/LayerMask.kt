package com.artflow.studio.domain.model.layer

import android.graphics.Bitmap

/**
 * Domain model representing a layer mask for non-destructive editing
 * Layer masks use grayscale values to control layer visibility:
 * - White (255): Fully visible
 * - Black (0): Fully hidden
 * - Gray (1-254): Partially visible based on brightness
 *
 * Implements Phase 16: Layer Masks - Non-destructive layer masking
 */
data class LayerMask(
    val id: Long,
    val layerId: Long, // The layer this mask is attached to
    val bitmap: Bitmap?, // Grayscale bitmap (null if empty mask)
    val isEnabled: Boolean = true, // Whether mask is currently active
    val isInverted: Boolean = false, // If true, black=visible, white=hidden
    val density: Float = 1.0f, // Overall mask opacity (0.0 - 1.0)
    val featherRadius: Float = 0f, // Edge feathering in pixels
    val thumbnailPath: String? = null, // Path to mask thumbnail
) {
    /**
     * Check if this mask can affect the layer visibility
     */
    fun isActive(): Boolean = isEnabled && (bitmap != null || featherRadius > 0)

    /**
     * Get effective density after inversion consideration
     */
    fun getEffectiveDensity(pixelValue: Int): Float {
        val normalizedPixel = pixelValue / 255.0f
        return if (isInverted) {
            (1.0f - normalizedPixel) * density
        } else {
            normalizedPixel * density
        }.coerceIn(0f, 1f)
    }

    /**
     * Create a copy of this mask with modified properties
     */
    fun copyWith(
        id: Long = this.id,
        layerId: Long = this.layerId,
        bitmap: Bitmap? = this.bitmap,
        isEnabled: Boolean = this.isEnabled,
        isInverted: Boolean = this.isInverted,
        density: Float = this.density,
        featherRadius: Float = this.featherRadius,
        thumbnailPath: String? = this.thumbnailPath,
    ): LayerMask =
        LayerMask(
            id = id,
            layerId = layerId,
            bitmap = bitmap,
            isEnabled = isEnabled,
            isInverted = isInverted,
            density = density,
            featherRadius = featherRadius,
            thumbnailPath = thumbnailPath,
        )

    /**
     * Get mask dimensions if bitmap exists
     */
    fun getDimensions(): Pair<Int, Int>? = bitmap?.let { Pair(it.width, it.height) }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as LayerMask
        return id == other.id &&
            layerId == other.layerId &&
            isEnabled == other.isEnabled &&
            isInverted == other.isInverted &&
            density == other.density &&
            featherRadius == other.featherRadius &&
            thumbnailPath == other.thumbnailPath
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + layerId.hashCode()
        result = 31 * result + isEnabled.hashCode()
        result = 31 * result + isInverted.hashCode()
        result = 31 * result + density.hashCode()
        result = 31 * result + featherRadius.hashCode()
        result = 31 * result + (thumbnailPath?.hashCode() ?: 0)
        return result
    }
}

/**
 * Represents how a layer mask was created
 */
enum class MaskCreationMethod {
    EMPTY, // Blank white mask (reveals all)
    HIDE_ALL, // Blank black mask (hides all)
    FROM_SELECTION, // Created from active selection
    FROM_LAYER_ALPHA, // Based on layer's alpha channel
    FROM_GRADIENT, // Gradient-based mask
    CUSTOM, // User-painted custom mask
}

/**
 * Event types for layer mask operations
 */
sealed class LayerMaskEvent {
    data class MaskAdded(
        val mask: LayerMask,
        val creationMethod: MaskCreationMethod,
    ) : LayerMaskEvent()

    data class MaskRemoved(
        val layerId: Long,
    ) : LayerMaskEvent()

    data class MaskToggled(
        val layerId: Long,
        val isEnabled: Boolean,
    ) : LayerMaskEvent()

    data class MaskInverted(
        val layerId: Long,
        val isInverted: Boolean,
    ) : LayerMaskEvent()

    data class MaskDensityChanged(
        val layerId: Long,
        val density: Float,
    ) : LayerMaskEvent()

    data class MaskFeatherChanged(
        val layerId: Long,
        val featherRadius: Float,
    ) : LayerMaskEvent()

    data class MaskPainted(
        val layerId: Long,
        val affectedArea: MaskAffectedArea,
    ) : LayerMaskEvent()

    data class MaskLinked(
        val layerId: Long,
        val isLinked: Boolean,
    ) : LayerMaskEvent()

    object MasksCleared : LayerMaskEvent()
}

/**
 * Represents the area affected by a mask paint operation
 */
data class MaskAffectedArea(
    val bounds: Rect,
    val pixelCount: Int,
    val averageIntensity: Float,
)

/**
 * Simple rectangle structure for mask operations
 */
data class Rect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top

    companion object {
        fun fromXYWH(
            x: Int,
            y: Int,
            width: Int,
            height: Int,
        ): Rect = Rect(x, y, x + width, y + height)
    }
}
