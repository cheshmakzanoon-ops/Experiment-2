package com.artflow.studio.core.three

import com.artflow.studio.core.export.PngCodec
import com.artflow.studio.core.pixels.Channels
import com.artflow.studio.core.pixels.PixelBuffer
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Self-contained glTF 2.0 binary (`.glb`) PBR material exporter.
 * Packs BaseColor PNG, glTF-spec Metallic-Roughness PNG (G = roughness, B = metallic),
 * and optional tangent-space Normal PNG directly inside the GLB binary chunk.
 */
object GltfPbrExport {
    private const val GLB_MAGIC = 0x46546C67 // "glTF"
    private const val GLB_VERSION = 2
    private const val CHUNK_JSON = 0x4E4F534A // "JSON"
    private const val CHUNK_BIN = 0x004E4942 // "BIN\0"

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

    /** Exports a textured PBR quad/mesh with embedded BaseColor, Metallic-Roughness, and optional Normal PNGs as a valid `.glb`. */
    fun exportGlb(
        name: String,
        baseColor: PixelBuffer,
        metallic: PixelBuffer? = null,
        roughness: PixelBuffer? = null,
        normalMap: PixelBuffer? = null,
    ): ByteArray {
        val basePng = PngCodec.encode(baseColor)
        val mrPng = PngCodec.encode(packMetallicRoughness(metallic, roughness, baseColor.width, baseColor.height))
        val normalPng = normalMap?.let { PngCodec.encode(it) }

        val bin = ByteArrayOutputStream()
        val baseOffset = appendPadded(bin, basePng)
        val mrOffset = appendPadded(bin, mrPng)
        val normalOffset = normalPng?.let { appendPadded(bin, it) }
        val binBytes = bin.toByteArray()

        val safeName = name.replace("\"", "").ifBlank { "ArtFlowMaterial" }
        val bufferViews =
            buildList {
                add("""{"buffer":0,"byteOffset":$baseOffset,"byteLength":${basePng.size}}""")
                add("""{"buffer":0,"byteOffset":$mrOffset,"byteLength":${mrPng.size}}""")
                if (normalPng != null && normalOffset != null) {
                    add("""{"buffer":0,"byteOffset":$normalOffset,"byteLength":${normalPng.size}}""")
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
        val jsonText =
            """{"asset":{"version":"2.0","generator":"ArtFlow Studio"},""" +
                """"materials":[{"name":"$safeName","pbrMetallicRoughness":{""" +
                """"baseColorTexture":{"index":0},"metallicRoughnessTexture":{"index":1},""" +
                """"metallicFactor":1.0,"roughnessFactor":1.0}$normalField}],""" +
                """"textures":[$textures],"images":[$images],"bufferViews":[$bufferViews],""" +
                """"buffers":[{"byteLength":${binBytes.size}}]}"""

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
