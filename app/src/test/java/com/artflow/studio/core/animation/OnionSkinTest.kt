package com.artflow.studio.core.animation

import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.domain.model.animation.AnimationSettings
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OnionSkinTest {
    private val tinted = AnimationSettings(onionSkinTinted = true, onionSkinOpacity = 0.6f)

    @Test
    fun earlierAndLaterFramesTakeTheirOwnTints() {
        assertEquals(tinted.onionSkinPreviousColor, OnionSkin.tint(tinted, -2))
        assertEquals(tinted.onionSkinNextColor, OnionSkin.tint(tinted, 1))
        assertNull(OnionSkin.tint(tinted, 0))
        assertNull(OnionSkin.tint(AnimationSettings(), -1))
    }

    @Test
    fun nearerFramesShowMoreStrongly() {
        assertEquals(0.6f, OnionSkin.opacity(tinted, -1), 1e-6f)
        assertEquals(0.3f, OnionSkin.opacity(tinted, 2), 1e-6f)
        assertEquals(0f, OnionSkin.opacity(tinted, 0), 0f)
    }

    @Test
    fun tintingKeepsCoverageAndReplacesColour() {
        val frame = PixelBuffer(3, 1, intArrayOf(0x00000000, 0x80123456.toInt(), 0xFF00FF00.toInt()))
        OnionSkin.tinted(frame, 0xFFFF0000.toInt())
        assertArrayEquals(intArrayOf(0x00000000, 0x80FF0000.toInt(), 0xFFFF0000.toInt()), frame.pixels)
    }
}
