package com.artflow.studio.data.renderer.opengl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CanvasPreviewStateTest {
    @Test
    fun anAlreadyQueuedFrameCannotReenableInputAfterCompositionFails() {
        val state = CanvasPreviewState()
        val queued = state.generation()
        state.presented(queued, true)
        state.invalidateContent()
        state.presented(queued, true)
        assertEquals(CanvasPreviewAvailability.UNAVAILABLE, state.availability)
        assertTrue(state.generation() > queued)

        state.presented(state.generation(), true)
        assertEquals(CanvasPreviewAvailability.AVAILABLE, state.availability)
    }

    @Test
    fun aNewSurfaceRequiresPresentationButCanReuseTheRetainedCpuImage() {
        val state = CanvasPreviewState()
        val retained = state.generation()
        state.presented(retained, true)
        val beforeReset = state.interruptions
        state.awaitSurface()
        assertEquals(CanvasPreviewAvailability.WAITING_FOR_FRAME, state.availability)
        assertEquals(beforeReset + 1, state.interruptions)
        assertEquals(retained, state.generation())
        state.presented(retained, true)
        assertEquals(CanvasPreviewAvailability.AVAILABLE, state.availability)
    }

    @Test
    fun repeatedFailuresDoNotLoseTheInterruptionWhenThePreviewRecovers() {
        val state = CanvasPreviewState()
        val events = mutableListOf<CanvasPreviewAvailability>()
        state.onChanged = { events += it }
        state.presented(state.generation(), true)
        val activeGesture = state.interruptions
        repeat(3) { state.presented(state.generation(), false) }
        state.presented(state.generation(), true)
        assertTrue("A trailing pointer must still be cancelled after rapid recovery", state.interruptions > activeGesture)
        assertEquals(
            listOf(CanvasPreviewAvailability.AVAILABLE, CanvasPreviewAvailability.UNAVAILABLE, CanvasPreviewAvailability.AVAILABLE),
            events,
        )
    }

    @Test
    fun anOldUploadFailureCannotDisableANewerCorrectFrame() {
        val state = CanvasPreviewState()
        val stale = state.generation()
        state.invalidateContent()
        state.presented(state.generation(), true)
        val recoveredInterruption = state.interruptions
        state.presented(stale, false)
        assertEquals(CanvasPreviewAvailability.AVAILABLE, state.availability)
        assertEquals(recoveredInterruption, state.interruptions)
    }

    @Test
    fun callbacksSeeThePublishedAvailabilityAndInterruptionCount() {
        val state = CanvasPreviewState()
        state.onChanged = { observed ->
            assertEquals(observed, state.availability)
            if (observed == CanvasPreviewAvailability.UNAVAILABLE) assertEquals(1L, state.interruptions)
        }
        state.presented(state.generation(), true)
        state.presented(state.generation(), false)
    }
}
