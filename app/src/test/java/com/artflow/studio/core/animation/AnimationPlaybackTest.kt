package com.artflow.studio.core.animation

import com.artflow.studio.core.export.GifEncoder
import com.artflow.studio.domain.model.animation.AnimationFrame
import com.artflow.studio.domain.model.animation.AnimationSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import javax.imageio.stream.MemoryCacheImageInputStream

class AnimationPlaybackTest {
    private val ids = listOf(11L, 22L, 33L, 44L, 55L, 66L, 77L)

    private fun state(settings: AnimationSettings = AnimationSettings()): AnimationTimeline.State =
        AnimationTimeline.State(
            frames = ids.mapIndexed { index, id -> AnimationFrame(id, "Frame $id", emptyList(), durationMs = (index + 1) * 100) },
            settings = settings,
        )

    @Test
    fun legacyPinsAreExcludedFromMovingFramesAndStillComposite() {
        val settings = AnimationSettings(backgroundFrame = true, foregroundFrame = true)
        assertEquals(listOf(1, 2, 3, 4, 5), AnimationPlayback.forwardIndices(ids, settings))
        assertEquals(listOf(0, 3, 6), AnimationPlayback.compositeIndices(ids, settings, 3))
    }

    @Test
    fun multiplePinsKeepTheirStackOrderWithoutBecomingPlaybackIntervals() {
        val settings = AnimationSettings(backgroundFrameIds = setOf(11, 22), foregroundFrameIds = setOf(66, 77))
        assertEquals(listOf(2, 3, 4), AnimationPlayback.forwardIndices(ids, settings))
        assertEquals(listOf(0, 1, 3, 5, 6), AnimationPlayback.compositeIndices(ids, settings, 3))
        assertEquals(listOf(0, 1, 5, 6), AnimationPlayback.compositeIndices(ids, settings, 1))
    }

    @Test
    fun hiddenPinsDoNotCompositeButRemainAvailableForEditing() {
        val settings = AnimationSettings(backgroundFrameIds = setOf(11, 22), hiddenFrameIds = setOf(11))
        assertEquals(listOf(1, 3), AnimationPlayback.compositeIndices(ids, settings, 3))
        assertEquals(listOf(0, 1), AnimationPlayback.compositeIndices(ids, settings, 0))
    }

    @Test
    fun rangeVisibilityAndPinsAreAppliedBeforeBuildingTheReverseLeg() {
        val settings =
            AnimationSettings(
                playbackRangeStart = 1,
                playbackRangeEnd = 5,
                hiddenFrameIds = setOf(33),
                foregroundFrameIds = setOf(55),
                pingPong = true,
            )
        assertEquals(listOf(1, 3, 5, 3), AnimationPlayback.cycleIndices(ids, settings))
        assertEquals(1600L, AnimationPlayback.durationMs(state(settings)))
    }

    @Test
    fun legacyNonLoopingPingPongRetainsItsReturnLegAndFinalFirstFrameHold() {
        val settings = AnimationSettings(loop = false, pingPong = true, playbackRangeStart = 2, playbackRangeEnd = 4)
        assertEquals(listOf(2, 3, 4, 3, 2), AnimationPlayback.cycleIndices(ids, settings))
        assertEquals(1900L, AnimationPlayback.durationMs(state(settings)))
        val timeline = state(settings)
        var cursor = PlaybackStepper.start(timeline)
        val preview = mutableListOf<Int>()
        repeat(6) {
            cursor?.let { current ->
                preview += current.index
                cursor = PlaybackStepper.next(timeline, current)
            }
        }
        assertNull(cursor)
        assertEquals(AnimationPlayback.cycleIndices(timeline), preview)
    }

    @Test
    fun previewVisitsExactlyTheSameCycleAsExportWithInvisibleHoles() {
        val timeline = state(AnimationSettings(pingPong = true, hiddenFrameIds = setOf(22, 44), backgroundFrameIds = setOf(77)))
        val expected = AnimationPlayback.cycleIndices(timeline)
        var cursor = requireNotNull(PlaybackStepper.start(timeline))
        val actual = mutableListOf<Int>()
        repeat(expected.size * 2) {
            actual += cursor.index
            cursor = requireNotNull(PlaybackStepper.next(timeline, cursor))
        }
        assertEquals(expected + expected, actual)
    }

    @Test
    fun emptyAllHiddenAndAllPinnedClipsHaveNoPlayableCycle() {
        assertTrue(AnimationPlayback.cycleIndices(emptyList(), AnimationSettings()).isEmpty())
        val hidden = state(AnimationSettings(hiddenFrameIds = ids.toSet()))
        assertTrue(AnimationPlayback.cycleIndices(hidden).isEmpty())
        assertNull(PlaybackStepper.start(hidden))
        assertTrue(AnimationPlayback.cycleIndices(ids, AnimationSettings(backgroundFrameIds = ids.toSet())).isEmpty())
    }

