package com.artflow.studio.core.pixels

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToInt

/** Independent source-filter equations exercise the same mesh renderer used by final commits. */
class WarpMeshEdgeSamplingTest {
    @Test
    fun fractionalBilinearMovesKeepBothEdgesOfThinMarksOnEitherMeshSize() {
        for (side in SIDES) {
            for (horizontal in listOf(false, true)) {
                for (shift in listOf(-0.75f, -0.25f, 0.25f, 0.75f)) {
                    translated(line(horizontal), side, horizontal, shift, TransformQuad.Interpolation.BILINEAR)
                }
            }
        }
    }

    @Test
    fun bicubicSupportExtendsPastEitherShortAxisWithoutChangingTheSource() {
        for (side in SIDES) {
            for (horizontal in listOf(false, true)) {
                for (shift in listOf(-0.75f, 0.75f)) {
                    translated(line(horizontal), side, horizontal, shift, TransformQuad.Interpolation.BICUBIC)
                }
            }
        }
    }

    @Test
    fun enlargedBilinearAndBicubicFringesAreNotClippedToTheGeometricPatch() {
        for (side in SIDES) {
            for (horizontal in listOf(false, true)) {
                for (interpolation in FILTERED) {
                    val source = line(horizontal)
                    val bounds = requireNotNull(source.contentBounds())
                    val box = Quad.fromBounds(bounds)
                    val mesh = WarpMesh.fromBounds(bounds, side).scale(8f, box.centerX, box.centerY)
                    val output = render(source, bounds, mesh, interpolation)
                    assertReference(source, output, interpolation) { x, y ->
                        box.centerX + (x - box.centerX) / 8.0 to box.centerY + (y - box.centerY) / 8.0
                    }
                }
            }
        }
    }

    @Test
    fun rotatedAndMirroredMeshesKeepTheSameOrientedFilterCoverage() {
        for (side in SIDES) {
            for (interpolation in FILTERED) {
                val source = line(horizontal = true, variedAlpha = true)
                val bounds = requireNotNull(source.contentBounds())
                val box = Quad.fromBounds(bounds)
                val original = WarpMesh.fromBounds(bounds, side)
                val rotated = render(source, bounds, original.rotate(90f).translate(0.75f, -0.25f), interpolation)
                assertReference(source, rotated, interpolation) { x, y ->
                    box.centerX + (y + 0.25 - box.centerY) to box.centerY - (x - 0.75 - box.centerX)
                }
                val mirrored = render(source, bounds, original.flip(horizontal = true).translate(0.25f, 0.75f), interpolation)
                assertReference(source, mirrored, interpolation) { x, y ->
                    2 * box.centerX - (x - 0.25) to y - 0.75
                }
            }
        }
    }

    @Test
    fun nearestRetainsItsOriginalPixelBoundaryRules() {
        for (side in SIDES) {
            for (horizontal in listOf(false, true)) {
                for (shift in listOf(-0.75f, -0.25f, 0.25f, 0.75f)) {
                    translated(line(horizontal), side, horizontal, shift, TransformQuad.Interpolation.NEAREST)
                }
            }
        }
    }

    @Test
    fun aSelectedThinMarkMovesItsFringeWhileUnselectedPixelsAndMaskRemainUnchanged() {
        val source = line(horizontal = true)
        source.setUnchecked(2, 2, BLUE)
        val before = source.pixels.copyOf()
        val selection = selectedLine()
        val beforeSelection = selection.coverage.copyOf()
        val bounds = requireNotNull(LayerTransform.floatingBounds(source, selection))
        val target = PixelBuffer(SIZE, SIZE)
        WarpMesh.render(
            source,
            target,
            bounds,
            WarpMesh.fromBounds(bounds).translate(0f, 0.75f),
            selection,
            TransformQuad.Interpolation.BILINEAR,
        )
        assertEquals(0x40FF0000, target.getUnchecked(START + 3, START))
        assertEquals(0xBFFF0000.toInt(), target.getUnchecked(START + 3, START + 1))
        assertEquals(BLUE, target.getUnchecked(2, 2))
        assertArrayEquals(before, source.pixels)
        assertArrayEquals(beforeSelection, selection.coverage)
    }

