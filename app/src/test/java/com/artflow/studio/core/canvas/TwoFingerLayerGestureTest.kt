package com.artflow.studio.core.canvas

import com.artflow.studio.core.canvas.TwoFingerLayerGesture.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TwoFingerLayerGestureTest {
    private val all = Kind.entries.toSet()

    private fun classify(
        fingers: Int = 2,
        travel: Float = 0f,
        sideways: Float = 0f,
        durationMs: Long = 200L,
        enabled: Set<Kind> = all,
    ) = TwoFingerLayerGesture.classify(
        fingers = fingers,
        travel = travel,
        stillSlop = 16f,
        sideways = sideways,
        swipeThreshold = 48f,
        durationMs = durationMs,
        enabled = enabled,
    )

    @Test
    fun quickStillTapOpensOpacity() {
        assertEquals(Kind.TAP_OPACITY, classify(durationMs = 200L))
        assertEquals(Kind.TAP_OPACITY, classify(durationMs = TwoFingerLayerGesture.TAP_MAX_MS - 1))
    }

    @Test
    fun sidewaysSwipeTogglesAlphaLockEvenWhenItIsLong() {
        assertEquals(Kind.SWIPE_ALPHA_LOCK, classify(travel = 60f, sideways = 60f, durationMs = 150L))
        assertEquals("a swipe exactly at the threshold is not one", null, classify(travel = 48f, sideways = 48f))
    }

    @Test
    fun longStillTouchSelectsContents() {
        assertEquals(Kind.HOLD_SELECT_CONTENTS, classify(durationMs = 600L))
        assertEquals(Kind.HOLD_SELECT_CONTENTS, classify(durationMs = TwoFingerLayerGesture.HOLD_MIN_MS))
    }

    @Test
    fun theGapBetweenTapAndHoldIsNeitherAndMovingIsNotAStillTouch() {
        assertNull(classify(durationMs = 450L))
        assertNull("a drag that is not sideways enough is nothing", classify(travel = 40f, sideways = 0f, durationMs = 600L))
    }

    @Test
    fun onlyExactlyTwoFingersCount() {
        assertNull(classify(fingers = 1))
        assertNull(classify(fingers = 3))
    }

    @Test
    fun aDisabledKindIsNeverReturnedAndTheOthersAreUnchanged() {
        val samples =
            mapOf(
                Kind.TAP_OPACITY to classify(durationMs = 200L),
                Kind.SWIPE_ALPHA_LOCK to classify(travel = 60f, sideways = 60f, durationMs = 150L),
                Kind.HOLD_SELECT_CONTENTS to classify(durationMs = 600L),
            )
        for (off in Kind.entries) {
            for (kind in Kind.entries) {
                val outcome =
                    when (kind) {
                        Kind.TAP_OPACITY -> classify(durationMs = 200L, enabled = all - off)
                        Kind.SWIPE_ALPHA_LOCK -> classify(travel = 60f, sideways = 60f, durationMs = 150L, enabled = all - off)
                        Kind.HOLD_SELECT_CONTENTS -> classify(durationMs = 600L, enabled = all - off)
                    }
                if (kind == off) assertNull("$off is off", outcome) else assertEquals(samples[kind], outcome)
            }
        }
    }
}
