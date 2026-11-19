package com.artflow.studio.core.three

import com.artflow.studio.core.pixels.ImportMemoryBudget
import java.util.Base64

/** Conservative allocation admission for one package/parser operation, before expanding its inputs. */
internal class ModelReadBudget(
    private val maximumBytes: Long = ImportMemoryBudget.currentBytes(),
) {
    private var reserved = 0L

    init {
        require(maximumBytes > 0) { "Invalid model memory budget" }
    }

    fun reserve(bytes: Long) {
        require(bytes >= 0 && bytes <= maximumBytes - reserved) {
            "This model needs more memory than is available. Try a smaller model."
        }
        reserved += bytes
    }

    fun text(bytes: ByteArray): String {
        reserve(bytes.size.toLong() * Char.SIZE_BYTES)
        return bytes.decodeToString()
    }

    /** Bounds the JSON tree and nesting before the serialization parser creates any nodes. */
    fun json(text: String) {
        reserve(text.length.toLong() * Char.SIZE_BYTES)
        var quoted = false
        var escaped = false
        var scalar = false
        var depth = 0
        for (character in text) {
            if (quoted) {
                when {
                    escaped -> escaped = false
                    character == '\\' -> escaped = true
                    character == '"' -> quoted = false
                }
                continue
            }
            when {
                character == '"' -> {
                    reserve(JSON_NODE_BYTES)
                    quoted = true
                    scalar = false
                }
                character == '{' || character == '[' -> {
                    require(++depth <= MAX_JSON_DEPTH) { "The model's JSON structure is too deep" }
                    reserve(JSON_NODE_BYTES)
                    scalar = false
                }
                character == '}' || character == ']' -> {
                    depth--
                    scalar = false
                }
                character == ',' || character == ':' || character.isWhitespace() -> scalar = false
                !scalar -> {
                    reserve(JSON_NODE_BYTES)
                    scalar = true
                }
            }
        }
    }

    fun embedded(uri: String): ByteArray {
        val start = uri.indexOf(',')
        require(start >= 0 && uri.substring(0, start).endsWith(";base64")) { "The model has an unsupported embedded data URI" }
        // The substring, decoder's byte input, and decoded result can coexist.
        reserve((uri.length - start - 1).toLong() * EMBEDDED_COPY_BYTES)
        return Base64.getDecoder().decode(uri.substring(start + 1))
    }

    private companion object {
        const val JSON_NODE_BYTES = 128L
        const val MAX_JSON_DEPTH = 64
        const val EMBEDDED_COPY_BYTES = 4L
    }
}

/** Counts without retaining token lists, vertex boxes, face arrays, or fan-expanded triangles. */
internal object ObjGeometryBudget {
    fun check(
        text: String,
        budget: ModelReadBudget,
    ) {
        budget.reserve(text.length.toLong() * TEXT_WORKSPACE_BYTES)
        var values = 0L
        var faceCorners = 0L
        var triangles = 0L
        var largestLine = 0L
        for (raw in text.lineSequence()) {
            val words =
                raw
                    .substringBefore('#')
                    .trim()
                    .splitToSequence(WHITESPACE)
                    .iterator()
            if (!words.hasNext()) continue
            val kind = words.next()
            var count = 0L
            while (words.hasNext()) {
                words.next()
                count++
            }
            largestLine = maxOf(largestLine, count)
            when (kind) {
                "v", "vn" -> {
                    require(count >= 3) { "A model vertex needs three components" }
                    values += 3
                }
                "vt" -> {
                    require(count >= 2) { "A model texture coordinate needs two components" }
                    values += 2
                }
                "f" -> {
                    require(count >= 3) { "A face needs at least three corners" }
                    triangles += count - 2
                    require(triangles <= ObjParser.MAX_TRIANGLES) { "This model has too many triangles" }
                    faceCorners += count
                }
            }
        }
        // Boxed source values/list growth, source corner records, output planes/reference lists,
        // and the largest transient split/corner-token workspace are all live before build returns.
        budget.reserve(
            values * BOXED_VALUE_BYTES + faceCorners * SOURCE_CORNER_BYTES +
                triangles * 3 * OUTPUT_CORNER_BYTES + largestLine * TOKEN_WORKSPACE_BYTES,
        )
    }

    private const val TEXT_WORKSPACE_BYTES = 4L
    private const val BOXED_VALUE_BYTES = 32L
    private const val SOURCE_CORNER_BYTES = 64L
    private const val OUTPUT_CORNER_BYTES = 64L
    private const val TOKEN_WORKSPACE_BYTES = 160L
    private val WHITESPACE = Regex("\\s+")
}
