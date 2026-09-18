package com.artflow.studio.core.pixels

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CancellationException
import kotlin.random.Random

class ColorSelectionTest {
    private val red = 0xFFFF0000.toInt()
    private val blue = 0xFF0000FF.toInt()

    @Test fun homogeneousRegionsDoNotOverflowTheFrontier() {
        for (side in listOf(1, 2, 3, 4, 16, 64, 1024)) {
            val source = PixelBuffer.filled(side, side, red)
            val mask = SelectionMask.magicWand(source, 0, 0, tolerance = 0)
            assertEquals(side * side, mask.selectedPixelCount())
            assertTrue(mask.isFull())
        }
    }

    @Test fun branchingRegionsGrowThePrimitiveRunStackSafely() {
        val source = PixelBuffer(511, 31, IntArray(511 * 31) { i -> if (i / 511 == 15 || i % 2 == 0) red else blue })
        val actual = SelectionMask.magicWand(source, 0, 15, tolerance = 0, antiAlias = false)
        assertArrayEquals(oracle(source, 0, 15), actual.coverage)
    }

    @Test fun randomConnectivityAgreesWithIndependentBreadthFirstSearch() {
        val random = Random(61237)
        repeat(120) {
            val width = random.nextInt(1, 40)
            val height = random.nextInt(1, 40)
            val source = PixelBuffer(width, height, IntArray(width * height) { if (random.nextInt(4) == 0) blue else red })
            val x = random.nextInt(width)
            val y = random.nextInt(height)
            assertArrayEquals(oracle(source, x, y), SelectionMask.magicWand(source, x, y, 0, true, false).coverage)
        }
    }

    @Test fun diagonallyTouchingPixelsAreNotConnected() {
        val source = PixelBuffer(2, 2, intArrayOf(red, blue, blue, red))
        assertEquals(1, SelectionMask.magicWand(source, 0, 0, tolerance = 0).selectedPixelCount())
        assertEquals(2, SelectionMask.magicWand(source, 0, 0, tolerance = 0, contiguous = false).selectedPixelCount())
    }

    @Test fun globalMatchingSelectsDisconnectedIslands() {
        val source = PixelBuffer(5, 1, intArrayOf(red, red, blue, red, red))
        assertEquals(2, SelectionMask.magicWand(source, 0, 0, tolerance = 0).selectedPixelCount())
        assertEquals(4, SelectionMask.magicWand(source, 0, 0, tolerance = 0, contiguous = false).selectedPixelCount())
    }

    @Test fun transparentHiddenColoursDoNotSplitTheSelection() {
        val source = PixelBuffer(4, 1, intArrayOf(0, 0x00FF0000, 0x000000FF, red))
        assertEquals(3, SelectionMask.magicWand(source, 0, 0, tolerance = 0).selectedPixelCount())
        assertEquals(3, SelectionMask.colorRange(source, 0x00FFFFFF, 0).selectedPixelCount())
    }

    @Test fun alphaStillSeparatesOpaqueBlackFromTransparency() {
        val source = PixelBuffer(2, 1, intArrayOf(0xFF000000.toInt(), 0))
        assertEquals(1, SelectionMask.magicWand(source, 0, 0, tolerance = 0).selectedPixelCount())
    }

    @Test fun antialiasingAddsPartialCoverageWithoutBridgingAnotherIsland() {
        val black = 0xFF000000.toInt()
        val source = PixelBuffer(3, 1, intArrayOf(black, 0xFF280000.toInt(), black))
        val mask = SelectionMask.magicWand(source, 0, 0, 10, true, true)
        assertEquals(255, mask.coverageAt(0, 0))
        assertEquals(170, mask.coverageAt(1, 0))
        assertEquals(0, mask.coverageAt(2, 0))
        assertEquals(0, SelectionMask.magicWand(source, 0, 0, 10, true, false).coverageAt(1, 0))
        assertEquals(255, SelectionMask.magicWand(source, 0, 0, 10, false, true).coverageAt(2, 0))
    }

    @Test fun zeroToleranceRemainsAnExactSelection() {
        val source = PixelBuffer(2, 1, intArrayOf(red, 0xFFFE0000.toInt()))
        assertEquals(1, SelectionMask.magicWand(source, 0, 0, 0, true, true).selectedPixelCount())
        assertEquals(1, SelectionMask.colorRange(source, red, 0, true).selectedPixelCount())
    }

    @Test fun largeToleranceAndOneDimensionalCanvasesWork() {
        for ((width, height) in listOf(1 to 64, 64 to 1)) {
            val source = PixelBuffer(width, height, IntArray(64) { if (it % 2 == 0) red else 0 })
            assertTrue(SelectionMask.magicWand(source, 0, 0, 255).isFull())
        }
    }

