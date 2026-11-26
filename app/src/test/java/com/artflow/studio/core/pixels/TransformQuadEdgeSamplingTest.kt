package com.artflow.studio.core.pixels

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToInt

/** Analytical coverage checks independent of the production interpolation helpers. */
class TransformQuadEdgeSamplingTest {
    @Test
    fun fractionalMovesKeepBothEdgesOfAThinHorizontalMark() {
        for (shift in listOf(-0.75f, -0.25f, 0.25f, 0.75f)) {
            assertTranslated(horizontal = true, shift, TransformQuad.Interpolation.BILINEAR)
        }
    }

    @Test
    fun fractionalMovesKeepBothEdgesOfAThinVerticalMark() {
        for (shift in listOf(-0.75f, -0.25f, 0.25f, 0.75f)) {
            assertTranslated(horizontal = false, shift, TransformQuad.Interpolation.BILINEAR)
        }
    }

    @Test
    fun bicubicMovesUseTheShortAxisFootprintWithoutChangingTheSource() {
        for (horizontal in listOf(false, true)) {
            for (shift in listOf(-0.75f, -0.25f, 0.25f, 0.75f)) {
                assertTranslated(horizontal, shift, TransformQuad.Interpolation.BICUBIC)
            }
        }
    }

    @Test
    fun aSelectedThinMarkKeepsItsFringeAndUnselectedPixelsStayPut() {
        val source = line(horizontal = true)
        source.setUnchecked(2, 2, BLUE)
        val before = source.pixels.copyOf()
        val selection = SelectionMask(SIZE, SIZE)
        for (x in START until START + LENGTH) selection.coverage[START * SIZE + x] = 255.toByte()
        val beforeSelection = selection.coverage.copyOf()
        val bounds = requireNotNull(LayerTransform.floatingBounds(source, selection))
        val target = PixelBuffer(SIZE, SIZE)
        TransformQuad.render(
            source,
            target,
            bounds,
            TransformQuad.translate(Quad.fromBounds(bounds), 0f, 0.75f),
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
    fun nearestKeepsItsOriginalPixelBoundaryRules() {
        for (horizontal in listOf(false, true)) {
            for (shift in listOf(-0.75f, -0.25f, 0.25f, 0.75f)) {
                assertTranslated(horizontal, shift, TransformQuad.Interpolation.NEAREST)
            }
        }
    }

    @Test
    fun enlargedThinMarksKeepTheirEntireBilinearFringeOnEitherAxis() {
        for (horizontal in listOf(false, true)) {
            val source = line(horizontal)
            val before = source.pixels.copyOf()
            val bounds = requireNotNull(source.contentBounds())
            val target = PixelBuffer(SIZE, SIZE)
            val box = Quad.fromBounds(bounds)
            TransformQuad.render(source, target, bounds, TransformQuad.scale(box, 8f), null, TransformQuad.Interpolation.BILINEAR)
            assertReference(source, target, TransformQuad.Interpolation.BILINEAR) { x, y ->
                box.centerX + (x - box.centerX) / 8.0 to box.centerY + (y - box.centerY) / 8.0
            }
            assertArrayEquals(before, source.pixels)
        }
    }

    @Test
    fun anExpandedFootprintAcrossTheProjectiveHorizonStillUsesFiniteCanvasBounds() {
        val source = PixelBuffer(SIZE, SIZE)
        source.setUnchecked(8, 8, RED)
        val bounds = requireNotNull(source.contentBounds())
        val target = PixelBuffer(SIZE, SIZE)
        // Known map: destination = (8, 8) + (8u, 8v) / (1 + 3u).
        // Its horizon u=-1/3 lies in the bilinear footprint [-1/2, 3/2].
        TransformQuad.render(
            source,
            target,
            bounds,
            Quad(8f, 8f, 10f, 8f, 10f, 10f, 8f, 16f),
            null,
            TransformQuad.Interpolation.BILINEAR,
        )
        assertReference(source, target, TransformQuad.Interpolation.BILINEAR) { x, y ->
            val denominator = 8.0 - 3.0 * (x - 8.0)
            8.0 + (x - 8.0) / denominator to 8.0 + (y - 8.0) / denominator
        }
        assertTrue("The expanded footprint reaches beyond the original quad", target.getUnchecked(20, 8) ushr 24 > 0)
    }

    @Test
    fun collapsedOrUnrepresentableMappingsPreserveTheOriginalPixels() {
        val source = line(horizontal = true)
        val bounds = requireNotNull(source.contentBounds())
        val box = Quad.fromBounds(bounds)
        val invalid =
            listOf(
                box.copy(x0 = Float.NaN),
                box.copy(y1 = Float.POSITIVE_INFINITY),
                box.map { _, _ -> 0f to 0f },
                box.map { x, y -> x * 1e35f to y * 1e35f },
            )
        for (quad in invalid) {
            assertNull(TransformQuad.inverseMapping(quad))
            val target = PixelBuffer.filled(SIZE, SIZE, BLUE)
            TransformQuad.render(source, target, bounds, quad, null, TransformQuad.Interpolation.BILINEAR)
            assertArrayEquals(source.pixels, target.pixels)
        }
    }

    private fun assertTranslated(
        horizontal: Boolean,
        shift: Float,
        interpolation: TransformQuad.Interpolation,
    ) {
        val source = line(horizontal)
        val before = source.pixels.copyOf()
        val bounds = requireNotNull(source.contentBounds())
        val dx = if (horizontal) 0f else shift
        val dy = if (horizontal) shift else 0f
        val target = PixelBuffer(SIZE, SIZE)
        TransformQuad.render(source, target, bounds, TransformQuad.translate(Quad.fromBounds(bounds), dx, dy), null, interpolation)
        assertReference(source, target, interpolation) { x, y -> x - dx to y - dy }
        assertArrayEquals(before, source.pixels)
    }

    private fun assertReference(
        source: PixelBuffer,
        target: PixelBuffer,
        interpolation: TransformQuad.Interpolation,
        sourcePoint: (Double, Double) -> Pair<Double, Double>,
    ) {
        val painted = source.pixels.indices.filter { source.pixels[it] ushr 24 != 0 }
        for (y in 0 until SIZE) {
            for (x in 0 until SIZE) {
                val (sx, sy) = sourcePoint(x + 0.5, y + 0.5)
                val coverage =
                    painted.sumOf { index ->
                        weight(sx - (index % SIZE + 0.5), interpolation) * weight(sy - (index / SIZE + 0.5), interpolation)
                    }
                val expectedAlpha = (coverage * 255).roundToInt().coerceIn(0, 255)
                val actual = target.getUnchecked(x, y)
                assertEquals("$interpolation at ($x,$y)", expectedAlpha.toDouble(), (actual ushr 24).toDouble(), 1.0)
                if (actual ushr 24 > 0) assertEquals("Visible edge RGB", RED and 0xFFFFFF, actual and 0xFFFFFF)
            }
        }
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

    private fun line(horizontal: Boolean): PixelBuffer =
        PixelBuffer(SIZE, SIZE).also { source ->
            repeat(LENGTH) { offset ->
                source.setUnchecked(START + if (horizontal) offset else 0, START + if (horizontal) 0 else offset, RED)
            }
        }

    private companion object {
        const val SIZE = 64
        const val START = 24
        const val LENGTH = 8
        const val RED = 0xFFFF0000.toInt()
        const val BLUE = 0xFF0000FF.toInt()
    }
}
