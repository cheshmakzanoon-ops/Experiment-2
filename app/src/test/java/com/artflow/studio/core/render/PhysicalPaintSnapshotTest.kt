package com.artflow.studio.core.render

import com.artflow.studio.core.pixels.LayerTransform
import com.artflow.studio.core.pixels.SelectionMask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PhysicalPaintSnapshotTest {
    @Test
    fun identityPreservesExactPhysicalFloatsWithoutQuantizingReset() {
        val plane = PhysicalPaintPlane(16, 16).apply { setCell(3, 5, 0.123456f, -0.234567f, 0.345678f) }
        val source = PhysicalPaintSnapshot(plane)
        val result = source.transformed { input, output -> input.pixels.copyInto(output.pixels) }
        assertSame(source, result)
        assertEquals(0.123456f, result.plane!!.wetnessAt(3, 5), 0f)
        assertEquals(-0.234567f, result.plane.impastoAt(3, 5), 0f)
    }

    @Test
    fun selectedTranslationMovesOnlySelectedPhysicalPaint() {
        val plane =
            PhysicalPaintPlane(16, 16).apply {
                setCell(3, 5, 0.8f, 0.5f, 0.6f)
                setCell(13, 12, 0.4f, -0.25f, 0.2f)
            }
        val source = PhysicalPaintSnapshot(plane)
        val selection = SelectionMask.rectangle(16, 16, 2f, 4f, 5f, 7f)
        val moved =
            source
                .transformed(selection) { input, output ->
                    LayerTransform.render(input, output, LayerTransform.Params(0f, 0f, 5f, 0f))
                }.plane!!
        assertEquals(0f, moved.wetnessAt(3, 5), 0f)
        assertEquals(0.8f, moved.wetnessAt(8, 5), 0.01f)
        assertEquals(0.4f, moved.wetnessAt(13, 12), 0.01f)
        assertEquals(0.8f, plane.wetnessAt(3, 5), 0f)
        assertFalse(source.sameContent(PhysicalPaintSnapshot(moved)))
    }

    @Test
    fun featheredMoveAddsScalarContributionsWithoutSourceOverLoss() {
        val plane =
            PhysicalPaintPlane(16, 16).apply {
                setCell(3, 5, 0.8f, -0.25f, 0.6f)
                setCell(8, 5, 0.4f, 0.1f, 0.2f)
            }
        val selection = SelectionMask(16, 16).apply { coverage[5 * 16 + 3] = 128.toByte() }
        val moved =
            PhysicalPaintSnapshot(plane)
                .transformed(selection) { input, output ->
                    LayerTransform.render(input, output, LayerTransform.Params(0f, 0f, 5f, 0f))
                }.plane!!
        assertEquals(0.4f + 0.8f * 128f / 255f, moved.wetnessAt(8, 5), 0.01f)
        assertEquals(0.1f - 0.25f * 128f / 255f, moved.impastoAt(8, 5), 0.01f)
        assertEquals(0.2f + 0.6f * 128f / 255f, moved.pigmentAt(8, 5), 0.01f)
        assertEquals(0.8f * 127f / 255f, moved.wetnessAt(3, 5), 0.01f)
    }

    @Test
    fun allocationRefusalHappensBeforeRenderingOrChangingTheSource() {
        val plane = PhysicalPaintPlane(16, 16).apply { setCell(3, 5, 0.8f, 0.5f, 0.6f) }
        var rendered = false
        val failure =
            runCatching {
                PhysicalPaintSnapshot(plane, workingLimit = 0L).transformed { _, _ -> rendered = true }
            }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertFalse(rendered)
        assertEquals(0.8f, plane.wetnessAt(3, 5), 0f)
    }

    @Test
    fun companionAdmissionCountsAllRetainedSourcesAndResultsTogether() {
        val plane = PhysicalPaintPlane(16, 16).apply { setCell(3, 5, 0.8f, 0.5f, 0.6f) }
        val source = PhysicalPaintSnapshot(plane, workingLimit = 150_000L)
        PhysicalPaintSnapshot.requireTransformCapacity(listOf(source))
        assertTrue(runCatching { PhysicalPaintSnapshot.requireTransformCapacity(listOf(source, source)) }.isFailure)
    }

    @Test
    fun ordinaryRasterLayerDoesNotAllocatePhysicalBuffers() {
        var rendered = false
        val empty = PhysicalPaintSnapshot.EMPTY
        assertSame(empty, empty.transformed { _, _ -> rendered = true })
        assertFalse(rendered)
    }
}
