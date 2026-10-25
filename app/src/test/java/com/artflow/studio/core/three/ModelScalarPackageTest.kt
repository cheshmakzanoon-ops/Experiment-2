package com.artflow.studio.core.three

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Material declarations are authored here, independently of ObjExport. Image decoding is tested separately. */
class ModelScalarPackageTest {
    private val obj =
        "mtllib paint.mtl\nv 0 0 0\nv 1 0 0\nv 0 1 0\nvt 0.1 0.2\nvt 0.8 0.2\nvt 0.5 0.9\nusemtl Paint\nf 1/1 2/2 3/3\n"
    private val color = byteArrayOf(1, 2, 3)
    private val metallic = byteArrayOf(4, 5, 6)
    private val roughness = byteArrayOf(7, 8, 9)
    private val both = "newmtl Paint\nKd 1 1 1\nmap_Kd color.png\nPm 1\nPr 1\nmap_Pm metal.png\nmap_Pr rough.png\n"

    @Test
    fun oneMaterialRetainsSeparateMapRolesAndUnchangedGeometry() {
        val contents = read(both)
        assertEquals(obj, contents.objText)
        assertArrayEquals(color, contents.texture)
        assertEquals(setOf("Paint"), contents.textures.keys)
        val maps = requireNotNull(contents.scalarMaps)
        assertArrayEquals(metallic, maps.metallic)
        assertArrayEquals(roughness, maps.roughness)
    }

    @Test
    fun missingMapUsesOnlyItsEstablishedDefault() {
        val onlyMetal = read(both.replace("Pr 1\n", "Pr 0.6\n").replace("map_Pr rough.png\n", ""))
        assertArrayEquals(metallic, requireNotNull(onlyMetal.scalarMaps).metallic)
        assertNull(onlyMetal.scalarMaps?.roughness)
        val onlyRough = read(both.replace("Pm 1\n", "Pm 0\n").replace("map_Pm metal.png\n", ""))
        assertNull(onlyRough.scalarMaps?.metallic)
        assertArrayEquals(roughness, requireNotNull(onlyRough.scalarMaps).roughness)
        val defaults = read("newmtl Paint\nmap_Kd color.png\nPm 0\nPr 0.6\n")
        assertNotNull(defaults.scalarMaps)
        assertNull(defaults.scalarMaps?.metallic)
        assertNull(defaults.scalarMaps?.roughness)
    }

    @Test
    fun mapFactorsMustBeExplicitAndExactlyOne() {
        for (key in listOf("Pm", "Pr")) {
            for (value in listOf("0", "0.5", "NaN", "Infinity", "-1", "2", "")) {
                refused(both.replace("$key 1\n", "$key $value\n"), key)
            }
            refused(both.replace("$key 1\n", ""), key)
        }
    }

    @Test
    fun unsupportedConstantMaterialsAreRefusedInsteadOfLosingTheirValues() {
        refused("newmtl Paint\nmap_Kd color.png\nPm 0.4\nPr 0.6\n", "Pm")
        refused("newmtl Paint\nmap_Kd color.png\nPm 0\nPr 0.8\n", "Pr")
    }

    @Test
    fun everyDeclaredMapMustExist() {
        for ((name, key) in listOf("color.png" to "map_Kd", "metal.png" to "map_Pm", "rough.png" to "map_Pr")) {
            val error = assertThrows(IllegalArgumentException::class.java) { read(both, omit = name) }
            assertTrue(error.message.orEmpty().contains(key))
            assertTrue(error.message.orEmpty().contains("missing"))
        }
    }

    @Test
    fun mapOptionsAndNonPngMapsAreRefused() {
        for ((key, name) in listOf("map_Kd" to "color.png", "map_Pm" to "metal.png", "map_Pr" to "rough.png")) {
            for (options in listOf("-s 2 1 1", "-o 0.25 0 0", "-imfchan r")) {
                refused(both.replace("$key $name", "$key $options $name"), "options")
            }
            refused(both.replace("$key $name", "$key ${name.replace(".png", ".jpg")}"), "PNG")
        }
    }

    @Test
    fun multipleUsedMaterialsCannotAttachScalarMapsToUnrelatedUvCells() {
        val two = obj + "usemtl Other\nf 1/1 2/2 3/3\n"
        val error = assertThrows(IllegalArgumentException::class.java) { read(both, model = two) }
        assertTrue(error.message.orEmpty().contains("one used OBJ material"))
    }

    @Test
    fun duplicatedMaterialOrMapDeclarationsAreAmbiguous() {
        refused(both + "Pm 1\n", "repeats Pm")
        refused(both + "map_Pr rough.png\n", "repeats map_Pr")
        refused(both + "newmtl Paint\nmap_Kd color.png\n", "ambiguous")
    }

    @Test
    fun duplicateFileBasenamesCannotSilentlySelectTheWrongScalarTexture() {
        val error =
            assertThrows(IllegalArgumentException::class.java) {
                read(both, extra = "different/metal.png" to byteArrayOf(90))
            }
        assertTrue(error.message.orEmpty().contains("ambiguous texture"))
        val libraryError =
            assertThrows(IllegalArgumentException::class.java) {
                read(both, extra = "different/paint.mtl" to both.toByteArray())
            }
        assertTrue(libraryError.message.orEmpty().contains("ambiguous file name"))
    }

    @Test
    fun aSupportedScalarMaterialNeedsItsColorMapAndAnUnmodifiedColorFactor() {
        refused(both.replace("map_Kd color.png\n", ""), "map_Kd")
        refused(both.replace("Kd 1 1 1", "Kd 0.5 1 1"), "Kd 1 1 1")
        refused(both + "map_d opacity.png\n", "unsupported properties")
    }

    @Test
    fun anUnownedScalarDeclarationIsRefused() {
        refused("map_Pm metal.png\n" + both, "no material definition")
    }

    @Test
    fun legacyColorPackagesKeepTheirOptionsAndFirstFileBehavior() {
        val legacy = "newmtl Paint\nmap_Kd -s 2 1 1 color.png\n"
        val contents = read(legacy, extra = "other/color.png" to byteArrayOf(99))
        assertArrayEquals(color, contents.texture)
        assertNull(contents.scalarMaps)
        val unusedScalar = read(legacy + "newmtl Unused\nPm 0.4\nmap_Pm absent.png\n")
        assertArrayEquals(color, unusedScalar.texture)
        assertNull(unusedScalar.scalarMaps)
    }

    private fun refused(
        mtl: String,
        message: String,
    ) {
        val error = assertThrows(IllegalArgumentException::class.java) { read(mtl) }
        assertTrue(error.message.orEmpty(), error.message.orEmpty().contains(message))
    }

    private fun read(
        mtl: String,
        model: String = obj,
        omit: String? = null,
        extra: Pair<String, ByteArray>? = null,
    ): ModelPackage.Contents {
        val entries =
            linkedMapOf(
                "scene.obj" to model.toByteArray(),
                "paint.mtl" to mtl.toByteArray(),
                "color.png" to color,
                "metal.png" to metallic,
                "rough.png" to roughness,
            )
        omit?.let(entries::remove)
        extra?.let { entries[it.first] = it.second }
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            entries.forEach { (name, data) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(data)
                zip.closeEntry()
            }
        }
        return ModelPackage.read(bytes.toByteArray())
    }
}
