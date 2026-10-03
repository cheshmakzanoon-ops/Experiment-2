package com.artflow.studio.domain.model.brush

import kotlinx.serialization.Serializable

/** A second brush drawn along the same path, whose marks combine with the main brush's stroke. */
@Serializable
data class DualBrush(
    val params: BrushParams,
    val mode: Mode = Mode.MULTIPLY,
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
    }
}
