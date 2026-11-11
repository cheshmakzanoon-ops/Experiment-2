package com.artflow.studio.domain.model.brush

import kotlinx.serialization.Serializable

/** A second brush drawn along the same path, whose marks combine with the main brush's stroke. */
@Serializable
data class DualBrush(
    val params: BrushParams,
    val mode: Mode = Mode.MULTIPLY,
    /** Source mode when a reference compound brush currently uses an approximate ArtFlow combine mode. */
    val sourceBlendMode: Int? = null,
) {
    @Serializable
    enum class Mode(
        val displayName: String,
    ) {
        /** Paint only where both brushes reach: the second brush textures the first. */
        MULTIPLY("Multiply"),

        /** The second brush cuts its marks out of the first. */
        SUBTRACT("Subtract"),

        /** Both brushes paint. */
        ADD("Add"),

        /** Combine both brushes with normal alpha blending. */
        COMBINE("Combine"),

        /** High-contrast intense blend between primary and secondary brush dabs. */
        INTENSE_BLEND("Intense Blend"),

        /** Secondary brush modulates and displaces primary dab coverage. */
        DISPLACE("Displace"),
    }
}
