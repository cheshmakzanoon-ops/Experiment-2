package com.artflow.studio.core.tool

import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.core.pixels.SelectionMask
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Pixel ramps make the direction of the visible result observable, not just the map's sign. */
class LiquifyToolTest {
    private val width = 65
    private val center = 32.5f

    @Test
    fun pushMovesVisibleArtworkWithThePointer() {
        val horizontal = ramp(horizontal = true)
        val right = session(LiquifyTool.Mode.PUSH).apply { dragTo(center + 4f, center) }
        val left = session(LiquifyTool.Mode.PUSH).apply { dragTo(center - 4f, center) }
        assertTrue(channel(right.render(horizontal), 32, 32) < channel(horizontal, 32, 32))
        assertTrue(channel(left.render(horizontal), 32, 32) > channel(horizontal, 32, 32))
        val vertical = ramp(horizontal = false)
        val down = session(LiquifyTool.Mode.PUSH).apply { dragTo(center, center + 4f) }
        assertTrue(channel(down.render(vertical), 32, 32) < channel(vertical, 32, 32))
    }

    @Test
    fun clockwiseTwirlMovesRightHandContentDownward() {
        val source = ramp(horizontal = false)
        val clockwise = session(LiquifyTool.Mode.TWIRL_CLOCKWISE).apply { dragTo(center + 0.01f, center + 4f) }
        val counter = session(LiquifyTool.Mode.TWIRL_COUNTER_CLOCKWISE).apply { dragTo(center + 0.01f, center + 4f) }
        assertTrue(channel(clockwise.render(source), 40, 34) < channel(source, 40, 34))
        assertTrue(channel(counter.render(source), 40, 34) > channel(source, 40, 34))
    }

    @Test
    fun pinchPullsArtworkInAndBloatPushesItOut() {
        val source = ramp(horizontal = true)
        val pinch = session(LiquifyTool.Mode.PINCH).apply { dragTo(center, center + 5f) }
        val bloat = session(LiquifyTool.Mode.BLOAT).apply { dragTo(center, center + 5f) }
        assertTrue(channel(pinch.render(source), 40, 34) > channel(source, 40, 34))
        assertTrue(channel(bloat.render(source), 40, 34) < channel(source, 40, 34))
    }

    @Test
    fun pressureZeroAndZeroStrengthAreTrueNoOps() {
        for (mode in LiquifyTool.Mode.entries) {
            val zeroPressure = session(mode).apply { dragTo(center + 8, center, pressure = 0f) }
            val zeroStrength = session(mode, strength = 0f).apply { dragTo(center + 8, center) }
            val source = ramp(true)
            val original = PixelBuffer.filled(width, width, 0xFF0000FF.toInt())
            assertArrayEquals(source.pixels, zeroPressure.render(source, original).pixels)
            assertArrayEquals(source.pixels, zeroStrength.render(source, original).pixels)
        }
    }

    @Test
    fun livePressureAndFeatheredSelectionScaleDisplacement() {
        val full = session(LiquifyTool.Mode.PUSH).apply { dragTo(center + 3f, center) }
        val halfPressure = session(LiquifyTool.Mode.PUSH).apply { dragTo(center + 3f, center, pressure = 0.5f) }
        val mask = SelectionMask(width, width, ByteArray(width * width) { 128.toByte() })
        val halfSelection = session(LiquifyTool.Mode.PUSH, mask = mask).apply { dragTo(center + 3f, center) }
        val index = 32 * width + 32
        assertEquals(full.map.offsetX[index] * 0.5f, halfPressure.map.offsetX[index], 0.0001f)
        assertEquals(full.map.offsetX[index] * (128f / 255), halfSelection.map.offsetX[index], 0.0001f)
    }

    @Test
    fun allModesRespectFreezeAndEmptySelectionIncludingReconstruct() {
        val source = ramp(true)
        val reference = PixelBuffer.filled(width, width, 0xFF0000FF.toInt())
        for (mode in LiquifyTool.Mode.entries) {
            val frozen = session(mode, freeze = BooleanArray(width * width) { true })
            val unselected = session(mode, mask = SelectionMask(width, width))
            frozen.dragTo(center + 10f, center)
            unselected.dragTo(center + 10f, center)
            assertArrayEquals(source.pixels, frozen.render(source, reference).pixels)
            assertArrayEquals(source.pixels, unselected.render(source, reference).pixels)
        }
    }

