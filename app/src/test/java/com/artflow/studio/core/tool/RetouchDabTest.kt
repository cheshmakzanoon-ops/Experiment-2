package com.artflow.studio.core.tool

import com.artflow.studio.core.pixels.PixelBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RetouchDabTest {
    @Test
    fun cloneTapStampsOnceWithoutAccumulatingStationarySamples() {
        val target = PixelBuffer(32, 32)
        val source = PixelBuffer.filled(32, 32, RED)
        val session = PixelBrushes.beginCloneStamp(20f, 16f, 8f, 16f, PixelBrushes.CloneSettings(size = 8f, opacity = 0.5f))

        assertNotNull(session.dragTo(20f, 16f, target, source))
        assertTrue(target.getSafe(20, 16) ushr 24 in 127..128)
        val once = target.pixels.copyOf()
        repeat(4) { assertNull(session.dragTo(20f, 16f, target, source)) }
        assertArrayEquals(once, target.pixels)
    }

    @Test
    fun healingTapChangesTheBlemishOnce() {
        val target = healingTarget()
        val source = PixelBuffer.filled(32, 32, GRAY)
        val session = PixelBrushes.beginHealing(16f, 16f, 8f, 8f, PixelBrushes.HealingSettings(size = 8f, strength = 0.5f))

        assertNotNull(session.dragTo(16f, 16f, target, source))
        assertTrue(target.getSafe(16, 16) != DARK)
        val once = target.pixels.copyOf()
        assertNull(session.dragTo(16f, 16f, target, source))
        assertArrayEquals(once, target.pixels)
    }

    @Test
    fun firstMovedSampleIncludesThePenDownPosition() {
        val source = PixelBuffer.filled(32, 32, RED)
        val clone = PixelBuffer(32, 32)
        val healing = healingTarget()
        PixelBrushes
            .beginCloneStamp(16f, 16f, 8f, 8f, PixelBrushes.CloneSettings(size = 2f))
            .dragTo(22f, 16f, clone, source)
        PixelBrushes
            .beginHealing(16f, 16f, 8f, 8f, PixelBrushes.HealingSettings(size = 2f))
            .dragTo(22f, 16f, healing, PixelBuffer.filled(32, 32, GRAY))

        assertEquals(RED, clone.getSafe(16, 16))
        assertFalse(healing.getSafe(16, 16) == DARK)
    }

    @Test
    fun emptyOrOutOfBoundsHealingSourceDoesNotEraseUnlockedArtwork() {
        val target = healingTarget()
        val before = target.pixels.copyOf()
        PixelBrushes
            .beginHealing(16f, 16f, 8f, 8f, PixelBrushes.HealingSettings(size = 8f))
            .dragTo(16f, 16f, target, PixelBuffer(32, 32))
        assertArrayEquals(before, target.pixels)

        PixelBrushes
            .beginHealing(16f, 16f, -100f, -100f, PixelBrushes.HealingSettings(size = 8f))
            .dragTo(18f, 16f, target, PixelBuffer.filled(32, 32, RED))
        assertArrayEquals(before, target.pixels)
    }

    @Test
    fun reversalDamageIncludesThePreviousSampleForBothRetouchTools() {
        val source = PixelBuffer.filled(64, 32, RED)
        val clone = PixelBuffer(64, 32)
        val cloneSession = PixelBrushes.beginCloneStamp(8f, 16f, 8f, 16f, PixelBrushes.CloneSettings(size = 4f))
        cloneSession.dragTo(50f, 16f, clone, source)
        val cloneDamage = requireNotNull(cloneSession.dragTo(20f, 16f, clone, source))
        assertTrue(cloneDamage.contains(48, 16))

        val healing = PixelBuffer.filled(64, 32, GRAY)
        val healSession = PixelBrushes.beginHealing(8f, 16f, 8f, 16f, PixelBrushes.HealingSettings(size = 4f))
        healSession.dragTo(50f, 16f, healing, source)
        val healDamage = requireNotNull(healSession.dragTo(20f, 16f, healing, source))
        assertTrue(healDamage.contains(48, 16))
    }

    private fun healingTarget(): PixelBuffer = PixelBuffer.filled(32, 32, GRAY).apply { setUnchecked(16, 16, DARK) }

    private companion object {
        const val RED = 0xFFFF0000.toInt()
        const val GRAY = 0xFF808080.toInt()
        const val DARK = 0xFF101010.toInt()
    }
}
