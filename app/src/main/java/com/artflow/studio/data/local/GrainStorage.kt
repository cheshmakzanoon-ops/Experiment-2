package com.artflow.studio.data.local

import com.artflow.studio.core.render.CustomGrains
import com.artflow.studio.core.render.GrainRegistry
import kotlinx.coroutines.CancellationException
import timber.log.Timber
import java.io.Closeable
import java.io.DataInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption

/** Imported grain tiles as raw 8-bit files in the app's private storage, one per grain identity. */
object GrainStorage {
    const val EXTENSION = ".grain"
    private val importLock = Any()
    private val pendingFiles = mutableMapOf<File, ImportedFile>()
    private val pendingTiles = mutableMapOf<Pair<GrainRegistry, String>, ImportedTile>()

    private class ImportedFile(
        val file: File,
        val bytes: ByteArray,
        var users: Int = 0,
        var retained: Boolean = false,
    )

    private class ImportedTile(
        val id: String,
        val tile: CustomGrains.Tile,
        var users: Int = 0,
        var retained: Boolean = false,
    )

    fun directory(filesDir: File): File = File(filesDir, "grains")

    fun fileFor(
        directory: File,
        id: String,
    ): File? = if (CustomGrains.isCustom(id)) File(directory, id + EXTENSION) else null

