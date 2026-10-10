package com.artflow.studio.core.three

import com.artflow.studio.core.export.Lz4
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Reads USD (binary "crate" .usdc, or text .usda, the layer inside a .usdz) into the OBJ form the
 * 3D workflow stores: every mesh with its transforms, texture coordinates and normals, Z-up scenes turned
 * Y-up, and the image of the texture feeding the material's diffuse colour.
 */
object UsdReader {
    private val MAGIC = "PXR-USDC".toByteArray()

    fun isUsdc(bytes: ByteArray): Boolean = bytes.size > MAGIC.size && MAGIC.indices.all { bytes[it] == MAGIC[it] }

    fun isUsd(bytes: ByteArray): Boolean = isUsdc(bytes) || UsdaParser.isUsda(bytes)

    /** Typed access to a layer's field values ([rep]), however the layer stores them. */
    internal interface Values<F> {
        fun token(rep: F?): String?

        fun tokens(rep: F): List<String>

        fun assetPath(rep: F): String?

        fun paths(rep: F): List<String>

        fun ints(rep: F): IntArray?

        fun floats(rep: F): FloatArray
    }

    /** [files] holds the package's other files by lower-case name, for the texture. */
    fun read(
        bytes: ByteArray,
        files: Map<String, ByteArray> = emptyMap(),
    ): GltfReader.Result {
        require(isUsd(bytes)) { "This USD file is not supported" }
        val scene =
            if (isUsdc(bytes)) {
                Crate(bytes).let { Scene(it.specs(), it) }
            } else {
                Scene(UsdaParser.parse(bytes.decodeToString()), UsdaParser)
            }
        val out = StringBuilder()
        var vertices = 0
        var triangles = 0
        val materials = LinkedHashSet<String>()
        for (mesh in scene.meshes()) {
            // Each mesh's faces carry its bound material, so models with several can share one artwork.
            val material = scene.boundMaterial(mesh) ?: "unassigned"
            materials += material
            out.append("usemtl ").append(material).append('\n')
            val added = scene.writeMesh(mesh, out, vertices)
            vertices += added.first
            triangles += added.second
            require(triangles <= ObjParser.MAX_TRIANGLES) { "This model has too many triangles" }
        }
        require(triangles > 0) { "This model has no textured triangles (UVs) to paint on" }
        val textures =
            materials
                .mapNotNull { material ->
                    scene.diffuseTexture(material)?.let { files[baseName(it)] }?.let { material to it }
                }.toMap()
        return GltfReader.Result(out.toString(), scene.diffuseTexture(null)?.let { files[baseName(it)] }, textures)
    }

    private fun baseName(path: String): String =
        path
            .replace('\\', '/')
            .substringAfterLast('/')
            .trim()
            .lowercase()

