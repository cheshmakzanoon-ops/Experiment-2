package com.artflow.studio.core.render

import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.core.pixels.Stamping
import com.artflow.studio.domain.model.brush.BrushParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomGrainsTest {
    private fun stripes(): PixelBuffer =
        PixelBuffer(40, 20).also { image ->
            for (y in 0 until 20) for (x in 0 until 40) image.pixels[y * 40 + x] = if (x % 4 < 2) -1 else 0xFF000000.toInt()
        }

    @Test fun photoBecomesAFullRangeSquareTile() {
        val tile = CustomGrains.tileFrom(stripes(), size = 8)
        assertEquals(8, tile.size)
        val values = tile.values.map { it.toInt() and 0xFF }
        assertEquals(0, values.min())
        assertEquals(255, values.max())
        assertEquals(tile.at(0, 0), tile.at(8, 16), 0f)
    }

    @Test fun importedGrainDrivesBrushTexture() {
        val tile = CustomGrains.tileFrom(stripes(), size = 8)
        val id = CustomGrains.idFor(tile)
        assertTrue(CustomGrains.isCustom(id))
        assertEquals(id, CustomGrains.idFor(CustomGrains.tileFrom(stripes(), size = 8)))
        val params = BrushParams(textureId = id, blendTexture = true)
        assertNull("A missing tile paints without grain", BrushTexture.from(params.copy(textureId = "custom-0")))
        CustomGrains.register(id, tile)
        val texture = BrushTexture.from(params)
        assertNotNull(texture)
        assertEquals(tile.at(3, 5), texture!!.coverage(3, 5), 0f)
        assertFalse(CustomGrains.isCustom("paper"))
    }

    @Test fun importedShapeStampsItsImage() {
        // A tile that is white on its left half only: the dab paints only left of centre.
        val values = ByteArray(16 * 16) { (if (it % 16 < 8) -1 else 0).toByte() }
        val tile = CustomGrains.Tile(16, values)
        val target = PixelBuffer(41, 41)
        Stamping.dab(
            target,
            20.5f,
            20.5f,
            radius = 16f,
            color = 0xFF000000.toInt(),
            tip = Stamping.TipShape(1f, 0f) { u, v -> tile.sample(u, v) },
        )
        assertTrue((target.pixels[20 * 41 + 10] ushr 24) > 200)
        assertEquals(0, target.pixels[20 * 41 + 30] ushr 24)
    }
}
