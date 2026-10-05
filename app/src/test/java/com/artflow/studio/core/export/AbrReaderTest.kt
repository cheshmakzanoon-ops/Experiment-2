package com.artflow.studio.core.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fixtures follow the layout GIMP's and Krita's .abr loaders read (tools/fixtures/make_procreate_fixtures.py). */
class AbrReaderTest {
    private fun fixture(name: String): ByteArray = requireNotNull(javaClass.getResourceAsStream("/procreate/$name")).use { it.readBytes() }

    private fun value(
        tip: AbrReader.Tip,
        x: Int,
        y: Int,
    ) = tip.values[y * tip.width + x].toInt() and 0xFF

    @Test
    fun version6TipsComeWithTheirPresetNames() {
        val bytes = fixture("tips-v6.abr")
        assertTrue(AbrReader.isAbr(bytes))
        val tips = AbrReader.read(bytes)
        assertEquals(listOf("Ring Tip", "Smooth Ramp"), tips.map { it.name })
        val ring = tips[0]
        assertEquals(20, ring.width)
        assertEquals(14, ring.height)
        for (y in 0 until 14) {
            for (x in 0 until 20) {
                val d = (x - 10) * (x - 10) + (y - 7) * (y - 7)
                assertEquals("ring ($x, $y)", if (d in 36 until 49) 255 else 0, value(ring, x, y))
            }
        }
        // A 16-bit tip keeps the high byte of each sample.
        val ramp = tips[1]
        assertEquals(12, ramp.width)
        assertEquals(9, ramp.height)
        for (x in 0 until 12) assertEquals(x * 255 / 11, value(ramp, x, 4))
    }

    @Test
    fun version2SkipsComputedBrushesAndKeepsNames() {
        val tips = AbrReader.read(fixture("tips-v2.abr"))
        assertEquals(1, tips.size)
        val cross = tips[0]
        assertEquals("Cross", cross.name)
        assertEquals(9, cross.width)
        assertEquals(7, cross.height)
        assertEquals(255, value(cross, 4, 0))
        assertEquals(255, value(cross, 0, 3))
        assertEquals(0, value(cross, 1, 1))
    }

    @Test
    fun otherFilesAndDamageAreRefused() {
        assertFalse(AbrReader.isAbr("PK\u0003\u0004".toByteArray()))
        val bytes = fixture("tips-v6.abr")
        for (length in listOf(20, bytes.size / 2, bytes.size - 5)) {
            val failure = runCatching { AbrReader.read(bytes.copyOf(length)) }.exceptionOrNull()
            assertTrue("length $length: $failure", failure is IllegalArgumentException)
        }
    }
}
