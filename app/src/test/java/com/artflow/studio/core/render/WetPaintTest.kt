package com.artflow.studio.core.render

import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.domain.model.brush.BrushParams
import com.artflow.studio.domain.model.brush.RenderingMode
import com.artflow.studio.domain.model.brush.Stroke
import com.artflow.studio.domain.model.brush.StrokePoint
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

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
    fun wetBlurPicksUpPaintFromAroundTheDab() {
        // A blue band above the stroke's centre line: a sharp pickup only ever samples the line itself.
        fun painted(params: BrushParams): PixelBuffer {
            val layer = PixelBuffer(width, height)
            for (y in 6..9) for (x in 0 until width) layer.pixels[y * width + x] = 0xFF1030C0.toInt()
            return layer.also { StrokeRasterizer().draw(it, strokeOf(params)) }
        }
        val wide = base.copy(size = 24f)
        val sharp = painted(wide)
        val blurred = painted(wide.copy(wetBlur = 1f))
        assertTrue(blue(blurred.getSafe(100, 20)) > blue(sharp.getSafe(100, 20)) + 3)
        assertArrayEquals(sharp.pixels, painted(wide.copy(wetBlur = 0f)).pixels)
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

    @Test
    fun consecutiveStrokesGetDifferentPerStrokeColours() {
        val firsts = (1L..10L).map { Random(strokeColorSeed(it)).nextFloat() }
        assertTrue("Neighbouring stroke ids must not share a colour ($firsts)", firsts.max() - firsts.min() > 0.3f)
    }

    @Test
    fun countJitterStampsBetweenOneAndCountCopies() {
        val many = BrushParams(size = 12f, spacing = 0.3f, pressureToSize = 0f, pressureToOpacity = 0f, count = 4)

        fun stamps(params: BrushParams): Int {
            val rasterizer = StrokeRasterizer()
            rasterizer.draw(PixelBuffer(width, height), strokeOf(params))
            return rasterizer.lastDabCount
        }
        val full = stamps(many)
        val jittered = stamps(many.copy(countJitter = 1f))
        assertTrue("Jitter leaves out some copies ($jittered of $full)", jittered < full)
        assertTrue("Every dab keeps at least one copy ($jittered of $full)", jittered >= full / 4)
    }

    @Test
    fun renderingModesKeepOldBrushesAndIntensifyPaint() {
        val soft = BrushParams(size = 12f, spacing = 0.3f, pressureToSize = 0f, pressureToOpacity = 0f, opacity = 0.5f, flow = 0.4f)

        fun painted(params: BrushParams) = PixelBuffer(width, height).also { StrokeRasterizer().draw(it, strokeOf(params)) }
        assertArrayEquals(painted(soft).pixels, painted(soft.copy(renderingMode = RenderingMode.LIGHT_GLAZE)).pixels)
        val built = soft.copy(buildUp = true)
        assertArrayEquals(painted(built).pixels, painted(built.copy(renderingMode = RenderingMode.UNIFORM_BLENDING)).pixels)
        val light = alpha(painted(soft).getSafe(80, 20))
        val intense = alpha(painted(soft.copy(renderingMode = RenderingMode.INTENSE_GLAZE)).getSafe(80, 20))
        assertTrue("Intense glaze lays more paint ($intense vs $light)", intense > light)
    }

    @Test
    fun tipSharpnessThinsTheTaper() {
        val tapered =
            BrushParams(size = 16f, spacing = 0.1f, pressureToSize = 0f, pressureToOpacity = 0f, taperStart = 0.5f, taperEnd = 0.5f)

        fun inked(params: BrushParams) =
            PixelBuffer(width, height).also { StrokeRasterizer().draw(it, strokeOf(params)) }.pixels.count { alpha(it) > 0 }
        assertArrayEquals(
            PixelBuffer(width, height).also { StrokeRasterizer().draw(it, strokeOf(tapered)) }.pixels,
            PixelBuffer(width, height).also { StrokeRasterizer().draw(it, strokeOf(tapered.copy(tipSharpness = 0f))) }.pixels,
        )
        assertTrue(inked(tapered.copy(tipSharpness = 1f)) < inked(tapered))
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
