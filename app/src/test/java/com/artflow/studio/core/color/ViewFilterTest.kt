package com.artflow.studio.core.color

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ViewFilterTest {
    @Test
    fun offAndWhiteAreUnchanged() {
        assertArrayEquals(floatArrayOf(0.2f, 0.4f, 0.6f), ViewFilter.OFF.apply(0.2f, 0.4f, 0.6f), 0f)
        ViewFilter.entries.forEach { filter ->
            filter.apply(1f, 1f, 1f).forEach { assertEquals(filter.name, 1f, it, 0.01f) }
        }
    }

    @Test
    fun greyscaleKeepsOnlyValue() {
        val red = ViewFilter.GREYSCALE.apply(1f, 0f, 0f)
        assertEquals(red[0], red[1], 1e-5f)
        assertEquals(red[1], red[2], 1e-5f)
    }

    @Test
    fun redAndGreenGrowAlikeForDeuteranopes() {
        fun distance(
            a: FloatArray,
            b: FloatArray,
        ) = a.indices.sumOf { abs(a[it] - b[it]).toDouble() }
        val normal = distance(floatArrayOf(0.8f, 0.2f, 0.1f), floatArrayOf(0.3f, 0.6f, 0.1f))
        val seen = distance(ViewFilter.DEUTERANOPIA.apply(0.8f, 0.2f, 0.1f), ViewFilter.DEUTERANOPIA.apply(0.3f, 0.6f, 0.1f))
        assertTrue(seen < normal)
    }

    @Test
    fun columnMajorTransposes() {
        val m = ViewFilter.PROTANOPIA.matrix!!
        val c = ViewFilter.PROTANOPIA.columnMajor()
        assertEquals(m[1], c[3], 0f)
        assertEquals(m[3], c[1], 0f)
        assertArrayEquals(floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f), ViewFilter.OFF.columnMajor(), 0f)
    }
}
