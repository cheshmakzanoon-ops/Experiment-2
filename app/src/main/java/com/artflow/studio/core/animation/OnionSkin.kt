package com.artflow.studio.core.animation

import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.domain.model.animation.AnimationSettings
import kotlin.math.abs

/** How neighbouring frames are ghosted behind the frame being drawn. */
object OnionSkin {
    /** Ghost opacity for the frame [offset] frames away: nearer frames show more strongly. */
    fun opacity(
        settings: AnimationSettings,
        offset: Int,
    ): Float = if (offset == 0) 0f else settings.onionSkinOpacity.coerceIn(0f, 1f) / abs(offset)

    /** Opacity of the frame being drawn while ghosts show: see-through when it blends with them. */
    fun primaryOpacity(settings: AnimationSettings): Float = if (settings.blendPrimaryFrame) BLENDED_PRIMARY else 1f

    /** The tint for a ghost [offset] frames away, or null when ghosts keep their own colours. */
    fun tint(
        settings: AnimationSettings,
        offset: Int,
    ): Int? =
        when {
            !settings.onionSkinTinted || offset == 0 -> null
            offset < 0 -> settings.onionSkinPreviousColor
            else -> settings.onionSkinNextColor
        }

    /** Recolours [frame] in place with [color], keeping each pixel's coverage. */
    fun tinted(
        frame: PixelBuffer,
        color: Int,
    ): PixelBuffer {
        val rgb = color and 0x00FFFFFF
        val pixels = frame.pixels
        for (i in pixels.indices) {
            val alpha = pixels[i] ushr 24
            if (alpha != 0) pixels[i] = (alpha shl 24) or rgb
        }
        return frame
    }

    private const val BLENDED_PRIMARY = 0.5f
}
