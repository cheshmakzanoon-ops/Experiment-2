package com.artflow.studio.data.local

import com.artflow.studio.core.export.readAtMost
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/** Fixed-depth housekeeping, called only while ShareExportFiles holds its request-ownership lock. */
internal object SharedCopyDirectories {
    private const val MAX_MARKER_BYTES = 720L

    private data class Copy(
        val directory: File,
        val file: File,
        val marker: File,
        val fileBytes: Long,
        val markerBytes: Long,
    ) {
        val bytes: Long get() = Math.addExact(fileBytes, markerBytes)
    }

    private data class Scan(
        val copies: List<Copy>,
        val activeCount: Int,
        val unreadableCount: Int,
    ) {
        fun summary() = SharedCopySummary(copies.size, copies.sumOf { it.bytes }, activeCount, unreadableCount)
    }

    fun inspect(
        exportsRoot: File,
        boundary: File,
        active: Set<Path>,
    ): SharedCopySummary = scan(exportsRoot, boundary, active).summary()

    fun remove(
        exportsRoot: File,
        boundary: File,
        active: Set<Path>,
    ): SharedCopyCleanupResult {
        val found = scan(exportsRoot, boundary, active)
        var removedCount = 0
        var removedBytes = 0L
        var failedCount = found.unreadableCount
        found.copies.forEach { copy ->
            try {
                // Recheck the exact flat directory before deleting any leaf. Unexpected siblings,
                // linked files or changed ownership markers preserve the whole remaining copy.
                val current = readCopy(copy.directory, boundary)
                if (current != copy) throw IOException("The shared copy changed during cleanup")
                if (Files.deleteIfExists(copy.file.toPath())) removedBytes = Math.addExact(removedBytes, copy.fileBytes)
                if (Files.deleteIfExists(copy.marker.toPath())) removedBytes = Math.addExact(removedBytes, copy.markerBytes)
                Files.deleteIfExists(copy.directory.toPath())
                removedCount++
            } catch (_: IOException) {
                failedCount++
            } catch (_: SecurityException) {
                failedCount++
            }
        }
        return SharedCopyCleanupResult(removedCount, removedBytes, failedCount, scan(exportsRoot, boundary, active).summary())
    }

    private fun scan(
        exportsRoot: File,
        boundary: File,
        active: Set<Path>,
    ): Scan {
        StorageFileTree.requireUnlinked(exportsRoot, boundary)
        val root = exportsRoot.toPath().toAbsolutePath()
        val activeCount = active.count { it.startsWith(root) }
        if (Files.notExists(root, LinkOption.NOFOLLOW_LINKS)) return Scan(emptyList(), activeCount, 0)
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) throw IOException("The export directory is unavailable")
        val copies = mutableListOf<Copy>()
        var unreadable = 0
        Files.newDirectoryStream(root).use { owners ->
            owners.forEach { owner ->
                // No recursion: only project IDs, palette and brush export buckets are managed.
                if (!isExportBucket(owner.fileName.toString())) return@forEach
                try {
                    unreadable += scanBucket(owner, boundary, active, copies)
                } catch (_: IOException) {
                    unreadable++
                } catch (_: SecurityException) {
                    unreadable++
                }
            }
        }
        return Scan(copies, activeCount, unreadable)
    }

    private fun scanBucket(
        owner: Path,
        boundary: File,
        active: Set<Path>,
        copies: MutableList<Copy>,
    ): Int {
        StorageFileTree.requireUnlinked(owner.toFile(), boundary)
        if (!Files.isDirectory(owner, LinkOption.NOFOLLOW_LINKS)) return 0
        var unreadable = 0
        Files.newDirectoryStream(owner).use { directories ->
            directories.forEach { directory ->
                if (!directory.fileName.toString().startsWith(ShareExportFiles.DIRECTORY_PREFIX)) return@forEach
                if (directory.toAbsolutePath() in active) return@forEach
                try {
                    readCopy(directory.toFile(), boundary)?.let(copies::add)
                } catch (_: IOException) {
                    unreadable++
                } catch (_: SecurityException) {
                    unreadable++
                }
            }
        }
        return unreadable
    }

    private fun readCopy(
        directory: File,
        boundary: File,
    ): Copy? {
        StorageFileTree.requireUnlinked(directory, boundary)
        if (!Files.isDirectory(directory.toPath(), LinkOption.NOFOLLOW_LINKS)) return null
        val marker = File(directory, ShareExportFiles.OWNER_MARKER)
        if (Files.notExists(marker.toPath(), LinkOption.NOFOLLOW_LINKS)) return null
        StorageFileTree.requireUnlinked(marker, boundary)
        val (name, markerBytes) = readMarker(marker)
        val file = File(directory, name)
        StorageFileTree.requireUnlinked(file, boundary)
        return Copy(directory, file, marker, checkedPayloadBytes(directory, file), markerBytes)
    }

    private fun readMarker(marker: File): Pair<String, Long> {
        if (!Files.isRegularFile(marker.toPath(), LinkOption.NOFOLLOW_LINKS)) throw IOException("Invalid shared-copy marker")
        val markerBytes =
            marker.inputStream().use { it.readAtMost(MAX_MARKER_BYTES) }
                ?: throw IOException("Invalid shared-copy marker")
        val name = markerBytes.toString(Charsets.UTF_8)
        validateMarkerName(name)
        return name to markerBytes.size.toLong()
    }

    private fun validateMarkerName(name: String) {
        try {
            ShareExportFiles.requireValidName(name)
        } catch (invalid: IllegalArgumentException) {
            throw IOException("Invalid shared-copy marker", invalid)
        }
    }

    private fun checkedPayloadBytes(
        directory: File,
        file: File,
    ): Long {
        Files.newDirectoryStream(directory.toPath()).use { children ->
            if (children.any { it.fileName.toString() !in setOf(file.name, ShareExportFiles.OWNER_MARKER) }) {
                throw IOException("The shared-copy directory contains other files")
            }
        }
        // A previous process may have stopped after recording ownership but before writing.
        if (Files.notExists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) return 0L
        if (!Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) throw IOException("Invalid shared-copy file")
        return Files.size(file.toPath())
    }

    private fun isExportBucket(name: String): Boolean = name in setOf("palettes", "brushes") || (name.toLongOrNull() ?: 0L) > 0L
}
