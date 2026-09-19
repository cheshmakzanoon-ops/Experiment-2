package com.artflow.studio.core.animation

import com.artflow.studio.domain.model.animation.AnimationFrame
import com.artflow.studio.domain.model.animation.AnimationSettings
import org.junit.Assert.*
import org.junit.Test

class PlaybackStepperTest {
    @Test
    fun emptyAndSingleFrameRangesDoNotPlay() {
        assertNull(PlaybackStepper.start(AnimationTimeline.State()))
        assertNull(PlaybackStepper.start(state(1)))
        assertNull(PlaybackStepper.start(state(4, AnimationSettings(playbackRangeStart = 2, playbackRangeEnd = 2))))
    }

    @Test
    fun finiteForwardClipStopsAndCanRestartAtItsFirstFrame() {
        val state = state(3, AnimationSettings(loop = false))
        assertEquals(listOf(0, 1, 2), sequence(state, 10))
        assertEquals(0, PlaybackStepper.start(state.copy(activeIndex = 2))!!.index)
    }

    @Test
    fun finitePingPongStopsAfterOneReturnPass() {
        val state = state(3, AnimationSettings(loop = false, pingPong = true))
        assertEquals(listOf(0, 1, 2, 1, 0), sequence(state, 10))
    }

    @Test
    fun twoFramePingPongDoesNotDuplicateEndpoints() {
        val state = state(2, AnimationSettings(loop = true, pingPong = true))
        assertEquals(listOf(0, 1, 0, 1, 0, 1), sequence(state, 6))
    }

    @Test
    fun restrictedRangeNormalizesAnOutOfRangeStart() {
        val state = state(5, AnimationSettings(playbackRangeStart = 1, playbackRangeEnd = 3))
        assertEquals(listOf(1, 2, 3, 1, 2, 3), sequence(state, 6))
    }

    @Test
    fun validCurrentFrameStartsWithoutAnUnexpectedJump() {
        val state = state(4).copy(activeIndex = 2)
        assertEquals(listOf(2, 3, 0, 1), sequence(state, 4))
    }

    private fun state(
        count: Int,
        settings: AnimationSettings = AnimationSettings(),
    ): AnimationTimeline.State =
        AnimationTimeline.State(
            frames = List(count) { AnimationFrame(it.toLong() + 1, "Frame", emptyList()) },
            settings = settings,
        )

    private fun sequence(
        state: AnimationTimeline.State,
        count: Int,
    ): List<Int> {
        var cursor = PlaybackStepper.start(state)
        val indices = mutableListOf<Int>()
        repeat(count) {
            val current = cursor ?: return indices
            indices += current.index
            cursor = PlaybackStepper.next(state, current)
        }
        return indices
    }
}
