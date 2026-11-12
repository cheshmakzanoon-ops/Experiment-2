package com.artflow.studio.core.text

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

/**
 * What an editable text layer says and how it looks. The layer's pixels are rendered from this;
 * painting on the layer turns it into ordinary pixels, like rasterising text in Procreate.
 * Non-destructive vector transformation preserves rotation, scale, tracking, kerning, and baseline shift.
 */
@Serializable
@OptIn(ExperimentalSerializationApi::class)
data class TextLayerContent(
    val text: String,
    val style: TextLayout.TextStyle,
    val color: Int,
    val x: Float,
    val y: Float,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val rotationDegrees: Float = 0f,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val scaleX: Float = 1f,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val scaleY: Float = 1f,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val baselineShift: Float = 0f,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val kerning: Float = 0f,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val tracking: Float = 0f,
) {
    /**
     * Returns true if any non-default vector typography transformation is present.
     */
    fun hasTransform(): Boolean =
        rotationDegrees != 0f ||
            scaleX != 1f ||
            scaleY != 1f ||
            baselineShift != 0f ||
            kerning != 0f ||
            tracking != 0f

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
