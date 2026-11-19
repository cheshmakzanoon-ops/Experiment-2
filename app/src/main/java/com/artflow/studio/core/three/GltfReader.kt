package com.artflow.studio.core.three

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Reads glTF 2.0 models (binary .glb, or .gltf with embedded or accompanying buffers) into the OBJ
 * form the 3D workflow stores, with node transforms applied and the base colour texture of the
 * first textured material.
 */
object GltfReader {
    class Result(
        val objText: String,
        val texture: ByteArray?,
        /** Each material's base colour image by the name its faces use (`usemtl`), when it has one. */
        val textures: Map<String, ByteArray> = emptyMap(),
    )

    private const val GLB_MAGIC = 0x46546C67
    private const val CHUNK_JSON = 0x4E4F534A
    private const val CHUNK_BIN = 0x004E4942
    private const val GLB_HEADER = 12
    private const val CHUNK_HEADER = 8
    private const val TRIANGLES = 4
    private const val FLOAT = 5126
    private const val UNSIGNED_BYTE = 5121
    private const val UNSIGNED_SHORT = 5123
    private const val UNSIGNED_INT = 5125
    private const val BYTE_MAX = 255f
    private const val SHORT_MAX = 65535f
    private val json = Json { ignoreUnknownKeys = true }

    fun isGlb(bytes: ByteArray): Boolean = bytes.size >= GLB_HEADER && little(bytes).getInt(0) == GLB_MAGIC

    /** [bytes] is a .glb or .gltf file; [files] holds other files from the same package by lower-case name. */
    fun read(
        bytes: ByteArray,
        files: Map<String, ByteArray> = emptyMap(),
    ): Result = read(bytes, files, ModelReadBudget())

    internal fun read(
        bytes: ByteArray,
        files: Map<String, ByteArray>,
        budget: ModelReadBudget,
    ): Result {
        val (text, bin) = if (isGlb(bytes)) chunks(bytes, budget) else budget.text(bytes) to null
        budget.json(text)
        val root = json.parseToJsonElement(text).jsonObject
        val document = Document(root, buffers(root, bin, files, budget), files, budget)
        val out = ObjWriter(budget)
        val textures = LinkedHashMap<String, ByteArray>()
        val instances = meshInstances(root)
        // Account for every instance before allocating any accessor arrays or expanded OBJ text.
        for ((meshIndex, _) in instances) {
            for (primitive in array(document.mesh(meshIndex)["primitives"])) {
                document.geometry(primitive.jsonObject)?.let(out::reserve)
            }
        }
        for ((meshIndex, matrix) in instances) {
            for (primitive in array(document.mesh(meshIndex)["primitives"])) {
                val material = addPrimitive(document, primitive.jsonObject, matrix, out)
                if (material != null && material !in textures) remember(textures, material, document.baseColour(material))
            }
        }
        require(out.triangles > 0) { "This model has no textured triangles (UVs) to paint on" }
        return Result(out.text(), textures.values.firstOrNull(), textures)
    }

    /**
     * Adds a triangle primitive with positions and texture coordinates to [out]; others are skipped.
     * Returns the name its faces use for the primitive's material when it was added with one.
     */
    private fun addPrimitive(
        document: Document,
        primitive: JsonObject,
        matrix: FloatArray,
        out: ObjWriter,
    ): String? {
        val attributes = primitive["attributes"]?.jsonObject ?: return null
        val mode = primitive["mode"]?.jsonPrimitive?.intOrNull ?: TRIANGLES
        val uvAccessor = attributes["TEXCOORD_0"]?.jsonPrimitive?.intOrNull
        val positionAccessor = attributes["POSITION"]?.jsonPrimitive?.intOrNull
        if (mode != TRIANGLES || uvAccessor == null || positionAccessor == null) return null
        val positions = document.floats(positionAccessor)
        val normals = attributes["NORMAL"]?.jsonPrimitive?.intOrNull?.let(document::floats)
        val indices = primitive["indices"]?.jsonPrimitive?.intOrNull?.let(document::ints) ?: IntArray(positions.size / 3) { it }
        // Faces without a material get a name of their own, so they never take on the previous one.
        val material = primitive["material"]?.jsonPrimitive?.intOrNull?.let { "material$it" } ?: "unassigned"
        out.add(positions, normals, document.floats(uvAccessor), indices, matrix, material)
        return material
    }

