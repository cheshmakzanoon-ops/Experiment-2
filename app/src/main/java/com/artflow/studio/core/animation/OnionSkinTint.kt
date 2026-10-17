package com.artflow.studio.core.animation

import com.artflow.studio.core.pixels.PixelBuffer

/**
 * Colours for onion-skin ghosts. Earlier frames use [AnimationSettings.onionSkinPreviousColor] and
 * later frames use [AnimationSettings.onionSkinNextColor]; the canvas view tints each ghost with
 * its colour before drawing it at the alpha [AnimationTimeline.onionGhosts] gives.
 */
object OnionSkinTint {
    /** Swatches offered for the before and after colours, as opaque `0xAARRGGBB` values. */
    val COLOUR_CHOICES: List<Int> =
        listOf(
            0xFFFF0000.toInt(),
            0xFF00FF00.toInt(),
            0xFF0000FF.toInt(),
            0xFFFFC107.toInt(),
            0xFFBB6BD9.toInt(),
            0xFF00BCD4.toInt(),
            0xFFFFFFFF.toInt(),
        )

    /**
     * A copy of [frame] in which every pixel has the RGB of [colour] and keeps its own alpha, so the
     * ghost reads as a silhouette of the drawing in that colour. The alpha of [colour] is ignored.
     */
    fun tint(
        frame: PixelBuffer,
        colour: Int,
    ): PixelBuffer {
        val rgb = colour and RGB_MASK
        val alphaMask = ALPHA_MASK
        val tinted = IntArray(frame.pixels.size) { index -> (frame.pixels[index] and alphaMask) or rgb }
        return PixelBuffer(frame.width, frame.height, tinted)
    }

    private const val RGB_MASK = 0x00FFFFFF
    private val ALPHA_MASK = 0xFF000000.toInt()
}
