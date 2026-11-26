package com.artflow.studio.data.local

import android.content.Context
import com.artflow.studio.core.render.CustomGrains
import com.artflow.studio.data.export.ArtflowPackage
import com.artflow.studio.data.export.StagedImport
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
import kotlin.coroutines.coroutineContext

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
        private val documentByteLimit: Long = MAX_DOCUMENT_BYTES,
    ) {
        init {
            // Tests may exercise the exact boundary with small documents; callers cannot widen the
            // production format limit and create metadata that another reader would refuse.
            require(documentByteLimit in 1..MAX_DOCUMENT_BYTES) { "Invalid project metadata limit" }
        }

        // -----------------------------------------------------------------------------------------
        // Paths
        // -----------------------------------------------------------------------------------------

        private fun ownedPath(relative: String): File =
            File(context.filesDir, relative).apply { StorageFileTree.requireUnlinked(this, context.filesDir) }

        fun projectDir(projectId: Long): File {
            require(projectId > 0) { "Invalid project id" }
            return ownedPath("projects/$projectId").apply {
                StorageFileTree.requireDirectory(this, context.filesDir, "Cannot create project directory")
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
                StorageFileTree.requireDirectory(this, context.filesDir, "Cannot create export directory")
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

        /** The byte-array compatibility API uses the same streamed, capacity-checked package import. */
        internal suspend fun <T> withStagedPackage(
            bytes: ByteArray,
            consume: suspend (File) -> T,
        ): T =
            withContext(ioDispatcher) {
                require(bytes.isNotEmpty() && bytes.size <= StagedImport.MAX_PACKAGE_BYTES) {
                    "This artwork file is empty or too large to import"
                }
                StagedImport.use(context.cacheDir, { ByteArrayInputStream(bytes) }, consume = consume)
            }

        // -----------------------------------------------------------------------------------------
        // Document
        // -----------------------------------------------------------------------------------------

        suspend fun saveDocument(
            projectId: Long,
            document: CanvasDocument,
        ): File =
            withContext(ioDispatcher) {
                val bytes = encodeDocument(document)
                rasterRetentionMutex.withLock {
                    val file = documentFile(projectId)
                    val previous = maxOf(file.lastModified(), autosaveFile(projectId).lastModified())
                    writeAtomically(file, previous) { out -> out.write(bytes) }
                    Timber.d("Saved project $projectId (v${document.version}, ${document.resolvedFrames().size} frames)")
                    file
                }
            }

        /** Writes recovery metadata only after the caller has committed its immutable rasters. */
        suspend fun saveAutosave(
            projectId: Long,
            document: CanvasDocument,
        ): File =
            withContext(ioDispatcher) {
                val bytes = encodeDocument(document)
                rasterRetentionMutex.withLock {
                    val file = autosaveFile(projectId)
                    val previous = maxOf(file.lastModified(), documentFile(projectId).lastModified())
                    writeAtomically(file, previous) { out -> out.write(bytes) }
                    file
                }
            }

        private fun encodeDocument(document: CanvasDocument): ByteArray {
            // Reject unreadable metadata before touching either committed manifest. In particular,
            // compressed selection strings can still exceed the byte limit on detailed masks.
            CanvasDocumentCodec.requireValidHeader(document)
            CanvasDocumentCodec.requireValidStructure(document)
            return CanvasDocumentCodec.encode(document).also {
                require(it.size in 1..documentByteLimit) { "Project metadata is empty or too large" }
            }
        }

        suspend fun discardAutosave(projectId: Long) =
            withContext(ioDispatcher) {
                rasterRetentionMutex.withLock {
                    val file = autosaveFile(projectId)
                    check(!file.exists() || file.delete()) { "Could not discard the recovery snapshot" }
                }
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

        /** An older recovery can still be useful when the primary's referenced assets cannot load. */
        suspend fun hasAutosave(projectId: Long): Boolean = withContext(ioDispatcher) { autosaveFile(projectId).exists() }

        private fun decodeDocument(
            file: File,
            projectId: Long,
        ): CanvasDocument = decodeDocument(readDocumentBytes(file, projectId), projectId)

        private fun readDocumentBytes(
            file: File,
            projectId: Long,
        ): ByteArray {
            require(file.length() in 1..documentByteLimit) { "Project $projectId metadata is empty or too large" }
            return file.readBytes().also {
                require(it.size in 1..documentByteLimit) { "Project $projectId metadata is empty or too large" }
            }
        }

        private fun decodeDocument(
            bytes: ByteArray,
            projectId: Long,
        ): CanvasDocument {
            // Missing is different from corrupt. Propagate invalid data; callers must NEVER replace it
            // with a new blank canvas or silently discard a newer format's fields on the next save.
            val document = CanvasDocumentCodec.decode(bytes.decodeToString())
            CanvasDocumentCodec.requireValidHeader(document)
            CanvasDocumentCodec.requireValidStructure(document)
            // Every manifest-named asset is path-validated, including physical-paint planes.
            referencedFiles(document).forEach { resolve(projectId, it) }
            return document
        }

        /**
         * True when an autosave exists that the artist may need: it is newer than the last explicit save,
         * there is no saved document, or the saved document metadata cannot be read. The canvas loader
         * also protects an existing [hasAutosave] snapshot when the primary's referenced assets fail to load.
         */
        suspend fun hasUnsavedRecovery(projectId: Long): Boolean =
            withContext(ioDispatcher) {
                val autosave = autosaveFile(projectId)
                val document = documentFile(projectId)
                autosave.exists() &&
                    (
                        !document.exists() ||
                            autosave.lastModified() > document.lastModified() ||
                            runCatching { decodeDocument(document, projectId) }.isFailure
                    )
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
        ): String = writeAuxiliaryBinary(projectId, layerId, kind, version, "png", pngBytes)

        /** Writes an immutable content-addressed auxiliary binary file (e.g. `.png` mask or `.phys` paint plane). */
        suspend fun writeAuxiliaryBinary(
            projectId: Long,
            layerId: Long,
            kind: String,
            version: Int,
            extension: String,
            bytes: ByteArray,
        ): String =
            withContext(ioDispatcher) {
                require(kind.matches(Regex("[a-zA-Z0-9_-]+"))) { "Invalid auxiliary file kind" }
                require(extension.matches(Regex("[a-zA-Z0-9]+"))) { "Invalid auxiliary file extension" }
                val relative = "$LAYERS_DIR/$layerId/$kind$version-${digest(bytes)}.$extension"
                val file = resolve(projectId, relative)
                file.parentFile?.mkdirs()
                writeAtomically(file) { out -> out.write(bytes) }
                relative
            }

        fun rasterExists(
            projectId: Long,
            relativePath: String?,
        ): Boolean = relativePath != null && resolve(projectId, relativePath).exists()

        private val pinnedRasters = ConcurrentHashMap<Long, Map<String, Int>>()

        // Snapshot acquisition must observe both manifests and their timestamp ordering together.
        // Writers publish metadata under this lock; streaming/copying holds only counted leases.
        private val rasterRetentionMutex = Mutex()

        fun pinRasters(
            projectId: Long,
            paths: Collection<String>,
        ) {
            val normalized = paths.map(::normalizeRelativePath).toSet()
            if (normalized.isEmpty()) return
            pinnedRasters.compute(projectId) { _, pins ->
                pins.orEmpty().toMutableMap().apply {
                    normalized.forEach { path -> this[path] = (this[path] ?: 0) + 1 }
                }
            }
        }

        fun unpinRasters(
            projectId: Long,
            paths: Collection<String>,
        ) {
            val normalized = paths.map(::normalizeRelativePath).toSet()
            pinnedRasters.computeIfPresent(projectId) { _, pins ->
                pins
                    .toMutableMap()
                    .apply {
                        normalized.forEach { path ->
                            val remaining = (this[path] ?: 0) - 1
                            if (remaining > 0) this[path] = remaining else remove(path)
                        }
                    }.takeIf { it.isNotEmpty() }
            }
        }

        /** Use the same lexical target as [resolve], so aliases cannot lose their retention lease. */
        fun normalizeRelativePath(path: String): String {
            require('\u0000' !in path) { "Invalid project path" }
            val normalized = File(path.replace('\\', '/').trimStart('/')).normalize().invariantSeparatorsPath
            require(normalized.isNotEmpty() && normalized != ".." && !normalized.startsWith("../")) {
                "Path escapes the project directory"
            }
            return normalized
        }

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
                rasterRetentionMutex.withLock {
                    val layersDir = File(projectDir(projectId), LAYERS_DIR)
                    if (!layersDir.exists()) return@withLock 0
                    // Fail closed if either manifest is unreadable; never guess which artwork can be deleted.
                    val retained = keep.map(::normalizeRelativePath).toMutableSet()
                    pinnedRasters[projectId]?.let { retained.addAll(it.keys) }
                    listOfNotNull(loadDocument(projectId), loadAutosave(projectId)).forEach { document ->
                        referencedFiles(document).forEach { retained.add(normalizeRelativePath(it)) }
                    }
                    val deleted =
                        StorageFileTree.prune(layersDir, context.filesDir) { file ->
                            val relative = normalizeRelativePath(file.relativeTo(projectDir(projectId)).path)
                            (file.extension == "png" || file.extension == "phys") && relative !in retained
                        }
                    if (deleted > 0) Timber.d("Pruned $deleted stale raster versions for project $projectId")
                    deleted
                }
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

        /** Import result with fonts requiring attention and referenced grains missing on this device. */
        data class ImportedPackageOutcome(
            val document: CanvasDocument,
            val missingFonts: List<String>,
            val missingGrains: List<String>,
        )

        /** Collects portable font references (no third-party binaries) and custom grain IDs used by [document]. */
        fun collectDependencies(
            document: CanvasDocument,
            fingerprintFonts: Boolean = true,
        ): ArtflowPackage.Dependencies {
            val layers = document.resolvedFrames().flatMap { it.layers }
            require(layers.size <= CanvasDocumentCodec.MAX_LAYERS) { "Project has too many layers" }
            val grainIds =
                layers
                    .flatMap { it.strokes }
                    .flatMap { it.brushParams.imageIds }
                    .filter { CustomGrains.isCustom(it) }
                    .distinct()
                    .sorted()
            require(grainIds.size <= ArtflowPackage.MAX_GRAIN_DEPENDENCIES) { "Too many grain dependencies" }
            val fontFingerprints = FontLibrary.FingerprintSession(context.filesDir)
            val fonts =
                importedFontFamilies(document)
                    .groupBy { FontLibrary.portableId(it) ?: it }
                    .map { (id, families) ->
                        ArtflowPackage.PackagedFont(
                            id = id,
                            label = FontLibrary.label(families.first()),
                            sha256 = if (fingerprintFonts) fontFingerprints.sharedFingerprint(families) else null,
                        )
                    }
            return ArtflowPackage.Dependencies(fonts = fonts, grainIds = grainIds)
        }

        private fun importedFontFamilies(document: CanvasDocument): List<String> =
            document
                .resolvedFrames()
                .flatMap { it.layers }
                .mapNotNull { it.textContent?.style?.fontFamily }
                .filter { FontLibrary.isImported(it) }
                .distinct()

        /** A saved project's files for an `.artflow` package: manifest, layer pixels, previews, and grain/dependency assets. */
        suspend fun packageFiles(projectId: Long): Map<String, ByteArray> =
            withPackageSnapshot(projectId) { snapshot ->
                snapshot.sources.associate { source ->
                    source.path to source.openStream().use { it.readBytes() }
                }
            }

        class PackageSnapshot internal constructor(
            val document: CanvasDocument,
            val sources: List<ArtflowPackage.EntrySource>,
        )

        /**
         * Keeps one committed manifest and its immutable rasters together for the entire export.
         * Acquisition is serialized with pruning, but the archive callback never holds that lock.
         * Sources belong to this scope; callers must finish reading them before returning.
         */
        suspend fun <T> withPackageSnapshot(
            projectId: Long,
            block: suspend (PackageSnapshot) -> T,
        ): T =
            withContext(ioDispatcher) {
                PackageAssetSnapshots().use { assets ->
                    var pinned: List<String> = emptyList()
                    try {
                        val snapshot =
                            rasterRetentionMutex.withLock {
                                val file = documentFile(projectId)
                                require(file.exists()) { "Save the artwork before sharing it" }
                                val bytes = readDocumentBytes(file, projectId)
                                val document = decodeDocument(bytes, projectId)
                                pinned = referencedFiles(document)
                                pinRasters(projectId, pinned)
                                PackageSnapshot(document, packageSources(projectId, document, bytes, assets))
                            }
                        block(snapshot)
                    } finally {
                        // Synchronous release also runs when the archive or dispatcher return is cancelled.
                        unpinRasters(projectId, pinned)
                    }
                }
            }

        /** Preserves the exact committed metadata, including compatible fields unknown to this version. */
        private fun packageSources(
            projectId: Long,
            document: CanvasDocument,
            documentBytes: ByteArray,
            assets: PackageAssetSnapshots,
        ): List<ArtflowPackage.EntrySource> {
            val sources = mutableListOf<ArtflowPackage.EntrySource>()
            sources += ArtflowPackage.EntrySource(DOCUMENT_NAME) { ByteArrayInputStream(documentBytes) }
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
                    val limit = if (name == MODEL_NAME) MAX_MODEL_BYTES else MAX_RASTER_BYTES
                    sources += assets.capture(name, file, limit)
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
            return sources
        }

        /** The manifest of an `.artflow` package, checked before any project is created for it. */
        fun decodePackagedDocument(bytes: ByteArray): CanvasDocument {
            require(bytes.size in 1..documentByteLimit) { "The artwork's document is empty or too large" }
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
            grainImport: GrainStorage.ImportTransaction? = null,
        ): CanvasDocument =
            withContext(ioDispatcher) {
                withGrainImport(grainImport) { admittedImport ->
                    prepareGrainImport(files.keys, admittedImport)
                    val manifest = requireNotNull(files[DOCUMENT_NAME]) { "The artwork has no document" }
                    val destination = ownedPath("projects/$projectId")
                    check(
                        !Files.exists(destination.toPath(), LinkOption.NOFOLLOW_LINKS),
                    ) { "The destination already contains project data" }
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
                        restoreGrainsAndAuditDependencies(dependenciesBytes, grainEntries, document, admittedImport)
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
            }

        /**
         * Streaming import from a verified [ArtflowPackage.StreamContents]: copies layers/previews via
         * bounded stream without buffering all rasters in heap, writes `canvas.artflow` last, and
         * restores bundled custom grains into `filesDir/grains`.
         * [onDestinationCreated] runs synchronously after this operation creates the directory,
         * inside its rollback scope. A prior absence check does not establish cleanup ownership.
         */
        suspend fun importProjectStream(
            projectId: Long,
            contents: ArtflowPackage.StreamContents,
            grainImport: GrainStorage.ImportTransaction? = null,
            onDestinationCreated: () -> Unit = {},
        ): ImportedPackageOutcome =
            withContext(ioDispatcher) {
                withGrainImport(grainImport) { admittedImport ->
                    prepareGrainImport(contents.entries, admittedImport)
                    val manifest = contents.readBytes(DOCUMENT_NAME, documentByteLimit)
                    val destination = ownedPath("projects/$projectId")
                    check(
                        !Files.exists(destination.toPath(), LinkOption.NOFOLLOW_LINKS),
                    ) { "The destination already contains project data" }
                    check(destination.mkdirs()) { "Cannot create the imported project directory" }
                    var completed = false
                    try {
                        // Record creation before any IO result can be cancelled on delivery.
                        onDestinationCreated()
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
                            restoreGrainsAndAuditDependencies(dependenciesBytes, grainEntries, document, admittedImport)
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
            }

        private inline fun <T> withGrainImport(
            supplied: GrainStorage.ImportTransaction?,
            block: (GrainStorage.ImportTransaction) -> T,
        ): T {
            if (supplied != null) return block(supplied)
            return GrainStorage.ImportTransaction().use { owned ->
                block(owned).also { owned.commit() }
            }
        }

        /** Reserve the full grain batch before any payload is buffered or project file is published. */
        private fun prepareGrainImport(
            paths: Collection<String>,
            transaction: GrainStorage.ImportTransaction,
        ) {
            val ids =
                paths.filter { it.startsWith("${ArtflowPackage.GRAINS_DIR}/") }.map { path ->
                    val id = path.removePrefix("${ArtflowPackage.GRAINS_DIR}/").removeSuffix(GrainStorage.EXTENSION)
                    require(CustomGrains.isCustom(id) && path == ArtflowPackage.grainEntryPath(id)) {
                        "Invalid brush grain in artwork package: $path"
                    }
                    id
                }
            transaction.prepare(ids)
        }

        private fun restoreGrainsAndAuditDependencies(
            dependenciesBytes: ByteArray?,
            grainEntries: Map<String, ByteArray>,
            document: CanvasDocument,
            grainImport: GrainStorage.ImportTransaction,
        ): Pair<List<String>, List<String>> {
            val grainDir = GrainStorage.directory(context.filesDir)
            val expectedGrainLength = CustomGrains.TILE * CustomGrains.TILE
            // Neither a late invalid manifest nor a bad later tile may change global brush assets.
            val packagedDeps = dependenciesBytes?.let(ArtflowPackage::decodeDependencies)
            val importedTiles =
                grainEntries
                    .map { (path, bytes) ->
                        val id = path.removePrefix("${ArtflowPackage.GRAINS_DIR}/").removeSuffix(GrainStorage.EXTENSION)
                        require(CustomGrains.isCustom(id) && bytes.size == expectedGrainLength) {
                            "Invalid brush grain in artwork package: $path"
                        }
                        id to CustomGrains.Tile(CustomGrains.TILE, bytes)
                    }.toMap()
            // The receiving device cannot invent the source font's identity for older packages
            // or references missing from their dependency manifest.
            val documentDeps = collectDependencies(document, fingerprintFonts = false)
            val packagedFonts =
                packagedDeps?.fonts.orEmpty().map { font ->
                    font.copy(id = FontLibrary.portableId(font.id) ?: font.id)
                }
            require(packagedFonts.groupBy { it.id }.values.all { matching -> matching.map { it.sha256 }.distinct().size == 1 }) {
                "Conflicting font content fingerprints"
            }
            val declaredFonts = packagedFonts.associateBy { it.id }
            val fonts = documentDeps.fonts.map { declaredFonts[it.id] ?: it }
            val grainIds = documentDeps.grainIds
            val documentFamilies = importedFontFamilies(document).groupBy { FontLibrary.portableId(it) ?: it }
            val fontFingerprints = FontLibrary.FingerprintSession(context.filesDir)
            val missingFonts =
                fonts
                    .mapNotNull { font ->
                        val localFingerprint = fontFingerprints.sharedFingerprint(documentFamilies.getValue(font.id))
                        when {
                            localFingerprint == null && FontLibrary.exists(font.id, context.filesDir) -> "Unverified font: ${font.label}"
                            localFingerprint == null -> font.label
                            font.sha256 == null -> "Unverified font: ${font.label}"
                            font.sha256 != localFingerprint -> "Different font: ${font.label}"
                            else -> null
                        }
                    }.distinct()
            val missingGrains =
                grainIds.filter { id ->
                    id !in importedTiles && CustomGrains.get(id) == null && !GrainStorage.exists(grainDir, id)
                }
            grainImport.install(grainDir, importedTiles)
            return missingFonts to missingGrains
        }

        /**
         * The single authoritative asset-dependency contract: every file a document manifest names.
         * Duplication, packaging, import validation, pinning and pruning all enumerate through here, so a
         * new per-layer asset kind can never be copied by one operation and silently dropped by another.
         */
        fun referencedFiles(document: CanvasDocument): List<String> =
            document
                .resolvedFrames()
                .flatMap { it.layers }
                .flatMap { listOfNotNull(it.rasterFile, it.maskFile, it.physicalPaintFile) }
                .distinct()

        private data class CopyManifest(
            val bytes: ByteArray,
            val document: CanvasDocument,
            val modifiedAt: Long,
        )

        private data class CopySnapshot(
            val saved: CopyManifest?,
            val recovery: CopyManifest?,
            val sources: List<ArtflowPackage.EntrySource>,
        ) {
            val newerRecovery: Boolean
                get() = recovery != null && (saved == null || recovery.modifiedAt > saved.modifiedAt)
        }

        private fun copyManifest(
            projectId: Long,
            name: String,
        ): CopyManifest? {
            val file = projectFile(projectId, name)
            if (!file.exists()) return null
            val bytes = readDocumentBytes(file, projectId)
            return CopyManifest(bytes, decodeDocument(bytes, projectId), file.lastModified())
        }

        /** Captures a duplication's saved and recovery generations before either can be pruned. */
        private suspend fun <T> withCopySnapshot(
            projectId: Long,
            block: suspend (CopySnapshot) -> T,
        ): T =
            PackageAssetSnapshots().use { assets ->
                var pinned: List<String> = emptyList()
                try {
                    val snapshot =
                        rasterRetentionMutex.withLock {
                            val saved = copyManifest(projectId, DOCUMENT_NAME)
                            val recovery = copyManifest(projectId, AUTOSAVE_NAME)
                            pinned = listOfNotNull(saved, recovery).flatMap { referencedFiles(it.document) }.distinct()
                            pinRasters(projectId, pinned)
                            val sources =
                                pinned
                                    .map { relative ->
                                        val file = resolve(projectId, relative)
                                        require(file.isFile && file.length() in 1..MAX_RASTER_BYTES) {
                                            "Missing or invalid layer pixels: $relative"
                                        }
                                        ArtflowPackage.EntrySource(relative) { FileInputStream(file) }
                                    }.toMutableList()
                            listOf(FLATTENED_NAME, THUMBNAIL_NAME, MODEL_NAME).forEach { name ->
                                val file = resolve(projectId, name)
                                if (file.isFile) {
                                    val limit = if (name == MODEL_NAME) MAX_MODEL_BYTES else MAX_RASTER_BYTES
                                    sources += assets.capture(name, file, limit)
                                }
                            }
                            CopySnapshot(saved, recovery, sources)
                        }
                    block(snapshot)
                } finally {
                    unpinRasters(projectId, pinned)
                }
            }

        /** Preserve compatible metadata verbatim; only destination publication times are new. */
        private suspend fun copyManifestTo(
            projectId: Long,
            name: String,
            manifest: CopyManifest?,
        ) {
            if (manifest == null) return
            rasterRetentionMutex.withLock {
                val target = projectFile(projectId, name)
                val previous = maxOf(documentFile(projectId).lastModified(), autosaveFile(projectId).lastModified())
                writeAtomically(target, previous) { it.write(manifest.bytes) }
            }
        }

        /**
         * Copies immutable saved/recovery generations into a new project, never sharing files with
         * the original. Call inside the gallery transaction so an incomplete copy is not visible.
         * Exports and unreferenced temporary generations are intentionally not duplicated.
         * [onDestinationCreated] records ownership before an IO result can be cancelled on delivery;
         * callback failure still rolls back the newly created directory.
         */
        suspend fun copyProject(
            sourceId: Long,
            destinationId: Long,
            onDestinationCreated: () -> Unit = {},
        ): CanvasDocument? =
            withContext(ioDispatcher) {
                require(sourceId > 0 && destinationId > 0 && sourceId != destinationId) { "Invalid project copy" }
                withCopySnapshot(sourceId) { snapshot ->
                    val destination = ownedPath("projects/$destinationId")
                    check(
                        !Files.exists(destination.toPath(), LinkOption.NOFOLLOW_LINKS),
                    ) { "The destination already contains project data" }
                    check(destination.mkdirs()) { "Cannot create the copied project directory" }
                    var completed = false
                    try {
                        // The callback is inside cleanup ownership, including when it throws.
                        onDestinationCreated()
                        snapshot.sources.forEach { source ->
                            coroutineContext.ensureActive()
                            writeAtomically(resolve(destinationId, source.path)) { out -> source.openStream().use { it.copyTo(out) } }
                        }
                        coroutineContext.ensureActive()
                        // Commit the captured authoritative snapshot last, preserving both exact
                        // manifests and their recovery ordering even if the source has since changed.
                        if (snapshot.newerRecovery) {
                            copyManifestTo(destinationId, DOCUMENT_NAME, snapshot.saved)
                            copyManifestTo(destinationId, AUTOSAVE_NAME, snapshot.recovery)
                        } else {
                            copyManifestTo(destinationId, AUTOSAVE_NAME, snapshot.recovery)
                            copyManifestTo(destinationId, DOCUMENT_NAME, snapshot.saved)
                        }
                        completed = true
                        snapshot.saved?.document
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
                val coroutine = currentCoroutineContext()
                val file = File(exportsDir(projectId), fileName)
                writeAtomically(file, null, beforeCommit = coroutine::ensureActive) { out ->
                    var offset = 0
                    while (offset < bytes.size) {
                        coroutine.ensureActive()
                        val count = minOf(DEFAULT_BUFFER_SIZE, bytes.size - offset)
                        out.write(bytes, offset, count)
                        offset += count
                    }
                }
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

        /** Publishes an encoded file without allocating a second, file-sized byte array. */
        suspend fun saveExportFromFile(
            projectId: Long,
            fileName: String,
            source: File,
        ): File =
            withContext(ioDispatcher) {
                require(fileName.isNotBlank() && fileName.length <= 180 && fileName !in setOf(".", "..")) { "Invalid export filename" }
                require(fileName.none { it == '/' || it == '\\' || it.code < 32 }) { "Export filename must not contain a path" }
                require(Files.isRegularFile(source.toPath(), LinkOption.NOFOLLOW_LINKS)) { "The encoded export is unavailable" }
                val expectedBytes = source.length()
                require(expectedBytes > 0L) { "The export is empty" }
                val coroutine = currentCoroutineContext()
                val target = File(exportsDir(projectId), fileName)
                writeAtomically(target, null, beforeCommit = coroutine::ensureActive) { out ->
                    coroutine.ensureActive()
                    source.inputStream().use { input ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var copied = 0L
                        while (true) {
                            coroutine.ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            copied += count
                            check(copied <= expectedBytes) { "The encoded export changed while it was being published" }
                            out.write(buffer, 0, count)
                        }
                        check(copied == expectedBytes) { "The encoded export changed while it was being published" }
                    }
                    coroutine.ensureActive()
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
        ) = writeAtomically(target, null, write = write)

        internal fun writeAtomically(
            target: File,
            modifiedAfter: Long?,
            beforeCommit: () -> Unit = {},
            write: (FileOutputStream) -> Unit,
        ) {
            StorageFileTree.requireUnlinked(target, context.filesDir)
            val parent = requireNotNull(target.parentFile)
            StorageFileTree.requireDirectory(parent, context.filesDir, "Cannot create storage directory")
            val temp = File.createTempFile(".${target.name}-", ".tmp", parent)
            try {
                FileOutputStream(temp).use { out ->
                    write(out)
                    out.flush()
                    // Recovery ordering must already be valid if the process stops immediately
                    // after rename. A failed adjustment cannot replace either committed manifest.
                    modifiedAfter?.let { StorageCommitTime.ensureAfter(temp, it) }
                    out.fd.sync()
                }
                // Export cancellation can arrive during sync/close. Document persistence keeps its
                // existing commit semantics unless the caller explicitly supplies this check.
                beforeCommit()
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
            private const val MAX_DOCUMENT_BYTES = ArtflowPackage.MAX_DOCUMENT_BYTES
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
