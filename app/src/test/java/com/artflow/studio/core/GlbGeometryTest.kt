package com.artflow.studio.core

import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.core.three.GltfPbrExport
import com.artflow.studio.core.three.ObjParser
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** A model export must contain the model: geometry, UVs, a node, a scene and the material assignment. */
class GlbGeometryTest {
    private val obj =
        """
        v 0 0 0
        v 1 0 0
        v 0 1 0
        vt 0 0
        vt 1 0
        vt 0 0.25
        vn 0 0 1
        f 1/1/1 2/2/1 3/3/1
        """.trimIndent()

    private data class Parsed(
        val json: JsonObject,
        val bin: ByteBuffer,
    )

    private fun parse(glb: ByteArray): Parsed {
        val header = ByteBuffer.wrap(glb).order(ByteOrder.LITTLE_ENDIAN)
        header.position(12)
        val jsonLength = header.int
        header.int
        val json = Json.parseToJsonElement(String(glb, 20, jsonLength, Charsets.UTF_8)).jsonObject
        header.position(20 + jsonLength)
        val binLength = header.int
        header.int
        val bin = ByteBuffer.wrap(glb, header.position(), binLength).slice().order(ByteOrder.LITTLE_ENDIAN)
        return Parsed(json, bin)
    }

    @Test
    fun exportedGlbHoldsTheModelsGeometryNodeSceneAndMaterialAssignment() {
        val mesh = ObjParser.parse(obj)
        val glb =
            GltfPbrExport.exportGlb(
                "Statue \"one\"\\",
                PixelBuffer(4, 4).apply { fill(0xFFCC4422.toInt()) },
                mesh = mesh,
            )
        val (json, bin) = parse(glb)

        val primitive =
            json["meshes"]!!
                .jsonArray
                .single()
                .jsonObject["primitives"]!!
                .jsonArray
                .single()
                .jsonObject
        assertEquals(0, primitive["material"]!!.jsonPrimitive.int)
        val attributes = primitive["attributes"]!!.jsonObject
        val accessors = json["accessors"]!!.jsonArray
        assertEquals(3, accessors.size)
        val position = accessors[attributes["POSITION"]!!.jsonPrimitive.int].jsonObject
        assertEquals(3, position["count"]!!.jsonPrimitive.int)
        assertEquals("VEC3", position["type"]!!.jsonPrimitive.content)
        assertTrue("A POSITION accessor must declare min and max", position["min"] is JsonArray && position["max"] is JsonArray)

        assertEquals(
            0,
            json["nodes"]!!
                .jsonArray
                .single()
                .jsonObject["mesh"]!!
                .jsonPrimitive.int,
        )
        assertEquals(0, json["scene"]!!.jsonPrimitive.int)
        assertEquals(
            0,
            json["scenes"]!!
                .jsonArray
                .single()
                .jsonObject["nodes"]!!
                .jsonArray
                .single()
                .jsonPrimitive.int,
        )

        // The positions in the binary chunk are the mesh's own, and glTF's v axis points down.
        val views = json["bufferViews"]!!.jsonArray
        val uvAccessor = accessors[attributes["TEXCOORD_0"]!!.jsonPrimitive.int].jsonObject
        val uvView = views[uvAccessor["bufferView"]!!.jsonPrimitive.int].jsonObject
        val uvOffset = uvView["byteOffset"]!!.jsonPrimitive.int
        val flipped = (0 until 6).map { bin.getFloat(uvOffset + it * 4) }
        assertEquals(1f, flipped[1], 1e-6f)
        assertEquals(0.75f, flipped[5], 1e-6f)
        val positionView = views[position["bufferView"]!!.jsonPrimitive.int].jsonObject
        val positionOffset = positionView["byteOffset"]!!.jsonPrimitive.int
        mesh.positions.forEachIndexed { i, expected -> assertEquals(expected, bin.getFloat(positionOffset + i * 4), 1e-6f) }
    }

    @Test
    fun aMaterialOnlyExportStillDeclaresNoGeometry() {
        val (json, _) = parse(GltfPbrExport.exportGlb("Flat", PixelBuffer(2, 2).apply { fill(-1) }))
        assertNull(json["meshes"])
        assertNull(json["scenes"])
    }
}
