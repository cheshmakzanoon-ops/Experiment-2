package com.artflow.studio.core.perspective

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

class IsometricGridTest {
    private val width = 1000
    private val height = 800
    private val grid =
        PerspectiveGuide.Settings(type = PerspectiveGuide.GuideType.ISOMETRIC, gridSpacing = 64, snapRadius = 24f)

    /** Distance from (px, py) to the infinite line through a guide segment. */
    private fun distanceToLine(
        px: Float,
        py: Float,
        line: PerspectiveGuide.GuideLine,
    ): Float {
        val dx = (line.endX - line.startX).toDouble()
        val dy = (line.endY - line.startY).toDouble()
        val cross = dy * (px - line.startX) - dx * (py - line.startY)
        return (abs(cross) / hypot(dx, dy)).toFloat()
    }

    @Test fun drawsOnlyTheThreeIsometricFamilies() {
        val lines = PerspectiveGuide.guideLines(grid, width, height)

        assertTrue(lines.none { abs(it.startY - it.endY) < 0.001f && abs(it.startX - it.endX) > 1f })
        assertTrue(lines.any { abs(it.startX - it.endX) < 0.001f })
    }

    @Test fun snapsOntoAPointWhereTwoDrawnLinesCross() {
        val lines = PerspectiveGuide.guideLines(grid, width, height)
        var snapped = 0
        for (x in 120..880 step 41) {
            for (y in 120..680 step 37) {
                val (sx, sy) = PerspectiveGuide.snap(x + 2f, y + 1f, grid, width, height)
                if (sx == x + 2f && sy == y + 1f) continue
                snapped++
                val onLines = lines.count { distanceToLine(sx, sy, it) < 0.05f }
                assertTrue("snapped to ($sx, $sy), which lies on $onLines drawn guide lines", onLines >= 2)
            }
        }
        assertTrue(snapped > 0)
    }
}