    @Test
    fun oneAndTwoMovingFramesNeverDuplicateEndpoints() {
        val settings = AnimationSettings(pingPong = true, hiddenFrameIds = ids.drop(2).toSet())
        assertEquals(listOf(0, 1), AnimationPlayback.cycleIndices(ids, settings))
        val single = settings.copy(hiddenFrameIds = ids.drop(1).toSet())
        assertEquals(listOf(0), AnimationPlayback.cycleIndices(ids, single))
        assertNull(PlaybackStepper.start(state(single)))
    }

    @Test
    fun hiddenOrPinnedCurrentFramesStartAtTheFirstMovingFrame() {
        val timeline = state(AnimationSettings(backgroundFrameIds = setOf(11), hiddenFrameIds = setOf(22)))
        assertEquals(2, PlaybackStepper.start(timeline)!!.index)
        assertEquals(2, PlaybackStepper.start(timeline.copy(activeIndex = 1))!!.index)
        assertEquals(4, PlaybackStepper.start(timeline.copy(activeIndex = 4))!!.index)
    }

    @Test
    fun changingAFrameRoleIsExclusiveAndKeepsOtherPins() {
        val legacy = AnimationSettings(backgroundFrame = true, foregroundFrame = true)
        val first = AnimationPlayback.withRole(ids, legacy, 2, AnimationPlayback.Role.BACKGROUND)
        assertFalse(first.backgroundFrame || first.foregroundFrame)
        assertEquals(setOf(11L, 33L), first.backgroundFrameIds)
        assertEquals(setOf(77L), first.foregroundFrameIds)
        val moved = AnimationPlayback.withRole(ids, first, 2, AnimationPlayback.Role.FOREGROUND)
        assertEquals(setOf(11L), moved.backgroundFrameIds)
        assertEquals(setOf(33L, 77L), moved.foregroundFrameIds)
        val ordinary = AnimationPlayback.withRole(ids, moved, 2, AnimationPlayback.Role.ANIMATED)
        assertEquals(setOf(77L), ordinary.foregroundFrameIds)
    }

    @Test
    fun pinsFollowIdentitiesAfterReorderingAndDeletedIdentitiesArePruned() {
        val settings = AnimationPlayback.materializePins(ids, AnimationSettings(backgroundFrame = true, foregroundFrame = true))
        val reordered = ids.reversed()
        assertEquals(AnimationPlayback.Role.BACKGROUND, AnimationPlayback.role(reordered, settings, 6))
        assertEquals(AnimationPlayback.Role.FOREGROUND, AnimationPlayback.role(reordered, settings, 0))
        val pruned = AnimationPlayback.materializePins(ids.drop(1), settings.copy(hiddenFrameIds = setOf(11)))
        assertTrue(pruned.backgroundFrameIds.isEmpty())
        assertTrue(pruned.hiddenFrameIds.isEmpty())
    }

    @Test
    fun ghostsSkipHiddenAndPinnedDrawingsAndFadeByVisibleNeighbourDistance() {
        val settings =
            AnimationSettings(
                onionSkinFrames = 3,
                onionSkinOpacity = 0.8f,
                backgroundFrameIds = setOf(11),
                foregroundFrameIds = setOf(77),
                hiddenFrameIds = setOf(33),
            )
        val ghosts = AnimationTimeline.onionGhosts(state(settings).copy(activeIndex = 3))
        assertEquals(listOf(1 to 0.8f), ghosts.previous)
        assertEquals(listOf(4 to 0.8f, 5 to 0.4f), ghosts.next)
        assertTrue(AnimationTimeline.onionGhosts(state(settings)).next.isEmpty())
    }

    @Test
    fun gifEncodingKeepsOrderedArtworkAndAsymmetricHoldsAcrossTheBounce() {
        val settings = AnimationSettings(pingPong = true, playbackRangeStart = 1, playbackRangeEnd = 3)
        val timeline = state(settings)
        val order = AnimationPlayback.cycleIndices(timeline)
        val colors = intArrayOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt())
        val bytes =
            GifEncoder.encode(
                frames = order.map { intArrayOf(colors[it - 1]) },
                width = 1,
                height = 1,
                delaysMs = order.map { timeline.frames[it].durationMs },
            )
        val reader = ImageIO.getImageReadersByFormatName("gif").next()
        MemoryCacheImageInputStream(ByteArrayInputStream(bytes)).use { input ->
            try {
                reader.input = input
                assertEquals(4, reader.getNumImages(true))
                val durations = mutableListOf<Int>()
                order.forEachIndexed { index, source ->
                    assertEquals(colors[source - 1], reader.read(index).getRGB(0, 0))
                    val root = reader.getImageMetadata(index).getAsTree("javax_imageio_gif_image_1.0")
                    var node = root.firstChild
                    while (node.nodeName != "GraphicControlExtension") node = node.nextSibling
                    durations += node.attributes
                        .getNamedItem("delayTime")
                        .nodeValue
                        .toInt() * 10
                }
                assertEquals(listOf(200, 300, 400, 300), durations)
            } finally {
                reader.dispose()
            }
        }
    }
}
