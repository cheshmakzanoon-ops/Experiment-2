package com.artflow.studio.core.render

import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.core.pixels.SelectionMask
import com.artflow.studio.domain.model.brush.BrushParams
import com.artflow.studio.domain.model.brush.PhysicalPaintMode
import com.artflow.studio.domain.model.brush.Stroke
import com.artflow.studio.domain.model.brush.StrokePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Selection and alpha-lock protection cover physical state as well as visible paint. */
class PhysicalStrokeCoverageTest {
    private val size = 16
    private val ink = 0xFFE04020.toInt()
    private val params =
        BrushParams(
            size = 12f,
            physicalMode = PhysicalPaintMode.WATERCOLOR,
            waterAmount = 0.9f,
            impastoThickness = 0.8f,
        )

    private fun paint(
        plane: PhysicalPaintPlane,
        base: PixelBuffer = PixelBuffer(size, size),
        selection: SelectionMask? = null,
        alphaLock: Boolean = false,
        brush: BrushParams = params,
    ): PixelBuffer =
        LayerStrokeRenderer.renderWithPhysical(
            base = base,
            historicalStrokes = emptyList(),
            incomingStrokes =
                listOf(
                    Stroke(
                        id = 1L,
                        points = listOf(StrokePoint(8.5f, 8.5f, pressure = 1f)),
                        brushParams = brush,
                        layerId = 1L,
                        color = ink,
                    ),
                ),
            width = size,
            height = size,
            alphaLock = alphaLock,
            selection = selection,
            physicalPlane = plane,
        )

    @Test
    fun anEmptySelectionDoesNotAllocateInvisiblePhysicalPaint() {
        val plane = PhysicalPaintPlane(size, size)
        val pixels = paint(plane, selection = SelectionMask(size, size))
        assertTrue(pixels.pixels.all { it == 0 })
        assertTrue("Rejected paint must not leave water or impasto behind", plane.isEmpty())
    }

    @Test
    fun aSelectionPreservesExistingPhysicalStateOutsideItsCoverage() {
        val plane = PhysicalPaintPlane(size, size)
        plane.setCell(7, 8, wetness = 0.2f, impasto = 0.4f, pigment = 0.3f)
        val selection = SelectionMask(size, size)
        for (y in 0 until size) {
            for (x in 8 until size) selection.coverage[y * size + x] = 255.toByte()
        }
        val pixels = paint(plane, selection = selection)
        assertEquals(0, pixels.getSafe(7, 8))
        assertEquals(0.2f, plane.wetnessAt(7, 8), 0f)
        assertEquals(0.4f, plane.impastoAt(7, 8), 0f)
        assertEquals(0.3f, plane.pigmentAt(7, 8), 0f)
        assertTrue(pixels.getSafe(8, 8) ushr 24 > 0)
        assertTrue(plane.wetnessAt(8, 8) > 0f)
        assertTrue(plane.impastoAt(8, 8) > 0f)
    }

    @Test
    fun alphaLockProtectsTransparentPixelsFromPhysicalPaint() {
        val plane = PhysicalPaintPlane(size, size)
        val base = PixelBuffer(size, size)
        base.pixels[8 * size + 8] = 0xFFFFFFFF.toInt()
        val pixels = paint(plane, base = base, alphaLock = true)
        assertEquals(0, pixels.getSafe(7, 8))
        assertEquals(0f, plane.wetnessAt(7, 8), 0f)
        assertEquals(0f, plane.impastoAt(7, 8), 0f)
        assertEquals(0f, plane.pigmentAt(7, 8), 0f)
        assertTrue(plane.wetnessAt(8, 8) > 0f)
        assertTrue(plane.impastoAt(8, 8) > 0f)
    }

    @Test
    fun featheredSelectionAndStrokeOpacityScalePhysicalDeposit() {
        val full = PhysicalPaintPlane(size, size)
        paint(full)
        val faded = PhysicalPaintPlane(size, size)
        val selection = SelectionMask(size, size, ByteArray(size * size) { 128.toByte() })
        paint(faded, selection = selection, brush = params.copy(opacity = 0.25f))
        val amount = 0.25f * 128f / 255f
        assertEquals(full.wetnessAt(8, 8) * amount, faded.wetnessAt(8, 8), 0.00001f)
        assertEquals(full.impastoAt(8, 8) * amount, faded.impastoAt(8, 8), 0.00001f)
        assertEquals(full.pigmentAt(8, 8) * amount, faded.pigmentAt(8, 8), 0.00001f)
    }

    @Test
    fun aFlatTipDoesNotDepositPhysicalPaintOutsideItsVisibleShape() {
        val plane = PhysicalPaintPlane(size, size)
        val pixels = paint(plane, brush = params.copy(roundness = 0.1f))
        assertTrue(plane.wetnessAt(8, 8) > 0f)
        for (y in 0 until size) {
            for (x in 0 until size) {
                if (pixels.getSafe(x, y) ushr 24 == 0) {
                    assertEquals("Wetness at unpainted $x,$y", 0f, plane.wetnessAt(x, y), 0f)
                    assertEquals("Relief at unpainted $x,$y", 0f, plane.impastoAt(x, y), 0f)
                    assertEquals("Pigment at unpainted $x,$y", 0f, plane.pigmentAt(x, y), 0f)
                }
            }
        }
    }

    @Test
    fun zeroFlowDoesNotLeaveInvisiblePhysicalPaint() {
        val plane = PhysicalPaintPlane(size, size)
        val pixels = paint(plane, brush = params.copy(flow = 0f))
        assertTrue(pixels.pixels.all { it == 0 })
        assertTrue(plane.isEmpty())
    }
}
