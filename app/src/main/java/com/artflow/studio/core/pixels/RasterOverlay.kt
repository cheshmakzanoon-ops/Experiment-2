package com.artflow.studio.core.pixels

/** Composite an independently rendered shape or glyph run without erasing its backdrop. */
object RasterOverlay {
    /** Returns true only when a pixel changed; empty overlays must not create undo entries. */
    fun draw(
        target: PixelBuffer,
        overlay: PixelBuffer,
        selection: SelectionMask? = null,
        alphaLocked: Boolean = false,
    ): Boolean {
        require(target.width == overlay.width && target.height == overlay.height) { "Overlay dimensions must match the layer" }
        require(selection == null || (selection.width == target.width && selection.height == target.height)) {
            "Selection dimensions must match the layer"
        }
        var changed = false
        for (i in target.pixels.indices) {
            val coverage = selection?.alphaAt(i) ?: 1f
            val source = Channels.scaleAlpha(overlay.pixels[i], coverage)
            if ((source ushr 24) == 0) continue
            val before = target.pixels[i]
            val after = if (alphaLocked) BlendModes.sourceAtop(before, source) else BlendModes.sourceOver(before, source)
            if (after != before) {
                target.pixels[i] = after
                changed = true
            }
        }
        return changed
    }
}