    fun exists(
        directory: File,
        id: String,
    ): Boolean =
        fileFor(directory, id)?.let { file ->
            Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS) &&
                file.length() == (CustomGrains.TILE * CustomGrains.TILE).toLong()
        } == true

    /** Startup reports unreadable stored assets; existing best-effort maintenance callers may skip them. */
    fun loadAll(
        directory: File,
        strict: Boolean = false,
    ) = loadAll(directory, strict, CustomGrains.registry)

    internal fun loadAll(
        directory: File,
        strict: Boolean,
        registry: GrainRegistry,
    ) = synchronized(importLock) {
        if (strict) StorageFileTree.requireUnlinked(directory, requireNotNull(directory.parentFile))
        val stored = directory.listFiles { file -> file.name.endsWith(EXTENSION) }
        if (stored == null && strict && directory.exists()) throw IOException("Cannot read saved brush assets")
        loadAvailableTiles(stored.orEmpty(), strict, registry)
    }

    /** A failed tile must not hide other available brushes when the user explicitly continues. */
    private fun loadAvailableTiles(
        stored: Array<out File>,
        strict: Boolean,
        registry: GrainRegistry,
    ) {
        var failure: Exception? = null
        stored.forEach { file ->
            val error = runCatching { loadStoredTile(file, strict, registry) }.exceptionOrNull()
            if (error != null) {
                if (error is CancellationException || error !is Exception || !strict) throw error
                failure = failure ?: error
            }
        }
        failure?.let { throw it }
    }

    private fun loadStoredTile(
        file: File,
        strict: Boolean,
        registry: GrainRegistry,
    ) {
        if (pendingFiles[file.absoluteFile.normalize()]?.retained == false) return
        val id = file.name.removeSuffix(EXTENSION)
        if (!CustomGrains.isCustom(id)) return
        if (!Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS) ||
            file.length() != (CustomGrains.TILE * CustomGrains.TILE).toLong()
        ) {
            if (strict) throw IOException("Saved brush texture is missing or damaged: $id")
            return
        }
        try {
            // Reserve before reading, so a full registry cannot keep allocating discarded tiles.
            registry.reserve(mapOf(id to tileBytes()), bufferedCopies = 2).use { reservation ->
                reservation.register(id, CustomGrains.Tile(CustomGrains.TILE, readExact(file, tileBytes())))
            }
        } catch (error: IOException) {
            if (strict) throw error
            Timber.w(error, "Skipping unreadable grain %s", id)
        }
    }

    /** Stores and registers [tile], returning its identity. */
    fun save(
        directory: File,
        tile: CustomGrains.Tile,
    ): String {
        val id = CustomGrains.idFor(tile)
        save(directory, id, tile)
        return id
    }

    /** Atomically writes and registers [tile] under an explicit custom [id]. */
    fun save(
        directory: File,
        id: String,
        tile: CustomGrains.Tile,
    ) = save(directory, id, tile, CustomGrains.registry)

    internal fun save(
        directory: File,
        id: String,
        tile: CustomGrains.Tile,
        registry: GrainRegistry,
    ) = synchronized(importLock) {
        require(CustomGrains.isCustom(id)) { "Invalid grain identity" }
        val target = File(directory, id + EXTENSION).absoluteFile.normalize()
        registry.reserve(mapOf(id to tile.values.size)).use { reservation ->
            writeTile(target, tile, replace = true)
            reservation.register(id, tile)
        }
        // A brush saved independently now owns any matching provisional import asset as well.
        pendingFiles[target]?.retained = true
        pendingTiles[registry to id]?.retained = true
    }

    /**
     * Owns only newly installed assets until the project's database transaction commits. Shared
     * claims let concurrent imports reuse one tile without a failed import removing another's data.
     */
    class ImportTransaction internal constructor(
        private val registry: GrainRegistry,
    ) : Closeable {
        constructor() : this(CustomGrains.registry)

        private val files = linkedSetOf<ImportedFile>()
        private val tiles = linkedSetOf<ImportedTile>()
        private val reservations = mutableListOf<GrainRegistry.Reservation>()
        private var prepared: GrainRegistry.Reservation? = null
        private var finished = false

        /** Called before the package reader buffers even the first grain payload. */
        internal fun prepare(ids: Collection<String>) =
            synchronized(importLock) {
                check(!finished && prepared == null) { "The brush texture import is already prepared or finished" }
                val sizes = ids.associateWith { tileBytes() }
                require(sizes.size == ids.size) { "Duplicate brush texture identity" }
                prepared = registry.reserve(sizes, bufferedCopies = 2).also(reservations::add)
            }

        fun install(
            directory: File,
            incoming: Map<String, CustomGrains.Tile>,
        ) = synchronized(importLock) {
            check(!finished) { "The grain import has finished" }
            StorageFileTree.requireUnlinked(directory, requireNotNull(directory.parentFile))
            if (prepared == null) prepare(incoming.keys)
            val reservation = requireNotNull(prepared)
            require(incoming.keys == reservation.sizes.keys) { "The admitted brush texture batch changed" }
            prepared = null
            // Validate the complete batch before the first write or cache registration.
            incoming.forEach { (id, tile) ->
                require(CustomGrains.isCustom(id) && tile.size == CustomGrains.TILE) { "Invalid brush grain in artwork: $id" }
                val target = File(directory, id + EXTENSION).absoluteFile.normalize()
                require(!Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS) || matches(target, tile.values)) {
                    "This artwork contains a brush texture that conflicts with an existing texture: $id"
                }
                val cached = registry.get(id)
                require(cached == null || sameTile(cached, tile)) {
                    "This artwork contains a brush texture that conflicts with an existing texture: $id"
                }
            }
            incoming.forEach { (id, tile) ->
                val target = File(directory, id + EXTENSION).absoluteFile.normalize()
                val claim =
                    if (!Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                        writeTile(target, tile, replace = false)
                        ImportedFile(target, tile.values).also { pendingFiles[target] = it }
                    } else {
                        pendingFiles[target]?.takeIf { it.bytes.contentEquals(tile.values) }
                    }
                if (claim != null && files.add(claim)) claim.users++
            }
            incoming.forEach { (id, tile) ->
                val existing = reservation.registerIfAbsent(id, tile)
                require(existing == null || sameTile(existing, tile)) {
                    "This artwork contains a brush texture that conflicts with an existing texture: $id"
                }
                val claim =
                    if (existing == null) {
                        ImportedTile(id, tile).also { pendingTiles[registry to id] = it }
                    } else {
                        pendingTiles[registry to id]?.takeIf { it.tile === existing }
                    }
                if (claim != null && tiles.add(claim)) claim.users++
            }
        }

        fun commit() = finish(retain = true)

        override fun close() = finish(retain = false)

        private fun finish(retain: Boolean) =
            synchronized(importLock) {
                if (finished) return@synchronized
                finished = true
                files.forEach { claim ->
                    claim.retained = claim.retained || retain
                    if (--claim.users == 0) {
                        pendingFiles.remove(claim.file, claim)
                        if (!claim.retained) removeImportedFile(claim)
                    }
                }
                tiles.forEach { claim ->
                    claim.retained = claim.retained || retain
                    if (--claim.users == 0) {
                        pendingTiles.remove(registry to claim.id, claim)
                        if (!claim.retained) registry.removeIfSame(claim.id, claim.tile)
                    }
                }
                files.clear()
                tiles.clear()
                reservations.forEach(GrainRegistry.Reservation::close)
                reservations.clear()
                prepared = null
            }
    }

    private fun tileBytes(): Int = CustomGrains.TILE * CustomGrains.TILE

    private fun sameTile(
        left: CustomGrains.Tile,
        right: CustomGrains.Tile,
    ): Boolean = left.size == right.size && left.values.contentEquals(right.values)

    private fun matches(
        file: File,
        bytes: ByteArray,
    ): Boolean =
        Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS) &&
            file.length() == bytes.size.toLong() &&
            readExact(file, bytes.size).contentEquals(bytes)

    /** A file changed after the size check cannot make the reader allocate beyond its admission. */
    private fun readExact(
        file: File,
        size: Int,
    ): ByteArray =
        DataInputStream(file.inputStream()).use { input ->
            ByteArray(size).also { bytes ->
                input.readFully(bytes)
                if (input.read() != -1) throw IOException("Saved brush texture changed while reading: ${file.name}")
            }
        }

    private fun removeImportedFile(claim: ImportedFile) {
        try {
            if (matches(claim.file, claim.bytes)) Files.delete(claim.file.toPath())
        } catch (failure: IOException) {
            Timber.w(failure, "Could not remove an incomplete grain import")
        }
    }

    private fun writeTile(
        target: File,
        tile: CustomGrains.Tile,
        replace: Boolean,
    ) {
        val directory = requireNotNull(target.parentFile)
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create the brush texture directory" }
        val id = target.name.removeSuffix(EXTENSION)
        val temporary = File.createTempFile(".$id-", "$EXTENSION.tmp", directory)
        try {
            FileOutputStream(temporary).use { out ->
                out.write(tile.values)
                out.flush()
                out.fd.sync()
            }
            if (replace) {
                Files.move(
                    temporary.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } else {
                // A same-directory move publishes complete bytes and refuses a newly occupied ID.
                Files.move(temporary.toPath(), target.toPath())
            }
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }
}
