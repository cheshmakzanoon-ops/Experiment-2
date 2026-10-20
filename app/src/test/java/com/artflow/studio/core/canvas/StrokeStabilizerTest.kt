package com.artflow.studio.core.canvas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class StrokeStabilizerTest {
    @Test
    fun theBrushWaitsUntilTheStringIsTaut() {
        val string = StrokeStabilizer(amount = 0f, stringLength = 10f)
        assertTrue(string.isActive)
        string.start(0f, 0f)
        // A wobble shorter than the string leaves the brush where it was.
        assertEquals(0f to 0f, string.add(6f, -5f))
        assertEquals(0f to 0f, string.add(-4f, 7f))
        // Pulled further, the brush follows and stays one string length behind the pen.
        val (x, y) = string.add(30f, 0f)
        assertEquals(20f, x, 1e-4f)
        assertEquals(0f, y, 1e-4f)
        val (x2, y2) = string.add(30f, 40f)
        assertEquals(10f, hypot(30f - x2, 40f - y2), 1e-3f)
    }

    @Test
    fun withAStringTheLineEndsAtTheBrushNotThePen() {
        val string = StrokeStabilizer(amount = 0f, stringLength = 10f).apply { start(0f, 0f) }
        string.add(25f, 0f)
        assertTrue(string.finish(25f, 0f).isEmpty())
        val streamLine = StrokeStabilizer(amount = 0.8f).apply { start(0f, 0f) }
        streamLine.add(25f, 0f)
        assertEquals(25f to 0f, streamLine.finish(25f, 0f).last())
    }

    @Test
    fun noStringAndNoSmoothingIsInactive() {
        assertEquals(false, StrokeStabilizer(0f).isActive)
        assertEquals(false, StrokeStabilizer(0f, Float.NaN).isActive)
    }
}
