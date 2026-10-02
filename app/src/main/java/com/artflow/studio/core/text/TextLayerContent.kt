package com.artflow.studio.core.text

import kotlinx.serialization.Serializable

/**
 * What an editable text layer says and how it looks. The layer's pixels are rendered from this;
 * painting on the layer turns it into ordinary pixels, like rasterising text in Procreate.
 */
@Serializable
data class TextLayerContent(
    val text: String,
    val style: TextLayout.TextStyle,
    val color: Int,
    val x: Float,
    val y: Float,
)
