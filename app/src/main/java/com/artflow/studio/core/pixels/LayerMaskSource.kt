package com.artflow.studio.core.pixels

import kotlin.math.hypot
import kotlin.math.roundToInt

/** Explicit mask sources. Alpha is copied, not removed from the layer's original pixels. */
enum class LayerMaskSource(
    val label: String,
) {
    REVEAL_ALL("Reveal all"),
    HIDE_ALL("Hide all"),
    SELECTION("Current selection"),
    LAYER_ALPHA("Copy layer transparency"),
    HORIZONTAL("Reveal toward right"),
    VERTICAL("Reveal toward bottom"),
    RADIAL("Reveal center"),
}

/** Canonical opaque grayscale masks, independent of Android graphics or device density. */
object LayerMaskFactory {
    fun create(
        source: LayerMaskSource,
        width: Int,
        height: Int,
        pixels: PixelBuffer? = null,
        selection: SelectionMask? = null,
    ): PixelBuffer {
        if (source == LayerMaskSource.LAYER_ALPHA) {
            require(pixels != null && pixels.width == width && pixels.height == height) { "Layer pixels must match mask dimensions" }
        }
        if (source == LayerMaskSource.SELECTION) {
            require(selection != null && selection.width == width && selection.height == height) { "A canvas-sized selection is required" }
        }
        val out = PixelBuffer(width, height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val amount = coverage(source, x, y, width, height, pixels, selection)
                out.pixels[y * width + x] = 0xFF000000.toInt() or (amount shl 16) or (amount shl 8) or amount
            }
        }
        return out
    }

    private fun coverage(
        source: LayerMaskSource,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        pixels: PixelBuffer?,
        selection: SelectionMask?,
    ): Int =
        when (source) {
            LayerMaskSource.REVEAL_ALL -> 255
            LayerMaskSource.HIDE_ALL -> 0
            LayerMaskSource.SELECTION -> checkNotNull(selection).coverageAt(x, y)
            LayerMaskSource.LAYER_ALPHA -> checkNotNull(pixels).pixels[y * width + x] ushr 24
            LayerMaskSource.HORIZONTAL -> if (width == 1) 255 else (255f * x / (width - 1)).roundToInt()
            LayerMaskSource.VERTICAL -> if (height == 1) 255 else (255f * y / (height - 1)).roundToInt()
            LayerMaskSource.RADIAL -> {
                val nx = if (width == 1) 0f else (2f * x / (width - 1) - 1f)
                val ny = if (height == 1) 0f else (2f * y / (height - 1) - 1f)
                (255f * (1f - hypot(nx, ny)).coerceIn(0f, 1f)).roundToInt()
            }
        }
}
