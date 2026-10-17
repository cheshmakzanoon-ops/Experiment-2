package com.artflow.studio.core.animation

import com.artflow.studio.domain.model.animation.AnimationSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayModeTest {
    @Test
    fun storedSwitchesReadAsOneModeEach() {
        assertEquals(PlayMode.LOOP, PlayMode.of(AnimationSettings(loop = true, pingPong = false)))
        assertEquals(PlayMode.PING_PONG, PlayMode.of(AnimationSettings(loop = true, pingPong = true)))
        assertEquals(PlayMode.ONE_SHOT, PlayMode.of(AnimationSettings(loop = false, pingPong = false)))
    }

    @Test
    fun contradictoryLegacySwitchesResolveToOneShot() {
        assertEquals(PlayMode.ONE_SHOT, PlayMode.of(AnimationSettings(loop = false, pingPong = true)))
    }

    @Test
    fun everyModeIsWrittenAsItsOwnSwitchPair() {
        val base = AnimationSettings(fps = 24, onionSkinFrames = 2)
        for (mode in PlayMode.entries) {
            val written = base.withPlayMode(mode)
            assertEquals(mode, PlayMode.of(written))
            // Only the play switches change; the other settings keep their values.
            assertEquals(24, written.fps)
            assertEquals(2, written.onionSkinFrames)
        }
    }

    @Test
    fun writingAModeOverridesContradictoryLegacySwitches() {
        val legacy = AnimationSettings(loop = false, pingPong = true)
        val oneShot = legacy.withPlayMode(PlayMode.ONE_SHOT)
        assertFalse(oneShot.loop)
        assertFalse(oneShot.pingPong)
        val pingPong = legacy.withPlayMode(PlayMode.PING_PONG)
        assertTrue(pingPong.loop)
        assertTrue(pingPong.pingPong)
    }

    @Test
    fun modesHaveTheNamesThePanelShows() {
        assertEquals(listOf("Loop", "Ping-pong", "One shot"), PlayMode.entries.map { it.displayName })
    }
}
