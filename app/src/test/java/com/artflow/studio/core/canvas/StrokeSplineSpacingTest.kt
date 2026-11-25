package com.artflow.studio.core.canvas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StrokeSplineSpacingTest {
    private fun run(
        samples: List<Pair<Float, Float>>,
        step: Float,
    ): List<Pair<Float, Float>> {
        val spline = StrokeSplineInterpolator(maxStepPx = step)
        val out = mutableListOf<Pair<Float, Float>>()
        samples.forEachIndexed { index, (x, y) ->
            spline.add(x, y, 1f, 0f, 0f, index * 10L) { px, py, _, _, _, _ -> out += px to py }
        }
        spline.finish { px, py, _, _, _, _ -> out += px to py }
        return out
    }

    @Test
    fun aShortSpanBesideALongOneNeitherOvershootsNorRunsBackwards() {
        // A 100 px jump, then 1 px steps: a uniform Catmull-Rom tangent of ~50 px on the 1 px span flings it far away.
        val points = run(listOf(0f to 0f, 100f to 0f, 101f to 0f, 102f to 0f), step = 0.2f)
        assertTrue(points.all { it.first in 0f..102f })
        val x = points.map { it.first }
        x.zipWithNext().forEach { (a, b) -> assertTrue("x must never move backwards: $a then $b", b >= a - 1e-4f) }
        assertEquals(102f, x.last(), 1e-4f)
    }

    @Test
    fun aCollinearStrokeStaysOnItsLineWhateverTheSpacing() {
        val points = run(listOf(0f to 5f, 1f to 5f, 2f to 5f, 60f to 5f, 61f to 5f, 90f to 5f), step = 0.5f)
        assertTrue(points.all { kotlin.math.abs(it.second - 5f) < 1e-3f })
        points.map { it.first }.zipWithNext().forEach { (a, b) -> assertTrue(b >= a - 1e-4f) }
    }

    @Test
    fun evenlySpacedSamplesStillGetTheSmoothCatmullRomCurve() {
        // A quarter-circle sampled evenly: interpolated points must stay close to the circle.
        val radius = 100f
        val samples =
            (0..6).map { i ->
                val angle = Math.toRadians(i * 15.0)
                (radius * kotlin.math.cos(angle)).toFloat() to (radius * kotlin.math.sin(angle)).toFloat()
            }
        val points = run(samples, step = 2.5f)
        points.forEach { (px, py) ->
            val distance = kotlin.math.hypot(px, py)
            assertTrue("Point ($px, $py) is ${distance - radius} px off the circle", kotlin.math.abs(distance - radius) < 1.5f)
        }
    }
}
