package com.artflow.studio.core.perspective

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Regression coverage for restored rotated-square-grid rendering after branch consolidation. */
class PerspectiveGridRotationTest {
    @Test
    fun unrotatedGridAndSnappingShareTheCanvasCentre() {
        val settings =
            PerspectiveGuide.Settings(
                type = PerspectiveGuide.GuideType.GRID,
                gridSpacing = 32,
            )
        val lines = PerspectiveGuide.guideLines(settings, width = 128, height = 128)
        assertTrue(lines.any { it.startX == 64f && it.endX == 64f })
        assertTrue(lines.any { it.startY == 64f && it.endY == 64f })
        val snapped =
            PerspectiveGuide.snapToSquareGrid(
                x = 63f,
                y = 80f,
                settings = settings,
                originX = 64f,
                originY = 64f,
            )
        assertEquals(64f, snapped.first, 0.001f)
    }

    @Test
    fun fortyFiveDegreeRotationProducesDiagonalFamiliesCrossingTheCentre() {
        val settings =
            PerspectiveGuide.Settings(
                type = PerspectiveGuide.GuideType.GRID,
                gridSpacing = 32,
                gridRotation = 45f,
            )
        val lines = PerspectiveGuide.guideLines(settings, width = 128, height = 128)
        assertTrue(lines.isNotEmpty())
        assertTrue(lines.all { it.startX in 0f..128f && it.endX in 0f..128f })
        assertTrue(lines.all { it.startY in 0f..128f && it.endY in 0f..128f })
        assertTrue(
            lines.any {
                val dx = it.endX - it.startX
                val dy = it.endY - it.startY
                abs(dx) > 1f && abs(abs(dx) - abs(dy)) < 0.01f
            },
        )
        assertTrue(
            lines.any {
                abs((it.startX + it.endX) / 2f - 64f) < 0.001f &&
                    abs((it.startY + it.endY) / 2f - 64f) < 0.001f
            },
        )
    }

    @Test
    fun sanitizationRejectsNonFiniteAnglesAndStyles() {
        val input =
            PerspectiveGuide.Settings(
                gridRotation = Float.NaN,
                lineStyle = GuideLineStyle(thickness = -9f, opacity = Float.NaN),
            )
        val sanitized = PerspectiveGuide.sanitize(input)
        assertEquals(0f, sanitized.gridRotation, 0f)
        assertTrue(sanitized.lineStyle.thickness > 0f)
        assertTrue(sanitized.lineStyle.opacity.isFinite())
    }
}
