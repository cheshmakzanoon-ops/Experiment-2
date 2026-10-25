package com.artflow.studio.core.three

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

/**
 * A 3D model as picked by the artist: plain OBJ text, glTF, USD, or a zip holding the OBJ with its MTL
 * materials and texture images. Color-only packages retain their diffuse textures. The bounded
 * single-material contract written by [ObjExport] also retains its independent scalar maps.
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
        /** Non-null only for the supported single-material OBJ contract; these are data, not RGB color. */
        val scalarMaps: ScalarMaps? = null,
    )

    class ScalarMaps(
        val metallic: ByteArray? = null,
        val roughness: ByteArray? = null,
    )

    fun read(bytes: ByteArray): Contents {
        require(bytes.size <= MAX_BYTES) { "This 3D model is too large" }
        if (GltfReader.isGlb(bytes) || isGltfText(bytes)) return gltf(bytes, emptyMap())
        if (UsdReader.isUsd(bytes)) return contents(UsdReader.read(bytes))
        if (!isZip(bytes)) return Contents(bytes.decodeToString())
        val duplicates = HashSet<String>()
        val files =
            try {
                unzip(bytes, duplicates)
            } catch (damaged: java.io.IOException) {
                // A damaged archive, such as a CRC mismatch or a cut-off entry, is a refusal like any other.
                throw IllegalArgumentException("The 3D model package is damaged", damaged)
            }
        // A .usdz is a zip whose first USD file is the scene.
        files.keys.firstOrNull { it.endsWith(".usdc") || it.endsWith(".usda") || it.endsWith(".usd") }?.let { name ->
            return contents(UsdReader.read(requireNotNull(files[name]), files))
        }
        files.keys.firstOrNull { it.endsWith(".obj") }?.let { name ->
            val objText = requireNotNull(files[name]).decodeToString()
            scalarMaterial(objText, files, duplicates)?.let { material ->
                return Contents(objText, material.color, mapOf(material.name to material.color), material.maps)
            }
            val textures = materialTextures(objText, files)
            return Contents(objText, textureFor(objText, files), textures)
        }
        val scene = files.keys.firstOrNull { it.endsWith(".glb") } ?: files.keys.firstOrNull { it.endsWith(".gltf") }
        val name = scene ?: throw IllegalArgumentException("The zip has no OBJ or glTF model in it")
        return gltf(requireNotNull(files[name]), files)
    }

    private fun gltf(
        bytes: ByteArray,
        files: Map<String, ByteArray>,
    ): Contents = contents(GltfReader.read(bytes, files))

    private fun contents(result: GltfReader.Result) = Contents(result.objText, result.texture, result.textures)

    private class MaterialDefinition(
        val name: String,
        val library: String,
    ) {
        val values = LinkedHashMap<String, MutableList<String>>()

        fun one(key: String): String? {
            val declarations = values[key] ?: return null
            require(declarations.size == 1) { "The model material repeats $key" }
            return declarations.single()
        }
    }

    private class ScalarMaterial(
        val name: String,
        val color: ByteArray,
        val maps: ScalarMaps,
    )

    /** Optional PBR MTL fields are accepted only where their meanings match our own export. */
    private fun scalarMaterial(
        objText: String,
        files: Map<String, ByteArray>,
        duplicates: Set<String>,
    ): ScalarMaterial? {
        val used = TextureAtlas.materials(objText)
        val libraries = statements(objText, "mtllib").map(::baseName).distinct()
        val definitions =
            files
                .filterKeys { it.endsWith(".mtl") && (libraries.isEmpty() || it in libraries) }
                .flatMap { (name, bytes) -> materialDefinitions(name, bytes.decodeToString()) }
                .filter { it.name in used }
        if (definitions.none { definition -> definition.values.keys.any { it in SCALAR_FIELDS } }) return null
        require(used.size == 1 && used.single().isNotBlank()) { "Scalar maps require one used OBJ material with one shared UV atlas" }
        require(definitions.size == 1) { "The scalar material definition is missing or ambiguous" }
        val material = definitions.single()
        require(material.library !in duplicates) { "The scalar material library has an ambiguous file name" }
        require(material.values.keys.all { it in SUPPORTED_SCALAR_MATERIAL_FIELDS }) {
            "This scalar material uses unsupported properties; import an ArtFlow-compatible OBJ material"
        }
        material.one("Kd")?.let { color ->
            val values = color.split(WHITESPACE).map { it.toFloatOrNull() }
            require(values == listOf(1f, 1f, 1f)) { "Scalar material import requires an unmodified color texture (Kd 1 1 1)" }
        }
        val metallic = scalarImage(material, "Pm", MaterialChannel.METALLIC.defaultValue, files, duplicates)
        val roughness = scalarImage(material, "Pr", MaterialChannel.ROUGHNESS.defaultValue, files, duplicates)
        val color = requireNotNull(material.one("map_Kd")) { "The scalar material needs its color texture (map_Kd)" }
        return ScalarMaterial(material.name, materialImage("map_Kd", color, files, duplicates), ScalarMaps(metallic, roughness))
    }

    private fun materialDefinitions(
        library: String,
        text: String,
    ): List<MaterialDefinition> {
        val definitions = mutableListOf<MaterialDefinition>()
        var current: MaterialDefinition? = null
        text.lineSequence().forEach { raw ->
            val line = raw.substringBefore('#').trim()
            if (line.isNotEmpty()) {
                val parts = line.split(WHITESPACE, limit = 2)
                val key = parts[0]
                val value = parts.getOrElse(1) { "" }.trim()
                if (key == "newmtl") {
                    current = MaterialDefinition(value, library).also { definitions += it }
                } else {
                    require(current != null || key !in SCALAR_FIELDS) { "A scalar map has no material definition" }
                    current?.values?.getOrPut(key) { mutableListOf() }?.add(value)
                }
            }
        }
        return definitions
    }

    private fun scalarImage(
        material: MaterialDefinition,
        factorKey: String,
        fallback: Float,
        files: Map<String, ByteArray>,
        duplicates: Set<String>,
    ): ByteArray? {
        val mapKey = "map_$factorKey"
        val map = material.one(mapKey)
        val factor = material.one(factorKey)?.toFloatOrNull() ?: if (map == null && factorKey !in material.values) fallback else null
        val expected = if (map == null) fallback else 1f
        require(factor != null && factor.isFinite() && factor == expected) {
            "The supported scalar material requires $factorKey $expected ${if (map == null) "without $mapKey" else "with $mapKey"}"
        }
        return map?.let { materialImage(mapKey, it, files, duplicates) }
    }

    private fun materialImage(
        key: String,
        path: String,
        files: Map<String, ByteArray>,
        duplicates: Set<String>,
    ): ByteArray {
        require(path.isNotBlank() && path.split(WHITESPACE).none { it.startsWith('-') }) {
            "$key needs a texture file without map options"
        }
        val name = baseName(path)
        require(name.endsWith(".png")) { "$key must reference a PNG texture" }
        require(name !in duplicates) { "$key has an ambiguous texture file name" }
        return requireNotNull(files[name]) { "The model package is missing the $key texture: $path" }
    }

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
    private fun unzip(
        bytes: ByteArray,
        duplicates: MutableSet<String>,
    ): Map<String, ByteArray> {
        val files = LinkedHashMap<String, ByteArray>()
        var total = 0L
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entries = 0
            var entry = zip.nextEntry
            while (entry != null) {
                require(++entries <= MAX_ENTRIES) { "The zip has too many files" }
                if (entry.isDirectory) {
                    entry = zip.nextEntry
                    continue
                }
                val data = readEntry(zip, MAX_BYTES - total)
                total += data.size
                val name = baseName(entry.name)
                if (files.putIfAbsent(name, data) != null) duplicates += name
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
    private val WHITESPACE = Regex("\\s+")
    private val SCALAR_FIELDS = setOf("Pm", "Pr", "map_Pm", "map_Pr")
    private val SUPPORTED_SCALAR_MATERIAL_FIELDS = SCALAR_FIELDS + setOf("Ka", "Kd", "Ks", "map_Kd")
}
