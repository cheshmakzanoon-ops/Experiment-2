package com.artflow.studio.data.local

import com.artflow.studio.core.render.CustomGrains
import timber.log.Timber
import java.io.File
import java.io.IOException

/** Imported grain tiles as raw 8-bit files in the app's private storage, one per grain identity. */
object GrainStorage {
    private const val EXTENSION = ".grain"

    fun directory(filesDir: File): File = File(filesDir, "grains")

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
        directory.mkdirs()
        val target = File(directory, id + EXTENSION)
        val temporary = File(directory, "$id$EXTENSION.tmp")
        temporary.writeBytes(tile.values)
        if (!temporary.renameTo(target)) {
            temporary.delete()
            throw IOException("Could not store the grain")
        }
        CustomGrains.register(id, tile)
        return id
    }
}
