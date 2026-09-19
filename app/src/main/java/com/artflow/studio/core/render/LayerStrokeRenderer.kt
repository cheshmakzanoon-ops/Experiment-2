package com.artflow.studio.core.render

import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.core.pixels.SelectionMask
import com.artflow.studio.domain.model.brush.Stroke

/**
 * Builds raw layer pixels for both provisional brush previews and committed strokes.
 * Filters, clipping, layer opacity and masks are deliberately left to the layer compositor.
 * Historical vector strokes are baked before new paint, never replayed over newer edits.
 */
object LayerStrokeRenderer {
    fun render(
        base: PixelBuffer?,
        historicalStrokes: List<Stroke>,
        incomingStrokes: List<Stroke>,
        width: Int,
        height: Int,
        alphaLock: Boolean,
        selection: SelectionMask?,
    ): PixelBuffer {
        val result = PixelBuffer(width, height)
        if (base != null) result.drawInto(base, 0, 0)
        val renderer = StrokeRasterizer()
        try {
            historicalStrokes.forEach { renderer.draw(result, it, alphaLock = alphaLock) }
            // Old saved vectors retain dry replay. New paint is baked into pixels, so later
            // load/export never reinterprets its wet-mix amount using different semantics.
            incomingStrokes.forEach { renderer.draw(result, it, alphaLock = alphaLock, mask = selection, enableWetMix = true) }
        } finally {
            renderer.release()
        }
        return result
    }
}
