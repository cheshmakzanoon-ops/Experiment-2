package com.artflow.studio.presentation.ui.components.editor

import com.artflow.studio.core.canvas.LayerTransform

/** One immutable draft owns all numerical fields; it never mutates canvas pixels. */
data class TransformDraft(
    val width: String = "100",
    val height: String = "100",
    val rotation: String = "0",
    val skew: String = "0",
    val offsetX: String = "0",
    val offsetY: String = "0",
    val uniform: Boolean = true,
    val flipX: Boolean = false,
    val flipY: Boolean = false,
    val interpolation: LayerTransform.Interpolation = LayerTransform.Interpolation.BILINEAR,
) {
    /** Reuse the production transform contract, including finite coordinates and scale/skew bounds. */
    fun parametersOrNull(): LayerTransform.Parameters? =
        try {
            LayerTransform.Parameters(
                translationX = number(offsetX),
                translationY = number(offsetY),
                scaleX = number(width) / 100f,
                scaleY = number(if (uniform) width else height) / 100f,
                rotationDegrees = number(rotation),
                skewXDegrees = number(skew),
                flipHorizontal = flipX,
                flipVertical = flipY,
                interpolation = interpolation,
            )
        } catch (_: IllegalArgumentException) {
            null
        }

    companion object {
        fun number(text: String): Float = text.trim().replace(',', '.').toFloatOrNull() ?: Float.NaN
    }
}
