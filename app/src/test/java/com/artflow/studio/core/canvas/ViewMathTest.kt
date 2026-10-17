package com.artflow.studio.core.canvas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewMathTest {
    private val frame = ViewFrame(canvasWidth = 1920f, canvasHeight = 1080f, viewWidth = 1000f, viewHeight = 700f)
    private val transform = ViewTransform(scale = 0.5f, offsetX = 30f, offsetY = -20f, rotationDegrees = 37f)

    private fun assertPoint(
        expected: Pair<Float, Float>,
        actual: Pair<Float, Float>,
    ) {
        assertEquals("x", expected.first, actual.first, 0.01f)
        assertEquals("y", expected.second, actual.second, 0.01f)
    }

    @Test
    fun screenAndCanvasCoordinatesAreInverses() {
        for ((x, y) in listOf(0f to 0f, 1919f to 1079f, 960f to 540f, 250f to 900f)) {
            val screen = ViewMath.canvasToScreen(transform, frame, x, y)
            assertPoint(x to y, ViewMath.screenToCanvas(transform, frame, screen.first, screen.second))
        }
    }

    @Test
    fun theCanvasCentreLandsInTheMiddleOfTheViewPlusOffset() {
        val centre = ViewMath.canvasToScreen(transform.copy(rotationDegrees = 0f), frame, 960f, 540f)
        assertPoint(500f + 30f to 350f - 20f, centre)
    }

    @Test
    fun zoomKeepsTheCanvasPointUnderTheFingerFixed() {
        val focusX = 320f
        val focusY = 610f
        val under = ViewMath.screenToCanvas(transform, frame, focusX, focusY)
        val zoomed = ViewMath.zoomAbout(transform, frame, 1.8f, minScale = 0.05f, maxScale = 32f, focusX = focusX, focusY = focusY)
        assertEquals(0.5f * 1.8f, zoomed.scale, 0.0001f)
        assertPoint(focusX to focusY, ViewMath.canvasToScreen(zoomed, frame, under.first, under.second))
    }

    @Test
    fun zoomOutAboutAPointAlsoKeepsItFixedAndRespectsTheLimits() {
        val focusX = 700f
        val focusY = 100f
        val under = ViewMath.screenToCanvas(transform, frame, focusX, focusY)
        val zoomed = ViewMath.zoomAbout(transform, frame, 0.01f, minScale = 0.05f, maxScale = 32f, focusX = focusX, focusY = focusY)
        assertEquals(0.05f, zoomed.scale, 0.0001f)
        assertPoint(focusX to focusY, ViewMath.canvasToScreen(zoomed, frame, under.first, under.second))
    }

    @Test
    fun zoomBeyondTheMaximumIsClampedAndStillAnchored() {
        val focusX = 500f
        val focusY = 350f
        val under = ViewMath.screenToCanvas(transform, frame, focusX, focusY)
        val zoomed = ViewMath.zoomAbout(transform, frame, 1000f, minScale = 0.05f, maxScale = 32f, focusX = focusX, focusY = focusY)
        assertEquals(32f, zoomed.scale, 0.0001f)
        assertPoint(focusX to focusY, ViewMath.canvasToScreen(zoomed, frame, under.first, under.second))
    }

    @Test
    fun aBadFactorLeavesTheViewAlone() {
        for (factor in listOf(0f, -2f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertEquals(transform, ViewMath.zoomAbout(transform, frame, factor, 0.05f, 32f, 1f, 1f))
        }
    }

    @Test
    fun zoomPercentReadsOneHundredAtActualSize() {
        assertEquals(100, ViewMath.zoomPercent(1f))
        assertEquals(25, ViewMath.zoomPercent(0.25f))
        assertEquals(235, ViewMath.zoomPercent(2.345f))
    }

    @Test
    fun readoutAngleIsSignedSoASmallTurnEitherWayReadsSmall() {
        assertEquals(0, ViewMath.readoutDegrees(0f))
        assertEquals(90, ViewMath.readoutDegrees(90f))
        assertEquals(180, ViewMath.readoutDegrees(180f))
        assertEquals(-10, ViewMath.readoutDegrees(350f))
        assertEquals(-170, ViewMath.readoutDegrees(190f))
        assertEquals(-90, ViewMath.readoutDegrees(-90f))
    }

    @Test
    fun normalisingAndDistanceWrapAroundFullTurns() {
        assertEquals(350f, ViewMath.normalizeDegrees(-10f), 0.0001f)
        assertEquals(0f, ViewMath.normalizeDegrees(720f), 0.0001f)
        assertEquals(10f, ViewMath.angularDistance(358f, 8f), 0.0001f)
        assertEquals(180f, ViewMath.angularDistance(0f, 180f), 0.0001f)
        assertTrue(ViewMath.angularDistance(90f, 90f) == 0f)
    }
}
