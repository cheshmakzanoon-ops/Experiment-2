package com.artflow.studio.core.canvas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class SmoothDrawingPipelineTest {
    @Test
    fun splineInterpolatorSubdividesCurvedPathAndPreservesExactEndpoints() {
        val spline = StrokeSplineInterpolator(maxStepPx = 4f)
        val emitted = mutableListOf<Triple<Float, Float, Float>>()
        val emit: (Float, Float, Float, Float, Float, Long) -> Unit = { x, y, p, _, _, _ ->
            emitted += Triple(x, y, p)
        }

        spline.start(0f, 0f, 0.2f, 0f, 0f, 0L)
        spline.add(20f, 0f, 0.5f, 0f, 0f, 10L, emit)
        spline.add(40f, 20f, 0.8f, 0f, 0f, 20L, emit)
        spline.add(40f, 40f, 1.0f, 0f, 0f, 30L, emit)
        spline.finish(emit)

        // Subdivided well beyond the 3 added points.
        assertTrue("Expected subdivided spline samples, got ${emitted.size}", emitted.size > 10)
        // Final emitted point must land on the exact pen-lift coordinate and pressure.
        val last = emitted.last()
        assertEquals(40f, last.first, 0.001f)
        assertEquals(40f, last.second, 0.001f)
        assertEquals(1.0f, last.third, 0.001f)
        // Pressure remains bounded in [0, 1].
        assertTrue(emitted.all { it.third in 0f..1f })
    }

    @Test
    fun splineInterpolatorTwoPointStrokeFlushesEndCoordinate() {
        val spline = StrokeSplineInterpolator(maxStepPx = 5f)
        val emitted = mutableListOf<Pair<Float, Float>>()
        spline.start(10f, 10f, 0.5f, 0f, 0f, 0L)
        spline.add(12f, 14f, 0.6f, 0f, 0f, 10L) { x, y, _, _, _, _ -> emitted += x to y }
        spline.finish { x, y, _, _, _, _ -> emitted += x to y }

        assertEquals(listOf(12f to 14f), emitted)
    }

    @Test
    fun strokePredictorClampsOvershootAndMatchesPredictInto() {
        val predictor = StrokePredictor(horizonMs = 32f)
        // Very fast movement that would otherwise overshoot past MAX_PREDICTION_PX.
        predictor.add(0f, 0f, 0L)
        predictor.add(40f, 0f, 4L)
        predictor.add(100f, 0f, 8L)
        predictor.add(180f, 0f, 12L)

        val listPrediction = predictor.predict(steps = 4)
        val scratch = FloatArray(8)
        val written = predictor.predictInto(scratch, steps = 4)

        assertEquals(8, written)
        assertEquals(4, listPrediction.size)
        for (i in 0 until 4) {
            assertEquals(listPrediction[i].first, scratch[i * 2], 0.0001f)
            assertEquals(listPrediction[i].second, scratch[i * 2 + 1], 0.0001f)
            val dist = hypot(scratch[i * 2] - 180f, scratch[i * 2 + 1] - 0f)
            assertTrue("Predicted distance $dist exceeded cap", dist <= StrokePredictor.MAX_PREDICTION_PX + 0.01f)
        }
    }
}