    /** The scene graph: prims by path with their fields, and attributes as "prim.name". */
    private class Scene<F>(
        private val specs: Map<String, Map<String, F>>,
        private val layer: Values<F>,
    ) {
        private val zUp = layer.token(specs["/"]?.get("upAxis")) == "Z"

        fun meshes(): List<String> =
            specs.keys.filter { path -> !path.contains('.') && layer.token(specs[path]?.get("typeName")) == "Mesh" }.sorted()

        private fun attribute(
            prim: String,
            name: String,
        ): Map<String, F>? = specs["$prim.$name"]

        private fun value(
            prim: String,
            name: String,
        ): F? = attribute(prim, name)?.get("default")

        /** Writes [prim]'s triangles as OBJ records numbered after [offset]; returns (vertices, triangles). */
        fun writeMesh(
            prim: String,
            out: StringBuilder,
            offset: Int,
        ): Pair<Int, Int> {
            val points = value(prim, "points")?.let(layer::floats) ?: return 0 to 0
            val counts = value(prim, "faceVertexCounts")?.let(layer::ints) ?: return 0 to 0
            val indices = value(prim, "faceVertexIndices")?.let(layer::ints) ?: return 0 to 0
            val uvs = primvar(prim, uvName(prim) ?: return 0 to 0, indices, 2) ?: return 0 to 0
            val normals = primvar(prim, "normals", indices, 3)
            val world = worldMatrix(prim)
            for (corner in indices.indices) {
                val p = indices[corner]
                require(p in 0 until points.size / 3) { "A face refers to a missing vertex" }
                val v = upright(Matrix4.point(world, points[p * 3], points[p * 3 + 1], points[p * 3 + 2]))
                out
                    .append("v ")
                    .append(v.x)
                    .append(' ')
                    .append(v.y)
                    .append(' ')
                    .append(v.z)
                    .append('\n')
                out
                    .append("vt ")
                    .append(uvs[corner * 2])
                    .append(' ')
                    .append(uvs[corner * 2 + 1])
                    .append('\n')
                if (normals != null) {
                    val n = upright(Matrix4.direction(world, normals[corner * 3], normals[corner * 3 + 1], normals[corner * 3 + 2]))
                    out
                        .append("vn ")
                        .append(n.x)
                        .append(' ')
                        .append(n.y)
                        .append(' ')
                        .append(n.z)
                        .append('\n')
                }
            }
            return indices.size to writeFaces(counts, indices.size, offset, normals != null, out)
        }

        private fun appendCorner(
            out: StringBuilder,
            id: Int,
            withNormals: Boolean,
        ) {
            out
                .append(' ')
                .append(id)
                .append('/')
                .append(id)
            if (withNormals) out.append('/').append(id)
        }

        /** Fans each polygon of [counts] into triangles; returns how many were written. */
        private fun writeFaces(
            counts: IntArray,
            corners: Int,
            offset: Int,
            withNormals: Boolean,
            out: StringBuilder,
        ): Int {
            var start = 0
            var triangles = 0
            for (count in counts) {
                require(count >= 0 && start + count <= corners) { "The mesh faces do not match its vertices" }
                for (k in 1 until count - 1) {
                    out.append('f')
                    intArrayOf(start, start + k, start + k + 1).forEach { corner -> appendCorner(out, offset + corner + 1, withNormals) }
                    out.append('\n')
                    triangles++
                }
                start += count
            }
            return triangles
        }

        /** USD prefers "st"; otherwise the first texture-coordinate primvar. */
        private fun uvName(prim: String): String? {
            if (attribute(prim, "primvars:st") != null) return "primvars:st"
            return specs.keys
                .filter { it.startsWith("$prim.primvars:") && !it.endsWith(":indices") }
                .firstOrNull { layer.token(specs[it]?.get("typeName"))?.startsWith("texCoord2") == true }
                ?.substringAfter("$prim.")
        }

        /** A primvar spread to one value per face corner, following its interpolation and indices. */
        private fun primvar(
            prim: String,
            name: String,
            corners: IntArray,
            width: Int,
        ): FloatArray? {
            val values = value(prim, name)?.let(layer::floats) ?: return null
            val lookup = value(prim, "$name:indices")?.let(layer::ints)
            val interpolation = layer.token(attribute(prim, name)?.get("interpolation")) ?: "vertex"
            val faceVarying = interpolation == "faceVarying"
            if (!faceVarying && interpolation != "vertex" && interpolation != "varying") return null
            val out = FloatArray(corners.size * width)
            for (corner in corners.indices) {
                val element = if (faceVarying) corner else corners[corner]
                val index = lookup?.getOrNull(element) ?: element
                if (index < 0 || (index + 1) * width > values.size) return null
                System.arraycopy(values, index * width, out, corner * width, width)
            }
            return out
        }

        private fun upright(v: Vec3): Vec3 = if (zUp) Vec3(v.x, v.z, -v.y) else v

        /** The prim's transform to the scene, from its own and its ancestors' transform operations. */
        private fun worldMatrix(prim: String): FloatArray {
            var world = Matrix4.IDENTITY
            var path = prim
            while (path.isNotEmpty() && path != "/") {
                val order = value(path, "xformOpOrder")?.let(layer::tokens).orEmpty()
                world = Matrix4.multiply(local(path, order), world)
                if ("!resetXformStack!" in order) break
                path = path.substringBeforeLast('/')
            }
            return world
        }

        private fun local(
            prim: String,
            order: List<String>,
        ): FloatArray =
            order.fold(Matrix4.IDENTITY) { matrix, op ->
                val rep = value(prim, op) ?: return@fold matrix
                Matrix4.multiply(matrix, operation(op, rep))
            }

        private fun operation(
            op: String,
            rep: F,
        ): FloatArray {
            val kind = op.removePrefix("xformOp:").substringBefore(':')
            return when (kind) {
                "transform" -> layer.floats(rep).also { require(it.size == MATRIX) { "Invalid transform" } }
                "translate" -> layer.floats(rep).let { Matrix4.compose(it, NO_ROTATION, ONES) }
                "scale" -> layer.floats(rep).let { Matrix4.compose(ZEROS, NO_ROTATION, it) }
                "rotateX", "rotateY", "rotateZ" -> axisRotation(kind.last(), layer.floats(rep).first())
                "rotateXYZ" ->
                    layer.floats(rep).let { (x, y, z) ->
                        Matrix4.multiply(axisRotation('Z', z), Matrix4.multiply(axisRotation('Y', y), axisRotation('X', x)))
                    }
                "orient" -> Matrix4.compose(ZEROS, layer.floats(rep), ONES)
                else -> Matrix4.IDENTITY
            }
        }

        /** The material bound to [prim] or its nearest ancestor with a binding. */
        fun boundMaterial(prim: String): String? {
            var path = prim
            while (path.isNotEmpty() && path != "/") {
                specs["$path.material:binding"]
                    ?.get("targetPaths")
                    ?.let(layer::paths)
                    ?.firstOrNull()
                    ?.let { return it }
                path = path.substringBeforeLast('/')
            }
            return null
        }

        /**
         * The image of the texture feeding a preview surface's diffuse colour within [material]
         * (anywhere when null), or failing that the first texture whose name suggests colour, or
         * the first texture at all.
         */
        fun diffuseTexture(material: String?): String? {
            fun inside(path: String) = material == null || path.startsWith("$material/")
            val textures =
                specs.keys
                    .filter { !it.contains('.') && inside(it) && layer.token(value(it, "info:id")) == "UsdUVTexture" }
                    .associateWith { prim -> value(prim, "inputs:file")?.let(layer::assetPath) }
                    .filterValues { it != null }
            val connected =
                specs.entries
                    .filter { (path, _) -> path.endsWith(".inputs:diffuseColor") && inside(path) }
                    .flatMap { (_, fields) -> fields["connectionPaths"]?.let(layer::paths).orEmpty() }
                    .map { it.substringBefore('.') }
            val chosen =
                connected.firstOrNull { it in textures }
                    ?: textures.keys.firstOrNull { COLOUR_HINT.containsMatchIn(it + " " + textures[it]) }
                    ?: textures.keys.firstOrNull()
            return chosen?.let { textures[it] }
        }

        private companion object {
            const val MATRIX = 16
            val NO_ROTATION = floatArrayOf(0f, 0f, 0f, 1f)
            val ONES = floatArrayOf(1f, 1f, 1f)
            val ZEROS = floatArrayOf(0f, 0f, 0f)
            val COLOUR_HINT = Regex("(?i)diffuse|albedo|base.?colou?r|color|colour")
        }
    }

