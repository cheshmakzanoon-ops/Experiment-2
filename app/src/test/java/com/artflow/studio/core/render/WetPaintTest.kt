package com.artflow.studio.core.render

import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.domain.model.brush.BrushParams
import com.artflow.studio.domain.model.brush.Stroke
import com.artflow.studio.domain.model.brush.StrokePoint
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WetPaintTest {
    private val width = 160
    private val height = 40

    /** Left half deep blue, right half bare, so a stroke runs out of the blue into nothing. */
    private fun layer(): PixelBuffer =
        PixelBuffer(width, height).also { buffer ->
            for (y in 0 until height) for (x in 0 until width / 2) buffer.pixels[y * width + x] = 0xFF1030C0.toInt()
        }

    private fun paint(params: BrushParams): PixelBuffer {
        val stroke =
            Stroke(
                id = 77L,
                points = (0..28).map { i -> StrokePoint(x = 10f + i * 5f, y = 20f, pressure = 1f, timestamp = i * 8L) },
                brushParams = params,
                layerId = 0L,
                color = 0xFFF0E020.toInt(),
                timestamp = 0L,
            )
        return layer().also { StrokeRasterizer().draw(it, stroke) }
    }

    private fun blue(pixel: Int) = pixel and 0xFF

    private fun alpha(pixel: Int) = pixel ushr 24

    private val base = BrushParams(size = 12f, spacing = 0.1f, pressureToSize = 0f, pressureToOpacity = 0f, wetMix = 0.8f)

    @Test
    fun noDilutionOrPullPaintsExactlyAsBefore() {
        assertArrayEquals(paint(base).pixels, paint(base.copy(dilution = 0f, pull = 0f)).pixels)
    }

    @Test
    fun pullDragsThePickedUpColourPastWhereItWasPickedUp() {
        val x = 120
        val dry = paint(base)
        val pulled = paint(base.copy(pull = 0.9f))
        // Out on the bare half there is nothing to pick up, so only pull can carry blue there.
        assertTrue(blue(pulled.getSafe(x, 20)) > blue(dry.getSafe(x, 20)) + 40)
    }

    @Test
    fun dilutionThinsThePaint() {
        val x = 120
        val thick = paint(base.copy(wetMix = 0f))
        val watery = paint(base.copy(wetMix = 0f, dilution = 1f))
        assertTrue(alpha(watery.getSafe(x, 20)) < alpha(thick.getSafe(x, 20)))
    }

    @Test
    fun dilutionPicksUpTheLayerEvenWithoutWetMix() {
        val x = 40
        val thick = paint(base.copy(wetMix = 0f))
        val watery = paint(base.copy(wetMix = 0f, dilution = 1f))
        assertTrue(blue(watery.getSafe(x, 20)) > blue(thick.getSafe(x, 20)))
    }

    @Test
    fun perStrokeColourJitterKeepsOneColourAlongTheStroke() {
        val jitter = BrushParams(size = 12f, spacing = 0.3f, pressureToSize = 0f, pressureToOpacity = 0f, hueJitter = 1f)

        fun hueSpread(params: BrushParams): Int {
            val painted = PixelBuffer(width, height).also { canvas -> StrokeRasterizer().draw(canvas, strokeOf(params)) }
            val reds = (40..120).map { painted.getSafe(it, 20) }.filter { alpha(it) >= 200 }.map { (it shr 16) and 0xFF }
            return reds.max() - reds.min()
        }
        assertTrue("Per-dab jitter varies the colour", hueSpread(jitter) > 20)
        assertTrue("Per-stroke jitter keeps one colour", hueSpread(jitter.copy(colorJitterPerStroke = true)) <= 2)
    }

    private fun strokeOf(params: BrushParams): Stroke =
        Stroke(
            id = 77L,
            points = (0..28).map { i -> StrokePoint(x = 10f + i * 5f, y = 20f, pressure = 1f, timestamp = i * 8L) },
            brushParams = params,
            layerId = 0L,
            color = 0xFFF0E020.toInt(),
            timestamp = 0L,
        )
}
