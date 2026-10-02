package com.artflow.studio.data.local

import com.artflow.studio.core.render.CustomGrains
import com.artflow.studio.domain.model.brush.BrushParams
import com.artflow.studio.domain.model.brush.SavedBrush
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class BrushFilesTest {
    @Test fun sharedBrushCarriesItsImportedGrain() {
        val tile = CustomGrains.Tile(CustomGrains.TILE, ByteArray(CustomGrains.TILE * CustomGrains.TILE) { (it % 251).toByte() })
        val id = CustomGrains.idFor(tile)
        CustomGrains.register(id, tile)
        val brush = SavedBrush("shared", "Paper ink", BrushParams(textureId = id, blendTexture = true))
        val (brushes, images) = BrushFiles.decode(BrushFiles.encode(listOf(brush)))
        assertEquals(listOf(brush), brushes)
        assertArrayEquals(tile.values, images.getValue(id).values)
    }

    @Test(expected = IllegalArgumentException::class)
    fun otherFilesAreRejected() {
        BrushFiles.decode("""{"format":"something-else","library":""}""")
    }

    @Test fun fileNamesAreSafe() {
        assertEquals("Ink pen.artbrush", BrushFiles.fileName("Ink pen/"))
        assertEquals("Brush.artbrush", BrushFiles.fileName("///"))
    }
}
