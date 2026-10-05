package com.artflow.studio.core.three

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

class GltfReaderTest {
    private val image = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 1, 2, 3, 4)

    /** A unit quad (two triangles): positions, UVs with v = 0 at the top, ushort indices, then the image. */
    private fun binary(): ByteArray {
        val data = ByteBuffer.allocate(48 + 32 + 12 + image.size).order(ByteOrder.LITTLE_ENDIAN)
        floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 1f, 1f, 0f, 0f, 1f, 0f).forEach { data.putFloat(it) }
        floatArrayOf(0f, 1f, 1f, 1f, 1f, 0f, 0f, 0f).forEach { data.putFloat(it) }
        shortArrayOf(0, 1, 2, 0, 2, 3).forEach { data.putShort(it) }
        data.put(image)
        return data.array()
    }

    private fun json(bufferUri: String?): String =
        """
        {"asset":{"version":"2.0"},"scene":0,"scenes":[{"nodes":[0]}],
         "nodes":[{"children":[1],"scale":[2,2,2]},{"mesh":0,"translation":[5,0,0]}],
         "meshes":[{"primitives":[{"attributes":{"POSITION":0,"TEXCOORD_0":1},"indices":2,"material":0}]}],
         "materials":[{"pbrMetallicRoughness":{"baseColorTexture":{"index":0}}}],
         "textures":[{"source":0}],"images":[{"bufferView":3,"mimeType":"image/png"}],
         "accessors":[
           {"bufferView":0,"componentType":5126,"count":4,"type":"VEC3"},
           {"bufferView":1,"componentType":5126,"count":4,"type":"VEC2"},
           {"bufferView":2,"componentType":5123,"count":6,"type":"SCALAR"}],
         "bufferViews":[
           {"buffer":0,"byteOffset":0,"byteLength":48},{"buffer":0,"byteOffset":48,"byteLength":32},
           {"buffer":0,"byteOffset":80,"byteLength":12},{"buffer":0,"byteOffset":92,"byteLength":${image.size}}],
         "buffers":[{"byteLength":${binary().size}${bufferUri?.let { ",\"uri\":\"$it\"" } ?: ""}}]}
        """.trimIndent()

    private fun glb(): ByteArray {
        val text = json(null).toByteArray().let { it + ByteArray((4 - it.size % 4) % 4) { ' '.code.toByte() } }
        val bin = binary().let { it + ByteArray((4 - it.size % 4) % 4) }
        val out = ByteArrayOutputStream()

        fun int(value: Int) =
            out.write(
                ByteBuffer
                    .allocate(4)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .putInt(value)
                    .array(),
            )
        int(0x46546C67)
        int(2)
        int(12 + 8 + text.size + 8 + bin.size)
        int(text.size)
        int(0x4E4F534A)
        out.write(text)
        int(bin.size)
        int(0x004E4942)
        out.write(bin)
        return out.toByteArray()
    }

    @Test
    fun aBinaryModelBecomesATexturedMeshWithItsNodeTransforms() {
        val contents = ModelPackage.read(glb())
        assertArrayEquals(image, contents.texture)
        val lines = contents.objText.lines()
        // Scale 2 around translation 5: the first corner lands at x = 10.
        assertTrue(lines.first { it.startsWith("v ") }.startsWith("v 10.0 0.0 0.0"))
        // v is flipped: glTF's top-left origin becomes OBJ's bottom-left.
        assertEquals("vt 0.0 0.0", lines.first { it.startsWith("vt ") })
        val mesh = ObjParser.parse(contents.objText)
        assertEquals(2, mesh.triangleCount)
        assertEquals(setOf(0, 0, 0, 0, 0, 0).size, mesh.islands.distinct().size)
    }

    @Test
    fun textGltfWithEmbeddedDataReadsTheSame() {
        val uri = "data:application/octet-stream;base64," + Base64.getEncoder().encodeToString(binary())
        val contents = ModelPackage.read(json(uri).toByteArray())
        assertArrayEquals(image, contents.texture)
        assertEquals(2, ObjParser.parse(contents.objText).triangleCount)
        // An external buffer that is not supplied asks for the whole package.
        assertThrows(IllegalArgumentException::class.java) { ModelPackage.read(json("scene.bin").toByteArray()) }
    }
}
