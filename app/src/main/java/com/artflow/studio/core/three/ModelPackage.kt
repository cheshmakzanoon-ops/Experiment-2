package com.artflow.studio.core.three

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

/**
 * A 3D model as picked by the artist: plain OBJ text, glTF, USD, or a zip holding the OBJ with its MTL
 * materials and texture images. From a zip, the diffuse texture (map_Kd) of the first material
 * the model uses comes along so painting starts from the model's own look.
 */
object ModelPackage {
    const val MAX_BYTES = 64 * 1024 * 1024
    private const val MAX_ENTRIES = 512
    private val ZIP_MAGIC = byteArrayOf(0x50, 0x4B, 0x03, 0x04)

    class Contents(
        val objText: String,
        /** Encoded image bytes of the model's texture, if the package had one. */
        val texture: ByteArray? = null,
        /** Each material's texture by the name its faces use (`usemtl`), for models with several. */
        val textures: Map<String, ByteArray> = emptyMap(),
    )

    fun read(bytes: ByteArray): Contents {
        require(bytes.size <= MAX_BYTES) { "This 3D model is too large" }
        if (GltfReader.isGlb(bytes) || isGltfText(bytes)) return gltf(bytes, emptyMap())
        if (UsdReader.isUsd(bytes)) return contents(UsdReader.read(bytes))
        if (!isZip(bytes)) return Contents(bytes.decodeToString())
        val files = unzip(bytes)
        // A .usdz is a zip whose first USD file is the scene.
        files.keys.firstOrNull { it.endsWith(".usdc") || it.endsWith(".usda") || it.endsWith(".usd") }?.let { name ->
            return contents(UsdReader.read(requireNotNull(files[name]), files))
        }
        files.keys.firstOrNull { it.endsWith(".obj") }?.let { name ->
            val objText = requireNotNull(files[name]).decodeToString()
            val textures = materialTextures(objText, files)
            return Contents(objText, textureFor(objText, files), textures)
        }
        val scene = files.keys.firstOrNull { it.endsWith(".glb") } ?: files.keys.firstOrNull { it.endsWith(".gltf") }
        return gltf(requireNotNull(files[scene ?: error("The zip has no OBJ or glTF model in it")]), files)
    }

    private fun gltf(
        bytes: ByteArray,
        files: Map<String, ByteArray>,
    ): Contents = contents(GltfReader.read(bytes, files))

    private fun contents(result: GltfReader.Result) = Contents(result.objText, result.texture, result.textures)

    /** Every material's diffuse texture found in the package, by material name. */
    internal fun materialTextures(
        objText: String,
        files: Map<String, ByteArray>,
    ): Map<String, ByteArray> {
        val maps =
            files.filterKeys { it.endsWith(".mtl") }.values.fold(emptyMap<String, String>()) { all, mtl ->
                diffuseMaps(mtl.decodeToString()) +
                    all
            }
        return statements(objText, "usemtl")
            .distinct()
            .mapNotNull { name ->
                maps[name]?.let { files[baseName(it)] }?.let { name to it }
            }.toMap()
    }

    /** A .gltf file is JSON describing an "asset". */
    private fun isGltfText(bytes: ByteArray): Boolean {
        val start = bytes.indexOfFirst { !it.toInt().toChar().isWhitespace() }
        return start >= 0 &&
            bytes[start] == '{'.code.toByte() &&
            String(bytes, start, minOf(bytes.size - start, PEEK)).contains("\"asset\"")
    }

    /** The diffuse texture of the first material [objText] uses (or of the first textured one). */
    internal fun textureFor(
        objText: String,
        files: Map<String, ByteArray>,
    ): ByteArray? {
        val libraries = statements(objText, "mtllib").map(::baseName)
        val used = statements(objText, "usemtl")
        val mtlText =
            (libraries.mapNotNull { files[it] } + files.filterKeys { it.endsWith(".mtl") }.values)
                .firstOrNull()
                ?.decodeToString() ?: return null
        val textures = diffuseMaps(mtlText)
        val chosen = used.firstNotNullOfOrNull { textures[it] } ?: textures.values.firstOrNull() ?: return null
        return files[baseName(chosen)]
    }

    /** Material name to its map_Kd file, in file order. */
    internal fun diffuseMaps(mtlText: String): Map<String, String> {
        val maps = LinkedHashMap<String, String>()
        var material: String? = null
        for (line in mtlText.lineSequence().map { it.trim() }) {
            if (line.startsWith("newmtl ")) material = line.removePrefix("newmtl ").trim()
            val name = material
            if (name == null || !line.startsWith("map_Kd ")) continue
            // Options such as "-s 1 1 1" come before the file name, which may contain spaces.
            val tokens =
                line
                    .removePrefix("map_Kd ")
                    .trim()
                    .split(Regex("\\s+"))
                    .dropWhile { it.startsWith("-") }
            textureFile(tokens)?.let { maps.putIfAbsent(name, it) }
        }
        return maps
    }

    private fun textureFile(tokens: List<String>): String? {
        val imageAt = tokens.indexOfFirst { token -> IMAGE_EXTENSIONS.any { token.lowercase().endsWith(it) } }
        if (imageAt < 0) return tokens.lastOrNull()
        // Skip numeric option values, then keep the rest of the name up to the extension.
        val start = tokens.take(imageAt + 1).indexOfFirst { it.toFloatOrNull() == null }
        return tokens.subList(start.coerceAtLeast(0), imageAt + 1).joinToString(" ")
    }

    private fun statements(
        text: String,
        keyword: String,
    ): List<String> =
        text
            .lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("$keyword ") }
            .map { it.removePrefix("$keyword ").trim() }
            .toList()

    /** Zip entries keyed by lower-case file name without folders; later duplicates are ignored. */
    private fun unzip(bytes: ByteArray): Map<String, ByteArray> {
        val files = LinkedHashMap<String, ByteArray>()
        var total = 0L
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entries = 0
            var entry = zip.nextEntry
            while (entry != null) {
                require(++entries <= MAX_ENTRIES) { "The zip has too many files" }
                if (!entry.isDirectory) {
                    val data = readEntry(zip, MAX_BYTES - total)
                    total += data.size
                    files.putIfAbsent(baseName(entry.name), data)
                }
                entry = zip.nextEntry
            }
        }
        return files
    }

    /** The current entry's bytes; fails once more than [budget] bytes would be read. */
    private fun readEntry(
        zip: ZipInputStream,
        budget: Long,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER)
        var read = zip.read(buffer)
        while (read >= 0) {
            out.write(buffer, 0, read)
            require(out.size() <= budget) { "This 3D model is too large" }
            read = zip.read(buffer)
        }
        return out.toByteArray()
    }

    private fun baseName(path: String): String =
        path
            .replace('\\', '/')
            .substringAfterLast('/')
            .trim()
            .lowercase()

    private fun isZip(bytes: ByteArray): Boolean = bytes.size >= ZIP_MAGIC.size && ZIP_MAGIC.indices.all { bytes[it] == ZIP_MAGIC[it] }

    private const val BUFFER = 64 * 1024
    private const val PEEK = 4096
    private val IMAGE_EXTENSIONS = listOf(".png", ".jpg", ".jpeg", ".webp", ".bmp")
}
