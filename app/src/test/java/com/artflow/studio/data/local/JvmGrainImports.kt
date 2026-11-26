package com.artflow.studio.data.local

import com.artflow.studio.core.render.CustomGrains
import com.artflow.studio.core.render.GrainRegistry
import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption

/**
 * Real JVM files and directory synchronization for transaction tests. Android descriptor validation
 * remains covered by GrainPublicationDeviceTest; an unsupported host filesystem fails this boundary.
 */
internal fun jvmGrainImport(registry: GrainRegistry = CustomGrains.registry): GrainStorage.ImportTransaction =
    GrainStorage.ImportTransaction(registry, ::syncJvmGrainDirectory)

private fun syncJvmGrainDirectory(directory: File) {
    val path = directory.toPath()
    if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
        throw IOException("Brush texture storage is not a directory")
    }
    FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
        channel.force(true)
    }
}
