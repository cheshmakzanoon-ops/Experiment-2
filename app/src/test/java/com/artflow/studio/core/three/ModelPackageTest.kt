package com.artflow.studio.core.three

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ModelPackageTest {
    private val obj = "mtllib box.mtl\nv 0 0 0\nv 1 0 0\nv 0 1 0\nvt 0 0\nvt 1 0\nvt 0 1\nusemtl Paint\nf 1/1 2/2 3/3\n"

    private fun zip(vararg files: Pair<String, ByteArray>): ByteArray =
        ByteArrayOutputStream()
            .also { bytes ->
                ZipOutputStream(bytes).use { zip ->
                    files.forEach { (name, data) ->
                        zip.putNextEntry(ZipEntry(name))
                        zip.write(data)
                        zip.closeEntry()
                    }
                }
            }.toByteArray()

    @Test
    fun plainObjTextIsReadAsIs() {
        val contents = ModelPackage.read(obj.toByteArray())
        assertEquals(obj, contents.objText)
        assertNull(contents.texture)
    }

    @Test
    fun aZipBringsTheTextureOfTheMaterialTheModelUses() {
        val mtl = "newmtl Other\nmap_Kd other.png\n\nnewmtl Paint\nKd 1 1 1\nmap_Kd -s 1 1 1 textures\\Wood Grain.PNG\n"
        val wood = byteArrayOf(1, 2, 3)
        val bytes =
            zip(
                "model/box.obj" to obj.toByteArray(),
                "model/box.mtl" to mtl.toByteArray(),
                "model/textures/other.png" to byteArrayOf(9),
                "model/textures/wood grain.png" to wood,
            )
        val contents = ModelPackage.read(bytes)
        assertEquals(1, ObjParser.parse(contents.objText).triangleCount)
        assertArrayEquals(wood, contents.texture)
    }

    @Test
    fun missingPiecesFallBackGracefully() {
        // No material file: the model still opens, without a texture.
        assertNull(ModelPackage.read(zip("box.obj" to obj.toByteArray())).texture)
        // A material without a map, but another textured one: that texture is used.
        val mtl = "newmtl Paint\nKd 1 0 0\nnewmtl Skin\nmap_Kd skin.jpg\n"
        val skin = byteArrayOf(7)
        assertArrayEquals(
            skin,
            ModelPackage.read(zip("box.obj" to obj.toByteArray(), "box.mtl" to mtl.toByteArray(), "skin.jpg" to skin)).texture,
        )
        assertThrows(IllegalArgumentException::class.java) { ModelPackage.read(zip("notes.txt" to byteArrayOf(1))) }
    }
}
