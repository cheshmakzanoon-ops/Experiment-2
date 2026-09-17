package com.artflow.studio.core.export

import com.artflow.studio.core.pixels.Channels
import com.artflow.studio.core.pixels.IntBounds
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.core.pixels.SelectionMask

/** Resolves one shared crop rectangle, so animation frames never change size between samples. */
object ExportRegion {
    fun resolve(
        frames: List<PixelBuffer>,
        selection: SelectionMask?,
        area: ExportArea,
    ): IntBounds? =
        when (area) {
            ExportArea.SELECTION -> requireNotNull(selection?.bounds()) { "Select some pixels before exporting a selection" }
            ExportArea.CONTENT_BOUNDS ->
                requireNotNull(frames.mapNotNull { it.contentBounds() }.reduceOrNull { a, b -> a.union(b) }) {
                    "There is no visible artwork to trim"
                }
            else -> null
        }

    /** Selection coverage clips alpha, not colour; feathered edges remain soft after cropping. */
    fun apply(
        source: PixelBuffer,
        selection: SelectionMask?,
        area: ExportArea,
        bounds: IntBounds?,
    ): PixelBuffer {
        if (area != ExportArea.SELECTION) return bounds?.let { source.crop(it) } ?: source
        val mask = requireNotNull(selection)
        require(mask.width == source.width && mask.height == source.height) { "Selection dimensions do not match the canvas" }
        val region = requireNotNull(bounds).intersect(IntBounds(0, 0, source.width - 1, source.height - 1))
        require(!region.isEmpty) { "The selection is outside the canvas" }
        val output = source.crop(region)
        for (y in 0 until output.height) {
            for (x in 0 until output.width) {
                val index = y * output.width + x
                output.pixels[index] = Channels.scaleAlpha(output.pixels[index], mask.coverageAt(x + region.left, y + region.top) / 255f)
            }
        }
        return output
    }
}
