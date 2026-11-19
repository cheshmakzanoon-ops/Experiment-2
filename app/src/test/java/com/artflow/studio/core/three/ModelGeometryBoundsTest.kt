package com.artflow.studio.core.three

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ModelGeometryBoundsTest {
    @Test
    fun anAccessorCannotReadTheNextBufferViewEvenWhenTheBufferContainsEnoughBytes() {
        assertThrows(IllegalArgumentException::class.java) { GltfReader.read(gltf(positionBytes = 4)) }
    }

    @Test
    fun unsupportedSparsePositionsAreRefusedInsteadOfLosingTheirDeclaredEdits() {
        val sparse = """, "sparse":{"count":1,"indices":{"bufferView":2,"componentType":5123},"values":{"bufferView":0}}"""
        assertThrows(IllegalArgumentException::class.java) { GltfReader.read(gltf(positionExtra = sparse)) }
    }

    @Test
    fun anIncompleteTriangleIsRefusedInsteadOfDroppingTheLastIndex() {
        assertThrows(IllegalArgumentException::class.java) { GltfReader.read(gltf(indexCount = 4)) }
    }

    @Test
    fun glbHeadersAndChunkBoundariesMustDescribeExactlyTheSuppliedFile() {
        val text = zeroModel(3).let { it + ByteArray((4 - it.size % 4) % 4) { ' '.code.toByte() } }
        val valid =
            ByteBuffer
                .allocate(20 + text.size)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(0x46546C67)
                .putInt(2)
                .putInt(20 + text.size)
                .putInt(text.size)
                .putInt(0x4E4F534A)
                .put(text)
                .array()
        assertEquals(1, ObjParser.parse(GltfReader.read(valid).objText).triangleCount)
        for ((offset, changed) in listOf(4 to 1, 8 to valid.size + 4, 12 to text.size - 1, 16 to 0x004E4942)) {
            val damaged = valid.copyOf()
            ByteBuffer.wrap(damaged).order(ByteOrder.LITTLE_ENDIAN).putInt(offset, changed)
            assertThrows("header field $offset", IllegalArgumentException::class.java) { GltfReader.read(damaged) }
        }
    }

    @Test
    fun aDeclaredMissingTextureIsRefusedInsteadOfImportingAnUntexturedModel() {
        val source =
            gltf()
                .decodeToString()
                .replace("\"indices\":2", "\"indices\":2,\"material\":0")
                .replace(
                    "\"buffers\":[",
                    """"materials":[{"pbrMetallicRoughness":{"baseColorTexture":{"index":0}}}],
                "textures":[{"source":0}],"images":[{"uri":"missing.png"}],"buffers":[""",
                )
        val failure = assertThrows(IllegalArgumentException::class.java) { GltfReader.read(source.toByteArray()) }
        assertTrue(failure.message.orEmpty().contains("missing.png"))
    }

    @Test
    fun zeroFilledAccessorMetadataCannotAllocateLargeArraysBeforeGeometryAdmission() {
        val failure =
            assertThrows(IllegalArgumentException::class.java) {
                GltfReader.read(zeroModel(600_000), emptyMap(), ModelReadBudget(1024 * 1024))
            }
        assertTrue(failure.message.orEmpty().contains("more memory"))
    }

    @Test
    fun repeatedMeshInstancesSpendOneAggregateBudgetBeforeOutputBegins() {
        val budget = 160L * 1024
        assertEquals(33, ObjParser.parse(GltfReader.read(zeroModel(99), emptyMap(), ModelReadBudget(budget)).objText).triangleCount)
        val failure =
            assertThrows(IllegalArgumentException::class.java) {
                GltfReader.read(zeroModel(99, instances = 2), emptyMap(), ModelReadBudget(budget))
            }
        assertTrue(failure.message.orEmpty().contains("more memory"))
    }

    @Test
    fun aggregateTriangleCountsAreCheckedBeforeTheFirstAccessorArray() {
        val failure =
            assertThrows(IllegalArgumentException::class.java) {
                GltfReader.read(zeroModel(900_000, instances = 2), emptyMap(), ModelReadBudget(Long.MAX_VALUE))
            }
        assertEquals("This model has too many triangles", failure.message)
    }

    @Test
    fun aSingleHugePolygonIsCountedBeforeAnyCornerIndexIsParsedOrFanned() {
        val polygon = "f " + "x ".repeat(ObjParser.MAX_TRIANGLES + 3)
        val failure = assertThrows(IllegalArgumentException::class.java) { ObjParser.parse(polygon, Long.MAX_VALUE) }
        assertEquals("This model has too many triangles", failure.message)
    }

    @Test
    fun unusedVertexRecordsStillCountTowardTheBoxedSourceAndOutputBudget() {
        val obj = "v 0 0 0\n".repeat(1000) + TRIANGLE
        val failure = assertThrows(IllegalArgumentException::class.java) { ObjParser.parse(obj, 64 * 1024) }
        assertTrue(failure.message.orEmpty().contains("more memory"))
        assertEquals(1, ObjParser.parse(obj, 1024 * 1024).triangleCount)
    }

    @Test
    fun finiteExtremeCoordinatesStillProduceFiniteFramedGeometryAndNormals() {
        val obj = "v 3e38 3e38 0\nv -3e38 3e38 0\nv 0 -3e38 0\nvt 0 0\nvt 1 0\nvt 0 1\nf 1/1 2/2 3/3"
        val mesh = ObjParser.parse(obj)
        assertTrue(mesh.positions.all { it.isFinite() && it in -1f..1f })
        assertTrue(mesh.normals.all { it.isFinite() })
        assertEquals(1f, kotlin.math.abs(mesh.normals[2]), 0.0001f)
    }

    @Test
    fun deeplyNestedUnusedJsonIsRefusedBeforeBuildingItsTree() {
        val source = zeroModel(3).decodeToString().dropLast(1) + ",\"extras\":" + "[".repeat(65) + "0" + "]".repeat(65) + "}"
        val failure = assertThrows(IllegalArgumentException::class.java) { GltfReader.read(source.toByteArray()) }
        assertEquals("The model's JSON structure is too deep", failure.message)
    }

    @Test
    fun compactJsonNodesHaveTheirOwnAllocationCostBeforeParsing() {
        val source = zeroModel(3).decodeToString().dropLast(1) + ",\"extras\":[" + List(1000) { "0" }.joinToString(",") + "]}"
        val failure =
            assertThrows(IllegalArgumentException::class.java) {
                GltfReader.read(source.toByteArray(), emptyMap(), ModelReadBudget(64 * 1024))
            }
        assertTrue(failure.message.orEmpty().contains("more memory"))
    }

    @Test
    fun zipExpansionAccountsForRetainedEntriesCapacityGrowthAndFinalCopies() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("mesh.obj"))
            zip.write(TRIANGLE.toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("unused.bin"))
            zip.write(ByteArray(180 * 1024))
            zip.closeEntry()
        }
        val source = out.toByteArray()
        assertTrue(source.size < 2048)
        val failure = assertThrows(IllegalArgumentException::class.java) { ModelPackage.read(source, 256 * 1024) }
        assertTrue(failure.message.orEmpty().contains("more memory"))
        assertEquals(TRIANGLE, ModelPackage.read(source, 1024 * 1024).objText)
    }

    private fun zeroModel(
        vertices: Int,
        instances: Int = 1,
    ): ByteArray =
        """{"asset":{"version":"2.0"},
        "nodes":[${List(instances) { "{\"mesh\":0}" }.joinToString(",")}],
        "meshes":[{"primitives":[{"attributes":{"POSITION":0,"TEXCOORD_0":1}}]}],
        "accessors":[{"componentType":5126,"count":$vertices,"type":"VEC3"},
        {"componentType":5126,"count":$vertices,"type":"VEC2"}]}""".toByteArray()

    private fun gltf(
        positionBytes: Int = 36,
        positionExtra: String = "",
        indexCount: Int = 3,
    ): ByteArray {
        val bytes = ByteArray(68)
        bytes[62] = 1
        bytes[64] = 2
        val uri = "data:application/octet-stream;base64," + Base64.getEncoder().encodeToString(bytes)
        return """
            {"asset":{"version":"2.0"},"meshes":[{"primitives":[{
              "attributes":{"POSITION":0,"TEXCOORD_0":1},"indices":2}]}],
             "accessors":[
              {"bufferView":0,"componentType":5126,"count":3,"type":"VEC3"$positionExtra},
              {"bufferView":1,"componentType":5126,"count":3,"type":"VEC2"},
              {"bufferView":2,"componentType":5123,"count":$indexCount,"type":"SCALAR"}],
             "bufferViews":[{"buffer":0,"byteLength":$positionBytes},
              {"buffer":0,"byteOffset":36,"byteLength":24},{"buffer":0,"byteOffset":60,"byteLength":8}],
             "buffers":[{"byteLength":68,"uri":"$uri"}]}
            """.trimIndent().toByteArray()
    }

    private companion object {
        const val TRIANGLE = "v 0 0 0\nv 1 0 0\nv 0 1 0\nvt 0 0\nvt 1 0\nvt 0 1\nf 1/1 2/2 3/3"
    }
}
