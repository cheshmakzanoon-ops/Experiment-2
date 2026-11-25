package com.artflow.studio.core.three

import com.artflow.studio.core.export.PngCodec
import com.artflow.studio.core.pixels.Channels
import com.artflow.studio.core.pixels.PixelBuffer
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Self-contained glTF 2.0 binary (`.glb`) PBR exporter.
 * Packs BaseColor PNG, glTF-spec Metallic-Roughness PNG (G = roughness, B = metallic),
 * an optional tangent-space Normal PNG and the model's own geometry directly inside the GLB binary chunk.
 */
object GltfPbrExport {
    private const val GLB_MAGIC = 0x46546C67 // "glTF"
    private const val GLB_VERSION = 2
    private const val CHUNK_JSON = 0x4E4F534A // "JSON"
    private const val CHUNK_BIN = 0x004E4942 // "BIN\0"
    private const val TARGET_ARRAY_BUFFER = 34962
    private const val COMPONENT_FLOAT = 5126
    private const val MODE_TRIANGLES = 4

    /** Packs separate roughness and metallic atlases into the glTF 2.0 metallicRoughnessTexture layout (G=roughness, B=metallic). */
    fun packMetallicRoughness(
        metallic: PixelBuffer?,
        roughness: PixelBuffer?,
        width: Int,
        height: Int,
    ): PixelBuffer {
        val out = PixelBuffer(width, height)
        val defaultM = (MaterialChannel.METALLIC.defaultValue * 255f).toInt().coerceIn(0, 255)
        val defaultR = (MaterialChannel.ROUGHNESS.defaultValue * 255f).toInt().coerceIn(0, 255)
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                val idx = row + x
                val m = if (metallic != null && x < metallic.width && y < metallic.height) metallic.getSafe(x, y) and 0xFF else defaultM
                val r = if (roughness != null && x < roughness.width && y < roughness.height) roughness.getSafe(x, y) and 0xFF else defaultR
                out.pixels[idx] = Channels.argb(255, 0, r, m)
            }
        }
        return out
    }

    /**
     * Exports a PBR model as a valid `.glb`: the embedded BaseColor, Metallic-Roughness and optional Normal PNGs
     * and, when [mesh] is given, the model's own geometry (positions, normals and texture coordinates), one
     * node, one scene and the material assigned to the mesh. Without a [mesh] the file holds the material and its
     * textures only and shows nothing, so a caller exporting a model must pass it.
     */
    fun exportGlb(
        name: String,
        baseColor: PixelBuffer,
        metallic: PixelBuffer? = null,
        roughness: PixelBuffer? = null,
        normalMap: PixelBuffer? = null,
        mesh: Mesh? = null,
    ): ByteArray {
        val basePng = PngCodec.encode(baseColor)
        val mrPng = PngCodec.encode(packMetallicRoughness(metallic, roughness, baseColor.width, baseColor.height))
        val normalPng = normalMap?.let { PngCodec.encode(it) }

        val bin = ByteArrayOutputStream()
        val baseOffset = appendPadded(bin, basePng)
        val mrOffset = appendPadded(bin, mrPng)
        val normalOffset = normalPng?.let { appendPadded(bin, it) }
        // glTF texture space has its origin at the top-left, while OBJ's v = 0 is the bottom of the image.
        val geometry =
            mesh?.let {
                val uvs = FloatArray(it.uvs.size) { index -> if (index % 2 == 1) 1f - it.uvs[index] else it.uvs[index] }
                Geometry(
                    positionOffset = appendPadded(bin, floats(it.positions)),
                    normalOffset = appendPadded(bin, floats(it.normals)),
                    uvOffset = appendPadded(bin, floats(uvs)),
                    positions = it.positions,
                    vertexCount = it.positions.size / 3,
                )
            }
        val binBytes = bin.toByteArray()

        // Quotes, backslashes and control characters would break the JSON text.
        val safeName = name.filter { it.code >= 0x20 && it != '"' && it != '\\' }.ifBlank { "ArtFlowMaterial" }
        val bufferViews =
            buildList {
                add("""{"buffer":0,"byteOffset":$baseOffset,"byteLength":${basePng.size}}""")
                add("""{"buffer":0,"byteOffset":$mrOffset,"byteLength":${mrPng.size}}""")
                if (normalPng != null && normalOffset != null) {
                    add("""{"buffer":0,"byteOffset":$normalOffset,"byteLength":${normalPng.size}}""")
                }
                if (geometry != null) {
                    val count = geometry.vertexCount
                    add("""{"buffer":0,"byteOffset":${geometry.positionOffset},"byteLength":${count * 12},"target":$TARGET_ARRAY_BUFFER}""")
                    add("""{"buffer":0,"byteOffset":${geometry.normalOffset},"byteLength":${count * 12},"target":$TARGET_ARRAY_BUFFER}""")
                    add("""{"buffer":0,"byteOffset":${geometry.uvOffset},"byteLength":${count * 8},"target":$TARGET_ARRAY_BUFFER}""")
                }
            }.joinToString(",")
        val images =
            buildList {
                add("""{"mimeType":"image/png","bufferView":0}""")
                add("""{"mimeType":"image/png","bufferView":1}""")
                if (normalPng != null) add("""{"mimeType":"image/png","bufferView":2}""")
            }.joinToString(",")
        val textures =
            buildList {
                add("""{"source":0}""")
                add("""{"source":1}""")
                if (normalPng != null) add("""{"source":2}""")
            }.joinToString(",")
        val normalField = if (normalPng != null) ""","normalTexture":{"index":2}""" else ""
        val firstGeometryView = if (normalPng != null) 3 else 2
        val geometryJson = geometry?.let { geometryJson(it, firstGeometryView, safeName) }.orEmpty()
        val jsonText =
            """{"asset":{"version":"2.0","generator":"ArtFlow Studio"},""" +
                """"materials":[{"name":"$safeName","pbrMetallicRoughness":{""" +
                """"baseColorTexture":{"index":0},"metallicRoughnessTexture":{"index":1},""" +
                """"metallicFactor":1.0,"roughnessFactor":1.0}$normalField}],""" +
                """"textures":[$textures],"images":[$images],"bufferViews":[$bufferViews],""" +
                """"buffers":[{"byteLength":${binBytes.size}}]$geometryJson}"""

        val jsonRaw = jsonText.toByteArray(Charsets.UTF_8)
        val jsonPad = (4 - (jsonRaw.size % 4)) % 4
        val jsonLen = jsonRaw.size + jsonPad
        val totalLen = 12 + 8 + jsonLen + 8 + binBytes.size

        val out = ByteBuffer.allocate(totalLen).order(ByteOrder.LITTLE_ENDIAN)
        out.putInt(GLB_MAGIC)
        out.putInt(GLB_VERSION)
        out.putInt(totalLen)
        out.putInt(jsonLen)
        out.putInt(CHUNK_JSON)
        out.put(jsonRaw)
        repeat(jsonPad) { out.put(0x20.toByte()) } // JSON chunk padded with spaces per glTF 2.0 spec
        out.putInt(binBytes.size)
        out.putInt(CHUNK_BIN)
        out.put(binBytes)
        return out.array()
    }

    /** Accessors, mesh, node and scene for the geometry; the three buffer views start at [firstView]. */
    private fun geometryJson(
        geometry: Geometry,
        firstView: Int,
        name: String,
    ): String {
        val (low, high) = geometry.bounds()
        val count = geometry.vertexCount
        val position =
            """{"bufferView":$firstView,"componentType":$COMPONENT_FLOAT,"count":$count,"type":"VEC3",""" +
                """"min":[${low.joinToString(",")}],"max":[${high.joinToString(",")}]}"""
        val normal = """{"bufferView":${firstView + 1},"componentType":$COMPONENT_FLOAT,"count":$count,"type":"VEC3"}"""
        val uv = """{"bufferView":${firstView + 2},"componentType":$COMPONENT_FLOAT,"count":$count,"type":"VEC2"}"""
        return ""","accessors":[$position,$normal,$uv],""" +
            """"meshes":[{"name":"$name","primitives":[{"attributes":{"POSITION":0,"NORMAL":1,"TEXCOORD_0":2},""" +
            """"material":0,"mode":$MODE_TRIANGLES}]}],"nodes":[{"name":"$name","mesh":0}],""" +
            """"scenes":[{"nodes":[0]}],"scene":0"""
    }

    private class Geometry(
        val positionOffset: Int,
        val normalOffset: Int,
        val uvOffset: Int,
        val positions: FloatArray,
        val vertexCount: Int,
    ) {
        /** The component-wise minimum and maximum a glTF POSITION accessor must declare. */
        fun bounds(): Pair<List<String>, List<String>> {
            val low = FloatArray(3) { Float.POSITIVE_INFINITY }
            val high = FloatArray(3) { Float.NEGATIVE_INFINITY }
            for (i in positions.indices) {
                val axis = i % 3
                if (positions[i] < low[axis]) low[axis] = positions[i]
                if (positions[i] > high[axis]) high[axis] = positions[i]
            }
            return low.map { java.lang.Float.toString(it) } to high.map { java.lang.Float.toString(it) }
        }
    }

    private fun floats(values: FloatArray): ByteArray {
        val buffer = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        values.forEach { buffer.putFloat(it) }
        return buffer.array()
    }

    private fun appendPadded(
        stream: ByteArrayOutputStream,
        bytes: ByteArray,
    ): Int {
        val offset = stream.size()
        stream.write(bytes)
        val pad = (4 - (bytes.size % 4)) % 4
        repeat(pad) { stream.write(0) }
        return offset
    }
}
