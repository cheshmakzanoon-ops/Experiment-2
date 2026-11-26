package com.artflow.studio.data.export

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A successful older write must never grant capture while a newer pause is pending or failed. */
class TimelapseControlStateTest {
    @Test
    fun anUncommittedPauseBlocksOnlyItsOwnArtworkUntilASuccessfulRetry() {
        val state = TimelapseControlState()
        val failed = state.begin(1, recording = false)
        assertFalse(state.permitsRecording(1))
        assertTrue(state.permitsRecording(2))
        val retry = state.begin(1, recording = false)
        assertFalse(state.owns(failed))
        state.persisted(failed)
        assertFalse(state.permitsRecording(1))
        state.persisted(retry)
        assertTrue(state.permitsRecording(1))
    }

    @Test
    fun completingAnOlderResumeDoesNotReleaseANewerPause() {
        val state = TimelapseControlState()
        val resume = state.begin(1, recording = true)
        assertFalse(state.permitsRecording(1))
        val pause = state.begin(1, recording = false)
        state.persisted(resume)
        assertFalse(state.permitsRecording(1))
        assertTrue(state.owns(pause))
        state.persisted(pause)
        assertTrue(state.permitsRecording(1))
    }

    @Test
    fun oldPauseCompletionCannotGrantAnUncommittedResume() {
        val state = TimelapseControlState()
        val pause = state.begin(1, recording = false)
        val resume = state.begin(1, recording = true)
        state.persisted(pause)
        assertFalse(state.permitsRecording(1))
        assertTrue(state.owns(resume))
        state.persisted(resume)
        assertTrue(state.permitsRecording(1))
    }
}
