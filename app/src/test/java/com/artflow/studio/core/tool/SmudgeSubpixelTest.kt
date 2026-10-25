package com.artflow.studio.core.tool

import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.core.pixels.SelectionMask
import com.artflow.studio.core.pixels.Stamping
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class SmudgeSubpixelTest {
    private val settings = PixelBrushes.SmudgeSettings(size = 16f, strength = 1f, hardness = 1f)

    @Test
    fun tinyTranslationsAcrossAnIntegerDoNotMoveThePatchByAWholePixel() {
        fun draw(offset: Float): PixelBuffer =
            fixture().also { target ->
                val session = PixelBrushes.beginSmudge(8f + offset, 32f, settings)
                session.dragTo(52.75f + offset, 32f, target)
                session.finish(target)
            }
        val below = draw(-0.0001f)
        val above = draw(0.0001f)
        val delta = maximumChannelDifference(below, above)
        assertTrue("A 0.0002px translation must stay within two 8-bit quantization levels, was $delta", delta <= 2)
        assertTrue(below.getSafe(28, 32) != fixture().getSafe(28, 32))
    }

    @Test
    fun fractionalTransportSamplesTheActualDisplacedSourceAtPixelCenters() {
        val target = fixture()
        val session = PixelBrushes.beginSmudge(32f, 32f, settings)
        session.dragTo(33.75f, 32.25f, target)
        session.finish(target)
        // Pixel (34,33)'s center samples (32.75,33.25): source texel coordinates
        // (32.25,32.75), giving rounded channels 32.25*3 and 32.75*3.
        assertEquals(0xFF616228.toInt(), target.getSafe(34, 33))
    }

    @Test
    fun translatingTheArtworkAndGestureByWholePixelsPreservesEverySample() {
        val original = fixture()
        val translated = PixelBuffer(80, 80)
        for (y in 0 until 64) for (x in 0 until 64) translated.setSafe(x + 8, y + 7, original.getSafe(x, y))
        val first = PixelBrushes.beginSmudge(24.25f, 24.5f, settings)
        val second = PixelBrushes.beginSmudge(32.25f, 31.5f, settings)
        first.dragTo(44.75f, 36.25f, original)
        second.dragTo(52.75f, 43.25f, translated)
        first.finish(original)
        second.finish(translated)
        val shiftedBack = IntArray(64 * 64) { i -> translated.getSafe(i % 64 + 8, i / 64 + 7) }
        assertArrayEquals(original.pixels, shiftedBack)
    }

    @Test
    fun fractionalCoverageHonorsSoftSelectionAndTransparentAlphaLock() {
        val target = fixture()
        target.setSafe(34, 33, 0)
        val before = target.copy()
        val mask = SelectionMask(64, 64, ByteArray(64 * 64) { i -> if (i % 64 >= 32) 128.toByte() else 0 })
        val session = PixelBrushes.beginSmudge(32f, 32f, settings.copy(mask = mask, alphaLock = true))
        session.dragTo(33.75f, 32.25f, target)
        session.finish(target)
        for (y in 0 until 64) for (x in 0 until 32) assertEquals(before.getSafe(x, y), target.getSafe(x, y))
        assertEquals(0, target.getSafe(34, 33))
        assertTrue(target.getSafe(35, 33) != before.getSafe(35, 33))
        assertArrayEquals(before.pixels.map { it ushr 24 }.toIntArray(), target.pixels.map { it ushr 24 }.toIntArray())
    }

    @Test
    fun authoredTipCoverageDoesNotInheritRoundBrushHardness() {
        val tip = Stamping.PatchTexture(shape = { x, y -> (1f - abs(x) - abs(y)).coerceIn(0f, 1f) })

        fun draw(hardness: Float): PixelBuffer =
            fixture().also { target ->
                val session = PixelBrushes.beginSmudge(8.25f, 32.5f, settings.copy(hardness = hardness, texture = tip))
                session.dragTo(52.75f, 32.5f, target)
                session.finish(target)
            }
        assertArrayEquals(draw(0f).pixels, draw(1f).pixels)
    }

    @Test
    fun fractionalPickupWeightsAlphaAndTreatsOutsideTheSourceAsTransparent() {
        val hiddenColor = PixelBuffer(64, 64)
        hiddenColor.setSafe(32, 32, 0x00FF0000)
        hiddenColor.setSafe(33, 32, 0xFF0000FF.toInt())
        val middle = PixelBrushes.beginSmudge(32.5f, 32.5f, settings)
        middle.dragTo(33f, 32.5f, hiddenColor)
        middle.finish(hiddenColor)
        assertEquals("Hidden red must not contaminate the half-covered blue sample", 0x800000FF.toInt(), hiddenColor.getSafe(33, 32))

        val edge = PixelBuffer.filled(8, 8, 0xFF0000FF.toInt())
        val clipped = PixelBrushes.beginSmudge(-0.25f, 2.5f, settings)
        clipped.dragTo(0.25f, 2.5f, edge)
        clipped.finish(edge)
        assertEquals("Half of the footprint samples transparent canvas exterior", 0x800000FF.toInt(), edge.getSafe(0, 2))
    }

    private fun fixture() =
        PixelBuffer(64, 64, IntArray(64 * 64) { i -> 0xFF000000.toInt() or ((i % 64 * 3) shl 16) or ((i / 64 * 3) shl 8) or 40 })

    private fun maximumChannelDifference(
        left: PixelBuffer,
        right: PixelBuffer,
    ): Int =
        left.pixels.indices.maxOf { i ->
            listOf(0, 8, 16, 24).maxOf { shift -> abs(((left.pixels[i] ushr shift) and 255) - ((right.pixels[i] ushr shift) and 255)) }
        }
}
