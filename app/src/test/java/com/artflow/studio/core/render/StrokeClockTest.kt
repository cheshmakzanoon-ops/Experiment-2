package com.artflow.studio.core.render

import org.junit.Assert.assertEquals
import org.junit.Test

class StrokeClockTest {
    @Test
    fun anEventKeepsItsAgeAgainstTheWallClock() {
        // Forty milliseconds before now, on the uptime clock.
        assertEquals(4_960L, StrokeClock.wallTime(eventTime = 960L, uptimeNow = 1_000L, wallNow = 5_000L))
    }

    @Test
    fun anEventAtTheCurrentUptimeIsNow() {
        assertEquals(5_000L, StrokeClock.wallTime(eventTime = 1_000L, uptimeNow = 1_000L, wallNow = 5_000L))
    }

    @Test
    fun batchedSamplesKeepTheirOwnSpacing() {
        // Two samples from one input batch, 10 ms apart: their gap survives the conversion.
        val first = StrokeClock.wallTime(eventTime = 100L, uptimeNow = 500L, wallNow = 9_000L)
        val second = StrokeClock.wallTime(eventTime = 110L, uptimeNow = 500L, wallNow = 9_000L)
        assertEquals(10L, second - first)
    }
}
