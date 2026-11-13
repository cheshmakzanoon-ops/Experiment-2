package com.artflow.studio.data.local

import android.content.Context
import com.artflow.studio.core.render.CustomGrains
import com.artflow.studio.data.export.ArtflowPackage
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Singleton

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
    constructor(
        @ApplicationContext private val context: Context,
        private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ) {
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
            withContext(ioDispatcher) {
                val file = documentFile(projectId)
                writeAtomically(file) { out -> out.write(CanvasDocumentCodec.encode(document)) }
                Timber.d("Saved project $projectId (v${document.version}, ${document.resolvedFrames().size} frames)")
                file
            }

        /** Writes recovery metadata only after the caller has committed its immutable rasters. */
        suspend fun saveAutosave(
            projectId: Long,
            document: CanvasDocument,
        ): File =
            withContext(ioDispatcher) {
                val file = autosaveFile(projectId)
                writeAtomically(file) { out -> out.write(CanvasDocumentCodec.encode(document)) }
                val doc = documentFile(projectId)
                if (doc.exists() && file.lastModified() <= doc.lastModified()) {
                    file.setLastModified(doc.lastModified() + 1000L)
                }
                file
            }

        suspend fun discardAutosave(projectId: Long) =
            withContext(ioDispatcher) {
                val file = autosaveFile(projectId)
                check(!file.exists() || file.delete()) { "Could not discard the recovery snapshot" }
            }

        suspend fun loadDocument(projectId: Long): CanvasDocument? =
            withContext(ioDispatcher) {
                val file = documentFile(projectId)
                if (!file.exists()) return@withContext null
                decodeDocument(file, projectId)
            }

        /** Loads the autosave copy, used by the crash-recovery prompt. */
        suspend fun loadAutosave(projectId: Long): CanvasDocument? =
            withContext(ioDispatcher) {
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
            val document = CanvasDocumentCodec.decode(file.readText())
            CanvasDocumentCodec.requireValidHeader(document)
            CanvasDocumentCodec.requireValidStructure(document)
            document.resolvedFrames().flatMap { it.layers }.forEach { layer ->
                listOfNotNull(layer.rasterFile, layer.maskFile).forEach { resolve(projectId, it) }
            }
            return document
        }

        /** True when the autosave is newer than the last explicit save. */
        suspend fun hasUnsavedRecovery(projectId: Long): Boolean =
            withContext(ioDispatcher) {
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
            withContext(ioDispatcher) {
                val file = flattenedFile(projectId)
                writeAtomically(file) { out -> out.write(pngBytes) }
                file
            }

        suspend fun loadFlattenedBytes(projectId: Long): ByteArray? =
            withContext(ioDispatcher) {
                val file = flattenedFile(projectId)
                if (file.exists()) file.readBytes() else null
            }

        suspend fun saveThumbnail(
            projectId: Long,
            pngBytes: ByteArray,
        ): String =
            withContext(ioDispatcher) {
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
            withContext(ioDispatcher) {
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
            withContext(ioDispatcher) {
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
            withContext(ioDispatcher) {
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

        private val pinnedRasters = ConcurrentHashMap<Long, MutableSet<String>>()

        fun pinRasters(
            projectId: Long,
            paths: Collection<String>,
        ) {
            val projectPins = pinnedRasters.computeIfAbsent(projectId) { ConcurrentHashMap.newKeySet() }
            projectPins.addAll(paths.map(::normalizeRelativePath))
        }

        fun unpinRasters(
            projectId: Long,
            paths: Collection<String>,
        ) {
            pinnedRasters[projectId]?.let { pins ->
                pins.removeAll(paths.map(::normalizeRelativePath).toSet())
                if (pins.isEmpty()) {
                    pinnedRasters.remove(projectId, pins)
                }
            }
        }

        fun normalizeRelativePath(path: String): String =
            path
                .replace('\\', '/')
                .trim()
                .trimStart('/')
                .split('/')
                .filter { it.isNotEmpty() && it != "." }
                .joinToString("/")

        /**
         * Deletes every pixel file that is not referenced by [keep] or actively pinned.
         *
         * Called after an atomic save. Rasters are immutable generations; both the saved document
         * and recovery manifest retain their own generations. Never traverse directory links.
         */
        suspend fun pruneRasters(
            projectId: Long,
            keep: Set<String>,
        ): Int =
            withContext(ioDispatcher) {
                val layersDir = File(projectDir(projectId), LAYERS_DIR)
                if (!layersDir.exists()) return@withContext 0
                // Fail closed if either manifest is unreadable; never guess which artwork can be deleted.
                val retained = keep.map(::normalizeRelativePath).toMutableSet()
                pinnedRasters[projectId]?.let { retained.addAll(it) }
                listOfNotNull(loadDocument(projectId), loadAutosave(projectId)).forEach { document ->
                    document.resolvedFrames().flatMap { it.layers }.forEach { layer ->
                        listOfNotNull(layer.rasterFile, layer.maskFile).forEach {
                            retained.add(normalizeRelativePath(it))
                        }
                    }
                }
                val deleted =
                    StorageFileTree.prune(layersDir, context.filesDir) { file ->
                        val relative = normalizeRelativePath(file.relativeTo(projectDir(projectId)).path)
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

        /** The 3D model (OBJ text) this artwork paints, if it has one. */
        suspend fun loadModel(projectId: Long): String? =
            withContext(ioDispatcher) {
                val file = resolve(projectId, MODEL_NAME)
                if (!file.isFile) return@withContext null
                require(file.length() in 1..MAX_MODEL_BYTES) { "The 3D model is empty or too large" }
                file.readText()
            }

        suspend fun saveModel(
            projectId: Long,
            objText: String,
        ) = withContext(ioDispatcher) {
            val bytes = objText.toByteArray()
            require(bytes.size in 1..MAX_MODEL_BYTES) { "The 3D model is empty or too large" }
            writeAtomically(resolve(projectId, MODEL_NAME)) { it.write(bytes) }
        }

        /** Result of importing an `.artflow` package along with any referenced assets missing on this device. */
        data class ImportedPackageOutcome(
            val document: CanvasDocument,
            val missingFonts: List<String>,
            val missingGrains: List<String>,
        )

        /** Collects portable font references (no third-party binaries) and custom grain IDs used by [document]. */
        fun collectDependencies(document: CanvasDocument): ArtflowPackage.Dependencies {
            val layers = document.resolvedFrames().flatMap { it.layers }
            val fonts =
                layers
                    .mapNotNull { it.textContent?.style?.fontFamily }
                    .filter { FontLibrary.isImported(it) }
                    .map { family ->
                        ArtflowPackage.PackagedFont(
                            id = FontLibrary.portableId(family) ?: family,
                            label = FontLibrary.label(family),
                        )
                    }.distinctBy { it.id }
            val grainIds =
                layers
                    .flatMap { it.strokes }
                    .flatMap { it.brushParams.imageIds }
                    .filter { CustomGrains.isCustom(it) }
                    .distinct()
                    .sorted()
            return ArtflowPackage.Dependencies(fonts = fonts, grainIds = grainIds)
        }

        /** A saved project's files for an `.artflow` package: manifest, layer pixels, previews, and grain/dependency assets. */
        suspend fun packageFiles(projectId: Long): Map<String, ByteArray> =
            withContext(ioDispatcher) {
                val document = requireNotNull(loadDocument(projectId)) { "Save the artwork before sharing it" }
                val files = linkedMapOf(DOCUMENT_NAME to documentFile(projectId).readBytes())
                referencedFiles(document).forEach { relative -> files[relative] = requireNotNull(readRaster(projectId, relative)) }
                listOf(FLATTENED_NAME, THUMBNAIL_NAME, MODEL_NAME).forEach { name ->
                    val file = resolve(projectId, name)
                    if (file.isFile) files[name] = file.readBytes()
                }
                val dependencies = collectDependencies(document)
                val grainDir = GrainStorage.directory(context.filesDir)
                dependencies.grainIds.forEach { id ->
                    val disk = GrainStorage.fileFor(grainDir, id)
                    val grainBytes =
                        when {
                            disk != null &&
                                Files.isRegularFile(disk.toPath(), LinkOption.NOFOLLOW_LINKS) &&
                                disk.length() == (CustomGrains.TILE * CustomGrains.TILE).toLong() -> disk.readBytes()
                            else -> CustomGrains.get(id)?.values
                        }
                    if (grainBytes != null) {
                        files[ArtflowPackage.grainEntryPath(id)] = grainBytes
                    }
                }
                if (dependencies.fonts.isNotEmpty() || dependencies.grainIds.isNotEmpty()) {
                    files[ArtflowPackage.DEPENDENCIES] = ArtflowPackage.encodeDependencies(dependencies)
                }
                files
            }

        /** Streaming entry sources for building an `.artflow` package without copying rasters into heap. */
        suspend fun packageSources(projectId: Long): List<ArtflowPackage.EntrySource> =
            withContext(ioDispatcher) {
                val document = requireNotNull(loadDocument(projectId)) { "Save the artwork before sharing it" }
                val sources = mutableListOf<ArtflowPackage.EntrySource>()
                val docFile = documentFile(projectId)
                sources += ArtflowPackage.EntrySource(DOCUMENT_NAME) { FileInputStream(docFile) }
                referencedFiles(document).forEach { relative ->
                    val file = resolve(projectId, relative)
                    require(file.isFile && file.length() in 1..MAX_RASTER_BYTES) {
                        "Missing or invalid layer pixels: $relative"
                    }
                    sources += ArtflowPackage.EntrySource(relative) { FileInputStream(file) }
                }
                listOf(FLATTENED_NAME, THUMBNAIL_NAME, MODEL_NAME).forEach { name ->
                    val file = resolve(projectId, name)
                    if (file.isFile) {
                        sources += ArtflowPackage.EntrySource(name) { FileInputStream(file) }
                    }
                }
                val dependencies = collectDependencies(document)
                val grainDir = GrainStorage.directory(context.filesDir)
                dependencies.grainIds.forEach { id ->
                    val disk = GrainStorage.fileFor(grainDir, id)
                    val path = ArtflowPackage.grainEntryPath(id)
                    when {
                        disk != null &&
                            Files.isRegularFile(disk.toPath(), LinkOption.NOFOLLOW_LINKS) &&
                            disk.length() == (CustomGrains.TILE * CustomGrains.TILE).toLong() ->
                            sources += ArtflowPackage.EntrySource(path) { FileInputStream(disk) }
                        CustomGrains.get(id) != null -> {
                            val bytes = CustomGrains.get(id)!!.values
                            sources += ArtflowPackage.EntrySource(path) { ByteArrayInputStream(bytes) }
                        }
                    }
                }
                if (dependencies.fonts.isNotEmpty() || dependencies.grainIds.isNotEmpty()) {
                    val encoded = ArtflowPackage.encodeDependencies(dependencies)
                    sources += ArtflowPackage.EntrySource(ArtflowPackage.DEPENDENCIES) { ByteArrayInputStream(encoded) }
                }
                sources
            }

        /** The manifest of an `.artflow` package, checked before any project is created for it. */
        fun decodePackagedDocument(bytes: ByteArray): CanvasDocument {
            require(bytes.size in 1..MAX_DOCUMENT_BYTES) { "The artwork's document is empty or too large" }
            val document = CanvasDocumentCodec.decode(bytes.decodeToString())
            CanvasDocumentCodec.requireValidHeader(document)
            return document
        }

        /**
         * Writes an imported package into a new, empty project directory: pixels and previews first,
         * the manifest last, then checks the manifest and that every pixel file it names arrived.
         * Bundled custom grains are restored to `filesDir/grains`. A failed import leaves nothing behind.
         */
        suspend fun importProject(
            projectId: Long,
            files: Map<String, ByteArray>,
        ): CanvasDocument =
            withContext(ioDispatcher) {
                val manifest = requireNotNull(files[DOCUMENT_NAME]) { "The artwork has no document" }
                val destination = ownedPath("projects/$projectId")
                check(!Files.exists(destination.toPath(), LinkOption.NOFOLLOW_LINKS)) { "The destination already contains project data" }
                check(destination.mkdirs()) { "Cannot create the imported project directory" }
                var completed = false
                try {
                    val grainEntries = linkedMapOf<String, ByteArray>()
                    var dependenciesBytes: ByteArray? = null
                    files.forEach { (relative, bytes) ->
                        when {
                            relative == DOCUMENT_NAME -> Unit
                            relative == ArtflowPackage.DEPENDENCIES -> dependenciesBytes = bytes
                            relative.startsWith("${ArtflowPackage.GRAINS_DIR}/") -> grainEntries[relative] = bytes
                            else -> {
                                val file = resolve(projectId, relative)
                                file.parentFile?.mkdirs()
                                writeAtomically(file) { it.write(bytes) }
                            }
                        }
                    }
                    writeAtomically(documentFile(projectId)) { it.write(manifest) }
                    val document = decodeDocument(documentFile(projectId), projectId)
                    referencedFiles(document).forEach { requireNotNull(readRaster(projectId, it)) }
                    restoreGrainsAndAuditDependencies(dependenciesBytes, grainEntries, document)
                    completed = true
                    document
                } finally {
                    if (!completed) {
                        try {
                            StorageFileTree.delete(destination, context.filesDir)
                        } catch (cleanupFailure: IOException) {
                            Timber.w(cleanupFailure, "Could not remove an incomplete import")
                        }
                    }
                }
            }

        /**
         * Streaming import from a verified [ArtflowPackage.StreamContents]: copies layers/previews via
         * bounded stream without buffering all rasters in heap, writes `canvas.artflow` last, and
         * restores bundled custom grains into `filesDir/grains`.
         */
        suspend fun importProjectStream(
            projectId: Long,
            contents: ArtflowPackage.StreamContents,
        ): ImportedPackageOutcome =
            withContext(ioDispatcher) {
                val manifest = contents.readBytes(DOCUMENT_NAME, MAX_DOCUMENT_BYTES)
                val destination = ownedPath("projects/$projectId")
                check(!Files.exists(destination.toPath(), LinkOption.NOFOLLOW_LINKS)) { "The destination already contains project data" }
                check(destination.mkdirs()) { "Cannot create the imported project directory" }
                var completed = false
                try {
                    val grainEntries = linkedMapOf<String, ByteArray>()
                    var dependenciesBytes: ByteArray? = null
                    val grainTileBytes = (CustomGrains.TILE * CustomGrains.TILE).toLong()
                    contents.entries.forEach { relative ->
                        when {
                            relative == DOCUMENT_NAME -> Unit
                            relative == ArtflowPackage.DEPENDENCIES ->
                                dependenciesBytes = contents.readBytes(relative, MAX_DOCUMENT_BYTES)
                            relative.startsWith("${ArtflowPackage.GRAINS_DIR}/") ->
                                grainEntries[relative] = contents.readBytes(relative, grainTileBytes)
                            else -> {
                                val file = resolve(projectId, relative)
                                file.parentFile?.mkdirs()
                                val limit = if (relative == MODEL_NAME) MAX_MODEL_BYTES else MAX_RASTER_BYTES
                                writeAtomically(file) { out -> contents.copyTo(relative, out, limit) }
                            }
                        }
                    }
                    writeAtomically(documentFile(projectId)) { it.write(manifest) }
                    val document = decodeDocument(documentFile(projectId), projectId)
                    referencedFiles(document).forEach { requireNotNull(readRaster(projectId, it)) }
                    val (missingFonts, missingGrains) =
                        restoreGrainsAndAuditDependencies(dependenciesBytes, grainEntries, document)
                    completed = true
                    ImportedPackageOutcome(document, missingFonts, missingGrains)
                } finally {
                    if (!completed) {
                        try {
                            StorageFileTree.delete(destination, context.filesDir)
                        } catch (cleanupFailure: IOException) {
                            Timber.w(cleanupFailure, "Could not remove an incomplete import")
                        }
                    }
                }
            }

        private fun restoreGrainsAndAuditDependencies(
            dependenciesBytes: ByteArray?,
            grainEntries: Map<String, ByteArray>,
            document: CanvasDocument,
        ): Pair<List<String>, List<String>> {
            val grainDir = GrainStorage.directory(context.filesDir)
            val expectedGrainLength = CustomGrains.TILE * CustomGrains.TILE
            grainEntries.forEach { (path, bytes) ->
                val id = path.removePrefix("${ArtflowPackage.GRAINS_DIR}/").removeSuffix(GrainStorage.EXTENSION)
                require(CustomGrains.isCustom(id) && bytes.size == expectedGrainLength) {
                    "Invalid brush grain in artwork package: $path"
                }
                GrainStorage.save(grainDir, id, CustomGrains.Tile(CustomGrains.TILE, bytes))
            }
            val packagedDeps = dependenciesBytes?.let(ArtflowPackage::decodeDependencies)
            val documentDeps = collectDependencies(document)
            val fonts =
                ((packagedDeps?.fonts ?: emptyList()) + documentDeps.fonts)
                    .distinctBy { it.id }
            val grainIds =
                ((packagedDeps?.grainIds ?: emptyList()) + documentDeps.grainIds)
                    .filter { CustomGrains.isCustom(it) }
                    .distinct()
            val missingFonts =
                fonts
                    .filter { !FontLibrary.exists(it.id, context.filesDir) }
                    .map { it.label }
                    .distinct()
            val missingGrains =
                grainIds.filter { id ->
                    CustomGrains.get(id) == null && !GrainStorage.exists(grainDir, id)
                }
            return missingFonts to missingGrains
        }

        fun referencedFiles(document: CanvasDocument): List<String> =
            document
                .resolvedFrames()
                .flatMap { it.layers }
                .flatMap { listOfNotNull(it.rasterFile, it.maskFile) }
                .distinct()

        /**
         * Copies immutable saved/recovery generations into a new project, never sharing files with
         * the original. Call inside the gallery transaction so an incomplete copy is not visible.
         * Exports and unreferenced temporary generations are intentionally not duplicated.
         */
        suspend fun copyProject(
            sourceId: Long,
            destinationId: Long,
        ): CanvasDocument? =
            withContext(ioDispatcher) {
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
                    listOf(FLATTENED_NAME, THUMBNAIL_NAME, MODEL_NAME).forEach { name ->
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
            withContext(ioDispatcher) {
                require(fileName.isNotBlank() && fileName.length <= 180 && fileName !in setOf(".", "..")) { "Invalid export filename" }
                require(fileName.none { it == '/' || it == '\\' || it.code < 32 }) { "Export filename must not contain a path" }
                require(bytes.isNotEmpty()) { "The export is empty" }
                val file = File(exportsDir(projectId), fileName)
                writeAtomically(file) { out -> out.write(bytes) }
                file
            }

        suspend fun saveExportFromTempFile(
            projectId: Long,
            fileName: String,
            writer: (File) -> Unit,
        ): File =
            withContext(ioDispatcher) {
                require(fileName.isNotBlank() && fileName.length <= 180 && fileName !in setOf(".", "..")) { "Invalid export filename" }
                require(fileName.none { it == '/' || it == '\\' || it.code < 32 }) { "Export filename must not contain a path" }
                val parent = exportsDir(projectId)
                val target = File(parent, fileName)
                StorageFileTree.requireUnlinked(target, context.filesDir)
                writer(target)
                check(target.isFile && target.length() > 0L) { "The export is empty" }
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
                target
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
            const val MAX_FRAMES = CanvasDocumentCodec.MAX_FRAMES
            const val MAX_LAYERS = CanvasDocumentCodec.MAX_LAYERS
            const val DOCUMENT_NAME = "canvas.artflow"
            const val AUTOSAVE_NAME = "autosave.artflow"
            const val FLATTENED_NAME = "canvas.png"
            const val THUMBNAIL_NAME = "thumbnail.png"
            const val MODEL_NAME = "model.obj"
            private const val MAX_MODEL_BYTES = 64L * 1024 * 1024
            const val LAYERS_DIR = "layers"
            const val EXPORTS_DIR = "exports"
            const val RASTER_PREFIX = "v"
        }
    }
