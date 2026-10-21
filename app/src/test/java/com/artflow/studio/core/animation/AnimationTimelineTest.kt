package com.artflow.studio.core.animation

import com.artflow.studio.domain.model.animation.AnimationFrame
import com.artflow.studio.domain.model.animation.AnimationSettings
import com.artflow.studio.domain.model.layer.Layer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnimationTimelineTest {
    private fun state(
        count: Int,
        settings: AnimationSettings = AnimationSettings(),
        active: Int = 0,
    ): AnimationTimeline.State =
        AnimationTimeline.State(
            frames = List(count) { AnimationFrame(it + 1L, "Frame ${it + 1}", emptyList(), durationMs = 100) },
            activeIndex = active,
            settings = settings,
        )

    private fun names(state: AnimationTimeline.State): List<String> = state.frames.map { it.name }

    // --- Frame ordering -------------------------------------------------------------------------

    @Test
    fun newBlankFrameGoesAfterTheActiveOneAndBecomesActive() {
        val result = AnimationTimeline.addBlankFrame(state(3, active = 0))
        assertEquals(4, result.frameCount)
        assertEquals(1, result.activeIndex)
        assertEquals(listOf(1L, 4L, 2L, 3L), result.frames.map { it.id })
    }

    @Test
    fun duplicateInsertsACopyAfterTheActiveFrameWithFreshLayerAndStrokeIds() {
        val source =
            AnimationFrame(
                id = 1L,
                name = "Frame 1",
                layers =
                    listOf(
                        Layer(id = 5L, name = "Ink", index = 0, strokes = emptyList()),
                        Layer(id = 6L, name = "Colour", index = 1, strokes = emptyList()),
                    ),
            )
        val result = AnimationTimeline.duplicateFrame(AnimationTimeline.State(frames = listOf(source), activeIndex = 0))
        assertEquals(2, result.frameCount)
        assertEquals(1, result.activeIndex)
        val copy = result.frames[1]
        assertEquals("Frame 1 copy", copy.name)
        assertNotEquals(source.id, copy.id)
        val copiedLayerIds = copy.layers.map { it.id }
        assertTrue(copiedLayerIds.none { it == 5L || it == 6L })
        assertEquals(copiedLayerIds.size, copiedLayerIds.toSet().size)
    }

    @Test
    fun deletingBeforeTheActiveFrameKeepsTheSameFrameActive() {
        val result = AnimationTimeline.deleteFrame(state(4, active = 2), index = 0)
        assertEquals(listOf("Frame 2", "Frame 3", "Frame 4"), names(result))
        assertEquals(1, result.activeIndex)
        assertEquals("Frame 3", result.activeFrame?.name)
    }

    @Test
    fun deletingTheActiveFrameSelectsTheFrameThatTakesItsPlace() {
        val result = AnimationTimeline.deleteFrame(state(3, active = 2), index = 2)
        assertEquals(listOf("Frame 1", "Frame 2"), names(result))
        assertEquals(1, result.activeIndex)
    }

    @Test
    fun theLastRemainingFrameCannotBeDeleted() {
        val single = state(1)
        assertEquals(single, AnimationTimeline.deleteFrame(single, index = 0))
    }

    @Test
    fun movingAFrameKeepsItActiveAndShiftsTheFramesBetween() {
        val result = AnimationTimeline.moveFrame(state(3, active = 0), from = 0, to = 2)
        assertEquals(listOf("Frame 2", "Frame 3", "Frame 1"), names(result))
        assertEquals(2, result.activeIndex)
        assertEquals("Frame 1", result.activeFrame?.name)
    }

    @Test
    fun movingBackwardsShiftsTheFramesBetweenForward() {
        val result = AnimationTimeline.moveFrame(state(4, active = 3), from = 3, to = 1)
        assertEquals(listOf("Frame 1", "Frame 4", "Frame 2", "Frame 3"), names(result))
    }

    @Test
    fun movingToAnOutOfRangeSlotClampsToTheEnds() {
        assertEquals(listOf("Frame 2", "Frame 3", "Frame 1"), names(AnimationTimeline.moveFrame(state(3), 0, 99)))
        assertEquals(listOf("Frame 3", "Frame 1", "Frame 2"), names(AnimationTimeline.moveFrame(state(3, active = 2), 2, -5)))
    }

    @Test
    fun movingAFrameOntoItselfChangesNothing() {
        val start = state(3)
        assertEquals(start, AnimationTimeline.moveFrame(start, from = 1, to = 1))
    }

    // --- Hold durations -------------------------------------------------------------------------

    @Test
    fun aFrameHoldIsClampedToTheSupportedRange() {
        val base = state(2)
        assertEquals(AnimationFrame.MIN_DURATION_MS, AnimationTimeline.setFrameDuration(base, 0, 1).frames[0].durationMs)
        assertEquals(AnimationFrame.MAX_DURATION_MS, AnimationTimeline.setFrameDuration(base, 1, 999_999).frames[1].durationMs)
        assertEquals(250, AnimationTimeline.setFrameDuration(base, 1, 250).frames[1].durationMs)
    }

    @Test
    fun aHoldOnAMissingFrameChangesNothing() {
        val base = state(2)
        assertEquals(base, AnimationTimeline.setFrameDuration(base, 7, 250))
    }

    @Test
    fun changingFpsRetimesOnlyFramesThatStillUseTheDefaultHold() {
        val mixed =
            AnimationTimeline.State(
                frames =
                    listOf(
                        AnimationFrame(1L, "Frame 1", emptyList(), durationMs = AnimationFrame.DEFAULT_DURATION_MS),
                        AnimationFrame(2L, "Frame 2", emptyList(), durationMs = 250),
                    ),
            )
        val result = AnimationTimeline.applyFps(mixed, fps = 20)
        assertEquals(50, result.frames[0].durationMs)
        assertEquals(250, result.frames[1].durationMs)
        assertEquals(20, result.settings.fps)
    }

    @Test
    fun fpsIsClampedToOneToSixtyFrames() {
        assertEquals(AnimationSettings.MIN_FPS, AnimationTimeline.applyFps(state(1), fps = 0).settings.fps)
        assertEquals(AnimationSettings.MAX_FPS, AnimationTimeline.applyFps(state(1), fps = 500).settings.fps)
    }

    @Test
    fun totalDurationAddsEveryHold() {
        val result = AnimationTimeline.setFrameDuration(state(3), 1, 400)
        assertEquals(600L, result.durationMs)
    }

    // --- Onion skin ranges ----------------------------------------------------------------------

    @Test
    fun onionGhostsCoverTheNearestFramesOnEachSideWithFadingAlpha() {
        val ghosts =
            AnimationTimeline.onionGhosts(
                state(7, AnimationSettings(onionSkinFrames = 2, onionSkinOpacity = 0.8f), active = 3),
            )
        assertGhosts(listOf(2 to 0.8f, 1 to 0.4f), ghosts.previous)
        assertGhosts(listOf(4 to 0.8f, 5 to 0.4f), ghosts.next)
    }

    @Test
    fun onionGhostCountIsCappedAtTwelveOnEachSide() {
        val ghosts = AnimationTimeline.onionGhosts(state(30, AnimationSettings(onionSkinFrames = 99), active = 15))
        assertEquals((3..14).reversed().toList(), ghosts.previous.map { it.first })
        assertEquals((16..27).toList(), ghosts.next.map { it.first })
    }

    @Test
    fun onionGhostsStopAtTheTimelineEnds() {
        val first = AnimationTimeline.onionGhosts(state(4, AnimationSettings(onionSkinFrames = 2), active = 0))
        assertTrue(first.previous.isEmpty())
        assertEquals(listOf(1, 2), first.next.map { it.first })
        val last = AnimationTimeline.onionGhosts(state(4, AnimationSettings(onionSkinFrames = 2), active = 3))
        assertEquals(listOf(2, 1), last.previous.map { it.first })
        assertTrue(last.next.isEmpty())
    }

    @Test
    fun zeroGhostFramesOrAnEmptyTimelineShowNothing() {
        assertTrue(AnimationTimeline.onionGhosts(state(3, AnimationSettings(onionSkinFrames = 0), active = 1)).previous.isEmpty())
        val none = AnimationTimeline.onionGhosts(AnimationTimeline.State())
        assertTrue(none.previous.isEmpty() && none.next.isEmpty())
    }

    @Test
    fun zeroOnionOpacityHidesAllGhosts() {
        val ghosts = AnimationTimeline.onionGhosts(state(3, AnimationSettings(onionSkinFrames = 1, onionSkinOpacity = 0f), active = 1))
        assertTrue(ghosts.previous.isEmpty() && ghosts.next.isEmpty())
    }

    @Test
    fun onionSkinIsOnlyShownWhenItsOwnSwitchIsOn() {
        val settings = AnimationSettings(onionSkinFrames = 1)
        assertTrue(AnimationTimeline.onionSkin(state(3, settings, active = 1)).previous.isEmpty())
        val on = settings.copy(onionSkinEnabled = true)
        val opacity = AnimationSettings().onionSkinOpacity
        assertGhosts(listOf(0 to opacity), AnimationTimeline.onionSkin(state(3, on, active = 1)).previous)
    }

    private fun assertGhosts(
        expected: List<Pair<Int, Float>>,
        actual: List<Pair<Int, Float>>,
    ) {
        assertEquals(expected.map { it.first }, actual.map { it.first })
        expected.zip(actual).forEach { (want, got) -> assertEquals(want.second, got.second, 1e-6f) }
    }
}
