package com.artflow.studio.data.local

import android.content.Context
import com.artflow.studio.core.export.AbrReader
import com.artflow.studio.data.renderer.BitmapPixelBridge
import com.artflow.studio.domain.model.brush.SavedBrush

/**
 * Reads brush files of every supported kind into saved brushes, keeping their shape and grain images.
 * Brush Studio's Import button and files opened from other apps both come through here.
 */
object BrushImports {
    const val MAX_BYTES = 64 * 1024 * 1024

    /** The brushes in [bytes]: a Procreate archive, a Photoshop `.abr` or an ArtFlow `.artbrush`; null when it is none of these. */
    fun read(
        context: Context,
        bytes: ByteArray,
    ): List<SavedBrush>? =
        runCatching {
            when {
                BrushArchives.isArchive(bytes) -> archive(context, bytes)
                AbrReader.isAbr(bytes) -> abr(context, bytes)
                bytes.size <= BrushFiles.MAX_BYTES -> artbrush(context, bytes.toString(Charsets.UTF_8))
                else -> null
            }
        }.getOrNull()

    /** Procreate `.brush` and `.brushset` files: each brush comes in with its name, settings, shape and grain. */
    private fun archive(
        context: Context,
        bytes: ByteArray,
    ): List<SavedBrush> {
        val directory = GrainStorage.directory(context.filesDir)
        return BrushArchives.read(bytes, BitmapPixelBridge::fromEncodedBytes).mapIndexed { index, brush ->
            val shape = brush.shape?.let { GrainStorage.save(directory, it) }
            val grain = brush.grain?.let { GrainStorage.save(directory, it) }
            val parameters = BrushArchives.parameters(shape, grain, brush.settings)
            SavedBrush("imported-$index", brush.name ?: "Imported brush ${index + 1}", parameters)
        }
    }

    /** Photoshop `.abr` files: each sampled tip comes in as a brush shape. */
    private fun abr(
        context: Context,
        bytes: ByteArray,
    ): List<SavedBrush> {
        val directory = GrainStorage.directory(context.filesDir)
        return BrushArchives.readAbr(bytes).mapIndexed { index, brush ->
            val shape = brush.shape?.let { GrainStorage.save(directory, it) }
            SavedBrush("imported-abr-$index", brush.name ?: "Photoshop brush ${index + 1}", BrushArchives.parameters(shape, null))
        }
    }

    /** Stores the file's images under their content identities and points the brushes at them. */
    private fun artbrush(
        context: Context,
        text: String,
    ): List<SavedBrush> {
        val (brushes, images) = BrushFiles.decode(text)
        val directory = GrainStorage.directory(context.filesDir)
        val renamed = images.mapValues { (_, tile) -> GrainStorage.save(directory, tile) }
        return brushes.map { brush ->
            val params = brush.parameters
            brush.copy(
                parameters =
                    params.copy(
                        textureId = params.textureId?.let { renamed[it] ?: it },
                        shapeId = params.shapeId?.let { renamed[it] ?: it },
                    ),
            )
        }
    }
}