    private fun remember(
        textures: MutableMap<String, ByteArray>,
        material: String,
        image: ByteArray?,
    ) {
        if (image != null) textures[material] = image
    }

    /** The JSON text and binary chunk of a .glb file. */
    private fun chunks(
        bytes: ByteArray,
        budget: ModelReadBudget,
    ): Pair<String, ByteArray?> {
        val data = little(bytes)
        require(data.getInt(4) == 2 && data.getInt(8) == bytes.size) { "This GLB file has an invalid header" }
        var offset = GLB_HEADER
        var text: String? = null
        var bin: ByteArray? = null
        while (offset + CHUNK_HEADER <= bytes.size) {
            val length = data.getInt(offset)
            val type = data.getInt(offset + 4)
            val start = offset + CHUNK_HEADER
            require(length >= 0 && length % 4 == 0 && length.toLong() <= (bytes.size - start).toLong()) { "This GLB file is damaged" }
            require(offset != GLB_HEADER || type == CHUNK_JSON) { "The GLB scene description must be first" }
            when (type) {
                CHUNK_JSON -> {
                    require(text == null) { "The GLB file repeats its scene description" }
                    budget.reserve(length.toLong() * Char.SIZE_BYTES)
                    text = String(bytes, start, length, Charsets.UTF_8)
                }
                CHUNK_BIN -> {
                    require(bin == null) { "The GLB file repeats its binary data" }
                    budget.reserve(length.toLong())
                    bin = bytes.copyOfRange(start, start + length)
                }
            }
            offset = start + length
        }
        require(offset == bytes.size) { "The GLB file ends inside a chunk header" }
        return requireNotNull(text) { "This GLB file has no scene description" } to bin
    }

    private fun buffers(
        root: JsonObject,
        bin: ByteArray?,
        files: Map<String, ByteArray>,
        budget: ModelReadBudget,
    ): List<ByteArray> =
        array(root["buffers"]).map { element ->
            val uri = element.jsonObject["uri"]?.jsonPrimitive?.content
            val data =
                when {
                    uri == null -> requireNotNull(bin) { "The model's binary data is missing" }
                    uri.startsWith("data:") -> budget.embedded(uri)
                    else -> requireNotNull(files[baseName(uri)]) { "The model needs $uri; pick a zip with all its files" }
                }
            val length = requireNotNull(element.jsonObject["byteLength"]?.jsonPrimitive?.intOrNull) { "Missing buffer length" }
            require(length >= 0 && length <= data.size) { "The model's buffer is cut short" }
            data
        }

    /** Every mesh in the default scene with its world transform (column-major 4 × 4). */
    private fun meshInstances(root: JsonObject): List<Pair<Int, FloatArray>> {
        val nodes = array(root["nodes"]).map { it.jsonObject }
        val scenes = array(root["scenes"])
        val sceneIndex = root["scene"]?.jsonPrimitive?.intOrNull ?: 0
        val children = nodes.flatMap { ints(it["children"]) }.toHashSet()
        val roots =
            scenes
                .getOrNull(sceneIndex)
                ?.jsonObject
                ?.get("nodes")
                ?.let { ints(it) }
                ?: nodes.indices.filter { index -> index !in children }
        val found = mutableListOf<Pair<Int, FloatArray>>()
        // A valid node has one parent. A node shared by several parents is walked once, not once per path.
        val visited = HashSet<Int>()

        fun visit(
            index: Int,
            parent: FloatArray,
            depth: Int,
        ) {
            val node = nodes.getOrNull(index) ?: return
            if (!visited.add(index)) return
            require(depth < MAX_DEPTH) { "The model's node tree is too deep" }
            val world = Matrix4.multiply(parent, local(node))
            require(world.all { it.isFinite() }) { "Invalid number in the model transform" }
            node["mesh"]?.jsonPrimitive?.intOrNull?.let { found += it to world }
            ints(node["children"]).forEach { visit(it, world, depth + 1) }
        }
        roots.forEach { visit(it, Matrix4.IDENTITY, 0) }
        if (found.isEmpty() && nodes.isEmpty()) array(root["meshes"]).indices.forEach { found += it to Matrix4.IDENTITY }
        return found
    }

