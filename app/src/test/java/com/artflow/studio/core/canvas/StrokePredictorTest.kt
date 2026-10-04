package com.artflow.studio.core.canvas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StrokePredictorTest {
    @Test fun steadyMotionIsExtendedAlongItsLine() {
        val predictor = StrokePredictor(horizonMs = 16f)
        // 1 px per ms to the right.
        for (i in 0..10) predictor.add(i * 8f, 50f, i * 8L)
        val ahead = predictor.predict(steps = 4)
        assertEquals(4, ahead.size)
        val (x, y) = ahead.last()
        assertTrue("predicted $x should lead the last sample at 80", x in 85f..100f)
        assertEquals(50f, y, 1f)
    }

    @Test fun nothingIsPredictedForAStillOrNewPen() {
        val predictor = StrokePredictor()
        predictor.add(10f, 10f, 0L)
        assertTrue(predictor.predict().isEmpty())
        for (i in 1..5) predictor.add(10f, 10f, i * 8L)
        assertTrue(predictor.predict().isEmpty())
        predictor.reset()
        assertTrue(predictor.predict().isEmpty())
    }
}
