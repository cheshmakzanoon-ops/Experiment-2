package com.artflow.studio.data.local

import com.artflow.studio.core.pixels.ImportMemoryBudget
import com.artflow.studio.core.render.CustomGrains
import com.artflow.studio.core.serialization.JsonReadBudget
import com.artflow.studio.core.serialization.JsonReadLimits
import com.artflow.studio.domain.model.brush.BrushLibraryCodec
import com.artflow.studio.domain.model.brush.SavedBrush
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Base64

/**
 * Shareable brush files (`.artbrush`): the saved-library JSON for one or more brushes plus every
 * imported grain or shape image they use, so a shared brush paints the same on another device.
 */
object BrushFiles {
    const val EXTENSION = "artbrush"
    const val MAX_BYTES = 4 * 1024 * 1024

    @Serializable
    private data class BrushFile(
        val format: String,
        val library: String,
        val tiles: Map<String, String> = emptyMap(),
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val jsonLimits =
        object : JsonReadLimits {
            override val message = "This brush file exceeds the supported data limits. Import fewer brushes."

            override fun objectEntries(property: String?): Int = if (property == "tiles") MAX_TILES else super.objectEntries(property)

            override fun stringCharacters(
                property: String?,
                owner: String?,
            ): Int =
                when {
                    owner == "tiles" -> ENCODED_TILE_CHARACTERS
                    property == "library" -> BrushLibraryCodec.MAX_CHARACTERS
                    else -> MAX_BYTES
                }
        }

    fun encode(brushes: List<SavedBrush>): String {
        val budget = JsonReadBudget()
        val owned = encodingSnapshot(brushes, budget)
        // Validate the finite dual-brush structure before traversing its image references.
        val library = BrushLibraryCodec.encode(owned)
        val tiles =
            owned
                .flatMap { it.parameters.imageIds }
                .distinct()
                .mapNotNull { id -> CustomGrains.get(id)?.let { id to it } }
                .toMap()
        BrushLibraryCodec.admit(library, budget)
        admitTiles(tiles.size, budget)
        val outputBytes =
            FILE_OVERHEAD_BYTES + utf8Bytes(library, escapeJson = true) +
                tiles.keys.sumOf { utf8Bytes(it, escapeJson = true) + ENCODED_TILE_CHARACTERS + TILE_ENTRY_OVERHEAD_BYTES }
        require(outputBytes <= MAX_BYTES) { "This brush file is too large. Share fewer brushes at a time." }
        // Base64 strings, the serializer's growing text buffer, and the final text may coexist.
        budget.reserve(outputBytes * ENCODING_WORKSPACE_COPIES)
        val encoded =
            tiles.mapValues { (_, tile) ->
                require(tile.size == CustomGrains.TILE && tile.values.size == TILE_BYTES) { "Unsupported brush texture dimensions" }
                Base64.getEncoder().encodeToString(tile.values)
            }
        return json.encodeToString(BrushFile.serializer(), BrushFile(FORMAT, library, encoded)).also {
            require(utf8Bytes(it) <= MAX_BYTES) { "This brush file is too large" }
        }
    }

    /** Library metadata and image references must come from the same bounded caller snapshot. */
    private fun encodingSnapshot(
        brushes: List<SavedBrush>,
        budget: JsonReadBudget,
    ): List<SavedBrush> {
        require(brushes.size in 0..BrushLibraryCodec.MAX_BRUSHES) { "Too many brushes to share" }
        budget.reserve(SNAPSHOT_LIST_BYTES)
        val owned = arrayListOf<SavedBrush>()
        for (brush in brushes) {
            require(owned.size < BrushLibraryCodec.MAX_BRUSHES) { "Too many brushes to share" }
            budget.reserve(SNAPSHOT_ENTRY_BYTES)
            owned.add(brush)
        }
        return owned
    }

    /** Brushes from a shared file and the images they use; throws for anything malformed. */
    fun decode(text: String): Pair<List<SavedBrush>, Map<String, CustomGrains.Tile>> = decode(text, ImportMemoryBudget.currentBytes())

    internal fun decode(
        text: String,
        memoryBudgetBytes: Long,
        decodeTile: (String) -> ByteArray = { Base64.getDecoder().decode(it) },
    ): Pair<List<SavedBrush>, Map<String, CustomGrains.Tile>> {
        require(text.length <= MAX_BYTES && utf8Bytes(text) <= MAX_BYTES) { "This brush file is too large" }
        val budget = JsonReadBudget(memoryBudgetBytes, "These brushes need too much memory. Import fewer brushes.")
        budget.check(text, jsonLimits)
        val file = json.decodeFromString(BrushFile.serializer(), text)
        require(file.format == FORMAT) { "This is not an ArtFlow brush file" }
        // The nested library shares the outer strings/map allowance rather than resetting it.
        val brushes = BrushLibraryCodec.decode(file.library, budget)
        val referenced = brushes.flatMap { it.parameters.imageIds }.distinct().filter { it in file.tiles }
        referenced.forEach { id ->
            require(CustomGrains.isCustom(id)) { "Invalid image in the brush file" }
            requireCanonicalTile(file.tiles.getValue(id))
        }
        admitTiles(referenced.size, budget)
        val images =
            referenced.associateWith { id ->
                CustomGrains.Tile(CustomGrains.TILE, decodeTile(file.tiles.getValue(id)))
            }
        return brushes to images
    }

    private fun admitTiles(
        count: Int,
        budget: JsonReadBudget,
    ) {
        val bytes = count.toLong() * TILE_BYTES
        val limit = minOf(16L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 32, budget.maximumBytes / 4)
        require(count <= MAX_TILES && bytes <= limit) { "These brush textures need too much memory. Import fewer brushes." }
        // Every returned plane plus one decoder's encoded byte input and possible result copy.
        budget.reserve(bytes + if (count == 0) 0 else ENCODED_TILE_CHARACTERS + TILE_BYTES.toLong())
    }

    private fun requireCanonicalTile(encoded: String) {
        require(encoded.length == ENCODED_TILE_CHARACTERS && encoded.endsWith("==")) { "Invalid brush texture encoding" }
        for (index in 0 until encoded.length - 2) {
            require(base64Value(encoded[index]) >= 0) { "Invalid brush texture encoding" }
        }
        require(base64Value(encoded[encoded.length - 3]) and 15 == 0) { "Invalid brush texture padding" }
    }

    private fun base64Value(character: Char): Int =
        when (character) {
            in 'A'..'Z' -> character - 'A'
            in 'a'..'z' -> character - 'a' + 26
            in '0'..'9' -> character - '0' + 52
            '+' -> 62
            '/' -> 63
            else -> -1
        }

    /** Counts before allocation; JSON control escapes are conservatively charged as six ASCII bytes. */
    private fun utf8Bytes(
        text: String,
        escapeJson: Boolean = false,
    ): Long {
        var bytes = if (escapeJson) 2L else 0L
        var index = 0
        while (index < text.length) {
            val character = text[index++]
            bytes +=
                when {
                    escapeJson && (character == '"' || character == '\\') -> 2
                    escapeJson && character < ' ' -> 6
                    character.code < 0x80 -> 1
                    character.code < 0x800 -> 2
                    character.isHighSurrogate() && index < text.length && text[index].isLowSurrogate() -> {
                        index++
                        4
                    }
                    character.isSurrogate() -> 1
                    else -> 3
                }
        }
        return bytes
    }

    /** A file name for [name] that is safe on every platform, with [extension] (ArtFlow's `.artbrush` by default). */
    fun fileName(
        name: String,
        extension: String = EXTENSION,
    ): String =
        name
            .filter { it.isLetterOrDigit() || it in " -_" }
            .trim()
            .ifEmpty { "Brush" }
            .take(MAX_NAME) + ".$extension"

    private const val FORMAT = "artflow-brush-1"
    private const val MAX_NAME = 60
    private const val MAX_TILES = BrushLibraryCodec.MAX_BRUSHES * 4
    private const val TILE_BYTES = CustomGrains.TILE * CustomGrains.TILE
    private const val ENCODED_TILE_CHARACTERS = ((TILE_BYTES + 2) / 3) * 4
    private const val FILE_OVERHEAD_BYTES = 128L
    private const val TILE_ENTRY_OVERHEAD_BYTES = 4L
    private const val ENCODING_WORKSPACE_COPIES = 8L
    private const val SNAPSHOT_LIST_BYTES = 128L
    private const val SNAPSHOT_ENTRY_BYTES = 32L
}
