package com.artflow.studio.core.render

import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.domain.model.brush.BrushParams
import com.artflow.studio.domain.model.brush.Stroke
import com.artflow.studio.domain.model.brush.StrokePoint
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SharedTimestampVelocityTest {
    private val black = 0xFF000000.toInt()

    private fun render(
        timestamps: (Int) -> Long,
        velocityToSize: Float,
    ): IntArray {
        val points = (0..10).map { StrokePoint(5f + it * 5f, 10f, pressure = 1f, timestamp = timestamps(it)) }
        val params = BrushParams(size = 12f, spacing = 0.25f, velocityToSize = velocityToSize)
        val target = PixelBuffer(60, 20)
        StrokeRasterizer().draw(target, Stroke(points = points, brushParams = params, layerId = 1, color = black))
        return target.pixels
    }

    @Test
    fun samplesThatShareATimestampDoNotReadAsInfinitelyFast() {
        // Batched input arrives with no time between samples. Speed dynamics must then have nothing to act on.
        val withSpeedDynamics = render({ 0L }, velocityToSize = 1f)
        val withoutSpeedDynamics = render({ 0L }, velocityToSize = 0f)
        assertArrayEquals(withoutSpeedDynamics, withSpeedDynamics)
    }

    @Test
    fun measuredSpeedStillChangesTheStroke() {
        // Samples 10 ms apart, 5 px each: a real speed of half a pixel per millisecond.
        val withSpeedDynamics = render({ it * 10L }, velocityToSize = 1f)
        val withoutSpeedDynamics = render({ it * 10L }, velocityToSize = 0f)
        assertFalse(withoutSpeedDynamics.contentEquals(withSpeedDynamics))
    }
}
