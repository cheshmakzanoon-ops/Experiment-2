package com.artflow.studio.core.pixels

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SmudgeTextureTest {
    private fun smeared(texture: Stamping.PatchTexture): PixelBuffer {
        val canvas = PixelBuffer(20, 10)
        for (x in 0 until 10) for (y in 0 until 10) canvas.pixels[y * 20 + x] = 0xFFFF0000.toInt()
        Stamping.smudgeSegment(canvas, 8f, 5f, 12f, 5f, radius = 4f, strength = 1f, hardness = 1f, texture = texture)
        return canvas
    }

    @Test fun grainAndShapeDecideWherePaintIsCarried() {
        val plain = smeared(Stamping.PatchTexture())
        assertNotEquals("A plain smudge carries red to the right", 0, plain.pixels[5 * 20 + 12] ushr 24)
        val bare = smeared(Stamping.PatchTexture(grain = { _, _ -> 0f }))
        assertEquals("No grain, no paint", 0, bare.pixels[5 * 20 + 12] ushr 24)
        val sliver = smeared(Stamping.PatchTexture(shape = { u, _ -> if (u < 0f) 1f else 0f }))
        assertEquals("The tip's empty half carries nothing", 0, sliver.pixels[5 * 20 + 14] ushr 24)
    }
}
