package com.artflow.studio.data.local

import android.content.Context
import com.artflow.studio.domain.model.animation.AnimationFrame
import com.artflow.studio.domain.model.animation.AnimationSettings
import com.artflow.studio.domain.model.animation.TimelapseRecording
import com.artflow.studio.domain.model.layer.Layer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The on-disk representation of a project.
 *
 * Version history:
 * - **v1** stored a single layer stack with no pixel data (strokes only).
 * - **v2** adds per-layer pixel files ([Layer.rasterFile]), layer masks, adjustment/filter layer
 *   parameters, animation frames and the timelapse recording. v1 documents still load: their
 *   single layer list becomes frame 0.
 *
 * Kept in the data layer because it is purely a storage concern.
 */
@Serializable
data class CanvasDocument(
    val version: Int = CURRENT_VERSION,
    val width: Int,
    val height: Int,
    val dpi: Int,
    val backgroundColor: Int,
    val activeLayerId: Long,
    val nextLayerId: Long,
    /** Layer stack of the first frame (v1 compatibility and the non-animated case). */
    val layers: List<Layer>,
    val frames: List<AnimationFrame> = emptyList(),
    val activeFrameIndex: Int = 0,
    val animation: AnimationSettings = AnimationSettings(),
    val timelapse: TimelapseRecording = TimelapseRecording(),
    val savedAt: Long = System.currentTimeMillis(),
) {
    /**
     * Frames in a canonical form. Documents written before animation existed have no [frames], so
     * their [layers] become frame 0 — this is what makes old projects load without a migration.
     */
    fun resolvedFrames(): List<AnimationFrame> {
        if (frames.isNotEmpty()) return frames
        return listOf(
            AnimationFrame(
                id = 1L,
                name = "Frame 1",
                layers = layers,
                durationMs = animation.frameDurationMs,
            ),
        )
    }

    fun resolvedActiveFrameIndex(): Int = activeFrameIndex.coerceIn(0, (resolvedFrames().size - 1).coerceAtLeast(0))

    companion object {
        /** Bump when the JSON shape changes in a way that needs migration code. */
        const val CURRENT_VERSION = 2
    }
}

/**
 * Reads and writes everything that belongs to one project inside the app's private storage.
 *
 * ```
 * filesDir/projects/<id>/canvas.artflow        JSON document (layers, frames, settings)
 * filesDir/projects/<id>/canvas.png            flattened composite (fast preview / recovery)
 * filesDir/projects/<id>/thumbnail.png         gallery thumbnail
 * filesDir/projects/<id>/autosave.artflow      rolling autosave used for crash recovery
 * filesDir/projects/<id>/layers/<layer>/v<n>.png  latest saved pixel content
 * filesDir/projects/<id>/exports/<name>        exported PNG/JPEG/PDF/GIF/PSD
 * ```
 *
 * Pixel content on disk is a *cache of the last save*, not the undo history. Undo/redo keeps
 * copy-on-write `PixelBuffer`s in memory (`CanvasRepositoryImpl`), so a save simply rewrites each
 * layer's current raster. The version segment exists so a future format can keep several raster
 * revisions side by side; today the repository always writes version 1.
 */
