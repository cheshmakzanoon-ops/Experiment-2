package com.artflow.studio.core.render

import com.artflow.studio.core.pixels.IntBounds
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.domain.model.brush.BrushParams
import com.artflow.studio.domain.model.brush.ColorDynamics
import com.artflow.studio.domain.model.brush.DualBrush
import com.artflow.studio.domain.model.brush.PressureMapping
import com.artflow.studio.domain.model.brush.Stroke
import com.artflow.studio.domain.model.brush.StrokePoint
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pressure → Flow must reach pixels in painting, erasing, compound brushes and every preview path. */
class PressureFlowRenderingTest {
    private val width = 96
    private val height = 64
    private val plain = BrushParams(size = 16f, spacing = 0.2f, pressureToSize = 0f, pressureToOpacity = 0f)

    private fun stroke(
        params: BrushParams,
        pressure: Float,
    ) = Stroke(
        id = 551L,
        points = listOf(StrokePoint(12f, 30f, pressure, timestamp = 0), StrokePoint(80f, 30f, pressure, timestamp = 80)),
        brushParams = params,
        layerId = 1L,
        color = 0xFFCC4411.toInt(),
    )

    private fun render(
        stroke: Stroke,
        fill: Int = 0,
    ): PixelBuffer = PixelBuffer.filled(width, height, fill).also { StrokeRasterizer().draw(it, stroke) }

    @Test
    fun pressureFlowChangesPaintDensityWithoutChangingBrushSize() {
        val params = plain.copy(pressureToFlow = 1f)
        val low = render(stroke(params, 0.25f))
        val high = render(stroke(params, 1f))
        val middle = 30 * width + 45
        assertEquals(64, low.pixels[middle] ushr 24)
        assertEquals(255, high.pixels[middle] ushr 24)
        assertEquals(high.pixels.count { it ushr 24 > 0 }, low.pixels.count { it ushr 24 > 0 })
        assertTrue(render(stroke(params, 0f)).pixels.all { it ushr 24 == 0 })
    }

    @Test
    fun disabledPressureFlowRetainsTheBaseFlowAtAnyPressure() {
        val params = plain.copy(flow = 0.6f)
        assertArrayEquals(render(stroke(params, 0f)).pixels, render(stroke(params, 1f)).pixels)
    }

    @Test
    fun pressureFlowAlsoControlsTapAndEraserCoverage() {
        val params = plain.copy(pressureToFlow = 1f)
        for (pressure in listOf(0f, 0.2f, 0.5f, 1f)) {
            val tap = stroke(params, pressure).let { it.copy(points = it.points.take(1)) }
            val painted = render(tap)
            val erased = render(tap.copy(isEraser = true), -1)
            for (index in painted.pixels.indices) {
                assertEquals("pressure=$pressure, pixel=$index", 255 - (painted.pixels[index] ushr 24), erased.pixels[index] ushr 24)
            }
        }
    }

    @Test
    fun theSecondaryBrushUsesItsOwnPressureFlowResponse() {
        val secondary = plain.copy(pressureToFlow = 1f)
        val compound = plain.copy(dual = DualBrush(secondary))
        val low = render(stroke(compound, 0.25f)).pixels[30 * width + 45] ushr 24
        val high = render(stroke(compound, 1f)).pixels[30 * width + 45] ushr 24
        assertEquals(64, low)
        assertEquals(255, high)
    }

    @Test
    fun importedColourCurvesAndPressureFlowAgreeAcrossLiveCropAndReplay() {
        val falling = PressureMapping(listOf(PressureMapping.Point(0f, 1f), PressureMapping.Point(1f, 0f)))
        val params =
            plain.copy(
                pressureToFlow = 0.85f,
                colorDynamics = ColorDynamics(pressureHue = 0.65f, pressureBrightness = 0.4f),
                importedAuxiliaryCurves = mapOf("dynamicsPressureHueCurve" to falling),
                wetMix = 0.4f,
                wetCharge = 0.8f,
                wetPull = 0.7f,
                wetAttack = 0.2f,
                wetBlur = 0.6f,
                wetBlurJitter = 0.5f,
                bleed = 0.2f,
            )
        val whole =
            stroke(params, 1f).copy(
                points = (0..12).map { i -> StrokePoint(10f + i * 6f, 27f + i % 3 * 4f, (i % 5) * 0.2f, timestamp = i * 13L) },
            )
        val canvas =
            PixelBuffer(width, height).also { buffer ->
                buffer.pixels.indices.forEach { i -> buffer.pixels[i] = if (i % 7 < 3) -1 else 0xFF114477.toInt() }
            }
        val renderer = StrokeRasterizer()
        val live = renderer.startLive(whole, width, height)
        val region = IntBounds(19, 11, 83, 53)
        val cropped = StrokeRasterizer(region.left, region.top)
        val cropLive = cropped.startLive(whole, width, height)
        assertTrue(StrokeRasterizer.canDrawLive(whole))
        for (count in 1..whole.points.size) {
            val partial = whole.copy(points = whole.points.take(count))
            val expected = canvas.copy().also { StrokeRasterizer().draw(it, partial) }
            val actual = canvas.copy().also { renderer.drawLive(it, live, partial, canvas) }
            val crop = canvas.crop(region).also { cropped.drawLive(it, cropLive, partial, canvas) }
            assertArrayEquals("live after $count samples", expected.pixels, actual.pixels)
            assertArrayEquals("cropped live after $count samples", expected.crop(region).pixels, crop.pixels)
        }
    }

    @Test
    fun auxiliaryBleedCurveControlsActualCanvasPickup() {
        val descending = PressureMapping(listOf(PressureMapping.Point(0f, 1f), PressureMapping.Point(1f, 0f)))
        val params = plain.copy(bleed = 1f, importedAuxiliaryCurves = mapOf("dynamicsPressureBleedCurve" to descending))
        val fill = 0xFF0000FF.toInt()
        val light = render(stroke(params, 0f), fill).pixels[30 * width + 45]
        val firm = render(stroke(params, 1f), fill).pixels[30 * width + 45]
        assertTrue("Descending bleed picks up more blue with a light touch", (light and 255) > (firm and 255) + 100)
        assertEquals(stroke(params, 1f).color, firm)
    }
}
