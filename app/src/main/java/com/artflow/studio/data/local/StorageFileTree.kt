package com.artflow.studio.data.local

import java.io.File
import java.io.IOException
import java.nio.file.DirectoryNotEmptyException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/** App-private housekeeping. Never follow symbolic links into another project's artwork. */
internal object StorageFileTree {
    fun sizeBytes(
        root: File,
        boundary: File? = null,
    ): Long {
        var total = 0L
        walk(
            root,
            boundary,
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(
                    file: Path,
                    attrs: BasicFileAttributes,
                ): FileVisitResult {
                    if (attrs.isRegularFile) total = Math.addExact(total, attrs.size())
                    return FileVisitResult.CONTINUE
                }
            },
        )
        return total
    }

    /** Delete matching regular files, then empty directories. Nonmatching files and links stay. */
    fun prune(
        root: File,
        boundary: File? = null,
        shouldDelete: (File) -> Boolean,
    ): Int {
        var deleted = 0
        walk(
            root,
            boundary,
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(
                    file: Path,
                    attrs: BasicFileAttributes,
                ): FileVisitResult {
                    if (attrs.isRegularFile && shouldDelete(file.toFile()) && Files.deleteIfExists(file)) deleted++
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(
                    dir: Path,
                    exc: IOException?,
                ): FileVisitResult {
                    if (exc != null) throw exc
                    try {
                        Files.deleteIfExists(dir)
                    } catch (_: DirectoryNotEmptyException) {
                        // Kept files, links, or concurrently written generations still own this directory.
                    }
                    return FileVisitResult.CONTINUE
                }
            },
        )
        return deleted
    }

    /** Missing roots are harmless. I/O failures propagate instead of reporting partial deletion as success. */
    fun delete(
        root: File,
        boundary: File? = null,
    ) {
        walk(
            root,
            boundary,
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(
                    file: Path,
                    attrs: BasicFileAttributes,
                ): FileVisitResult {
                    // walkFileTree without FOLLOW_LINKS visits the link itself, never its target.
                    Files.deleteIfExists(file)
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(
                    dir: Path,
                    exc: IOException?,
                ): FileVisitResult {
                    if (exc != null) throw exc
                    Files.deleteIfExists(dir)
                    return FileVisitResult.CONTINUE
                }
            },
        )
    }

    /** Validate an app-owned read/write path, including a linked leaf, before opening it. */
    fun requireUnlinked(
        root: File,
        boundary: File,
    ) {
        val path = root.toPath().toAbsolutePath()
        if (path != path.normalize()) throw IOException("Storage path contains dot segments")
        verifyAncestors(path, boundary.toPath().toAbsolutePath().normalize())
        if (Files.isSymbolicLink(path)) throw IOException("Storage path is a symbolic link")
    }

    private fun walk(
        root: File,
        boundary: File?,
        visitor: SimpleFileVisitor<Path>,
    ) {
        val path = root.toPath().toAbsolutePath().normalize()
        if (boundary != null) verifyAncestors(path, boundary.toPath().toAbsolutePath().normalize())
        if (!Files.notExists(path, LinkOption.NOFOLLOW_LINKS)) Files.walkFileTree(path, visitor)
    }

    /**
     * NOFOLLOW_LINKS protects the walk root and its descendants, but not ancestors of the root.
     * Callers supply the trusted app files directory, allowing system aliases above that boundary.
     * This guards existing links; it is not a sandbox against hostile concurrent filesystem changes.
     */
    private fun verifyAncestors(
        root: Path,
        boundary: Path,
    ) {
        if (!root.startsWith(boundary)) throw IOException("Storage root is outside the managed directory")
        if (root == boundary) return
        var parent = root.parent
        while (parent != null && parent != boundary) {
            if (Files.isSymbolicLink(parent)) throw IOException("Storage root has a linked ancestor")
            parent = parent.parent
        }
    }
}
