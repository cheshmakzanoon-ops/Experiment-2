package com.artflow.studio.core.perspective

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GridSpacingTest {
    private fun grid(spacing: Int) =
        PerspectiveGuide.Settings(
            type = PerspectiveGuide.GuideType.GRID,
            gridSpacing = spacing,
            snapRadius = 24f,
        )

    private fun verticalXs(spacing: Int): List<Float> =
        PerspectiveGuide
            .guideLines(grid(spacing), 1000, 600)
            .filter { it.startX == it.endX }
            .map { it.startX }

    private fun horizontalYs(spacing: Int): List<Float> =
        PerspectiveGuide
            .guideLines(grid(spacing), 1000, 600)
            .filter { it.startY == it.endY }
            .map { it.startY }

    @Test fun gridLinesFollowNonDefaultSpacing() {
        // Canvas centre 500 modulo 40 is 20, so the lines sit at 20, 60, 100, ...
        val xs = verticalXs(40)
        assertEquals(20f, xs.first(), 0f)
        assertEquals(60f, xs[1], 0f)
        assertTrue(xs.zipWithNext().all { (a, b) -> b - a == 40f })
        assertTrue(xs.last() <= 1000f)
    }

    @Test fun gridLinesAreOffsetFromTheOriginWhenCanvasCentreIsNotAMultiple() {
        // 500 modulo 64 is 52: the lines are at 52, 116, ... and not at the multiples of 64.
        val xs = verticalXs(64)
        assertEquals(52f, xs.first(), 0f)
        assertTrue(116f in xs)
        assertFalse(64f in xs)
        val ys = horizontalYs(64)
        // 300 modulo 64 is 44.
        assertEquals(44f, ys.first(), 0f)
        assertTrue(108f in ys)
    }

    @Test fun snapLandsOnAGridLineThatIsDrawn() {
        val settings = grid(64)
        // Nearest vertical line to x=120 is 116 (4 px away); nearest horizontal line to y=40 is 44 (4 px away).
        // The x distance wins the tie, so only x is pulled onto its line.
        val (snappedX, snappedY) = PerspectiveGuide.snap(120f, 40f, settings, 1000, 600)
        assertEquals(116f, snappedX, 1e-4f)
        assertEquals(40f, snappedY, 1e-4f)
        val drawnXs = verticalXs(64)
        assertTrue(drawnXs.any { kotlin.math.abs(it - snappedX) < 1e-4f })
    }

    @Test fun snapOnAnOffsetGridIsNotPulledToTheOriginMultiples() {
        // The old grid snapped to multiples of the spacing (x = 128 here) regardless of the drawn lines.
        val (snappedX, _) = PerspectiveGuide.snap(120f, 40f, grid(64), 1000, 600)
        assertFalse(snappedX == 128f)
    }

    @Test fun snapStrengthZeroLeavesThePointWhereItWas() {
        val settings = grid(64).copy(snapStrength = 0f)
        assertEquals(120f to 40f, PerspectiveGuide.snap(120f, 40f, settings, 1000, 600))
    }

    @Test fun squareGridPhaseIsHalfTheCanvasModuloTheCell() {
        assertEquals(20f, PerspectiveGuide.squareGridPhase(1000, 40), 0f)
        assertEquals(0f, PerspectiveGuide.squareGridPhase(1000, 100), 0f)
    }
}
