package com.artflow.studio.data.local

import com.artflow.studio.core.render.CustomGrains
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

    fun encode(brushes: List<SavedBrush>): String {
        val tiles =
            brushes
                .flatMap { it.parameters.imageIds }
                .distinct()
                .mapNotNull { id -> CustomGrains.get(id)?.let { id to Base64.getEncoder().encodeToString(it.values) } }
                .toMap()
        return json.encodeToString(BrushFile.serializer(), BrushFile(FORMAT, BrushLibraryCodec.encode(brushes), tiles))
    }

    /** Brushes from a shared file and the images they use; throws for anything malformed. */
    fun decode(text: String): Pair<List<SavedBrush>, Map<String, CustomGrains.Tile>> {
        require(text.length <= MAX_BYTES) { "This brush file is too large" }
        val file = json.decodeFromString(BrushFile.serializer(), text)
        require(file.format == FORMAT) { "This is not an ArtFlow brush file" }
        val images =
            file.tiles.mapValues { (id, encoded) ->
                require(CustomGrains.isCustom(id)) { "Invalid image in the brush file" }
                CustomGrains.Tile(CustomGrains.TILE, Base64.getDecoder().decode(encoded))
            }
        return BrushLibraryCodec.decode(file.library) to images
    }

    /** A file name for [name] that is safe on every platform. */
    fun fileName(name: String): String =
        name
            .filter { it.isLetterOrDigit() || it in " -_" }
            .trim()
            .ifEmpty { "Brush" }
            .take(MAX_NAME) + ".$EXTENSION"

    private const val FORMAT = "artflow-brush-1"
    private const val MAX_NAME = 60
}
