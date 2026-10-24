package com.artflow.studio.core.tool

import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.core.pixels.SelectionMask
import com.artflow.studio.core.render.BrushPatch
import com.artflow.studio.domain.model.brush.BrushParams
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** User-observable Smudge contracts; these do not claim the native application's wet equations. */
class SmudgeDynamicsTest {
    private val brush = BrushParams(size = 16f, opacity = 1f, pressureToSize = 0f, pressureToOpacity = 0f, flow = 1f)

    @Test
    fun zeroOpacityFlowAndPressureControlledCoverageLeaveEveryPixelUnchanged() {
        val original = fixture()
        val cases =
            listOf(
                brush.copy(opacity = 0f) to 1f,
                brush.copy(flow = 0f) to 1f,
                brush.copy(pressureToOpacity = 1f) to 0f,
                brush.copy(pressureToFlow = 1f) to 0f,
            )
        cases.forEach { (params, pressure) ->
            assertArrayEquals(original.pixels, stroke(params, pressure).pixels)
        }
    }

    @Test
    fun sparseMoveSmearsTheMiddleAndMatchesCollinearSubdivision() {
        val original = fixture()
        val sparse = stroke(brush, 1f, listOf(72f))
        val dense = stroke(brush, 1f, (9..72).map { it.toFloat() })
        assertArrayEquals("Input event rate must not change the same constant-pressure path", sparse.pixels, dense.pixels)
        for (x in 20..60) assertTrue("A fast move left a gap at $x", original.getSafe(x, 32) != sparse.getSafe(x, 32))
        assertEquals("Pixels away from the brush remain untouched", original.getSafe(40, 8), sparse.getSafe(40, 8))
    }

    @Test
    fun linearPressureRampDoesNotDependOnEventSubdivision() {
        fun draw(positions: List<Float>): PixelBuffer {
            val target = fixture()
            val session = PixelBrushes.beginSmudge(8f, 32f, settings(brush.copy(pressureToOpacity = 1f)), pressure = 0.25f)
            positions.forEach { x -> session.dragTo(x, 32f, target, pressure = 0.25f + (x - 8f) / 128f) }
            session.finish(target)
            return target
        }
        assertArrayEquals(draw(listOf(72f)).pixels, draw(listOf(24f, 40f, 56f, 72f)).pixels)
    }

    @Test
    fun pressureChangesActualFootprintAndSmudgeStrength() {
        val original = fixture()
        val sizes = brush.copy(pressureToSize = 1f)
        val lightSize = stroke(sizes, 0.25f)
        val firmSize = stroke(sizes, 1f)
        assertEquals(original.getSafe(40, 38), lightSize.getSafe(40, 38))
        assertTrue(original.getSafe(40, 38) != firmSize.getSafe(40, 38))
        val opacity = brush.copy(pressureToOpacity = 1f)
        assertTrue(difference(original, stroke(opacity, 0.2f)) < difference(original, stroke(opacity, 1f)))
        assertTrue(difference(original, stroke(brush.copy(flow = 0.2f), 1f)) < difference(original, stroke(brush, 1f)))
    }

    @Test
    fun lastFractionalIntervalIsFlushedExactlyOnce() {
        val target = fixture()
        val session = PixelBrushes.beginSmudge(8f, 32f, settings(brush))
        session.dragTo(42.75f, 32f, target)
        val beforeRelease = target.pixels.copyOf()
        assertTrue(session.finish(target) != null)
        assertFalse(beforeRelease.contentEquals(target.pixels))
        val released = target.pixels.copyOf()
        assertEquals(null, session.finish(target))
        assertArrayEquals(released, target.pixels)
        assertEquals(34.75f, session.distance, 0f)
    }

    @Test
    fun tiltAndOrientationReachTheSelectedTip() {
        val original = fixture()
        val tiltBrush = brush.copy(tiltInfluence = 1f, tiltOpacityInfluence = 0f)
        val upright = stroke(tiltBrush, 1f)
        val tilted = stroke(tiltBrush, 1f, tilt = (Math.PI / 2).toFloat())
        assertEquals(original.getSafe(40, 45), upright.getSafe(40, 45))
        assertTrue(original.getSafe(40, 45) != tilted.getSafe(40, 45))
        val flat = brush.copy(roundness = 0.25f, tiltToRotation = true)
        val horizontal = stroke(flat, 1f)
        val vertical = stroke(flat, 1f, orientation = (Math.PI / 2).toFloat())
        assertEquals(original.getSafe(40, 37), horizontal.getSafe(40, 37))
        assertTrue(original.getSafe(40, 37) != vertical.getSafe(40, 37))
    }