    @Test
    fun interiorAndFringeBoundariesCompositeSemiTransparentSelectionOnlyOnce() {
        for (side in SIDES) {
            val selected = line(horizontal = true)
            selected.pixels.indices.forEach { index ->
                if (selected.pixels[index] ushr 24 > 0) selected.pixels[index] = 0x80FF0000.toInt()
            }
            val source = selected.copy()
            for (x in 0 until SIZE) source.setUnchecked(x, START + 1, BLUE)
            val before = source.pixels.copyOf()
            val selection = selectedLine()
            val bounds = requireNotNull(selected.contentBounds())
            val target = PixelBuffer(SIZE, SIZE)
            WarpMesh.render(
                source,
                target,
                bounds,
                WarpMesh.fromBounds(bounds, side).translate(0.5f, 0.5f),
                selection,
                TransformQuad.Interpolation.BILINEAR,
            )
            assertSingleComposite(selected, target)
            assertArrayEquals(before, source.pixels)
        }
    }

    @Test
    fun aBentPatchKeepsItsInteriorCoverageAndOriginalSource() {
        val source = PixelBuffer(SIZE, SIZE)
        for (y in 8 until 56) for (x in 8 until 56) source.setUnchecked(x, y, 0x80FF0000.toInt())
        val bounds = requireNotNull(source.contentBounds())
        for (side in SIDES) {
            val mesh = WarpMesh.fromBounds(bounds, side).drag(WarpMesh.Target.Point(1), 0f, -12f)
            for (interpolation in FILTERED) {
                val target = render(source, bounds, mesh, interpolation)
                assertBentInterior(target)
                assertTrue(target.getUnchecked(24, 6) ushr 24 > 0)
            }
        }
    }

    @Test
    fun invalidOrCollapsedMeshesPreservePixelsAndDistantFiniteMeshesStayBounded() {
        val source = line(horizontal = true)
        val bounds = requireNotNull(source.contentBounds())
        val original = WarpMesh.fromBounds(bounds)
        val invalid =
            listOf(
                original.map { x, y -> if (x < START + 1) Float.NaN to y else x to y },
                original.map { x, _ -> x to Float.POSITIVE_INFINITY },
                original.map { _, _ -> 0f to 0f },
                original.map { x, y -> x * 1e35f to y * 1e35f },
            )
        for (mesh in invalid) {
            val target = render(source, bounds, mesh, TransformQuad.Interpolation.BILINEAR)
            assertArrayEquals(source.pixels, target.pixels)
        }
        val distant = original.map { x, y -> (x * 1e6f + 1e12f) to (y * 1e6f + 1e12f) }
        val target = render(source, bounds, distant, TransformQuad.Interpolation.BICUBIC)
        assertTrue(target.pixels.all { it == 0 })
    }

    private fun assertSingleComposite(
        selected: PixelBuffer,
        target: PixelBuffer,
    ) {
        for (y in 0 until SIZE) {
            for (x in 0 until SIZE) {
                val alpha = referenceAlpha(selected, x.toDouble(), y.toDouble(), TransformQuad.Interpolation.BILINEAR)
                val expected =
                    if (y == START + 1) {
                        (0xFF shl 24) or (alpha shl 16) or (255 - alpha)
                    } else if (alpha == 0) {
                        0
                    } else {
                        (alpha shl 24) or 0xFF0000
                    }
                assertChannelsClose("One composite at ($x,$y)", expected, target.getUnchecked(x, y))
            }
        }
    }

    private fun assertBentInterior(target: PixelBuffer) {
        for (y in 20 until 48) {
            for (x in 16 until 48) assertEquals(0x80FF0000.toInt(), target.getUnchecked(x, y))
        }
    }

    private fun translated(
        source: PixelBuffer,
        side: Int,
        horizontal: Boolean,
        shift: Float,
        interpolation: TransformQuad.Interpolation,
    ) {
        val bounds = requireNotNull(source.contentBounds())
        val dx = if (horizontal) 0f else shift
        val dy = if (horizontal) shift else 0f
        val target = render(source, bounds, WarpMesh.fromBounds(bounds, side).translate(dx, dy), interpolation)
        assertReference(source, target, interpolation) { x, y -> x - dx to y - dy }
    }

