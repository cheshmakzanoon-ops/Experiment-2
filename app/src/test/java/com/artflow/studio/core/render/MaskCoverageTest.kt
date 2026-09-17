package com.artflow.studio.core.render

import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.core.pixels.SelectionMask
import com.artflow.studio.domain.model.layer.AdjustmentType
import com.artflow.studio.domain.model.layer.Layer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class MaskCoverageTest {
    private val white = 0xFFFFFFFF.toInt()
    private val layer = Layer(1L, "Ink", 0)

    @Test
    fun softSelectionCoverageIsNotConvertedToAnOpaqueMask() {
        val selection = SelectionMask(4, 1, byteArrayOf(0, 64, 128.toByte(), 255.toByte()))
        val pixels = PixelBuffer.filled(4, 1, white)
        Compositor().applyMask(pixels, selection.toMaskBitmap(), layer)
        assertArrayEquals(intArrayOf(0, 64, 128, 255), pixels.pixels.map { it ushr 24 }.toIntArray())
    }

    @Test
    fun alphaEncodedAndOpaqueGrayscaleMasksHaveIdenticalCoverage() {
        val encoded = PixelBuffer(4, 1, intArrayOf(0x00FFFFFF, 0x40FFFFFF, 0x80FFFFFF.toInt(), white))
        val grayscale = PixelBuffer(4, 1, intArrayOf(0xFF000000.toInt(), 0xFF404040.toInt(), 0xFF808080.toInt(), white))
        val first = PixelBuffer.filled(4, 1, white)
        val second = first.copy()
        Compositor().applyMask(first, encoded, layer)
        Compositor().applyMask(second, grayscale, layer)
        assertArrayEquals(first.pixels, second.pixels)
    }

    @Test
    fun inversionDensityAndLayerAlphaApplyAfterCoverage() {
        val mask = PixelBuffer.filled(1, 1, 0x40FFFFFF)
        val result = PixelBuffer.filled(1, 1, 0x800000FF.toInt())
        Compositor().applyMask(result, mask, layer.copy(maskInverted = true, maskDensity = 0.5f))
        assertEquals(112, result.pixels.single() ushr 24)
    }

    @Test
    fun adjustmentMasksUseTheSameSoftCoverageAsPixelLayers() {
        val inputs =
            listOf(
                Compositor.LayerInput(layer, PixelBuffer.filled(1, 1, 0xFF000000.toInt())),
                Compositor.LayerInput(
                    Layer(2L, "Invert", 1, adjustmentType = AdjustmentType.INVERT),
                    mask = PixelBuffer.filled(1, 1, 0x80FFFFFF.toInt()),
                ),
            )
        val compositor = Compositor()
        try {
            assertEquals(0xFF808080.toInt(), compositor.composite(inputs, 1, 1).pixels.single())
        } finally {
            compositor.release()
        }
    }
}