    @Test
    fun touchDoesNotInheritPenOnlyPressureAndEmptySelectionDoesNotSmear() {
        val pressureBrush = brush.copy(pressureToOpacity = 1f, pressureToFlow = 1f)
        assertFalse(fixture().pixels.contentEquals(stroke(pressureBrush.forTouch(), 0f).pixels))
        val target = fixture()
        val before = target.pixels.copyOf()
        val session = PixelBrushes.beginSmudge(8f, 32f, settings(brush).copy(mask = SelectionMask(96, 64)))
        session.dragTo(72f, 32f, target)
        session.finish(target)
        assertArrayEquals(before, target.pixels)
    }

    @Test(timeout = 5000)
    fun wideBrushesKeepOverlappingCoverageWithBoundedPatchWork() {
        val wide = BrushParams(size = 512f, pressureToSize = 0f, pressureToOpacity = 0f)

        fun draw(positions: List<Float>): Pair<PixelBuffer, Int> {
            val target = PixelBuffer(1024, 512, IntArray(1024 * 512) { 0xFF000000.toInt() or ((it % 1024 / 4) shl 16) })
            val session = PixelBrushes.beginSmudge(8f, 256f, settings(wide))
            positions.forEach { session.dragTo(it, 256f, target) }
            session.finish(target)
            return target to session.dabCount
        }
        val (sparse, count) = draw(listOf(1008f))
        val (dense, denseCount) = draw((12..1008 step 4).map { it.toFloat() })
        assertTrue("A wide brush must not do a full patch read at every few pixels: $count", count in 1..16)
        assertEquals(count, denseCount)
        assertArrayEquals(sparse.pixels, dense.pixels)
        assertTrue("The long gesture must still change its middle", ((sparse.getSafe(500, 256) ushr 16) and 255) < 125)
    }

    @Test(timeout = 1000)
    fun enormousFiniteOutliersAreIgnoredWithoutLosingTheNextValidSample() {
        val target = fixture()
        val original = target.pixels.copyOf()
        val session = PixelBrushes.beginSmudge(8f, 32f, settings(brush))
        assertEquals(null, session.dragTo(1e30f, -1e30f, target))
        assertArrayEquals(original, target.pixels)
        assertEquals(0f, session.distance, 0f)
        session.dragTo(72f, 32f, target)
        session.finish(target)
        assertArrayEquals(stroke(brush, 1f).pixels, target.pixels)
    }

    @Test
    fun alphaLockPreservesEveryCoverageValueThroughoutASparseDrag() {
        val target = fixture()
        target.pixels.indices.forEach { i -> target.pixels[i] = (target.pixels[i] and 0xFFFFFF) or ((i % 256) shl 24) }
        val before = target.pixels.map { it ushr 24 }.toIntArray()
        val session = PixelBrushes.beginSmudge(8f, 32f, settings(brush).copy(alphaLock = true))
        session.dragTo(72f, 32f, target)
        session.finish(target)
        assertArrayEquals(before, target.pixels.map { it ushr 24 }.toIntArray())
    }

    private fun stroke(
        params: BrushParams,
        pressure: Float,
        positions: List<Float> = listOf(72f),
        tilt: Float = 0f,
        orientation: Float = 0f,
    ): PixelBuffer {
        val target = fixture()
        val session = PixelBrushes.beginSmudge(8f, 32f, settings(params), pressure, tilt, orientation)
        positions.forEach { session.dragTo(it, 32f, target, pressure, tilt, orientation) }
        session.finish(target)
        return target
    }

    private fun settings(params: BrushParams) =
        PixelBrushes.SmudgeSettings(
            size = params.size,
            hardness = 1f,
            strength = 1f,
            brushParams = params,
            texture = BrushPatch.texture(params),
        )

    private fun fixture() =
        PixelBuffer(
            96,
            64,
            IntArray(96 * 64) { i -> 0xFF000000.toInt() or ((i % 96 * 2) shl 16) or ((i / 96 * 3) shl 8) or 0x70 },
        )

    private fun difference(
        left: PixelBuffer,
        right: PixelBuffer,
    ): Long = left.pixels.indices.sumOf { abs(((left.pixels[it] ushr 16) and 255) - ((right.pixels[it] ushr 16) and 255)).toLong() }
}
