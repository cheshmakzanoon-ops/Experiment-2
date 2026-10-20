package com.artflow.studio.core.render

import com.artflow.studio.core.pixels.IntBounds
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
        originX: Int = 0,
        originY: Int = 0,
        region: IntBounds? = null,
        ownedBase: Boolean = false,
    ): PixelBuffer {
        if (region != null && historicalStrokes.isEmpty() && fitsRegion(base, selection, width, height, region)) {
            return renderRegion(base, incomingStrokes, width, height, alphaLock, selection, region, ownedBase)
        }
        val result = PixelBuffer(width, height)
        if (base != null) result.drawInto(base, 0, 0)
        val renderer = StrokeRasterizer(originX, originY)
        try {
            historicalStrokes.forEach { renderer.draw(result, it, alphaLock = alphaLock) }
            incomingStrokes.forEach { renderer.draw(result, it, alphaLock = alphaLock, mask = selection) }
        } finally {
            renderer.release()
        }
        return result
    }

    private fun fitsRegion(
        base: PixelBuffer?,
        selection: SelectionMask?,
        width: Int,
        height: Int,
        region: IntBounds,
    ): Boolean =
        !region.isEmpty &&
            region.left >= 0 &&
            region.top >= 0 &&
            region.right < width &&
            region.bottom < height &&
            (base == null || (base.width == width && base.height == height)) &&
            (selection == null || (selection.width == width && selection.height == height))

    /**
     * Same pixels as a full render when [region] covers everything the strokes can reach: only that
     * rectangle is rasterised, the rest is copied from [base]. When [ownedBase] is set, [base] is the
     * caller's private copy and is painted in place rather than copied again.
     */
    private fun renderRegion(
        base: PixelBuffer?,
        incomingStrokes: List<Stroke>,
        width: Int,
        height: Int,
        alphaLock: Boolean,
        selection: SelectionMask?,
        region: IntBounds,
        ownedBase: Boolean,
    ): PixelBuffer {
        val result = if (ownedBase && base != null) base else base?.copy() ?: PixelBuffer(width, height)
        val part = result.crop(region)
        val mask = selection?.crop(region)
        val renderer = StrokeRasterizer(region.left, region.top)
        val dx = -region.left.toFloat()
        val dy = -region.top.toFloat()
        try {
            incomingStrokes.forEach { stroke ->
                val shifted = stroke.copy(points = stroke.points.map { it.copy(x = it.x + dx, y = it.y + dy) })
                renderer.draw(part, shifted, alphaLock = alphaLock, mask = mask)
            }
        } finally {
            renderer.release()
        }
        for (y in 0 until region.height) {
            System.arraycopy(part.pixels, y * region.width, result.pixels, (region.top + y) * width + region.left, region.width)
        }
        return result
    }
}
