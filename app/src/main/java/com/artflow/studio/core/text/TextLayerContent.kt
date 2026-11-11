package com.artflow.studio.core.text

import kotlinx.serialization.Serializable

/**
 * What an editable text layer says and how it looks. The layer's pixels are rendered from this;
 * painting on the layer turns it into ordinary pixels, like rasterising text in Procreate.
 * Non-destructive vector transformation preserves rotation, scale, tracking, kerning, and baseline shift.
 */
@Serializable
data class TextLayerContent(
    val text: String,
    val style: TextLayout.TextStyle,
    val color: Int,
    val x: Float,
    val y: Float,
    val rotationDegrees: Float = 0f,
    val scaleX: Float = 1f,
    val scaleY: Float = 1f,
    val baselineShift: Float = 0f,
    val kerning: Float = 0f,
    val tracking: Float = 0f,
) {
    /**
     * Non-destructively transforms the vector text parameters without baking to pixels.
     */
    fun transform(
        deltaX: Float = 0f,
        deltaY: Float = 0f,
        deltaRotation: Float = 0f,
        scaleMultiplierX: Float = 1f,
        scaleMultiplierY: Float = 1f,
    ): TextLayerContent =
        copy(
            x = x + deltaX,
            y = y + deltaY,
            rotationDegrees = (rotationDegrees + deltaRotation) % 360f,
            scaleX = scaleX * scaleMultiplierX,
            scaleY = scaleY * scaleMultiplierY,
        )
}
