package com.artflow.studio.core.animation

import com.artflow.studio.core.color.ColorProfile
import com.artflow.studio.core.color.ColorProfiles
import com.artflow.studio.core.pixels.PixelBuffer

/**
 * Onion-skin ghosts are drawn as silhouettes in the tint colour of their direction (previous or next),
 * so they read as ghosts rather than as a second copy of the artwork.
 */
object OnionSkinTint {
    /**
     * A copy of [source] in which every pixel takes the RGB of [tint] and keeps its own alpha. The alpha
     * of [tint] is ignored; ghost opacity is applied separately. Picker colours are sRGB, so the
     * tint is expressed in [profile] before the renderer applies the document's display conversion.
     * [source] is left unchanged.
     */
    fun silhouette(
        source: PixelBuffer,
        tint: Int,
        profile: ColorProfile = ColorProfile.SRGB,
    ): PixelBuffer {
        val rgb = ColorProfiles.convert(tint, ColorProfile.SRGB, profile) and RGB_MASK
        val out = PixelBuffer(source.width, source.height)
        for (index in source.pixels.indices) {
            out.pixels[index] = (source.pixels[index] and ALPHA_MASK) or rgb
        }
        return out
    }

    private const val ALPHA_MASK = -0x1000000
    private const val RGB_MASK = 0x00FFFFFF
}
