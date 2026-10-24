package com.artflow.studio.core.render

import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.domain.model.brush.BrushParams
import com.artflow.studio.domain.model.brush.RenderingMode
import com.artflow.studio.domain.model.brush.Stroke
import com.artflow.studio.domain.model.brush.StrokePoint
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StrokeSamplingRegressionTest {
    private val brush = BrushParams(size = 10f, spacing = 1f, pressureToSize = 0f, pressureToOpacity = 0f)

    private fun stroke(
        positions: List<Float>,
        params: BrushParams = brush,
        eraser: Boolean = false,
    ): Stroke =
        Stroke(
            id = 91L,
            points = positions.map { StrokePoint(it, 20.5f, timestamp = (it * 10).toLong()) },
            brushParams = params,
            layerId = 1L,
            color = 0xFF264D96.toInt(),
            isEraser = eraser,
        )

    private fun paint(stroke: Stroke): PixelBuffer =
        PixelBuffer(64, 40).also { target ->
            if (stroke.isEraser) target.pixels.fill(0xFFE6CDA9.toInt())
            StrokeRasterizer().draw(target, stroke)
        }

    @Test
    fun aShortFinalSegmentRetainsTheEndpointAfterAnEarlierSegmentStampedAtTheSameLocalDistance() {
        // The grid stamps x=10.5 and 20.5. The last segment is four pixels long but has no grid dab.
        // Confusing its length with the previous segment's four-pixel dab offset drops x=26.5.
        val direct = paint(stroke(listOf(10.5f, 26.5f)))
        val sampled = paint(stroke(listOf(10.5f, 16.5f, 22.5f, 26.5f)))
        assertTrue("the closing dab reaches beyond the last grid dab", direct.getSafe(30, 20) ushr 24 > 0)
        assertArrayEquals(direct.pixels, sampled.pixels)
    }

    @Test
    fun erasingTheSamePathUsesTheSameCoverageRegardlessOfInputSampling() {
        val direct = paint(stroke(listOf(10.5f, 26.5f), eraser = true))
        val sampled = paint(stroke(listOf(10.5f, 16.5f, 22.5f, 26.5f), eraser = true))
        assertArrayEquals(direct.pixels, sampled.pixels)
    }

    @Test
    fun liveClosingDabsRemainTemporaryWhenFurtherSamplesArrive() {
        val whole = stroke(listOf(10.5f, 16.5f, 22.5f, 26.5f, 36.5f), brush.copy(flow = 0.3f, buildUp = true))
        val renderer = StrokeRasterizer()
        val state = renderer.startLive(whole, 64, 40)
        for (count in 1..whole.points.size) {
            val partial = whole.copy(points = whole.points.take(count))
            val direct = partial.copy(points = if (count == 1) partial.points else listOf(partial.points.first(), partial.points.last()))
            val actual = PixelBuffer(64, 40).also { renderer.drawLive(it, state, partial, null) }
            assertArrayEquals("after $count samples", paint(direct).pixels, actual.pixels)
        }
    }

    @Test
    fun anEndpointOnTheSpacingGridIsNotStampedTwice() {
        val input = stroke(listOf(10.5f, 16.5f, 20.5f), brush.copy(flow = 0.3f, buildUp = true))
        val renderer = StrokeRasterizer()
        val target = PixelBuffer(64, 40).also { renderer.draw(it, input) }
        assertEquals(2, renderer.lastDabCount)
        assertArrayEquals(paint(input.copy(points = listOf(input.points.first(), input.points.last()))).pixels, target.pixels)
    }

    @Test
    fun aSingleDabKeepsItsRenderingModeWhenSpacingReachesZero() {
        for (mode in RenderingMode.entries) {
            val params = brush.copy(size = 20f, flow = 0.2f, opacity = 0.7f, renderingMode = mode)
            val ordinary = paint(stroke(listOf(26.5f), params.copy(spacing = 0.1f)))
            val zeroSpacing = paint(stroke(listOf(26.5f), params.copy(spacing = 0f)))
            assertArrayEquals("$mode at zero spacing", ordinary.pixels, zeroSpacing.pixels)
        }
    }

    @Test
    fun zeroSpacingBlendingBuildsUpAlongTheStrokeRatherThanUsingGlazeCoverage() {
        val params = brush.copy(size = 12f, flow = 0.1f, renderingMode = RenderingMode.BLEND_UNIFORM)
        val zeroSpacing = paint(stroke(listOf(10.5f, 50.5f), params.copy(spacing = 0f)))
        val smallestSpacing = paint(stroke(listOf(10.5f, 50.5f), params.copy(spacing = 0.01f)))
        assertArrayEquals(smallestSpacing.pixels, zeroSpacing.pixels)
        assertTrue("overlapping low-flow dabs accumulate paint", zeroSpacing.getSafe(30, 20) ushr 24 > 150)
    }
}
