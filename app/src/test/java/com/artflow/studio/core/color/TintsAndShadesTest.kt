package com.artflow.studio.core.color

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TintsAndShadesTest {
    @Test
    fun aFullyBrightColourStillGetsLighterTints() {
        val red = 0xFFFF0000.toInt()
        val row = ColorHarmony.tintsAndShades(red)
        assertEquals(11, row.size)
        assertEquals(red, row[5])
        val tints = row.drop(6)
        assertEquals(5, tints.distinct().size)
        // Each tint keeps full red and adds more white.
        tints.zipWithNext().forEach { (a, b) -> assertTrue((b and 0xFF) > (a and 0xFF)) }
        assertTrue(tints.all { (it shr 16) and 0xFF == 0xFF })
        // Shades still darken toward black.
        assertTrue((row[0] shr 16) and 0xFF < (row[4] shr 16) and 0xFF)
    }
}
