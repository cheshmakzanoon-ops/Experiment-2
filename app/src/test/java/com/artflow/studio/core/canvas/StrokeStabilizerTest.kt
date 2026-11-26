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

    @Test
    fun finishingPreservesASubpixelLiftCoordinate() {
        val stabilizer = StrokeStabilizer(amount = 1f).apply { start(0f, 0f) }
        stabilizer.add(0.25f, -0.25f)

        assertEquals(listOf(0.25f to -0.25f), stabilizer.finish(0.25f, -0.25f))
        assertTrue(stabilizer.finish(0.25f, -0.25f).isEmpty())
    }

    @Test
    fun finishingPreservesTheExactFractionalEndpointAfterALongMovement() {
        val stabilizer = StrokeStabilizer(amount = 1f).apply { start(3_000f, -3_000f) }
        stabilizer.add(0.1f, -0.1f)

        assertEquals(0.1f to -0.1f, stabilizer.finish(0.1f, -0.1f).last())
    }

    @Test
    fun finishingATapDoesNotEmitADuplicatePoint() {
        val stabilizer = StrokeStabilizer(amount = 1f).apply { start(4f, 7f) }

        assertTrue(stabilizer.finish(4f, 7f).isEmpty())
    }
}