    private fun local(node: JsonObject): FloatArray {
        node["matrix"]?.let {
            val values = floats(it)
            require(values.size == 16) { "Invalid node matrix" }
            return values
        }
        val t = requireVector(node["translation"], 3, "Invalid node translation", floatArrayOf(0f, 0f, 0f))
        val r = requireVector(node["rotation"], 4, "Invalid node rotation", floatArrayOf(0f, 0f, 0f, 1f))
        val s = requireVector(node["scale"], 3, "Invalid node scale", floatArrayOf(1f, 1f, 1f))
        return Matrix4.compose(t, r, s)
    }

    private fun requireVector(
        element: JsonElement?,
        size: Int,
        message: String,
        fallback: FloatArray,
    ): FloatArray {
        if (element == null) return fallback
        return floats(element).also { require(it.size == size) { message } }
    }

    /** Typed access to the document's accessors, materials and images. */
    private class Geometry(
        val vertices: Int,
        val indices: Int,
    )

    private class AccessorLayout(
        val data: ByteBuffer?,
        val count: Int,
        val components: Int,
        val start: Int,
        val stride: Int,
        val size: Int,
    ) {
        fun position(index: Int): Int = start + (index / components) * stride + (index % components) * size
    }

    private class Document(
        val root: JsonObject,
        val buffers: List<ByteArray>,
        val files: Map<String, ByteArray>,
        val budget: ModelReadBudget,
    ) {
        fun mesh(index: Int): JsonObject =
            requireNotNull(array(root["meshes"]).getOrNull(index)) { "A node refers to a missing mesh" }.jsonObject

        private fun accessor(index: Int) = requireNotNull(array(root["accessors"]).getOrNull(index)) { "Missing accessor" }.jsonObject

        private fun view(index: Int) = requireNotNull(array(root["bufferViews"]).getOrNull(index)) { "Missing buffer view" }.jsonObject

        fun geometry(primitive: JsonObject): Geometry? {
            val attributes = primitive["attributes"]?.jsonObject ?: return null
            val mode = primitive["mode"]?.jsonPrimitive?.intOrNull ?: TRIANGLES
            val position = attributes["POSITION"]?.jsonPrimitive?.intOrNull
            val uv = attributes["TEXCOORD_0"]?.jsonPrimitive?.intOrNull
            if (mode != TRIANGLES || position == null || uv == null) return null
            val count = vertexAccessor(position, "VEC3", setOf(FLOAT))
            require(vertexAccessor(uv, "VEC2", setOf(FLOAT, UNSIGNED_BYTE, UNSIGNED_SHORT)) == count) {
                "The model's vertex data does not line up"
            }
            attributes["NORMAL"]?.jsonPrimitive?.intOrNull?.let {
                require(vertexAccessor(it, "VEC3", setOf(FLOAT)) == count) { "The model's vertex data does not line up" }
            }
            val indices =
                primitive["indices"]?.jsonPrimitive?.intOrNull?.let { index ->
                    val accessor = accessor(index)
                    require(accessor["type"]?.jsonPrimitive?.content == "SCALAR") { "The model indices must be scalar values" }
                    require(
                        int(accessor, "componentType") in setOf(UNSIGNED_BYTE, UNSIGNED_SHORT, UNSIGNED_INT),
                    ) { "Unsupported model indices" }
                    layout(accessor, 1).count
                } ?: count
            require(indices >= 3 && indices % 3 == 0) { "The model's triangle indices are incomplete" }
            return Geometry(count, indices)
        }

        private fun vertexAccessor(
            index: Int,
            expected: String,
            types: Set<Int>,
        ): Int {
            val accessor = accessor(index)
            val type = int(accessor, "componentType")
            require(accessor["type"]?.jsonPrimitive?.content == expected && type in types) { "Unsupported model vertex type" }
            require(type == FLOAT || accessor["normalized"]?.jsonPrimitive?.booleanOrNull == true) {
                "Integer texture coordinates must be normalized"
            }
            return layout(accessor, components(expected)).count
        }

        fun floats(index: Int): FloatArray {
            val accessor = accessor(index)
            val components = components(accessor["type"]?.jsonPrimitive?.content)
            val type = int(accessor, "componentType")
            val normalized = accessor["normalized"]?.jsonPrimitive?.booleanOrNull ?: false
            val layout = layout(accessor, components)
            return FloatArray(layout.count * components) { i ->
                val data = layout.data ?: return@FloatArray 0f
                val at = layout.position(i)
                when (type) {
                    FLOAT -> data.getFloat(at)
                    UNSIGNED_BYTE -> (data.get(at).toInt() and 0xFF).let { if (normalized) it / BYTE_MAX else it.toFloat() }
                    UNSIGNED_SHORT -> (data.getShort(at).toInt() and 0xFFFF).let { if (normalized) it / SHORT_MAX else it.toFloat() }
                    else -> throw IllegalArgumentException("Unsupported vertex data in the model")
                }
            }.also { values -> require(values.all { it.isFinite() }) { "Invalid number in the model" } }
        }

        fun ints(index: Int): IntArray {
            val accessor = accessor(index)
            val type = int(accessor, "componentType")
            val layout = layout(accessor, 1)
            return IntArray(layout.count) { i ->
                val data = layout.data ?: return@IntArray 0
                val at = layout.position(i)
                when (type) {
                    UNSIGNED_BYTE -> data.get(at).toInt() and 0xFF
                    UNSIGNED_SHORT -> data.getShort(at).toInt() and 0xFFFF
                    UNSIGNED_INT -> data.getInt(at).also { require(it >= 0) { "A model index exceeds the supported range" } }
                    else -> throw IllegalArgumentException("Unsupported index data in the model")
                }
            }
        }

        private fun layout(
            accessor: JsonObject,
            components: Int,
        ): AccessorLayout {
            val count = int(accessor, "count")
            require("sparse" !in accessor) { "Sparse glTF accessors are not supported; export a dense model" }
            require(count in 0..MAX_ELEMENTS && count.toLong() * components <= MAX_ELEMENTS.toLong()) { "This model is too large" }
            val viewIndex = accessor["bufferView"]?.jsonPrimitive?.intOrNull ?: return AccessorLayout(null, count, components, 0, 0, 0)
            val view = view(viewIndex)
            val bufferIndex = int(view, "buffer")
            val buffer = requireNotNull(buffers.getOrNull(bufferIndex)) { "Missing buffer" }
            val bufferLength = int(array(root["buffers"])[bufferIndex].jsonObject, "byteLength")
            val viewLength = int(view, "byteLength")
            val size = componentSize(int(accessor, "componentType"))
            val minStride = size * components
            val stride = view["byteStride"]?.jsonPrimitive?.intOrNull ?: minStride
            val viewOffset = view["byteOffset"]?.jsonPrimitive?.intOrNull ?: 0
            val accessorOffset = accessor["byteOffset"]?.jsonPrimitive?.intOrNull ?: 0
            require(viewOffset >= 0 && accessorOffset >= 0 && stride >= minStride && stride % size == 0) {
                "The model's data layout is invalid"
            }
            val startLong = viewOffset.toLong() + accessorOffset.toLong()
            require(
                viewLength >= 0 &&
                    viewOffset.toLong() + viewLength <= bufferLength &&
                    accessorOffset <= viewLength &&
                    startLong % size == 0L &&
                    (count == 0 || accessorOffset.toLong() + (count - 1).toLong() * stride + minStride <= viewLength),
            ) { "The model's data is cut short" }
            return AccessorLayout(little(buffer), count, components, startLong.toInt(), stride, size)
        }

        /** Encoded image bytes of [name]'s ("material" and its index) base colour texture, if it has one. */
        fun baseColour(name: String): ByteArray? {
            val material = name.removePrefix("material").toIntOrNull() ?: return null
            val definition = requireNotNull(array(root["materials"]).getOrNull(material)) { "The model refers to a missing material" }
            val pbr =
                definition.jsonObject["pbrMetallicRoughness"]?.jsonObject ?: return null
            val reference = pbr["baseColorTexture"]?.jsonObject ?: return null
            val textureIndex = int(reference, "index")
            val texture = requireNotNull(array(root["textures"]).getOrNull(textureIndex)) { "The model refers to a missing texture" }
            val source = int(texture.jsonObject, "source")
            val image = requireNotNull(array(root["images"]).getOrNull(source)) { "The model refers to a missing image" }.jsonObject
            image["bufferView"]?.jsonPrimitive?.intOrNull?.let { index ->
                val view = view(index)
                val bufferIndex = int(view, "buffer")
                val buffer = requireNotNull(buffers.getOrNull(bufferIndex)) { "The model texture buffer is missing" }
                val bufferLength = int(array(root["buffers"])[bufferIndex].jsonObject, "byteLength")
                val offset = view["byteOffset"]?.jsonPrimitive?.intOrNull ?: 0
                val length = int(view, "byteLength")
                require(offset >= 0 && length >= 0 && offset.toLong() + length <= bufferLength) { "The model texture is cut short" }
                budget.reserve(length.toLong())
                return buffer.copyOfRange(offset, offset + length)
            }
            val uri = requireNotNull(image["uri"]?.jsonPrimitive?.content) { "The model texture has no image data" }
            return if (uri.startsWith("data:")) {
                budget.embedded(uri)
            } else {
                requireNotNull(files[baseName(uri)]) { "The model needs $uri; pick a zip with all its files" }
            }
        }

        private fun int(
            item: JsonObject,
            key: String,
        ): Int = requireNotNull(item[key]?.jsonPrimitive?.intOrNull) { "The model is missing $key" }
    }