    @Test fun outOfBoundsSeedProducesEmptyCoverage() {
        val source = PixelBuffer.filled(3, 3, red)
        for ((x, y) in listOf(-1 to 0, 3 to 0, 0 to -1, 0 to 3)) {
            assertEquals(0, SelectionMask.magicWand(source, x, y).selectedPixelCount())
        }
    }

    @Test fun explicitSmallerMaskUsesCoordinatesAndLeavesOutsideUnselected() {
        val limit = SelectionMask(2, 2, byteArrayOf(0, 128.toByte(), 255.toByte(), 64))
        val source = PixelBuffer.filled(4, 3, red)
        val mask = SelectionMask.magicWand(source, 0, 0, respectExistingSelection = limit)
        assertEquals(128, mask.coverageAt(1, 0))
        assertEquals(255, mask.coverageAt(0, 1))
        assertEquals(64, mask.coverageAt(1, 1))
        assertEquals(0, mask.coverageAt(2, 0))
        assertEquals(0, mask.coverageAt(0, 2))
        assertEquals(128, limit.coverageAt(1, 0))
    }

    @Test fun explicitEmptyMaskSelectsNothing() {
        val source = PixelBuffer.filled(8, 8, red)
        assertEquals(0, SelectionMask.magicWand(source, 0, 0, respectExistingSelection = SelectionMask(8, 8)).selectedPixelCount())
    }

    @Test fun globalWandAndColourRangeHaveIdenticalCoverage() {
        val source = PixelBuffer(13, 7, IntArray(91) { 0xFF000000.toInt() or (it * 2 shl 16) })
        assertArrayEquals(
            SelectionMask.colorRange(source, source.pixels[15], 14).coverage,
            SelectionMask.magicWand(source, 2, 1, 14, false).coverage,
        )
    }

    @Test fun cancellationStopsWorkAndNeverMutatesSourcePixels() {
        val source = PixelBuffer.filled(512, 512, red)
        val before = source.pixels.copyOf()
        var polls = 0
        var cancelled = false
        try {
            SelectionMask.magicWand(source, 0, 0, checkActive = {
                if (++polls == 4) throw CancellationException("Test cancellation")
            })
        } catch (expected: CancellationException) {
            cancelled = true
        }
        assertTrue(cancelled)
        assertEquals(4, polls)
        assertArrayEquals(before, source.pixels)
    }

    @Test(expected = CancellationException::class)
    fun globalSelectionAlsoChecksCancellation() {
        SelectionMask.colorRange(PixelBuffer(8, 8), 0, 32, checkActive = { throw CancellationException() })
    }

    @Test(expected = IllegalArgumentException::class)
    fun nonFiniteRectangleCannotBecomeSelectAll() {
        SelectionMask.rectangle(8, 8, Float.NaN, 0f, 8f, 8f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun nonFiniteLassoCannotRasterize() {
        SelectionMask.polygon(8, 8, listOf(0f to 0f, Float.POSITIVE_INFINITY to 4f, 0f to 8f))
    }

    @Test(expected = IllegalArgumentException::class)
    fun nonFiniteBrushSelectionCannotScheduleBillionsOfSamples() {
        SelectionMask.fromStroke(8, 8, listOf(0f to 0f, Float.POSITIVE_INFINITY to 1f), 12f)
    }

    @Test fun distantNegativeRectangleDoesNotWrapIntoTheCanvas() {
        val mask = SelectionMask.rectangle(8, 8, -Float.MAX_VALUE, -Float.MAX_VALUE, -1e20f, -1e20f)
        assertEquals(0, mask.selectedPixelCount())
        assertTrue(SelectionMask.rectangle(8, 8, -Float.MAX_VALUE, -Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE).isFull())
    }

    private fun oracle(
        source: PixelBuffer,
        x: Int,
        y: Int,
    ): ByteArray {
        val coverage = ByteArray(source.size)
        val queue = IntArray(source.size)
        val seed = y * source.width + x
        val target = source.pixels[seed]
        var head = 0
        var tail = 0

        fun add(index: Int) {
            if (coverage[index].toInt() == 0 && source.pixels[index] == target) {
                coverage[index] = 255.toByte()
                queue[tail++] = index
            }
        }
        add(seed)
        while (head < tail) {
            val index = queue[head++]
            val column = index % source.width
            if (column > 0) add(index - 1)
            if (column + 1 < source.width) add(index + 1)
            if (index >= source.width) add(index - source.width)
            if (index + source.width < source.size) add(index + source.width)
        }
        return coverage
    }
}
