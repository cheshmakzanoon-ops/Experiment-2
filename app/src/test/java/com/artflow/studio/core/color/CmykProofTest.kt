package com.artflow.studio.core.color

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CmykProofTest {
    @Test
    fun separationRebuildsTheColourWithPerfectInks() {
        val inks = CmykProof.separate(0.2f, 0.6f, 0.9f)
        val c = inks[0]
        val m = inks[1]
        val y = inks[2]
        val k = inks[3]
        // Perfect inks: each channel is (1 - ink) * (1 - black).
        assertEquals(0.2f, (1f - c) * (1f - k), EPSILON)
        assertEquals(0.6f, (1f - m) * (1f - k), EPSILON)
        assertEquals(0.9f, (1f - y) * (1f - k), EPSILON)
        assertArrayEquals(floatArrayOf(0f, 0f, 0f, 1f), CmykProof.separate(0f, 0f, 0f), EPSILON)
    }

    @Test
    fun paperStaysWhiteAndPrintableColoursBarelyMove() {
        assertArrayEquals(floatArrayOf(1f, 1f, 1f), CmykProof.proof(1f, 1f, 1f), EPSILON)
        assertFalse(CmykProof.outOfGamut(1f, 1f, 1f))
        // A muted skin tone prints close to how it looks.
        assertFalse(CmykProof.outOfGamut(0.87f, 0.72f, 0.62f))
    }

    @Test
    fun saturatedScreenColoursDullAndAreFlagged() {
        val blue = CmykProof.proof(0f, 0f, 1f)
        assertTrue(blue[2] < 0.7f)
        assertTrue(CmykProof.outOfGamut(0f, 0f, 1f))
        assertTrue(CmykProof.outOfGamut(0f, 1f, 0f))
        // Black ink alone cannot reach pure black.
        assertTrue(CmykProof.proof(0f, 0f, 0f).all { it > 0.1f })
    }

    @Test
    fun applyKeepsAlphaAndGreysOnlyInGamutWarning() {
        val blue = 0x800000FF.toInt()
        assertEquals(blue, CmykProof.apply(blue, CmykProof.Mode.OFF))
        val proofed = CmykProof.apply(blue, CmykProof.Mode.PROOF)
        val warned = CmykProof.apply(blue, CmykProof.Mode.GAMUT_WARNING)
        assertEquals(0x80, proofed ushr 24)
        assertEquals(0x80, warned ushr 24)
        assertTrue(proofed != warned)
        val white = 0xFFFFFFFF.toInt()
        assertEquals(white, CmykProof.apply(white, CmykProof.Mode.GAMUT_WARNING))
    }

    private companion object {
        const val EPSILON = 1e-4f
    }
}
