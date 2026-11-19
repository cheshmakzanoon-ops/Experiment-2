package com.artflow.studio.presentation.ui.screens.canvas

import com.artflow.studio.data.local.StorageFileTree
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/** Owns only capture files in the app's private camera directory, including restored activity results. */
internal class CameraCaptureFiles(
    private val filesDirectory: File,
) {
    private val directory = File(filesDirectory, "camera")

    fun create(): File =
        synchronized(active) {
            StorageFileTree.requireDirectory(directory, filesDirectory, "Cannot create a camera capture directory")
            File.createTempFile(PREFIX, SUFFIX, directory).also { active.add(key(it)) }
        }

    /** Registration is synchronous so initial scavenging cannot race a restored camera result. */
    fun protect(fileName: String): File? {
        if (!ownsName(fileName)) return null
        return File(directory, fileName).also { synchronized(active) { active.add(key(it)) } }
    }

    /** Validate again before FileProvider opens a result; a linked destination is never read. */
    fun requireReadable(file: File) {
        requireOwned(file)
        StorageFileTree.requireUnlinked(file, filesDirectory)
        check(Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) { "The camera did not return a readable photo" }
    }

    fun discard(file: File) {
        synchronized(active) {
            requireOwned(file)
            active.remove(key(file))
            StorageFileTree.requireUnlinked(file, filesDirectory)
            if (Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) Files.deleteIfExists(file.toPath())
        }
    }

    /**
     * Process death and late provider writes can leave files behind. A full day's grace avoids
     * interrupting a camera app; active/restored results are protected regardless of their age.
     * Only direct, regular files with our prefix/suffix are eligible. Links and user files stay.
     */
    fun scavenge(nowMs: Long = System.currentTimeMillis()): Int {
        StorageFileTree.requireUnlinked(directory, filesDirectory)
        if (!Files.isDirectory(directory.toPath(), LinkOption.NOFOLLOW_LINKS)) return 0
        var removed = 0
        Files.newDirectoryStream(directory.toPath()).use { children ->
            for (path in children) {
                if (!ownsName(path.fileName.toString())) continue
                synchronized(active) {
                    if (key(path.toFile()) !in active && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                        val written = Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toMillis()
                        if (written > 0 && nowMs >= written && nowMs - written >= ORPHAN_AGE_MS) {
                            StorageFileTree.requireUnlinked(path.toFile(), filesDirectory)
                            if (Files.deleteIfExists(path)) removed++
                        }
                    }
                }
            }
        }
        return removed
    }

    private fun requireOwned(file: File) {
        require(file.parentFile?.absoluteFile == directory.absoluteFile && ownsName(file.name)) { "Invalid camera capture path" }
    }

    private fun ownsName(name: String): Boolean =
        name.startsWith(PREFIX) &&
            name.endsWith(SUFFIX) &&
            name.length > PREFIX.length + SUFFIX.length &&
            '/' !in name &&
            '\\' !in name &&
            '\u0000' !in name

    private fun key(file: File): Path = file.toPath().toAbsolutePath().normalize()

    private companion object {
        const val PREFIX = "capture-"
        const val SUFFIX = ".jpg"
        const val ORPHAN_AGE_MS = 24 * 60 * 60 * 1000L

        // Multiple launchers may overlap (canvas and palette), including while one decodes.
        val active = mutableSetOf<Path>()
    }
}