    private fun axisRotation(
        axis: Char,
        degrees: Float,
    ): FloatArray {
        val radians = Math.toRadians(degrees.toDouble())
        val c = cos(radians).toFloat()
        val s = sin(radians).toFloat()
        val m = Matrix4.IDENTITY.copyOf()
        val (a, b) =
            when (axis) {
                'X' -> 1 to 2
                'Y' -> 2 to 0
                else -> 0 to 1
            }
        m[a * 4 + a] = c
        m[a * 4 + b] = s
        m[b * 4 + a] = -s
        m[b * 4 + b] = c
        return m
    }

    /**
     * The crate file itself: its token, field, path and spec tables, and typed values. Structural
     * sections are LZ4 compressed, integer tables also delta coded, as written by USD 0.4 and later.
     */
    private class Crate(
        val data: ByteArray,
    ) : Values<Long> {
        private val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)

        // Read without a bounds failure here, so that a file too short for its header is refused by init below.
        private val minor = data.getOrNull(MAGIC.size + 1)?.toInt() ?: 0
        private val sections = HashMap<String, Int>()
        val tokens: List<String>
        private val fieldNames: IntArray
        private val fieldValues: LongArray
        private val fieldSets: IntArray
        private val pathNames: Array<String?>

        init {
            // The magic alone is not enough: the header and table of contents must be present before they are read.
            require(data.size >= TOC_OFFSET + 8) { "The USD file is cut short" }
            require(data[MAGIC.size].toInt() == 0 && minor >= MIN_MINOR) { "This USD file version is not supported" }
            var at = position(longAt(TOC_OFFSET))
            repeat(count(longAt(at))) { index ->
                val entry = at + 8 + index * SECTION_ENTRY
                require(entry.toLong() + SECTION_ENTRY <= data.size) { "The USD file is damaged" }
                val name = String(data, entry, SECTION_NAME).trimEnd('\u0000')
                sections[name] = position(longAt(entry + SECTION_NAME))
            }
            at = section("TOKENS")
            val tokenCount = count(longAt(at))
            val raw = decompress(at + 24, count(longAt(at + 16)), count(longAt(at + 8)))
            tokens = String(raw, Charsets.UTF_8).split('\u0000').take(tokenCount)
            at = section("FIELDS")
            val fields = count(longAt(at))
            at += 8
            fieldNames = ints(at, fields).also { at = it.second }.first
            val repBytes = decompress(at + 8, count(longAt(at)), fields * 8)
            fieldValues = LongArray(fields) { ByteBuffer.wrap(repBytes).order(ByteOrder.LITTLE_ENDIAN).getLong(it * 8) }
            at = section("FIELDSETS")
            fieldSets = ints(at + 8, count(longAt(at))).first
            pathNames = paths(section("PATHS"))
        }

