package com.artflow.studio.core.export

import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.core.render.Compositor
import com.artflow.studio.domain.model.layer.Layer

/** Independent coverage for formats that keep editable masks separate from the artwork's alpha. */
object LayerMaskExport {
    /**
     * Captures the compositor's effective mask into white pixels whose alpha is coverage. Density,
     * inversion and feather are evaluated on the mask alone, so obscured artwork stays recoverable.
     * A disabled mask still keeps its coverage; its enabled state belongs to the layer record.
     */
    fun capture(
        renderer: Compositor,
        layer: Layer,
        mask: PixelBuffer,
        width: Int,
        height: Int,
    ): PixelBuffer =
        PixelBuffer.filled(width, height, -1).also { coverage ->
            renderer.applyMask(coverage, mask, layer)
        }

    /**
     * True when the output interpolates samples, rather than only placing or cropping them.
     * Resampling paint and a mask separately can reveal hidden colour, so editable resized
     * masks need a rendered appearance layer. Integer placement alone keeps their appearance.
     */
    fun requiresResampling(
        coverage: PixelBuffer,
        width: Int,
        height: Int,
        fit: FitMode,
    ): Boolean {
        val placement = ExportNaming.applyFit(coverage.width, coverage.height, width, height, fit)
        return placement.drawWidth != coverage.width || placement.drawHeight != coverage.height
    }

    /** Resizes coverage using the same placement as the layer, without colour-profile conversion. */
    fun resize(
        coverage: PixelBuffer,
        width: Int,
        height: Int,
        fit: FitMode,
    ): PixelBuffer {
        if (coverage.width == width && coverage.height == height) return coverage
        if (fit == FitMode.STRETCH) return coverage.scaled(width, height)
        val placement = ExportNaming.applyFit(coverage.width, coverage.height, width, height, fit)
        val resized = coverage.scaled(placement.drawWidth, placement.drawHeight)
        val out = PixelBuffer.filled(placement.outputWidth, placement.outputHeight, -1)
        // Copy coverage, rather than alpha-compositing white onto white (which would erase the mask).
        for (y in 0 until resized.height) {
            for (x in 0 until resized.width) {
                out.setSafe(x + placement.offsetX, y + placement.offsetY, resized.getUnchecked(x, y))
            }
        }
        return out
    }

    fun psd(
        coverage: PixelBuffer,
        enabled: Boolean,
    ): PsdCodec.PsdMask =
        PsdCodec.PsdMask(
            left = 0,
            top = 0,
            width = coverage.width,
            height = coverage.height,
            coverage = ByteArray(coverage.size) { (coverage.pixels[it] ushr 24).toByte() },
            outside = 255,
            isEnabled = enabled,
        )
}
