package com.artflow.studio.core.canvas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RotationSnapTest {
    @Test
    fun aTurnWithinFourDegreesOfARightAngleSnapsToIt() {
        val snap = RotationSnap()
        val step = snap.update(3f)
        assertEquals(0f, step.degrees, 0f)
        assertTrue(step.entered)
    }

    @Test
    fun aTurnBeyondFourDegreesIsLeftAlone() {
        val snap = RotationSnap()
        val step = snap.update(5f)
        assertEquals(5f, step.degrees, 0.0001f)
        assertFalse(step.entered)
        assertEquals(45f, snap.update(45f).degrees, 0.0001f)
    }

    @Test
    fun everyQuadrantSnapsToItsRightAngle() {
        assertEquals(90f, RotationSnap().update(88f).degrees, 0f)
        assertEquals(180f, RotationSnap().update(177f).degrees, 0f)
        assertEquals(270f, RotationSnap().update(272f).degrees, 0f)
    }

    @Test
    fun snapsAcrossTheThreeSixtyBoundary() {
        val step = RotationSnap().update(358f)
        assertEquals(0f, step.degrees, 0f)
        assertTrue(step.entered)
        assertEquals(0f, RotationSnap().update(-2f).degrees, 0f)
    }

    @Test
    fun enteringAsnapIsReportedOnlyOnce() {
        val snap = RotationSnap()
        assertTrue(snap.update(2f).entered)
        val later = snap.update(3f)
        assertFalse(later.entered)
        assertEquals(0f, later.degrees, 0f)
        assertFalse(snap.update(1f).entered)
    }

    @Test
    fun aSnapIsHeldUntilTheTurnGoesSixDegreesBeyond() {
        val snap = RotationSnap()
        snap.update(0f)
        assertEquals(0f, snap.update(5.9f).degrees, 0f)
        assertEquals(0f, snap.update(-5.9f + 360f).degrees, 0f)
        val released = snap.update(6.5f)
        assertEquals(6.5f, released.degrees, 0.0001f)
        assertFalse(released.entered)
    }

    @Test
    fun aReleasedSnapCanBeEnteredAgain() {
        val snap = RotationSnap()
        assertTrue(snap.update(1f).entered)
        snap.update(20f)
        assertTrue(snap.update(89f).entered)
    }

    @Test
    fun resetForgetsTheHeldSnap() {
        val snap = RotationSnap()
        snap.update(1f)
        snap.reset()
        assertTrue(snap.update(2f).entered)
    }

    @Test
    fun nearestRightAngleIsZeroToThreeSixtyExclusive() {
        assertEquals(0f, RotationSnap.nearestRightAngle(359f), 0f)
        assertEquals(90f, RotationSnap.nearestRightAngle(45f), 0f)
        assertEquals(180f, RotationSnap.nearestRightAngle(180f), 0f)
    }
}
