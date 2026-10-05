package com.artflow.studio.core.canvas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class InputFiltersTest {
    /** A slow horizontal line with a pixel and a half of hand tremor, sampled at 120 Hz. */
    private fun tremble(filter: MotionFilter): Float {
        filter.start(0f, 100f, 0L)
        var worst = 0f
        for (i in 1..240) {
            val (_, y) = filter.add(i * 0.5f, 100f + if (i % 2 == 0) 1.5f else -1.5f, i * 8L)
            if (i > 20) worst = maxOf(worst, abs(y - 100f))
        }
        return worst
    }

    @Test
    fun motionFilteringTakesOutTremorAndExpressionGivesSomeBack() {
        val none = MotionFilter(0f, 0f)
        assertFalse(none.isActive)
        assertEquals(1.5f, tremble(none), 1e-6f)
        val filtered = tremble(MotionFilter(1f, 0f))
        assertTrue("filtered tremor $filtered", filtered < 0.3f)
        val expressive = tremble(MotionFilter(1f, 0.5f))
        assertTrue("expression $expressive between $filtered and 1.5", expressive > filtered && expressive < 1.5f)
    }

    @Test
    fun fastSweepsKeepUpWithThePen() {
        val filter = MotionFilter(1f, 0f)
        filter.start(0f, 0f, 0L)
        var lag = 0f
        // 2000 canvas pixels per second.
        for (i in 1..60) {
            val (x, _) = filter.add(i * 16f, 0f, i * 8L)
            lag = i * 16f - x
        }
        assertTrue("lag $lag", lag < 40f)
    }

    @Test
    fun pressureSmoothingEvensOutJumps() {
        val smooth = PressureSmoother(1f)
        smooth.start(0.2f)
        val first = smooth.add(1f)
        assertTrue("jump softened to $first", first < 0.4f)
        repeat(60) { smooth.add(1f) }
        assertEquals(1f, smooth.add(1f), 1e-2f)
        val none = PressureSmoother(0f)
        none.start(0.2f)
        assertEquals(1f, none.add(1f), 1e-6f)
    }
}
