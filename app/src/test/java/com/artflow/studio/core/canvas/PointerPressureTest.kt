package com.artflow.studio.core.canvas

import org.junit.Assert.assertEquals
import org.junit.Test

class PointerPressureTest {
    @Test
    fun stylusPressurePreservesZeroAndLightContact() {
        for (pressure in listOf(0f, 0.001f, 0.01f, 0.5f, 1f)) {
            assertEquals(pressure, PointerPressure.normalize(true, pressure, 0.1f), 0f)
        }
    }

    @Test
    fun invalidStylusPressureDoesNotInventForce() {
        for (pressure in listOf(-1f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertEquals(0f, PointerPressure.normalize(true, pressure, 0.1f), 0f)
        }
        assertEquals(1f, PointerPressure.normalize(true, 2f, 0.1f), 0f)
    }

    @Test
    fun fingersKeepContactAreaCalibrationRatherThanUsingThePenAxis() {
        assertEquals(0.545f, PointerPressure.normalize(false, 1f, 0.1f), 0.000001f)
        assertEquals(0.545f, PointerPressure.normalize(false, 0f, 0.1f), 0.000001f)
        assertEquals(0.35f, PointerPressure.normalize(false, 1f, 0f), 0f)
        assertEquals(1f, PointerPressure.normalize(false, 1f, 1f), 0f)
    }

    @Test
    fun invalidFingerSizeIsBounded() {
        for (size in listOf(-1f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertEquals(0.35f, PointerPressure.normalize(false, 1f, size), 0f)
        }
    }
}
