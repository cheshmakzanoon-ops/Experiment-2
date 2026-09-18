package com.artflow.studio.data.local

import android.content.Context
import com.artflow.studio.core.canvas.CanvasOperations
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
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
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
 * filesDir/exports/<id>/<name>                 exported files (the only FileProvider root)
 * ```
 *
 * Raster files are immutable, content-addressed generations. Explicit saves and recovery snapshots
 * may point to different generations of the same layer. A new manifest is published only after all
 * of its referenced rasters are durable. Cleanup retains the union of both committed manifests.
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

        private fun ownedPath(relative: String): File =
            File(context.filesDir, relative).apply { StorageFileTree.requireUnlinked(this, context.filesDir) }

        fun projectDir(projectId: Long): File {
            require(projectId > 0) { "Invalid project id" }
            return ownedPath("projects/$projectId").apply {
                check(isDirectory || mkdirs()) { "Cannot create project directory" }
            }
        }

        private fun projectFile(
            projectId: Long,
            name: String,
        ): File = File(projectDir(projectId), name).apply { StorageFileTree.requireUnlinked(this, context.filesDir) }

        fun documentFile(projectId: Long): File = projectFile(projectId, DOCUMENT_NAME)

        fun flattenedFile(projectId: Long): File = projectFile(projectId, FLATTENED_NAME)

        fun thumbnailFile(projectId: Long): File = projectFile(projectId, THUMBNAIL_NAME)

        fun autosaveFile(projectId: Long): File = projectFile(projectId, AUTOSAVE_NAME)

        fun exportsDir(projectId: Long): File {
            require(projectId > 0) { "Invalid project id" }
            return ownedPath("$EXPORTS_DIR/$projectId").apply {
                check(isDirectory || mkdirs()) { "Cannot create export directory" }
            }
        }

        /** Only exported copies, never editable project documents, can be shared externally. */
        fun exportedFile(path: String): File {
            val root = ownedPath(EXPORTS_DIR).canonicalFile
            val file = File(path).canonicalFile
            require(file.path.startsWith(root.path + File.separator) && file.isFile && file.length() > 0) {
                "This exported file is no longer available; export it again"
            }
            return file
        }

        /** Resolves a project-relative path such as `layers/3/v2.png`. */
        fun resolve(
            projectId: Long,
            relativePath: String,
        ): File {
            require(relativePath.isNotBlank() && !File(relativePath).isAbsolute) { "Expected a relative project path" }
            require('\\' !in relativePath && '\u0000' !in relativePath) { "Invalid project path" }
            val root = projectDir(projectId).absoluteFile.normalize()
            val file = File(root, relativePath).absoluteFile.normalize()
            require(file.path.startsWith(root.path + File.separator)) { "Path escapes the project directory" }
            StorageFileTree.requireUnlinked(file, context.filesDir)
            return file
        }

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

        /** Writes recovery metadata only after the caller has committed its immutable rasters. */
        suspend fun saveAutosave(
            projectId: Long,
            document: CanvasDocument,
        ): File =
            withContext(Dispatchers.IO) {
                val file = autosaveFile(projectId)
                writeAtomically(file) { out ->
                    out.write(json.encodeToString(CanvasDocument.serializer(), document).toByteArray())
                }
                file
            }

        suspend fun discardAutosave(projectId: Long) =
            withContext(Dispatchers.IO) {
                val file = autosaveFile(projectId)
                check(!file.exists() || file.delete()) { "Could not discard the recovery snapshot" }
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
        ): CanvasDocument {
            require(file.length() in 1..MAX_DOCUMENT_BYTES) { "Project $projectId metadata is empty or too large" }
            // Missing is different from corrupt. Propagate invalid data; callers must NEVER replace it
            // with a new blank canvas or silently discard a newer format's fields on the next save.
            val document = json.decodeFromString(CanvasDocument.serializer(), file.readText())
            require(document.version in 1..CanvasDocument.CURRENT_VERSION) { "Unsupported project version ${document.version}" }
            require(CanvasOperations.isSizeSafe(document.width, document.height)) { "Invalid project dimensions" }
            require(document.dpi in CanvasOperations.MIN_DPI..CanvasOperations.MAX_DPI) { "Invalid project DPI" }
            val frames = document.resolvedFrames()
            require(frames.size in 1..MAX_FRAMES) { "Project has too many animation frames" }
            require(frames.map { it.id }.distinct().size == frames.size) { "Duplicate frame identifiers" }
            val layers = frames.flatMap { it.layers }
            require(layers.size <= MAX_LAYERS) { "Project has too many layers" }
            require(layers.all { it.id > 0 && it.id < Long.MAX_VALUE }) { "Invalid layer identifier" }
            require(layers.map { it.id }.distinct().size == layers.size) { "Duplicate layer identifiers" }
            require(frames.all { it.layers.isNotEmpty() }) { "A frame must contain a layer" }
            require(frames.all { it.durationMs in AnimationFrame.MIN_DURATION_MS..AnimationFrame.MAX_DURATION_MS }) {
                "Invalid animation frame duration"
            }
            layers.forEach { layer ->
                require(layer.opacity.isFinite() && layer.opacity in 0f..1f) { "Invalid layer opacity" }
                listOfNotNull(layer.rasterFile, layer.maskFile).forEach { resolve(projectId, it) }
            }
            return document
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
         * The content digest makes changed pixels a new file, so autosaving cannot mutate a raster
         * referenced by the explicitly saved artwork. Legacy v1.png paths remain readable.
         */
        suspend fun writeRaster(
            projectId: Long,
            layerId: Long,
            version: Int,
            pngBytes: ByteArray,
        ): String =
            withContext(Dispatchers.IO) {
                val relative = "$LAYERS_DIR/$layerId/${RASTER_PREFIX}$version-${digest(pngBytes)}.png"
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
                require(file.isFile && file.length() in 1..MAX_RASTER_BYTES) { "Missing or invalid layer pixels: $relativePath" }
                file.readBytes()
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
                require(kind.matches(Regex("[a-zA-Z0-9_-]+"))) { "Invalid auxiliary image kind" }
                val relative = "$LAYERS_DIR/$layerId/$kind$version-${digest(pngBytes)}.png"
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
         * Called after an atomic save. Rasters are immutable generations; both the saved document
         * and recovery manifest retain their own generations. Never traverse directory links.
         */
        suspend fun pruneRasters(
            projectId: Long,
            keep: Set<String>,
        ): Int =
            withContext(Dispatchers.IO) {
                val layersDir = File(projectDir(projectId), LAYERS_DIR)
                if (!layersDir.exists()) return@withContext 0
                // Fail closed if either manifest is unreadable; never guess which artwork can be deleted.
                val retained = keep.toMutableSet()
                listOfNotNull(loadDocument(projectId), loadAutosave(projectId)).forEach { document ->
                    document.resolvedFrames().flatMap { it.layers }.forEach { layer ->
                        retained.addAll(listOfNotNull(layer.rasterFile, layer.maskFile))
                    }
                }
                val deleted =
                    StorageFileTree.prune(layersDir, context.filesDir) { file ->
                        val relative = file.relativeTo(projectDir(projectId)).path.replace('\\', '/')
                        file.extension == "png" && relative !in retained
                    }
                if (deleted > 0) Timber.d("Pruned $deleted stale raster versions for project $projectId")
                deleted
            }

        /** IDs with orphaned files remain reserved after an interrupted database transaction. */
        fun maximumStoredProjectId(): Long =
            ownedPath("projects")
                .listFiles()
                ?.mapNotNull { it.name.toLongOrNull() }
                ?.maxOrNull() ?: 0L

        /** Does not create a directory, unlike [projectDir]. */
        fun projectDirectoryExists(projectId: Long): Boolean {
            require(projectId > 0) { "Invalid project id" }
            val parent = ownedPath("projects")
            // A dangling link is still occupied; do not recycle an ID whose path already exists.
            return Files.exists(File(parent, "$projectId").toPath(), LinkOption.NOFOLLOW_LINKS)
        }

        /**
         * Copies immutable saved/recovery generations into a new project, never sharing files with
         * the original. Call inside the gallery transaction so an incomplete copy is not visible.
         * Exports and unreferenced temporary generations are intentionally not duplicated.
         */
        suspend fun copyProject(
            sourceId: Long,
            destinationId: Long,
        ): CanvasDocument? =
            withContext(Dispatchers.IO) {
                require(sourceId > 0 && destinationId > 0 && sourceId != destinationId) { "Invalid project copy" }
                val document = loadDocument(sourceId)
                val recovery = loadAutosave(sourceId)
                val newerRecovery = hasUnsavedRecovery(sourceId)
                val destination = ownedPath("projects/$destinationId")
                check(!Files.exists(destination.toPath(), LinkOption.NOFOLLOW_LINKS)) { "The destination already contains project data" }
                check(destination.mkdirs()) { "Cannot create the copied project directory" }
                var completed = false
                try {
                    val paths =
                        listOfNotNull(document, recovery)
                            .flatMap { it.resolvedFrames() }
                            .flatMap { it.layers }
                            .flatMap { listOfNotNull(it.rasterFile, it.maskFile) }
                            .distinct()
                    paths.forEach { relative ->
                        val bytes = requireNotNull(readRaster(sourceId, relative))
                        writeAtomically(resolve(destinationId, relative)) { it.write(bytes) }
                    }
                    listOf(FLATTENED_NAME, THUMBNAIL_NAME).forEach { name ->
                        val source = resolve(sourceId, name)
                        if (source.isFile) {
                            writeAtomically(resolve(destinationId, name)) { out -> source.inputStream().use { it.copyTo(out) } }
                        }
                    }
                    // Preserve recovery ordering: the newer autosave is written AFTER the saved
                    // manifest. savedAt is retained, so neither snapshot claims to be a new edit.
                    document?.let { saveDocument(destinationId, it) }
                    recovery?.let {
                        saveAutosave(destinationId, it)
                        val savedTime = documentFile(destinationId).lastModified()
                        val modified = if (newerRecovery) savedTime + 1000L else (savedTime - 1000L).coerceAtLeast(0L)
                        check(autosaveFile(destinationId).setLastModified(modified)) { "Cannot preserve recovery ordering" }
                    }
                    completed = true
                    document
                } finally {
                    if (!completed) {
                        try {
                            StorageFileTree.delete(destination, context.filesDir)
                        } catch (cleanupFailure: IOException) {
                            // Preserve the original copy failure; an orphan still reserves its ID.
                            Timber.w(cleanupFailure, "Could not remove incomplete project copy")
                        }
                    }
                }
            }

        /** Total bytes used by a project, shown in the settings storage screen. */
        fun projectSizeBytes(projectId: Long): Long =
            listOf("projects/$projectId", "$EXPORTS_DIR/$projectId").sumOf { path ->
                StorageFileTree.sizeBytes(File(context.filesDir, path), context.filesDir)
            }

        // -----------------------------------------------------------------------------------------
        // Exports
        // -----------------------------------------------------------------------------------------

        suspend fun saveExport(
            projectId: Long,
            fileName: String,
            bytes: ByteArray,
        ): File =
            withContext(Dispatchers.IO) {
                require(fileName.isNotBlank() && fileName.length <= 180 && fileName !in setOf(".", "..")) { "Invalid export filename" }
                require(fileName.none { it == '/' || it == '\\' || it.code < 32 }) { "Export filename must not contain a path" }
                require(bytes.isNotEmpty()) { "The export is empty" }
                val file = File(exportsDir(projectId), fileName)
                writeAtomically(file) { out -> out.write(bytes) }
                file
            }

        fun listExports(projectId: Long): List<File> {
            require(projectId > 0) { "Invalid project id" }
            return listOf(ownedPath("$EXPORTS_DIR/$projectId"), ownedPath("projects/$projectId/$EXPORTS_DIR"))
                .flatMap { it.listFiles()?.filter { file -> Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS) } ?: emptyList() }
                .sortedByDescending { it.lastModified() }
        }

        // -----------------------------------------------------------------------------------------
        // Deletion / housekeeping
        // -----------------------------------------------------------------------------------------

        fun deleteProjectFiles(projectId: Long) {
            require(projectId > 0) { "Invalid project id" }
            listOf(File(context.filesDir, "projects/$projectId"), File(context.filesDir, "$EXPORTS_DIR/$projectId")).forEach { dir ->
                StorageFileTree.delete(dir, context.filesDir)
            }
        }

        /** Project ids that still have a folder on disk (used to find orphaned files). */
        fun projectIdsOnDisk(): Set<Long> =
            ownedPath("projects")
                .listFiles()
                ?.filter { it.isDirectory }
                ?.mapNotNull { it.name.toLongOrNull() }
                ?.toSet()
                ?: emptySet()

        /** Total size of all projects, for the settings storage readout. */
        fun totalStorageBytes(): Long =
            listOf("projects", EXPORTS_DIR).sumOf { folder ->
                StorageFileTree.sizeBytes(File(context.filesDir, folder), context.filesDir)
            }

        /** Deletes leftover export files older than [olderThanMs]. */
        fun pruneOldExports(
            projectId: Long,
            olderThanMs: Long,
        ): Int {
            require(olderThanMs >= 0) { "Invalid export retention age" }
            val cutoff = System.currentTimeMillis() - olderThanMs
            var deleted = 0
            listExports(projectId).filter { it.lastModified() < cutoff }.forEach {
                StorageFileTree.requireUnlinked(it, context.filesDir)
                if (Files.deleteIfExists(it.toPath())) deleted++
            }
            return deleted
        }

        /**
         * Write through a temp file and rename, so an interrupted save can never leave a half-written
         * document or image behind.
         */
        internal fun writeAtomically(
            target: File,
            write: (FileOutputStream) -> Unit,
        ) {
            StorageFileTree.requireUnlinked(target, context.filesDir)
            val parent = requireNotNull(target.parentFile)
            check(parent.isDirectory || parent.mkdirs()) { "Cannot create storage directory" }
            val temp = File.createTempFile(".${target.name}-", ".tmp", parent)
            try {
                FileOutputStream(temp).use { out ->
                    write(out)
                    out.flush()
                    out.fd.sync()
                }
                // Never fall back to an overwrite copy: a failed atomic replacement must leave the
                // previous artwork intact. Both files are on the same app-private filesystem.
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                val descriptor =
                    android.system.Os.open(
                        parent.path,
                        android.system.OsConstants.O_RDONLY,
                        0,
                    )
                try {
                    android.system.Os.fsync(descriptor)
                } finally {
                    android.system.Os.close(descriptor)
                }
            } finally {
                if (temp.exists()) temp.delete()
            }
        }

        private fun digest(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }

        companion object {
            private const val MAX_DOCUMENT_BYTES = 32L * 1024 * 1024
            private const val MAX_RASTER_BYTES = 192L * 1024 * 1024
            const val MAX_FRAMES = 240
            const val MAX_LAYERS = 1024
            const val DOCUMENT_NAME = "canvas.artflow"
            const val AUTOSAVE_NAME = "autosave.artflow"
            const val FLATTENED_NAME = "canvas.png"
            const val THUMBNAIL_NAME = "thumbnail.png"
            const val LAYERS_DIR = "layers"
            const val EXPORTS_DIR = "exports"
            const val RASTER_PREFIX = "v"
        }
    }
