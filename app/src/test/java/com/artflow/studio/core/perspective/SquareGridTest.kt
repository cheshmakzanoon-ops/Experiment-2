package com.artflow.studio.core.perspective

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

class SquareGridTest {
    private val grid = PerspectiveGuide.Settings(type = PerspectiveGuide.GuideType.GRID, gridSpacing = 100, snapRadius = 24f)

    @Test fun snapsToNearestLine() {
        assertEquals(100f to 37f, PerspectiveGuide.snap(108f, 37f, grid, 1000, 1000))
        assertEquals(42f to 200f, PerspectiveGuide.snap(42f, 190f, grid, 1000, 1000))
        assertEquals(150f to 150f, PerspectiveGuide.snap(150f, 150f, grid, 1000, 1000))
    }

    @Test fun drawsBothAxes() {
        val lines = PerspectiveGuide.guideLines(grid, 1000, 600)
        assertTrue(lines.any { it.startX == it.endX })
        assertTrue(lines.any { it.startY == it.endY })
    }

    @Test fun snapsOntoTheLinesItDraws() {
        // On 1920 x 1080 the centred grid's lines sit at x = 60, 160 ... and y = 40, 140 ...
        val lines = PerspectiveGuide.guideLines(grid, 1920, 1080)
        assertTrue(lines.any { it.startX == 60f && it.endX == 60f })
        assertEquals(60f to 517f, PerspectiveGuide.snap(70f, 517f, grid, 1920, 1080))
        assertEquals(333f to 40f, PerspectiveGuide.snap(333f, 52f, grid, 1920, 1080))
    }

    @Test fun aRotatedGridDrawsTurnedLinesAndSnapsOntoThem() {
        val turned = grid.copy(gridRotation = 45f)
        val lines = PerspectiveGuide.guideLines(turned, 1000, 1000)
        assertTrue("No line stays axis-aligned", lines.none { abs(it.startX - it.endX) < 1e-3f || abs(it.startY - it.endY) < 1e-3f })
        // 10 px right of the centre is 7 px from both lines through it; it lands on one of them.
        val (sx, sy) = PerspectiveGuide.snap(510f, 500f, turned, 1000, 1000)
        val onLine =
            lines.any { line ->
                val dx = line.endX - line.startX
                val dy = line.endY - line.startY
                abs((sx - line.startX) * dy - (sy - line.startY) * dx) / hypot(dx, dy) < 1e-2f
            }
        assertTrue(onLine)
        assertEquals("Snapping moves the point straight onto the line", 7.07f, hypot(sx - 510f, sy - 500f), 1e-2f)
    }

    @Test fun isometricSnapsToWhereItsLinesCross() {
        val iso = grid.copy(type = PerspectiveGuide.GuideType.ISOMETRIC, snapRadius = 200f)
        // The canvas centre is a crossing of every family of lines.
        assertEquals(320f to 320f, PerspectiveGuide.snap(323f, 318f, iso, 640, 640))
        val lines = PerspectiveGuide.guideLines(iso, 640, 640)
        val (sx, sy) = PerspectiveGuide.snap(371f, 297f, iso, 640, 640)
        // The snapped point lies on at least two drawn lines.
        val onLines =
            lines.count { line ->
                val dx = line.endX - line.startX
                val dy = line.endY - line.startY
                abs((sx - line.startX) * dy - (sy - line.startY) * dx) / hypot(dx, dy) < 0.01f
            }
        assertTrue("on $onLines lines", onLines >= 2)
    }
}