    @Test
    fun sessionOwnsMasksSoExternalMutationCannotChangeTheGesture() {
        val mask = SelectionMask(width, width).apply { selectAll() }
        val freeze = BooleanArray(width * width)
        val owned = session(LiquifyTool.Mode.PUSH, mask = mask, freeze = freeze)
        mask.coverage.fill(0)
        freeze.fill(true)
        owned.dragTo(center + 5f, center)
        assertFalse(owned.map.isEmpty())
    }

    @Test
    fun splittingAStrokeDoesNotMultiplyItsStrengthByTheEventCount() {
        val whole = session(LiquifyTool.Mode.PUSH).apply { dragTo(center + 16f, center) }
        val split = session(LiquifyTool.Mode.PUSH)
        for (sample in 1..64) split.dragTo(center + sample * 0.25f, center)
        val delta =
            whole.map.offsetX.indices
                .maxOf { abs(whole.map.offsetX[it] - split.map.offsetX[it]) }
        assertTrue("Sampling must approximate the same path integral, not count MotionEvents", delta < 0.06f)
    }

    @Test
    fun twirlAndRadialToolsFollowTheEntireDragRatherThanOnlyItsStart() {
        for (mode in listOf(LiquifyTool.Mode.TWIRL_CLOCKWISE, LiquifyTool.Mode.PINCH, LiquifyTool.Mode.BLOAT)) {
            val drag = LiquifyTool.beginSession(8.5f, center, LiquifyTool.Settings(mode = mode, size = 12f, strength = 1f), width, width)
            drag.dragTo(50.5f, center)
            assertTrue("The far end of the drag must receive distortion", abs(drag.map.offsetY[34 * width + 46]) > 0.01f)
            assertEquals(0f, drag.map.offsetX[2 * width + 2], 0f)
        }
    }

    @Test
    fun reconstructProgressivelyRestoresFromTheFixedOriginal() {
        val source = PixelBuffer.filled(width, width, 0xFFFF0000.toInt())
        val original = PixelBuffer.filled(width, width, 0xFF0000FF.toInt())
        val repair = session(LiquifyTool.Mode.RECONSTRUCT)
        repair.dragTo(center + 10f, center)
        val first = repair.render(source, original)
        val before = first.getSafe(34, 32)
        assertTrue(((before ushr 16) and 255) in 1..254)
        assertTrue((before and 255) > 0)
        assertEquals(0xFFFF0000.toInt(), first.getSafe(0, 0))
        assertArrayEquals(first.pixels, repair.render(source, original).pixels)
        repair.dragTo(center, center)
        val second = repair.render(source, original).getSafe(34, 32)
        assertTrue(((second ushr 16) and 255) < ((before ushr 16) and 255))
        assertEquals(255, second ushr 24)
        assertTrue(source.pixels.all { it == 0xFFFF0000.toInt() })
        assertTrue(original.pixels.all { it == 0xFF0000FF.toInt() })
    }

    @Test
    fun reconstructInterpolatesPremultipliedAlphaWithoutDarkFringes() {
        val source = PixelBuffer.filled(width, width, 0xFFFF0000.toInt())
        val original = PixelBuffer(width, width)
        val repair = session(LiquifyTool.Mode.RECONSTRUCT).apply { dragTo(center + 10f, center) }
        val pixel = repair.render(source, original).getSafe(34, 32)
        assertTrue((pixel ushr 24) in 1..254)
        assertEquals(255, (pixel ushr 16) and 255)
        assertEquals(0, pixel and 0xFFFF)
    }

    @Test
    fun invalidOrZeroDisplacementsDoNotCreateDirtyMaps() {
        val map = LiquifyTool.DisplacementMap(2, 2)
        map.add(0, 0, 0f, 0f)
        map.add(0, 0, Float.NaN, 1f)
        map.add(0, 0, 1f, Float.POSITIVE_INFINITY)
        assertTrue(map.isEmpty())
        val source = PixelBuffer.filled(2, 2, 0xFFFFFFFF.toInt())
        assertArrayEquals(source.pixels, map.apply(source).pixels)
    }

