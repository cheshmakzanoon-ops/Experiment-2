package com.artflow.studio.core.color

import com.artflow.studio.core.serialization.JsonLimitException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ProcreateSwatchesWriteBudgetTest {
    @Test
    fun anExcessColorCountIsRefusedBeforeTraversingTheCollection() {
        val colors = CountingColors(65_537)
        assertThrows(IllegalArgumentException::class.java) { ProcreateSwatches.write(Palette(name = "Large", colors = colors)) }
        assertEquals(0, colors.reads)
    }

    @Test
    fun anUnreadableTokenCountIsRefusedBeforeBuildingAnySwatches() {
        val colors = CountingColors(65_536)
        assertThrows(JsonLimitException::class.java) { ProcreateSwatches.write(Palette(name = "Large", colors = colors)) }
        assertEquals(0, colors.reads)
    }

    @Test
    fun aSmallMemoryAllowanceRefusesBeforeTraversingTheCollection() {
        val colors = CountingColors(1024)
        assertThrows(JsonLimitException::class.java) {
            ProcreateSwatches.write(Palette(name = "Large", colors = colors), 1_700_000_000.0, 128L * 1024)
        }
        assertEquals(0, colors.reads)
    }

    @Test
    fun anOverlongNameIsRefusedBeforeTraversingTheCollection() {
        val colors = CountingColors(30)
        assertThrows(IllegalArgumentException::class.java) { ProcreateSwatches.write(Palette(name = "x".repeat(81), colors = colors)) }
        assertEquals(0, colors.reads)
    }

    @Test
    fun nonFiniteTimestampsAreRefusedBeforeTraversingTheCollection() {
        for (timestamp in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            val colors = CountingColors(30)
            assertThrows(
                IllegalArgumentException::class.java,
            ) { ProcreateSwatches.write(Palette(name = "Time", colors = colors), timestamp) }
            assertEquals(0, colors.reads)
        }
    }

    @Test
    fun streamingOutputRemainsReadableUnderTheSameAdmittedMemoryAllowance() {
        val colors = List(1024) { 0x80000000.toInt() or ((it * 16127) and 0xFFFFFF) }
        val original = Palette(name = "Brush \"mix\" \\ café 🎨", colors = colors)
        val budget = 8L * 1024 * 1024
        val bytes = ProcreateSwatches.write(original, 1_700_000_000.0, budget)
        val restored = ProcreateSwatches.read(bytes, ProcreateSwatches.ReadOptions(memoryBudgetBytes = budget)).single()
        assertEquals(original.name, restored.name)
        assertEquals(colors.size, restored.colors.size)
        assertTrue(bytes.size <= ProcreateSwatches.MAX_ARCHIVE_BYTES)
        colors.zip(restored.colors).forEach { (expected, actual) ->
            for (shift in intArrayOf(24, 16, 8, 0)) {
                assertTrue(abs(((expected ushr shift) and 255) - ((actual ushr shift) and 255)) <= 1)
            }
        }
    }

    @Test
    fun isolatedNameCodeUnitsRemainExactThroughTheUtf8ZipMember() {
        val names = listOf("X\uD800Y", "X\uDC00Y", "X\uD83D\uDE00Y", ("a".repeat(79) + "\uD83D\uDE00").take(80))
        names.forEach { name ->
            val original = Palette(name = name, colors = listOf(0xFF112233.toInt()))
            val restored = ProcreateSwatches.read(ProcreateSwatches.write(original)).single()
            assertEquals(original.name, restored.name)
            assertEquals(original.colors, restored.colors)
        }
    }

    private class CountingColors(
        override val size: Int,
    ) : AbstractList<Int>() {
        var reads = 0

        override fun get(index: Int): Int {
            reads++
            return 0xFF123456.toInt()
        }
    }
}