        private fun section(name: String): Int = requireNotNull(sections[name]) { "The USD file is missing its $name" }

        /** Prims and properties by path, each with its fields by name. */
        fun specs(): Map<String, Map<String, Long>> {
            val at = section("SPECS")
            val count = count(longAt(at))
            val (paths, afterPaths) = ints(at + 8, count)
            val (sets, _) = ints(afterPaths, count)
            val result = HashMap<String, Map<String, Long>>()
            for (spec in 0 until count) {
                val path = pathNames.getOrNull(paths[spec]) ?: continue
                val fields = HashMap<String, Long>()
                var index = sets[spec]
                while (index in fieldSets.indices && fieldSets[index] >= 0) {
                    val field = fieldSets[index++]
                    tokens.getOrNull(fieldNames.getOrElse(field) { -1 })?.let { fields[it] = fieldValues[field] }
                }
                result[path] = fields
            }
            return result
        }

        private fun paths(start: Int): Array<String?> {
            val total = count(longAt(start))
            val encoded = count(longAt(start + 8))
            val (indexes, a) = ints(start + 16, encoded)
            val (elements, b) = ints(a, encoded)
            val (jumps, _) = ints(b, encoded)
            val names = arrayOfNulls<String>(total)
            val pending = ArrayDeque<Pair<Int, String?>>()
            if (encoded > 0) pending.addLast(0 to null)
            while (pending.isNotEmpty()) {
                var (current, parent) = pending.removeLast()
                do {
                    val index = current++
                    require(index < encoded) { "The USD path table is damaged" }
                    val element = elements[index]
                    val token = tokens.getOrElse(abs(element)) { "" }
                    val path =
                        when {
                            parent == null -> "/"
                            element < 0 -> "$parent.$token"
                            parent == "/" -> "/$token"
                            else -> "$parent/$token"
                        }
                    if (indexes[index] in names.indices) names[indexes[index]] = path
                    val jump = jumps[index]
                    val child = jump > 0 || jump == -1
                    val sibling = jump >= 0
                    if (child && sibling) pending.addLast((index + jump) to parent)
                    if (child) parent = path
                } while (child || sibling)
            }
            return names
        }

        // --- Values -------------------------------------------------------------------------

        override fun token(rep: Long?): String? =
            rep?.takeIf { type(it) == TOKEN && inlined(it) }?.let { tokens.getOrNull(payload(it).toInt()) }

