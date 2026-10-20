package com.artflow.studio.core.render

import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.domain.model.brush.BrushParams
import com.artflow.studio.domain.model.brush.Stroke
import com.artflow.studio.domain.model.brush.StrokePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pressure bleed: firmer pressure takes up more of the canvas colour, separately from Wetness. */
class BleedTest {
    private val width = 40
    private val height = 20
    private val centre = 10 * width + 20
    private val red = 0xFFFF0000.toInt()
    private val blue = 0xFF0000FF.toInt()

    /**
     * A blue stroke at constant [pressure] over a red canvas. Size and opacity do not respond to pressure
     * here, so only the colour pickup differs between runs.
     */
    private fun centrePixel(
        params: BrushParams,
        pressure: Float,
    ): Int {
        val canvas = PixelBuffer(width, height).also { it.fill(red) }
        val stroke =
            Stroke(
                id = 3L,
                points = listOf(StrokePoint(4.5f, 10f, pressure, timestamp = 0L), StrokePoint(36.5f, 10f, pressure, timestamp = 16L)),
                brushParams = params,
                layerId = 1,
                color = blue,
            )
        return canvas.also { StrokeRasterizer().draw(it, stroke) }.pixels[centre]
    }

    private val steady = BrushParams(size = 8f, spacing = 0.05f, pressureToSize = 0f, pressureToOpacity = 0f)

    @Test
    fun aLightTouchLeavesTheBrushsOwnColour() {
        assertEquals(blue, centrePixel(steady.copy(bleed = 1f), pressure = 0f))
    }

    @Test
    fun firmerPressureMixesInMoreOfTheCanvas() {
        val light = blueOf(centrePixel(steady.copy(bleed = 1f), pressure = 0.5f))
        val firm = blueOf(centrePixel(steady.copy(bleed = 1f), pressure = 1f))
        assertTrue("Bleed at half pressure keeps more blue than full pressure: $light vs $firm", light > firm + 40)
        assertEquals("Full pressure with full bleed takes up 60% of the canvas", 102, firm)
    }

    @Test
    fun bleedIsNotWetness() {
        // Wetness picks up at any pressure; bleed needs pressure. At a light touch only Wetness changes the colour.
        val wetAtLightTouch = centrePixel(steady.copy(wetMix = 1f), pressure = 0f)
        val bleedAtLightTouch = centrePixel(steady.copy(bleed = 1f), pressure = 0f)
        assertTrue("Wetness picks up at a light touch", blueOf(wetAtLightTouch) < 200)
        assertEquals("Bleed does not pick up at a light touch", blue, bleedAtLightTouch)
    }

    @Test
    fun bleedAddsToWetnessAtTheSameRate() {
        // Half Wetness plus half Bleed at full pressure makes the same mix as full Wetness.
        val combined = centrePixel(steady.copy(wetMix = 0.5f, bleed = 0.5f), pressure = 1f)
        val wetOnly = centrePixel(steady.copy(wetMix = 1f), pressure = 1f)
        assertEquals(wetOnly, combined)
    }

    private fun blueOf(argb: Int) = argb and 0xFF
}