    /** Collects triangles as OBJ text: one vertex record per glTF vertex, faces in world space. */
    private class ObjWriter(
        private val budget: ModelReadBudget,
    ) {
        private val text = StringBuilder()
        private var vertices = 0
        private var plannedTriangles = 0L
        var triangles = 0
            private set

        fun reserve(geometry: Geometry) {
            plannedTriangles += geometry.indices / 3
            require(plannedTriangles <= ObjParser.MAX_TRIANGLES) { "This model has too many triangles" }
            // Worst-case float/index strings, StringBuilder growth + final string, accessor arrays,
            // and the subsequent OBJ mesh workspace. Shared mesh instances each expand into text.
            budget.reserve(geometry.vertices * OUTPUT_VERTEX_BYTES + geometry.indices * OUTPUT_INDEX_BYTES)
        }

        fun add(
            positions: FloatArray,
            normals: FloatArray?,
            uvs: FloatArray,
            indices: IntArray,
            matrix: FloatArray,
            material: String,
        ) {
            val count = positions.size / 3
            require(uvs.size / 2 >= count && (normals == null || normals.size / 3 >= count)) { "The model's vertex data does not line up" }
            require(indices.all { it in 0 until count }) { "A face refers to a missing vertex" }
            for (v in 0 until count) {
                val p = Matrix4.point(matrix, positions[v * 3], positions[v * 3 + 1], positions[v * 3 + 2])
                require(p.x.isFinite() && p.y.isFinite() && p.z.isFinite()) { "Invalid transformed vertex in the model" }
                text
                    .append("v ")
                    .append(p.x)
                    .append(' ')
                    .append(p.y)
                    .append(' ')
                    .append(p.z)
                    .append('\n')
                // glTF texture coordinates start at the top; OBJ's start at the bottom.
                text
                    .append("vt ")
                    .append(uvs[v * 2])
                    .append(' ')
                    .append(1f - uvs[v * 2 + 1])
                    .append('\n')
                if (normals != null) {
                    val n = Matrix4.direction(matrix, normals[v * 3], normals[v * 3 + 1], normals[v * 3 + 2])
                    require(n.x.isFinite() && n.y.isFinite() && n.z.isFinite()) { "Invalid transformed normal in the model" }
                    text
                        .append("vn ")
                        .append(n.x)
                        .append(' ')
                        .append(n.y)
                        .append(' ')
                        .append(n.z)
                        .append('\n')
                }
            }
            text.append("usemtl ").append(material).append('\n')
            for (t in 0 until indices.size / 3) {
                text.append('f')
                for (corner in 0 until 3) {
                    val index = indices[t * 3 + corner]
                    require(index in 0 until count) { "A face refers to a missing vertex" }
                    val id = vertices + index + 1
                    text
                        .append(' ')
                        .append(id)
                        .append('/')
                        .append(id)
                    if (normals != null) text.append('/').append(id)
                }
                text.append('\n')
            }
            vertices += count
            triangles += indices.size / 3
            require(triangles <= ObjParser.MAX_TRIANGLES) { "This model has too many triangles" }
        }

        fun text(): String = text.toString()

        private companion object {
            const val OUTPUT_VERTEX_BYTES = 1280L
            const val OUTPUT_INDEX_BYTES = 192L
        }
    }

