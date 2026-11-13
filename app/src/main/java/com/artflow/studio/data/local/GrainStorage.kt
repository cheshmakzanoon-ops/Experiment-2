package com.artflow.studio.data.local

import com.artflow.studio.core.render.CustomGrains
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption

/** Imported grain tiles as raw 8-bit files in the app's private storage, one per grain identity. */
object GrainStorage {
    const val EXTENSION = ".grain"

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
    fun loadAll(directory: File) {
        directory.listFiles { file -> file.name.endsWith(EXTENSION) }?.forEach { file ->
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
    ) {
        require(CustomGrains.isCustom(id)) { "Invalid grain identity" }
        directory.mkdirs()
        val target = File(directory, id + EXTENSION)
        val temporary = File.createTempFile(".$id-", "$EXTENSION.tmp", directory)
        try {
            FileOutputStream(temporary).use { out ->
                out.write(tile.values)
                out.flush()
                out.fd.sync()
            }
            Files.move(
                temporary.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            CustomGrains.register(id, tile)
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }
}