        /** A token list: a token[] attribute value, or a token vector field. */
        override fun tokens(rep: Long): List<String> {
            val tokenArray = type(rep) == TOKEN && array(rep)
            if (inlined(rep) || (!tokenArray && type(rep) != TOKEN_VECTOR)) return emptyList()
            val at = position(payload(rep))
            val count = if (tokenArray) arrayCount(at) else count(longAt(at))
            val start = at + if (tokenArray && minor < ARRAY_COUNT_64) 4 else 8
            return List(count) { tokens.getOrElse(intAt(start + it * 4)) { "" } }
        }

        override fun assetPath(rep: Long): String? =
            when {
                type(rep) != ASSET_PATH -> null
                inlined(rep) -> tokens.getOrNull(payload(rep).toInt())
                else -> tokens.getOrNull(intAt(position(payload(rep))))
            }

        /** The added and explicit items of a path list (connections), as path strings. */
        override fun paths(rep: Long): List<String> {
            if (type(rep) != PATH_LIST_OP || inlined(rep)) return emptyList()
            var at = position(payload(rep))
            val header = data[at++].toInt()
            val found = mutableListOf<String>()
            for (bit in LIST_BITS) {
                if (header and bit == 0) continue
                val count = count(longAt(at))
                repeat(count) { pathNames.getOrNull(intAt(at + 8 + it * 4))?.let(found::add) }
                at += 8 + count * 4
            }
            return found
        }

        override fun ints(rep: Long): IntArray? {
            if (type(rep) != INT || !array(rep)) return null
            val at = position(payload(rep))
            val count = arrayCount(at)
            val start = at + if (minor >= ARRAY_COUNT_64) 8 else 4
            if (compressed(rep)) return ints(start, count).first
            require(start.toLong() + count.toLong() * 4 <= data.size) { "The USD file is damaged" }
            return IntArray(count) { intAt(start + it * 4) }
        }

        /** Float-based values (scalars, vectors, matrices and their arrays) flattened to floats. */
        override fun floats(rep: Long): FloatArray {
            val type = type(rep)
            val width = WIDTH[type] ?: throw IllegalArgumentException("The USD file uses a value type that is not supported")
            val double = type in DOUBLES
            if (inlined(rep)) return inlinedFloats(rep, type, width)
            val at = position(payload(rep))
            val count = if (array(rep)) arrayCount(at) else 1
            require(!compressed(rep)) { "Compressed USD float data is not supported" }
            val start =
                at +
                    if (!array(rep)) {
                        0
                    } else if (minor >= ARRAY_COUNT_64) {
                        8
                    } else {
                        4
                    }
            val size = if (double) 8 else 4
            require(count.toLong() * width * size <= data.size - start) { "The USD data is cut short" }
            return FloatArray(count * width) { i ->
                if (double) buffer.getDouble(start + i * 8).toFloat() else buffer.getFloat(start + i * 4)
            }
        }

        /** Small whole-number vectors are stored inside the value itself as signed bytes. */
        private fun inlinedFloats(
            rep: Long,
            type: Int,
            width: Int,
        ): FloatArray {
            val bits = payload(rep)
            return when (type) {
                FLOAT, DOUBLE -> floatArrayOf(java.lang.Float.intBitsToFloat(bits.toInt()))
                MATRIX4D -> FloatArray(16).also { m -> for (i in 0 until 4) m[i * 5] = (bits shr (i * 8)).toByte().toFloat() }
                else -> FloatArray(width) { (bits shr (it * 8)).toByte().toFloat() }
            }
        }

        private fun arrayCount(at: Int): Int =
            if (minor >=
                ARRAY_COUNT_64
            ) {
                count(longAt(at))
            } else {
                intAt(at).also { require(it >= 0) }
            }

        /** Delta-coded integers behind an LZ4 block: returns the values and the position after them. */
        private fun ints(
            at: Int,
            count: Int,
        ): Pair<IntArray, Int> {
            val size = count(longAt(at))
            val coded = decompress(at + 8, size, 4 + (count * 2 + 7) / 8 + count * 4)
            val codes = ByteBuffer.wrap(coded).order(ByteOrder.LITTLE_ENDIAN)
            val common = codes.getInt(0)
            var value = 4 + (count * 2 + 7) / 8
            var previous = 0
            val result =
                IntArray(count) { i ->
                    val code = (coded[4 + i / 4].toInt() shr ((i % 4) * 2)) and 3
                    previous +=
                        when (code) {
                            0 -> common
                            1 -> coded[value].toInt().also { value += 1 }
                            2 -> codes.getShort(value).toInt().also { value += 2 }
                            else -> codes.getInt(value).also { value += 4 }
                        }
                    previous
                }
            return result to at + 8 + size
        }

