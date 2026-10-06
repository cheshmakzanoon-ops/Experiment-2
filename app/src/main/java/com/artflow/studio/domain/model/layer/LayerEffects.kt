package com.artflow.studio.domain.model.layer

import kotlinx.serialization.Serializable

/**
 * Non-destructive effects drawn around a layer's own pixels when it is composited. The pixels are
 * never changed, so painting on the layer keeps its outline and shadow up to date.
 */
@Serializable
data class LayerEffects(
    val outline: Outline? = null,
    val shadow: Shadow? = null,
) {
    /** A solid border of [width] pixels around everything painted on the layer. */
    @Serializable
    data class Outline(
        val color: Int = 0xFF000000.toInt(),
        val width: Float = 4f,
    )

    /** A soft copy of the layer's silhouette, offset by [distance] pixels toward [angleDegrees]. */
    @Serializable
    data class Shadow(
        val color: Int = 0xFF000000.toInt(),
        val opacity: Float = 0.5f,
        /** Clockwise from the +x axis, so the default 45 degrees falls down and to the right. */
        val angleDegrees: Float = 45f,
        val distance: Float = 12f,
        val blur: Float = 8f,
    )

    val isEmpty: Boolean get() = outline == null && shadow == null

    /** The same effects with every value finite and within its range. */
    fun normalized(): LayerEffects =
        LayerEffects(
            outline = outline?.let { it.copy(width = it.width.finiteIn(1f, MAX_OUTLINE_WIDTH)) },
            shadow =
                shadow?.let {
                    it.copy(
                        opacity = it.opacity.finiteIn(0f, 1f),
                        angleDegrees = it.angleDegrees.finiteIn(-FULL_TURN, FULL_TURN),
                        distance = it.distance.finiteIn(0f, MAX_SHADOW_DISTANCE),
                        blur = it.blur.finiteIn(0f, MAX_SHADOW_BLUR),
                    )
                },
        )

    private fun Float.finiteIn(
        low: Float,
        high: Float,
    ): Float = if (isFinite()) coerceIn(low, high) else low

    companion object {
        const val MAX_OUTLINE_WIDTH = 64f
        const val MAX_SHADOW_DISTANCE = 256f
        const val MAX_SHADOW_BLUR = 64f
        private const val FULL_TURN = 360f
    }
}
