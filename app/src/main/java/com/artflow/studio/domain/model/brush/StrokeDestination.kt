package com.artflow.studio.domain.model.brush

/** A gesture captures its destination at pointer-down; changing tools cannot redirect it. */
enum class StrokeDestination {
    LAYER,
    MASK_REVEAL,
    MASK_HIDE,
    ;

    val isMask: Boolean get() = this != LAYER

    fun color(
        ink: Int,
        inverted: Boolean = false,
    ): Int =
        when (this) {
            LAYER -> ink
            MASK_REVEAL -> if (inverted) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
            MASK_HIDE -> if (inverted) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
        }
}
