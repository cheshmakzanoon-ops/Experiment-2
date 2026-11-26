package com.artflow.studio.core.export

import com.artflow.studio.core.canvas.CanvasOperations
import com.artflow.studio.core.color.ColorProfile
import com.artflow.studio.core.color.ColorProfiles
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.core.render.LayerEffectsRenderer
import com.artflow.studio.domain.model.layer.Layer

/** A small sRGB view of the current artwork, independent of export format/settings. */
internal object ArtworkPreview {
    const val MAX_SIDE = 512
    private const val MAX_RENDER_BYTES = 128L * 1024 * 1024
    private const val THUMBNAIL_BYTES = MAX_SIDE * MAX_SIDE * 16L

    /**
     * Refuse before full-resolution compositing when its conservative scratch estimate exceeds
     * current headroom. This is an admission estimate, not a measured total-process peak.
     */
    fun canRender(
        width: Int,
        height: Int,
        layers: List<Layer>,
        availableBytes: Long,
    ): Boolean {
        if (width <= 0 || height <= 0) return false
        var allowance = minOf(availableBytes, MAX_RENDER_BYTES) - THUMBNAIL_BYTES
        if (allowance <= 0) return false
        for (layer in layers) {
            val shadow = layer.effects?.shadow ?: continue
            val padding = LayerEffectsRenderer.shadowPaddingBytes(width, height, shadow)
            if (padding > allowance) return false
            allowance -= padding
        }
        val groups = layers.count { it.isGroup }.toLong()
        val effects = layers.count { it.effects != null || it.filterType != null || it.adjustmentType != null }.toLong()
        val feathers = layers.count { it.hasActiveMask() && it.maskFeather > 0f }.toLong()
        val strokePlanes = if (layers.any { it.strokes.isNotEmpty() }) 2L else 0L
        val planes = 3L + groups + effects * 4L + feathers * 2L + strokePlanes
        return width.toLong() * height <= allowance / (planes * 4L)
    }

    fun encode(
        source: PixelBuffer,
        profile: ColorProfile = ColorProfile.SRGB,
    ): ByteArray {
        val (width, height) = CanvasOperations.fitInside(source.width, source.height, MAX_SIDE, MAX_SIDE)
        val scaled = if (width == source.width && height == source.height) source else source.scaled(width, height)
        val display = ColorProfiles.convert(scaled, profile, ColorProfile.SRGB)
        return PngCodec.encode(display)
    }
}
