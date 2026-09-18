package com.artflow.studio.core.tool

import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.core.pixels.SelectionMask
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PixelBrushesTest {
    @Test
    fun cloneAlphaLockPreservesTransparentAndFeatheredPixels() {
        val target = fixture()
        val original = target.copy()
        val source = PixelBuffer.filled(32, 32, 0xFFFF0000.toInt())
        val settings = PixelBrushes.CloneSettings(size = 18f, hardness = 1f, alphaLock = true)
        PixelBrushes.beginCloneStamp(13f, 16f, 12f, 16f, settings).dragTo(19f, 16f, target, source)
        assertArrayEquals(original.pixels.map { it ushr 24 }.toIntArray(), target.pixels.map { it ushr 24 }.toIntArray())
        assertFalse(original.pixels.contentEquals(target.pixels))
    }

    @Test
    fun healingAlphaLockPreservesTheWholeCoverageMask() {
        val target = fixture()
        val original = target.copy()
        val source = PixelBuffer.filled(32, 32, 0xFFFF0000.toInt())
        val settings = PixelBrushes.HealingSettings(size = 18f, hardness = 1f, alphaLock = true)
        PixelBrushes.beginHealing(13f, 16f, 12f, 16f, settings).heal(19f, 16f, target, source)
        assertArrayEquals(original.pixels.map { it ushr 24 }.toIntArray(), target.pixels.map { it ushr 24 }.toIntArray())
    }

    @Test
    fun smudgeAlphaLockCannotSmearOpacityIntoEmptySpace() {
        val target = fixture()
        val original = target.copy()
        val settings = PixelBrushes.SmudgeSettings(size = 18f, hardness = 1f, strength = 1f, alphaLock = true)
        PixelBrushes.beginSmudge(10f, 16f, settings).dragTo(18f, 16f, target)
        assertArrayEquals(original.pixels.map { it ushr 24 }.toIntArray(), target.pixels.map { it ushr 24 }.toIntArray())
    }

    @Test
    fun alphaLockedHealingWithAnEmptySourceDoesNotEraseExistingColor() {
        val target = fixture()
        val original = target.copy()
        val settings = PixelBrushes.HealingSettings(size = 18f, hardness = 1f, alphaLock = true)
        PixelBrushes.beginHealing(13f, 16f, 12f, 16f, settings).heal(19f, 16f, target, PixelBuffer(32, 32))
        assertArrayEquals(original.pixels, target.pixels)
    }

    @Test
    fun unlockedCloneAndSmudgeStillExtendArtworkIntoTransparentPixels() {
        val clone = fixture()
        val smudge = clone.copy()
        PixelBrushes
            .beginCloneStamp(13f, 16f, 12f, 16f, PixelBrushes.CloneSettings(size = 18f, hardness = 1f))
            .dragTo(19f, 16f, clone, PixelBuffer.filled(32, 32, 0xFFFF0000.toInt()))
        PixelBrushes
            .beginSmudge(10f, 16f, PixelBrushes.SmudgeSettings(size = 18f, hardness = 1f, strength = 1f))
            .dragTo(18f, 16f, smudge)
        assertTrue((clone.getSafe(19, 16) ushr 24) > 0)
        assertTrue((smudge.getSafe(19, 16) ushr 24) > 0)
    }

    @Test
    fun emptySelectionsBlockEveryRetouchTool() {
        val original = fixture()
        val source = PixelBuffer.filled(32, 32, 0xFFFF0000.toInt())
        val mask = SelectionMask(32, 32)
        for (tool in 0..2) {
            val target = original.copy()
            when (tool) {
                0 ->
                    PixelBrushes
                        .beginCloneStamp(13f, 16f, 12f, 16f, PixelBrushes.CloneSettings(size = 18f, mask = mask))
                        .dragTo(19f, 16f, target, source)
                1 ->
                    PixelBrushes
                        .beginHealing(13f, 16f, 12f, 16f, PixelBrushes.HealingSettings(size = 18f, mask = mask))
                        .heal(19f, 16f, target, source)
                else ->
                    PixelBrushes
                        .beginSmudge(10f, 16f, PixelBrushes.SmudgeSettings(size = 18f, mask = mask))
                        .dragTo(18f, 16f, target)
            }
            assertArrayEquals(original.pixels, target.pixels)
        }
    }

    private fun fixture(): PixelBuffer {
        val pixels =
            IntArray(32 * 32) { index ->
                val x = index % 32
                val y = index / 32
                val alpha =
                    when {
                        x >= 19 -> 0
                        x >= 13 -> 64
                        else -> 255
                    }
                (alpha shl 24) or (y * 7 shl 16) or 0xFF
            }
        return PixelBuffer(32, 32, pixels)
    }
}
