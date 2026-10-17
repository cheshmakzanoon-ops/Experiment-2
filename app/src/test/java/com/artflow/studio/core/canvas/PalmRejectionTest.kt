package com.artflow.studio.core.canvas

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PalmRejectionTest {
    private val small = 0.2f
    private val large = PalmRejection.PALM_CONTACT_SIZE + 0.1f

    @Test
    fun fingersWorkNormallyWhenNoStylusIsInRange() {
        val palm = PalmRejection()
        assertFalse(palm.rejectsFinger(large, 1_000L))
        assertFalse(palm.rejectsFinger(small, 1_000L))
    }

    @Test
    fun aLargeContactIsRejectedWhileTheStylusHovers() {
        val palm = PalmRejection()
        palm.stylusHover(true)
        assertTrue(palm.rejectsFinger(large, 1_000L))
        assertFalse("a normal fingertip still works beside a hovering pen", palm.rejectsFinger(small, 1_000L))
    }

    @Test
    fun aFingerNearThePensContactIsRejectedAndTheWindowExpires() {
        val palm = PalmRejection()
        palm.stylusHover(true)
        palm.stylusContact(true, 1_000L)
        assertTrue("while the pen is down", palm.rejectsFinger(small, 1_050L))
        palm.stylusContact(false, 1_200L)
        assertTrue("just after the pen lifts", palm.rejectsFinger(small, 1_300L))
        assertFalse("after the window", palm.rejectsFinger(small, 1_200L + PalmRejection.CONTACT_WINDOW_MS))
    }

    @Test
    fun aPalmSequenceIsRejectedButTheFingerLaterOnIsNot() {
        val palm = PalmRejection()
        palm.stylusHover(true)
        assertTrue("palm lands as the pen approaches", palm.rejectsFinger(large, 0L))
        palm.stylusContact(true, 200L)
        assertTrue("palm stays while the pen draws", palm.rejectsFinger(large, 400L))
        palm.stylusContact(false, 900L)
        palm.stylusHover(false)
        assertFalse("pen gone and the window passed", palm.rejectsFinger(large, 2_000L))
    }

    @Test
    fun leavingTheHoverRangeWithoutContactStopsRejectingLargeContacts() {
        val palm = PalmRejection()
        palm.stylusHover(true)
        palm.stylusHover(false)
        assertFalse(palm.rejectsFinger(large, 1_000L))
    }

    @Test
    fun aNonFiniteContactSizeIsNotTreatedAsAPalm() {
        val palm = PalmRejection()
        palm.stylusHover(true)
        assertFalse(palm.rejectsFinger(Float.NaN, 1_000L))
    }

    @Test
    fun resetForgetsThePenCompletely() {
        val palm = PalmRejection()
        palm.stylusHover(true)
        palm.stylusContact(true, 1_000L)
        palm.reset()
        assertFalse(palm.rejectsFinger(large, 1_010L))
    }
}
