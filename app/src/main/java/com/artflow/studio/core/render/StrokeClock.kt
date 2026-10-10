package com.artflow.studio.core.render

/**
 * Converts an input event's time, which Android counts from boot, to the wall clock that stroke points use.
 *
 * Stroke timestamps are wall-clock milliseconds, and the first point of a stroke is stamped with the wall clock
 * too. Converting each event by the offset between the two clocks keeps every sample on one base, while each
 * event keeps its own time: samples batched into one input event then carry distinct times.
 */
object StrokeClock {
    fun wallTime(
        eventTime: Long,
        uptimeNow: Long,
        wallNow: Long,
    ): Long = wallNow - (uptimeNow - eventTime)
}
