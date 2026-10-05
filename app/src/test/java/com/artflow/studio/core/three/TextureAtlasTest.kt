package com.artflow.studio.core.three

import com.artflow.studio.core.pixels.PixelBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextureAtlasTest {
    private val red = 0xFFFF0000.toInt()
    private val blue = 0xFF0000FF.toInt()
    private val twoMaterials =
        """
        v 0 0 0
        v 1 0 0
        v 0 1 0
        v 1 1 0
        vt 0 0
        vt 1 0
        vt 0 1
        vt 1 1
        usemtl Red
        f 1/1 2/2 3/3
        usemtl Blue
        f 2/2 4/4 3/3
        """.trimIndent()

    private fun uvs(objText: String): List<Pair<Float, Float>> =
        objText.lines().filter { it.startsWith("vt ") }.map { line -> line.split(' ').let { it[1].toFloat() to it[2].toFloat() } }

    @Test
    fun eachMaterialGetsItsOwnCellAndItsFacesMoveThere() {
        val packed =
            requireNotNull(
                TextureAtlas.pack(
                    twoMaterials,
                    mapOf("Red" to PixelBuffer.filled(4, 4, red), "Blue" to PixelBuffer.filled(4, 4, blue)),
                    4096,
                ),
            )
        assertEquals(8, packed.image.width)
        assertEquals(4, packed.image.height)
        assertEquals(red, packed.image.pixels[0])
        assertEquals(blue, packed.image.pixels[7])
        val coordinates = uvs(packed.objText)
        assertEquals(6, coordinates.size)
        // Red's corners land in the left half, Blue's in the right, inset half a texel from the edges.
        assertTrue(coordinates.take(3).all { (u, _) -> u in 0f..0.5f })
        assertTrue(coordinates.drop(3).all { (u, _) -> u in 0.5f..1f })
        assertEquals(0.5f / 4 / 2, coordinates[0].first, 1e-5f)
        assertEquals(2, ObjParser.parse(packed.objText).triangleCount)
    }

    @Test
    fun oneMaterialNeedsNoAtlasAndLargeTexturesShrinkToFit() {
        assertNull(TextureAtlas.pack(twoMaterials.replace("usemtl Blue", "usemtl Red"), emptyMap(), 4096))
        val packed = requireNotNull(TextureAtlas.pack(twoMaterials, mapOf("Red" to PixelBuffer.filled(64, 32, red)), 32))
        assertEquals(32, packed.image.width)
        assertEquals(8, packed.image.height)
        // Blue has no texture: its cell is white.
        assertEquals(0xFFFFFFFF.toInt(), packed.image.pixels[31])
    }

    @Test
    fun usdzMeshesKeepTheirOwnMaterialsTextures() {
        val bytes = requireNotNull(javaClass.getResourceAsStream("/models/two.usdz")).use { it.readBytes() }
        val contents = ModelPackage.read(bytes)
        assertEquals(listOf("/World/Looks/MA", "/World/Looks/MB"), TextureAtlas.materials(contents.objText))
        assertEquals(setOf("/World/Looks/MA", "/World/Looks/MB"), contents.textures.keys)
        assertTrue(!contents.textures.getValue("/World/Looks/MA").contentEquals(contents.textures.getValue("/World/Looks/MB")))
    }
}
