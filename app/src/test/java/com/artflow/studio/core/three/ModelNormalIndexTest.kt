package com.artflow.studio.core.three

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

class ModelNormalIndexTest {
    @Test
    fun gltfPrimitivesWithAndWithoutNormalsReachPreparedImport() {
        val data = triangleData()
        val uri = "data:application/octet-stream;base64," + Base64.getEncoder().encodeToString(data)
        assertPrepared(gltf(uri).toByteArray())
    }

    @Test
    fun glbPrimitivesWithAndWithoutNormalsReachPreparedImport() {
        val text = gltf(null).toByteArray().let { it + ByteArray((4 - it.size % 4) % 4) { ' '.code.toByte() } }
        val data = triangleData()
        val bytes =
            ByteBuffer
                .allocate(28 + text.size + data.size)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(0x46546C67)
                .putInt(2)
                .putInt(28 + text.size + data.size)
                .putInt(text.size)
                .putInt(0x4E4F534A)
                .put(text)
                .putInt(data.size)
                .putInt(0x004E4942)
                .put(data)
                .array()
        assertPrepared(bytes)
    }

    @Test
    fun usdMeshesWithAndWithoutNormalsReachPreparedImport() {
        val text =
            "#usda 1.0\n" +
                listOf(false, true, false, true)
                    .mapIndexed { index, normals ->
                        """
                        def Mesh "mesh$index" {
                            point3f[] points = [(0, 0, 0), (1, 0, 0), (0, 1, 0)]
                            int[] faceVertexCounts = [3]
                            int[] faceVertexIndices = [0, 1, 2]
                            texCoord2f[] primvars:st = [(0, 0), (1, 0), (0, 1)]
                            ${if (normals) "normal3f[] normals = [(0, 0, 1), (0, 0, 1), (0, 0, 1)]" else ""}
                        }
                        """.trimIndent()
                    }.joinToString("\n")
        assertPrepared(text.toByteArray())
    }

    private fun assertPrepared(bytes: ByteArray) {
        val contents = ModelPackage.read(bytes)
        val prepared =
            PreparedModelImport.prepare(
                contents,
                decode = { error("This model has no image to decode") },
                inspect = { error("This model has no image to inspect") },
                defaultSize = 16,
            )
        val mesh = ObjParser.parse(prepared.objText)
        assertEquals(4, mesh.triangleCount)
        assertTrue(mesh.positions.all(Float::isFinite))
        assertTrue(mesh.normals.all(Float::isFinite))
        assertEquals(16, prepared.width)
        assertEquals(16, prepared.height)
        assertNull(prepared.color)
        assertEquals(
            listOf("f 1/1 2/2 3/3", "f 4/4/1 5/5/2 6/6/3", "f 7/7 8/8 9/9", "f 10/10/4 11/11/5 12/12/6"),
            prepared.objText
                .lineSequence()
                .filter { it.startsWith("f ") }
                .toList(),
        )
    }

    private fun triangleData(): ByteArray =
        ByteBuffer
            .allocate(96)
            .order(ByteOrder.LITTLE_ENDIAN)
            .apply {
                floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f).forEach { putFloat(it) }
                floatArrayOf(0f, 1f, 1f, 1f, 0f, 0f).forEach { putFloat(it) }
                floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 0f, 0f, 1f).forEach { putFloat(it) }
            }.array()

    private fun gltf(uri: String?): String {
        val primitives =
            listOf(false, true, false, true).joinToString(",") { normals ->
                """{"attributes":{"POSITION":0,"TEXCOORD_0":1${if (normals) ",\"NORMAL\":2" else ""}}}"""
            }
        return """
            {"asset":{"version":"2.0"},"meshes":[{"primitives":[$primitives]}],
             "accessors":[
              {"bufferView":0,"componentType":5126,"count":3,"type":"VEC3"},
              {"bufferView":1,"componentType":5126,"count":3,"type":"VEC2"},
              {"bufferView":2,"componentType":5126,"count":3,"type":"VEC3"}],
             "bufferViews":[{"buffer":0,"byteLength":36},
              {"buffer":0,"byteOffset":36,"byteLength":24},{"buffer":0,"byteOffset":60,"byteLength":36}],
             "buffers":[{"byteLength":96${uri?.let { ",\"uri\":\"$it\"" } ?: ""}}]}
            """.trimIndent()
    }
}
