package com.artflow.studio.core.pixels

import org.junit.Assert.assertEquals
import org.junit.Test

class AreaSampleTest {
    @Test
    fun averagesTheSquareIgnoringTransparency() {
        val buffer = PixelBuffer(5, 5)
        buffer.setSafe(2, 2, 0xFFFF0000.toInt())
        buffer.setSafe(1, 2, 0xFF0000FF.toInt())
        assertEquals(0xFFFF0000.toInt(), AreaSample.average(buffer, 2, 2, 1))
        val mixed = AreaSample.average(buffer, 2, 2, 3)
        assertEquals(0x80, (mixed shr 16) and 0xFF)
        assertEquals(0x80, mixed and 0xFF)
        assertEquals(0, AreaSample.average(PixelBuffer(5, 5), 2, 2, 5))
    }
}