    private fun render(
        source: PixelBuffer,
        bounds: IntBounds,
        mesh: WarpMesh,
        interpolation: TransformQuad.Interpolation,
    ): PixelBuffer {
        val before = source.pixels.copyOf()
        val target = PixelBuffer.filled(SIZE, SIZE, BLUE)
        TransformQuad.renderShape(source, target, bounds, mesh.corners(), mesh, null, interpolation)
        assertArrayEquals(before, source.pixels)
        return target
    }

    private fun assertReference(
        source: PixelBuffer,
        target: PixelBuffer,
        interpolation: TransformQuad.Interpolation,
        inverse: (Double, Double) -> Pair<Double, Double>,
    ) {
        for (y in 0 until SIZE) {
            for (x in 0 until SIZE) {
                val (sx, sy) = inverse(x + 0.5, y + 0.5)
                val expectedAlpha = referenceAlpha(source, sx, sy, interpolation)
                val actual = target.getUnchecked(x, y)
                assertEquals("$interpolation at ($x,$y)", expectedAlpha.toDouble(), (actual ushr 24).toDouble(), 1.0)
                if (actual ushr 24 > 0) assertEquals("Visible edge RGB", 0xFF0000, actual and 0xFFFFFF)
            }
        }
    }

    private fun referenceAlpha(
        source: PixelBuffer,
        x: Double,
        y: Double,
        interpolation: TransformQuad.Interpolation,
    ): Int {
        var alpha = 0.0
        for (sy in START - 1..START + LENGTH) {
            for (sx in START - 1..START + LENGTH) {
                val sourceAlpha = source.getUnchecked(sx, sy) ushr 24
                if (sourceAlpha != 0) {
                    alpha += sourceAlpha * weight(x - sx - 0.5, interpolation) * weight(y - sy - 0.5, interpolation)
                }
            }
        }
        return alpha.roundToInt().coerceIn(0, 255)
    }

    private fun weight(
        distance: Double,
        interpolation: TransformQuad.Interpolation,
    ): Double {
        val t = abs(distance)
        return when (interpolation) {
            TransformQuad.Interpolation.NEAREST -> if (distance >= -0.5 && distance < 0.5) 1.0 else 0.0
            TransformQuad.Interpolation.BILINEAR -> (1.0 - t).coerceAtLeast(0.0)
            TransformQuad.Interpolation.BICUBIC ->
                when {
                    t <= 1.0 -> 1.5 * t * t * t - 2.5 * t * t + 1.0
                    t < 2.0 -> -0.5 * t * t * t + 2.5 * t * t - 4.0 * t + 2.0
                    else -> 0.0
                }
        }
    }

    private fun assertChannelsClose(
        message: String,
        expected: Int,
        actual: Int,
    ) {
        for (shift in listOf(24, 16, 8, 0)) {
            assertEquals(message, (expected ushr shift and 255).toDouble(), (actual ushr shift and 255).toDouble(), 1.0)
        }
    }

    private fun selectedLine(): SelectionMask =
        SelectionMask(SIZE, SIZE).also { selection ->
            for (x in START until START + LENGTH) selection.coverage[START * SIZE + x] = 255.toByte()
        }

    private fun line(
        horizontal: Boolean,
        variedAlpha: Boolean = false,
    ): PixelBuffer =
        PixelBuffer(SIZE, SIZE).also { source ->
            repeat(LENGTH) { offset ->
                val color = if (variedAlpha) ((32 + offset * 28) shl 24) or 0xFF0000 else RED
                source.setUnchecked(START + if (horizontal) offset else 0, START + if (horizontal) 0 else offset, color)
            }
        }

    private companion object {
        const val SIZE = 64
        const val START = 24
        const val LENGTH = 8
        const val RED = 0xFFFF0000.toInt()
        const val BLUE = 0xFF0000FF.toInt()
        val SIDES = listOf(WarpMesh.SIDE, WarpMesh.ADVANCED_SIDE)
        val FILTERED = listOf(TransformQuad.Interpolation.BILINEAR, TransformQuad.Interpolation.BICUBIC)
    }
}