    private fun components(type: String?): Int =
        when (type) {
            "SCALAR" -> 1
            "VEC2" -> 2
            "VEC3" -> 3
            "VEC4" -> 4
            else -> error("Unsupported accessor type $type")
        }

    private fun componentSize(type: Int): Int =
        when (type) {
            UNSIGNED_BYTE -> 1
            UNSIGNED_SHORT -> 2
            else -> 4
        }

    private fun array(element: JsonElement?): JsonArray = (element as? JsonArray) ?: JsonArray(emptyList())

    private fun ints(element: JsonElement?): List<Int> = array(element).mapNotNull { it.jsonPrimitive.intOrNull }

    private fun floats(element: JsonElement): FloatArray =
        element.jsonArray.map { requireNotNull(it.jsonPrimitive.floatOrNull) }.toFloatArray()

    private fun little(bytes: ByteArray): ByteBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

    private fun baseName(path: String): String =
        path
            .replace('\\', '/')
            .substringAfterLast('/')
            .trim()
            .lowercase()

    private const val MAX_DEPTH = 64
    private const val MAX_ELEMENTS = 10_000_000
}

/** Column-major 4 × 4 matrices for node transforms. */
internal object Matrix4 {
    val IDENTITY = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)

    fun multiply(
        a: FloatArray,
        b: FloatArray,
    ): FloatArray =
        FloatArray(16) { i ->
            val column = i / 4
            val row = i % 4
            (0 until 4).sumOf { k -> (a[k * 4 + row] * b[column * 4 + k]).toDouble() }.toFloat()
        }

    /** Translation × rotation (quaternion x, y, z, w) × scale. */
    fun compose(
        t: FloatArray,
        q: FloatArray,
        s: FloatArray,
    ): FloatArray {
        val x = q[0]
        val y = q[1]
        val z = q[2]
        val w = q[3]
        return floatArrayOf(
            (1 - 2 * (y * y + z * z)) * s[0],
            (2 * (x * y + z * w)) * s[0],
            (2 * (x * z - y * w)) * s[0],
            0f,
            (2 * (x * y - z * w)) * s[1],
            (1 - 2 * (x * x + z * z)) * s[1],
            (2 * (y * z + x * w)) * s[1],
            0f,
            (2 * (x * z + y * w)) * s[2],
            (2 * (y * z - x * w)) * s[2],
            (1 - 2 * (x * x + y * y)) * s[2],
            0f,
            t[0],
            t[1],
            t[2],
            1f,
        )
    }

    fun point(
        m: FloatArray,
        x: Float,
        y: Float,
        z: Float,
    ) = Vec3(m[0] * x + m[4] * y + m[8] * z + m[12], m[1] * x + m[5] * y + m[9] * z + m[13], m[2] * x + m[6] * y + m[10] * z + m[14])

    fun direction(
        m: FloatArray,
        x: Float,
        y: Float,
        z: Float,
    ) = Vec3(m[0] * x + m[4] * y + m[8] * z, m[1] * x + m[5] * y + m[9] * z, m[2] * x + m[6] * y + m[10] * z).normalised()
}
