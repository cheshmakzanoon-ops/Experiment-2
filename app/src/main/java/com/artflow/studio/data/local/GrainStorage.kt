package com.artflow.studio.data.local

import com.artflow.studio.core.render.CustomGrains
import timber.log.Timber
import java.io.Closeable
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
    private val pendingTiles = mutableMapOf<String, ImportedTile>()

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

    /** Registers every stored tile; damaged files are skipped. */
    fun loadAll(directory: File) = synchronized(importLock) {
        directory.listFiles { file -> file.name.endsWith(EXTENSION) }?.forEach { file ->
            if (pendingFiles[file.absoluteFile.normalize()]?.retained == false) return@forEach
            val id = file.name.removeSuffix(EXTENSION)
            if (!CustomGrains.isCustom(id) || file.length() != (CustomGrains.TILE * CustomGrains.TILE).toLong()) return@forEach
            try {
                CustomGrains.register(id, CustomGrains.Tile(CustomGrains.TILE, file.readBytes()))
            } catch (error: IOException) {
                Timber.w(error, "Skipping unreadable grain %s", id)
            }
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
    ) = synchronized(importLock) {
        require(CustomGrains.isCustom(id)) { "Invalid grain identity" }
        val target = File(directory, id + EXTENSION).absoluteFile.normalize()
        writeTile(target, tile, replace = true)
        CustomGrains.register(id, tile)
        // A brush saved independently now owns any matching provisional import asset as well.
        pendingFiles[target]?.retained = true
        pendingTiles[id]?.retained = true
    }

    /**
     * Owns only newly installed assets until the project's database transaction commits. Shared
     * claims let concurrent imports reuse one tile without a failed import removing another's data.
     */
    class ImportTransaction : Closeable {
        private val files = linkedSetOf<ImportedFile>()
        private val tiles = linkedSetOf<ImportedTile>()
        private var finished = false

        fun install(
            directory: File,
            incoming: Map<String, CustomGrains.Tile>,
        ) = synchronized(importLock) {
            check(!finished) { "The grain import has finished" }
            StorageFileTree.requireUnlinked(directory, requireNotNull(directory.parentFile))
            // Validate the complete batch before the first write or cache registration.
            incoming.forEach { (id, tile) ->
                require(CustomGrains.isCustom(id) && tile.size == CustomGrains.TILE) { "Invalid brush grain in artwork: $id" }
                val target = File(directory, id + EXTENSION).absoluteFile.normalize()
                require(!Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS) || matches(target, tile.values)) {
                    "This artwork contains a brush texture that conflicts with an existing texture: $id"
                }
                val cached = CustomGrains.get(id)
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
                val existing = CustomGrains.registerIfAbsent(id, tile)
                require(existing == null || sameTile(existing, tile)) {
                    "This artwork contains a brush texture that conflicts with an existing texture: $id"
                }
                val claim =
                    if (existing == null) {
                        ImportedTile(id, tile).also { pendingTiles[id] = it }
                    } else {
                        pendingTiles[id]?.takeIf { it.tile === existing }
                    }
                if (claim != null && tiles.add(claim)) claim.users++
            }
        }

        fun commit() = finish(retain = true)

        override fun close() = finish(retain = false)

        private fun finish(retain: Boolean) = synchronized(importLock) {
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
                    pendingTiles.remove(claim.id, claim)
                    if (!claim.retained) CustomGrains.removeIfSame(claim.id, claim.tile)
                }
            }
            files.clear()
            tiles.clear()
        }
    }

    private fun sameTile(
        left: CustomGrains.Tile,
        right: CustomGrains.Tile,
    ): Boolean = left.size == right.size && left.values.contentEquals(right.values)

    private fun matches(
        file: File,
        bytes: ByteArray,
    ): Boolean =
        Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS) &&
            file.length() == bytes.size.toLong() && file.readBytes().contentEquals(bytes)

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