@Singleton
class ProjectStorage
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        private val json =
            Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
                isLenient = false
            }

        // -----------------------------------------------------------------------------------------
        // Paths
        // -----------------------------------------------------------------------------------------

        fun projectDir(projectId: Long): File = File(context.filesDir, "projects/$projectId").apply { if (!exists()) mkdirs() }

        fun documentFile(projectId: Long): File = File(projectDir(projectId), DOCUMENT_NAME)

        fun flattenedFile(projectId: Long): File = File(projectDir(projectId), FLATTENED_NAME)

        fun thumbnailFile(projectId: Long): File = File(projectDir(projectId), THUMBNAIL_NAME)

        fun autosaveFile(projectId: Long): File = File(projectDir(projectId), AUTOSAVE_NAME)

        fun exportsDir(projectId: Long): File = File(projectDir(projectId), EXPORTS_DIR).apply { if (!exists()) mkdirs() }

        /** Resolves a project-relative path such as `layers/3/v2.png`. */
        fun resolve(
            projectId: Long,
            relativePath: String,
        ): File = File(projectDir(projectId), relativePath)

        // -----------------------------------------------------------------------------------------
        // Document
        // -----------------------------------------------------------------------------------------

        suspend fun saveDocument(
            projectId: Long,
            document: CanvasDocument,
        ): File =
            withContext(Dispatchers.IO) {
                val file = documentFile(projectId)
                writeAtomically(file) { out ->
                    out.write(json.encodeToString(CanvasDocument.serializer(), document).toByteArray())
                }
                Timber.d("Saved project $projectId (v${document.version}, ${document.resolvedFrames().size} frames)")
                file
            }

        /** Writes the rolling autosave copy. Never fails the caller: a save must not break the editor. */
        suspend fun saveAutosave(
            projectId: Long,
            document: CanvasDocument,
        ): File? =
            withContext(Dispatchers.IO) {
                runCatching {
                    val file = autosaveFile(projectId)
                    writeAtomically(file) { out ->
                        out.write(json.encodeToString(CanvasDocument.serializer(), document).toByteArray())
                    }
                    file
                }.onFailure { Timber.w(it, "Autosave failed for project $projectId") }.getOrNull()
            }

        suspend fun loadDocument(projectId: Long): CanvasDocument? =
            withContext(Dispatchers.IO) {
                val file = documentFile(projectId)
                if (!file.exists()) return@withContext null
                decodeDocument(file, projectId)
            }

        /** Loads the autosave copy, used by the crash-recovery prompt. */
        suspend fun loadAutosave(projectId: Long): CanvasDocument? =
            withContext(Dispatchers.IO) {
                val file = autosaveFile(projectId)
                if (!file.exists()) return@withContext null
                decodeDocument(file, projectId)
            }

        private fun decodeDocument(
            file: File,
            projectId: Long,
        ): CanvasDocument? =
            try {
                val document = json.decodeFromString(CanvasDocument.serializer(), file.readText())
                if (document.version > CanvasDocument.CURRENT_VERSION) {
                    Timber.w("Project $projectId was written by a newer app version (v${document.version})")
                }
                document
            } catch (e: Exception) {
                // A corrupt document must not take the app down; the flattened PNG is still usable.
                Timber.e(e, "Failed to read project $projectId document")
                null
            }

        /** True when the autosave is newer than the last explicit save. */
        suspend fun hasUnsavedRecovery(projectId: Long): Boolean =
            withContext(Dispatchers.IO) {
                val autosave = autosaveFile(projectId)
                val document = documentFile(projectId)
                autosave.exists() && (!document.exists() || autosave.lastModified() > document.lastModified())
            }

        // -----------------------------------------------------------------------------------------
        // Composite / thumbnail
        // -----------------------------------------------------------------------------------------

        suspend fun saveFlattened(
            projectId: Long,
            pngBytes: ByteArray,
        ): File =
            withContext(Dispatchers.IO) {
                val file = flattenedFile(projectId)
                writeAtomically(file) { out -> out.write(pngBytes) }
                file
            }

        suspend fun loadFlattenedBytes(projectId: Long): ByteArray? =
            withContext(Dispatchers.IO) {
                val file = flattenedFile(projectId)
                if (file.exists()) file.readBytes() else null
            }

        suspend fun saveThumbnail(
            projectId: Long,
            pngBytes: ByteArray,
        ): String =
            withContext(Dispatchers.IO) {
                val file = thumbnailFile(projectId)
                writeAtomically(file) { out -> out.write(pngBytes) }
                file.absolutePath
            }

        fun thumbnailPathIfExists(projectId: Long): String? = thumbnailFile(projectId).takeIf { it.exists() }?.absolutePath

        // -----------------------------------------------------------------------------------------
        // Layer pixel data
        // -----------------------------------------------------------------------------------------

        /**
         * Writes a new immutable version of a layer's pixels and returns its project-relative path.
         *
         * @param version raster revision, supplied by the repository. Every call currently writes
         *   revision 1, so a later save of the same layer replaces the previous file.
         */
        suspend fun writeRaster(
            projectId: Long,
            layerId: Long,
            version: Int,
            pngBytes: ByteArray,
        ): String =
            withContext(Dispatchers.IO) {
                val relative = "$LAYERS_DIR/$layerId/${RASTER_PREFIX}$version.png"
                val file = resolve(projectId, relative)
                file.parentFile?.mkdirs()
                writeAtomically(file) { out -> out.write(pngBytes) }
                relative
            }

        suspend fun readRaster(
            projectId: Long,
            relativePath: String?,
        ): ByteArray? =
            withContext(Dispatchers.IO) {
                if (relativePath == null) return@withContext null
                val file = resolve(projectId, relativePath)
                if (file.exists()) file.readBytes() else null
            }

        /** Layer mask and other auxiliary grayscale images share the raster storage. */
        suspend fun writeAuxiliaryImage(
            projectId: Long,
            layerId: Long,
            kind: String,
            version: Int,
            pngBytes: ByteArray,
        ): String =
            withContext(Dispatchers.IO) {
                val relative = "$LAYERS_DIR/$layerId/$kind$version.png"
                val file = resolve(projectId, relative)
                file.parentFile?.mkdirs()
                writeAtomically(file) { out -> out.write(pngBytes) }
                relative
            }

        fun rasterExists(
            projectId: Long,
            relativePath: String?,
        ): Boolean = relativePath != null && resolve(projectId, relativePath).exists()

        /**
         * Deletes every pixel file that is not referenced by [keep].
         *
         * Called by `CanvasRepositoryImpl.saveCanvas`. Because a save rewrites rasters in place, the
         * files this reclaims are the ones belonging to layers (and masks) that no longer exist in the
         * document — without it they would stay on disk until the whole project is deleted.
         */
        suspend fun pruneRasters(
            projectId: Long,
            keep: Set<String>,
        ): Int =
            withContext(Dispatchers.IO) {
                val layersDir = File(projectDir(projectId), LAYERS_DIR)
                if (!layersDir.exists()) return@withContext 0
                var deleted = 0
                layersDir.walkTopDown().filter { it.isFile && it.extension == "png" }.forEach { file ->
                    val relative = file.relativeTo(projectDir(projectId)).path.replace('\\', '/')
                    if (relative !in keep) {
                        if (file.delete()) deleted++
                    }
                }
                // Remove directories that are now empty.
                layersDir.walkBottomUp().filter { it.isDirectory && it.listFiles()?.isEmpty() == true }.forEach {
                    it.delete()
                }
                if (deleted > 0) Timber.d("Pruned $deleted stale raster versions for project $projectId")
                deleted
            }

        /** Total bytes used by a project, shown in the settings storage screen. */
        fun projectSizeBytes(projectId: Long): Long =
            File(context.filesDir, "projects/$projectId").walkTopDown().filter { it.isFile }.sumOf { it.length() }

        // -----------------------------------------------------------------------------------------
        // Exports
        // -----------------------------------------------------------------------------------------

        suspend fun saveExport(
            projectId: Long,
            fileName: String,
            bytes: ByteArray,
        ): File =
            withContext(Dispatchers.IO) {
                val file = File(exportsDir(projectId), fileName)
                writeAtomically(file) { out -> out.write(bytes) }
                file
            }

        fun listExports(projectId: Long): List<File> =
            exportsDir(projectId).listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()

        // -----------------------------------------------------------------------------------------
        // Deletion / housekeeping
        // -----------------------------------------------------------------------------------------

        fun deleteProjectFiles(projectId: Long) {
            val dir = File(context.filesDir, "projects/$projectId")
            if (dir.exists()) {
                val deleted = dir.deleteRecursively()
                Timber.d("Deleted project $projectId files: $deleted")
            }
        }

        /** Project ids that still have a folder on disk (used to find orphaned files). */
        fun projectIdsOnDisk(): Set<Long> =
            File(context.filesDir, "projects")
                .listFiles()
                ?.filter { it.isDirectory }
                ?.mapNotNull { it.name.toLongOrNull() }
                ?.toSet()
                ?: emptySet()

        /** Total size of all projects, for the settings storage readout. */
        fun totalStorageBytes(): Long = File(context.filesDir, "projects").walkTopDown().filter { it.isFile }.sumOf { it.length() }

        /** Deletes leftover export files older than [olderThanMs]. */
        fun pruneOldExports(
            projectId: Long,
            olderThanMs: Long,
        ): Int {
            val cutoff = System.currentTimeMillis() - olderThanMs
            var deleted = 0
            listExports(projectId).filter { it.lastModified() < cutoff }.forEach {
                if (it.delete()) deleted++
            }
            return deleted
        }

        /**
         * Write through a temp file and rename, so an interrupted save can never leave a half-written
         * document or image behind.
         */
        private inline fun writeAtomically(
            target: File,
            write: (FileOutputStream) -> Unit,
        ) {
            target.parentFile?.mkdirs()
            val temp = File(target.parentFile, "${target.name}.tmp")
            FileOutputStream(temp).use { out ->
                write(out)
                out.flush()
                out.fd.sync()
            }
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
        }

        companion object {
            const val DOCUMENT_NAME = "canvas.artflow"
            const val AUTOSAVE_NAME = "autosave.artflow"
            const val FLATTENED_NAME = "canvas.png"
            const val THUMBNAIL_NAME = "thumbnail.png"
            const val LAYERS_DIR = "layers"
            const val EXPORTS_DIR = "exports"
            const val RASTER_PREFIX = "v"
        }
    }
