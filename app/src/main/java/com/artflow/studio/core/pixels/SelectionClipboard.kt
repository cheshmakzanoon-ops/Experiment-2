package com.artflow.studio.core.pixels

/**
 * Pixel operations behind Cut, Copy and Paste: a null or mismatched [SelectionMask] means the whole layer.
 * A present but empty mask selects nothing, so Clear, Cut and Fill leave the layer unchanged.
 */
object SelectionClipboard {
    private fun usable(
        mask: SelectionMask?,
        pixels: PixelBuffer,
    ): SelectionMask? = mask?.takeIf { it.width == pixels.width && it.height == pixels.height }

    /** Copy of [source] keeping only the selected coverage. */
    fun extract(
        source: PixelBuffer,
        selection: SelectionMask?,
    ): PixelBuffer {
        val mask = usable(selection, source) ?: return source.copy()
        return PixelBuffer(source.width, source.height).also { out ->
            for (i in out.pixels.indices) out.pixels[i] = Channels.scaleAlpha(source.pixels[i], mask.alphaAt(i))
        }
    }

    /** Paints [color] over [target], blended by the selection's coverage. */
    fun fill(
        target: PixelBuffer,
        selection: SelectionMask?,
        color: Int,
    ) {
        val mask = usable(selection, target) ?: return target.fill(color)
        if (!mask.isActive()) return
        for (i in target.pixels.indices) target.pixels[i] = ImageFilters.lerpArgb(target.pixels[i], color, mask.alphaAt(i))
    }

    /** Removes the selected coverage from [target] in place. */
    fun erase(
        target: PixelBuffer,
        selection: SelectionMask?,
    ) {
        val mask = usable(selection, target) ?: return target.clear()
        if (!mask.isActive()) return
        for (i in target.pixels.indices) target.pixels[i] = Channels.scaleAlpha(target.pixels[i], 1f - mask.alphaAt(i))
    }
}
