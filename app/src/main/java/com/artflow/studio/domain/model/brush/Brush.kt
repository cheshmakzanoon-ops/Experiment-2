package com.artflow.studio.domain.model.brush

/**
 * Domain model representing a brush definition
 * Contains brush metadata and default parameters
 */
data class Brush(
    val id: String,
    val name: String,
    val category: BrushCategory,
    val iconResId: Int? = null,
    val defaultParams: BrushParams = BrushParams(),
    val isCustom: Boolean = false,
    val thumbnailPath: String? = null
) {
    /**
     * Brush categories for organization
     */
    enum class BrushCategory {
        PENCILS,
        PENS,
        MARKERS,
        BRUSHES,
        PAINT,
        AIRBRUSHES,
        CHARCOAL,
        WATERCOLOR,
        OIL,
        EXPERIMENTAL,
        CUSTOM
    }
}
