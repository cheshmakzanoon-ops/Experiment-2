package com.artflow.studio.data.local

import android.content.Context
import com.artflow.studio.core.export.AbrReader
import com.artflow.studio.core.render.CustomGrains
import com.artflow.studio.data.renderer.BitmapPixelBridge
import com.artflow.studio.domain.model.brush.BrushLibraryCodec
import com.artflow.studio.domain.model.brush.BrushParams
import com.artflow.studio.domain.model.brush.SavedBrush
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Reads brush files of every supported kind into saved brushes, keeping their shape and grain images.
 * Brush Studio's Import button and files opened from other apps both come through here.
 */
object BrushImports {
    const val MAX_BYTES = 64 * 1024 * 1024

    /** Installs a complete valid batch; production library imports use [importInto] to also own the database commit. */
    fun read(
        context: Context,
        bytes: ByteArray,
    ): List<SavedBrush>? =
        runCatching {
            GrainStorage.ImportTransaction().use { grains ->
                val decoded = decode(bytes) ?: return@use null
                grains.install(GrainStorage.directory(context.filesDir), decoded.images)
                grains.commit()
                decoded.brushes
            }
        }.onFailure { failure ->
            if (failure is CancellationException || failure !is Exception) throw failure
        }.getOrNull()

    /**
     * Owns provisional assets across parsing, IO dispatch, library readiness and persistence.
     * [persist] calls its non-throwing commit callback inside the durable library publication.
     * Cancellation before that boundary removes only this import's newly installed assets.
     */
    suspend fun importInto(
        context: Context,
        bytes: ByteArray,
        persist: suspend (brushes: List<SavedBrush>, onCommitted: () -> Unit) -> Unit,
    ): Int? = importInto(GrainStorage.directory(context.filesDir), bytes, GrainStorage.ImportTransaction(), persist)

    internal suspend fun importInto(
        directory: File,
        bytes: ByteArray,
        grains: GrainStorage.ImportTransaction,
        persist: suspend (brushes: List<SavedBrush>, onCommitted: () -> Unit) -> Unit,
    ): Int? =
        grains.use {
            // The owner exists outside withContext: a cancelled result delivery still rolls back.
            val brushes =
                withContext(Dispatchers.IO) {
                    val decoded = decode(bytes) ?: return@withContext null
                    currentCoroutineContext().ensureActive()
                    grains.install(directory, decoded.images)
                    decoded.brushes
                } ?: return@use null
            currentCoroutineContext().ensureActive()
            var committed = false
            persist(brushes) {
                grains.commit()
                committed = true
            }
            check(committed) { "The brush library did not commit this import" }
            brushes.size
        }

    private class Decoded(
        val brushes: List<SavedBrush>,
        val images: Map<String, CustomGrains.Tile>,
    )

    /** Collect content identities without publishing any files or cache entries while parsing. */
    private fun decode(bytes: ByteArray): Decoded? {
        require(bytes.size <= MAX_BYTES) { "This brush file is too large" }
        val images = linkedMapOf<String, CustomGrains.Tile>()
        val store: (CustomGrains.Tile) -> String = { tile ->
            val id = CustomGrains.idFor(tile)
            val previous = images.putIfAbsent(id, tile)
            require(previous == null || previous.values.contentEquals(tile.values)) { "Conflicting brush texture identities" }
            id
        }
        val brushes =
            when {
                BrushArchives.isArchive(bytes) -> archive(bytes, store)
                AbrReader.isAbr(bytes) -> abr(bytes, store)
                bytes.size <= BrushFiles.MAX_BYTES -> artbrush(bytes.toString(Charsets.UTF_8), store)
                else -> return null
            }
        if (brushes.isEmpty()) return null
        // The complete batch must be persistable before its first asset is installed.
        BrushLibraryCodec.encode(brushes)
        return Decoded(brushes, images)
    }

    /** Procreate `.brush` and `.brushset` files: each brush comes in with its name, settings, shape and grain. */
    private fun archive(
        bytes: ByteArray,
        store: (CustomGrains.Tile) -> String,
    ): List<SavedBrush> =
        BrushArchives.readForImport(bytes, BitmapPixelBridge::decodeBrushImage).mapIndexed { index, brush ->
            val parameters = brush.parameters(store)
            SavedBrush("imported-$index", brush.name ?: "Imported brush ${index + 1}", parameters)
        }

    /** Photoshop `.abr` files: each sampled tip comes in as a brush shape. */
    private fun abr(
        bytes: ByteArray,
        store: (CustomGrains.Tile) -> String,
    ): List<SavedBrush> =
        BrushArchives.readAbr(bytes).mapIndexed { index, brush ->
            val shape = brush.shape?.let(store)
            SavedBrush("imported-abr-$index", brush.name ?: "Photoshop brush ${index + 1}", BrushArchives.parameters(shape, null))
        }

    /** Stores the file's images under their content identities and points the brushes at them. */
    private fun artbrush(
        text: String,
        store: (CustomGrains.Tile) -> String,
    ): List<SavedBrush> {
        val (brushes, images) = BrushFiles.decode(text)
        val renamed = images.mapValues { (_, tile) -> store(tile) }
        return brushes.map { brush ->
            brush.copy(parameters = remapImages(brush.parameters, renamed))
        }
    }

    private fun remapImages(
        params: BrushParams,
        renamed: Map<String, String>,
    ): BrushParams =
        params.copy(
            textureId = params.textureId?.let { renamed[it] ?: it },
            shapeId = params.shapeId?.let { renamed[it] ?: it },
            dual = params.dual?.let { it.copy(params = remapImages(it.params, renamed)) },
        )
}