    @Test(expected = IllegalArgumentException::class)
    fun mismatchedDisplacementDimensionsFailBeforeSampling() {
        LiquifyTool.DisplacementMap(1, 4).apply(PixelBuffer(2, 2))
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidMaskDimensionsAreRejected() {
        session(LiquifyTool.Mode.PUSH, mask = SelectionMask(1, 1))
    }

    @Test(expected = IllegalArgumentException::class)
    fun enormousDisplacementAllocationIsRejectedBeforeIntegerOverflow() {
        LiquifyTool.DisplacementMap(Int.MAX_VALUE, Int.MAX_VALUE)
    }

    @Test
    fun publicCommitAppliesReconstructionAndDoesNotReportNoOps() {
        val source = PixelBuffer.filled(width, width, 0xFFFF0000.toInt())
        val original = PixelBuffer.filled(width, width, 0xFF0000FF.toInt())
        val repair = session(LiquifyTool.Mode.RECONSTRUCT).apply { dragTo(center + 10f, center) }
        val expected = repair.render(source, original)
        assertTrue(LiquifyTool.commit(source, repair, original) != null)
        assertArrayEquals(expected.pixels, source.pixels)
        val idle = session(LiquifyTool.Mode.PUSH)
        assertTrue(LiquifyTool.commit(source, idle) == null)
    }

    @Test
    fun malformedMovementDoesNotPoisonALaterValidSample() {
        val session = session(LiquifyTool.Mode.PUSH)
        session.dragTo(Float.NaN, center)
        session.dragTo(center, Float.POSITIVE_INFINITY)
        assertTrue(session.map.isEmpty())
        session.dragTo(center + 5f, center)
        assertFalse(session.map.isEmpty())
        assertTrue(session.map.offsetX.all { it.isFinite() })
    }

    @Test
    fun alphaLockedLiquifyPreservesEveryCoverageValueInEveryMode() {
        val pixels =
            IntArray(width * width) { index ->
                val x = index % width
                val y = index / width
                val alpha =
                    when {
                        x > 42 -> 0
                        x > 32 -> 64
                        else -> 255
                    }
                (alpha shl 24) or (x * 3 shl 16) or (y * 3)
            }
        val source = PixelBuffer(width, width, pixels)
        val original = PixelBuffer.filled(width, width, 0xFFFF0000.toInt())
        for (mode in LiquifyTool.Mode.entries) {
            val settings = LiquifyTool.Settings(mode = mode, size = 40f, strength = 1f, alphaLock = true)
            val session = LiquifyTool.beginSession(center, center, settings, width, width)
            session.dragTo(center + 12f, center)
            val result = session.render(source, original)
            assertArrayEquals(source.pixels.map { it ushr 24 }.toIntArray(), result.pixels.map { it ushr 24 }.toIntArray())
            for (index in result.pixels.indices) {
                if ((source.pixels[index] ushr 24) == 0) assertEquals(source.pixels[index], result.pixels[index])
            }
        }
    }

    @Test
    fun alphaLockedLiquifyStillWarpsColorInsteadOfDisablingTheTool() {
        val source = ramp(horizontal = true)
        val settings = LiquifyTool.Settings(size = 40f, strength = 1f, alphaLock = true)
        val session = LiquifyTool.beginSession(center, center, settings, width, width)
        session.dragTo(center + 10f, center)
        val result = session.render(source)
        assertFalse(source.pixels.contentEquals(result.pixels))
        assertTrue(result.pixels.all { (it ushr 24) == 255 })
    }

    private fun session(
        mode: LiquifyTool.Mode,
        strength: Float = 1f,
        mask: SelectionMask? = null,
        freeze: BooleanArray? = null,
    ): LiquifyTool.Session =
        LiquifyTool.beginSession(
            center,
            center,
            LiquifyTool.Settings(mode = mode, size = 40f, strength = strength, mask = mask, freezeMask = freeze),
            width,
            width,
        )

    private fun ramp(horizontal: Boolean): PixelBuffer =
        PixelBuffer(width, width).apply {
            for (y in 0 until height) {
                for (x in 0 until width) {
                    setUnchecked(
                        x,
                        y,
                        0xFF000000.toInt() or ((if (horizontal) x else y) * 3 shl 16),
                    )
                }
            }
        }

    private fun channel(
        buffer: PixelBuffer,
        x: Int,
        y: Int,
    ): Int = (buffer.getSafe(x, y) ushr 16) and 255
}
