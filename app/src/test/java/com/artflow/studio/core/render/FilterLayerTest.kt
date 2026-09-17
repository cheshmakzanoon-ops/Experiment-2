package com.artflow.studio.core.render

import com.artflow.studio.core.pixels.ImageFilters
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.domain.model.layer.FilterType
import com.artflow.studio.domain.model.layer.Layer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FilterLayerTest {
    private val white = 0xFFFFFFFF.toInt()
    private val red = 0xFFFF0000.toInt()

    private fun render(
        base: PixelBuffer,
        effect: Layer,
        mask: PixelBuffer? = null,
    ): PixelBuffer =
        Compositor().composite(
            listOf(
                Compositor.LayerInput(Layer(1, "Ink", 0), base),
                Compositor.LayerInput(effect, mask = mask),
            ),
            base.width,
            base.height,
        )

    private fun effect(type: FilterType = FilterType.VIGNETTE): Layer = Layer(2, "Effect", 1, filterType = type, filterAmount = 1f)

    @Test
    fun emptyFilterLayerActuallyFiltersTheCompositeBelowIt() {
        val base = PixelBuffer.filled(9, 9, white)
        val expected = base.copy()
        Compositor().applyFilter(expected, FilterType.VIGNETTE, 1f)
        val actual = render(base, effect())
        assertTrue(actual.getSafe(0, 0) != white)
        assertArrayEquals(expected.pixels, actual.pixels)
        assertTrue(base.pixels.all { it == white })
    }

    @Test
    fun filterAmountOpacityVisibilityAndMaskAreIndependentControls() {
        val base = PixelBuffer.filled(9, 9, white)
        val full = render(base, effect())
        assertArrayEquals(base.pixels, render(base, effect().copy(filterAmount = 0f)).pixels)
        assertArrayEquals(base.pixels, render(base, effect().copy(opacity = 0f)).pixels)
        assertArrayEquals(base.pixels, render(base, effect().copy(isVisible = false)).pixels)
        val mask = PixelBuffer.filled(9, 9, 0xFF000000.toInt())
        assertArrayEquals(base.pixels, render(base, effect(), mask).pixels)
        assertArrayEquals(full.pixels, render(base, effect().copy(maskInverted = true), mask).pixels)
        val partial = render(base, effect().copy(opacity = 0.5f))
        assertEquals(ImageFilters.lerpArgb(white, full.pixels[0], 0.5f), partial.pixels[0])
    }

    @Test
    fun clippedBlurCannotExpandOrReduceItsBaseAlpha() {
        val base = PixelBuffer(9, 9)
        base.setSafe(4, 4, red)
        val result = render(base, effect(FilterType.GAUSSIAN_BLUR).copy(isClippingMask = true))
        assertArrayEquals(base.pixels.map { it ushr 24 }.toIntArray(), result.pixels.map { it ushr 24 }.toIntArray())
    }

    @Test
    fun partialBlurUsesPremultipliedMixingAtTransparentEdges() {
        val base = PixelBuffer(9, 9)
        base.setSafe(4, 4, red)
        val blurred = render(base, effect(FilterType.GAUSSIAN_BLUR).copy(filterAmount = 0.1f, opacity = 0.5f))
        assertTrue(blurred.pixels.indices.any { it != 4 * 9 + 4 && (blurred.pixels[it] ushr 24) > 0 })
        assertTrue(blurred.pixels.filter { (it ushr 24) > 0 }.all { (it and 0x00FFFFFF) == 0x00FF0000 })
    }

    @Test
    fun interpolatingTransparentPixelsDoesNotCreateDarkFringes() {
        assertEquals(0x80FF0000.toInt(), ImageFilters.lerpArgb(0, red, 0.5f))
        assertEquals(0x8000FF00.toInt(), ImageFilters.lerpArgb(0x000000FF, 0xFF00FF00.toInt(), 0.5f))
        assertEquals(red, ImageFilters.lerpArgb(0, red, 1f))
    }

    @Test
    fun filterLayersWithTheirOwnPixelsKeepExistingPerLayerSemantics() {
        val base = PixelBuffer.filled(9, 9, white)
        val own = PixelBuffer.filled(9, 9, red)
        val expected = own.copy()
        Compositor().applyFilter(expected, FilterType.VIGNETTE, 1f)
        val result =
            Compositor().composite(
                listOf(
                    Compositor.LayerInput(Layer(1, "Ink", 0), base),
                    Compositor.LayerInput(effect(), own),
                ),
                9,
                9,
            )
        assertArrayEquals(expected.pixels, result.pixels)
    }
}