        /** USD's chunked LZ4: a chunk count byte, then one block, or that many sized blocks. */
        private fun decompress(
            at: Int,
            size: Int,
            capacity: Int,
        ): ByteArray {
            require(size >= 1 && at + size <= data.size && capacity in 0..MAX_DECODED) { "The USD data is cut short" }
            // LZ4 expands its input by at most about 255 times, so a larger declared size cannot be honest. Refusing it
            // here stops a small file from making the reader allocate hundreds of megabytes.
            require(capacity.toLong() <= size.toLong() * MAX_EXPANSION + EXPANSION_SLACK) { "The USD data is cut short" }
            val out = ByteArray(capacity)
            val chunks = data[at].toInt() and 0xFF
            if (chunks == 0) {
                Lz4.decompress(data, at + 1, size - 1, out, 0)
            } else {
                var source = at + 1
                var written = 0
                repeat(chunks) {
                    val length = intAt(source)
                    written += Lz4.decompress(data, source + 4, length, out, written)
                    source += 4 + length
                }
            }
            return out
        }

        private fun position(value: Long): Int = value.also { require(it in 0 until data.size) { "The USD file is damaged" } }.toInt()

        /** Little-endian reads that refuse a position whose bytes are not all inside the file. */
        private fun longAt(at: Int): Long {
            require(at >= 0 && at.toLong() + 8 <= data.size) { "The USD file is damaged" }
            return buffer.getLong(at)
        }

        private fun intAt(at: Int): Int {
            require(at >= 0 && at.toLong() + 4 <= data.size) { "The USD file is damaged" }
            return buffer.getInt(at)
        }

        private fun count(value: Long): Int = value.also { require(it in 0..MAX_COUNT) { "The USD file is damaged" } }.toInt()

        private fun type(rep: Long) = ((rep ushr TYPE_SHIFT) and 0xFF).toInt()

        private fun array(rep: Long) = (rep ushr ARRAY_BIT) and 1L == 1L

        private fun inlined(rep: Long) = (rep ushr INLINED_BIT) and 1L == 1L

        private fun compressed(rep: Long) = (rep ushr COMPRESSED_BIT) and 1L == 1L

        private fun payload(rep: Long) = rep and PAYLOAD_MASK

        private companion object {
            const val MIN_MINOR = 4
            const val ARRAY_COUNT_64 = 7
            const val TOC_OFFSET = 16
            const val SECTION_NAME = 16
            const val SECTION_ENTRY = 32
            const val MAX_COUNT = 50_000_000L
            const val MAX_DECODED = 512 * 1024 * 1024
            const val MAX_EXPANSION = 255L
            const val EXPANSION_SLACK = 64L
            const val TYPE_SHIFT = 48
            const val ARRAY_BIT = 63
            const val INLINED_BIT = 62
            const val COMPRESSED_BIT = 61
            const val PAYLOAD_MASK = (1L shl 48) - 1
            const val INT = 3
            const val FLOAT = 8
            const val DOUBLE = 9
            const val TOKEN = 11
            const val ASSET_PATH = 12
            const val MATRIX4D = 15
            const val PATH_LIST_OP = 34
            const val TOKEN_VECTOR = 41
            val LIST_BITS = intArrayOf(2, 4, 8, 16, 32, 64)
            val DOUBLES = setOf(DOUBLE, 13, 14, MATRIX4D, 16, 19, 23, 27)

            // Components per element of the float-based types (quaternions are i, j, k, real).
            val WIDTH =
                mapOf(
                    FLOAT to 1,
                    DOUBLE to 1,
                    13 to 4,
                    14 to 9,
                    MATRIX4D to 16,
                    16 to 4,
                    17 to 4,
                    19 to 2,
                    20 to 2,
                    23 to 3,
                    24 to 3,
                    27 to 4,
                    28 to 4,
                )
        }
    }
}

/** Raw LZ4 block decompression, as used inside USD files. */
