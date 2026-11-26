package com.artflow.studio.data.local

import com.artflow.studio.core.lifecycle.retainCleanupFailure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext

/** Only marked, inactive share directories are eligible; bytes include their small ownership marker. */
internal data class SharedCopySummary(
    val count: Int = 0,
    val bytes: Long = 0,
    val activeCount: Int = 0,
    val unreadableCount: Int = 0,
)

internal data class SharedCopyCleanupResult(
    val removedCount: Int,
    val removedBytes: Long,
    val failedCount: Int,
    val remaining: SharedCopySummary,
)

/** Each share owns a new path: a receiving app may open its URI after another share has completed. */
internal object ShareExportFiles {
    internal const val DIRECTORY_PREFIX = "share-"
    internal const val OWNER_MARKER = ".artflow-share"
    private val ownership = Any()
    private val active = ConcurrentHashMap<Path, Request>()

    private class Request(
        val file: File,
        @Volatile var published: Boolean = false,
    )

    /** [directory] is an already validated export directory, never an editable artwork directory. */
    suspend fun create(
        directory: File,
        fileName: String,
        write: suspend (FileOutputStream) -> Unit,
    ): File {
        requireValidName(fileName)
        var staged: File? = null
        val result =
            runCatching {
                withContext(Dispatchers.IO) {
                    val target =
                        synchronized(ownership) {
                            StorageFileTree.requireUnlinked(directory, directory)
                            val owned = Files.createTempDirectory(directory.toPath(), DIRECTORY_PREFIX).toFile()
                            File(owned, fileName).also {
                                staged = it
                                // Acquisition and registration share cleanup's lock: a scan cannot see an
                                // unregistered directory between mkdir and the writer's first suspension.
                                active[owned.toPath().toAbsolutePath()] = Request(it)
                                File(owned, OWNER_MARKER).outputStream().use { marker ->
                                    marker.write(fileName.toByteArray(Charsets.UTF_8))
                                    marker.fd.sync()
                                }
                            }
                        }
                    target.outputStream().use { output ->
                        write(output)
                        coroutineContext.ensureActive()
                        check(output.channel.size() > 0) { "The shared file is empty" }
                        output.flush()
                        output.fd.sync()
                    }
                    coroutineContext.ensureActive()
                    target
                }
            }
        // Keep ownership outside withContext: cancelled delivery back to the UI must remove this file too.
        result.exceptionOrNull()?.let { failure ->
            staged?.let { file ->
                runCatching { discardUnpublished(file) }.exceptionOrNull()?.let { retainCleanupFailure(failure, it) }
            }
        }
        return result.getOrThrow()
    }

    /** Streams the saved image from one open descriptor, which remains stable across an atomic save. */
    suspend fun copy(
        directory: File,
        fileName: String,
        source: File,
    ): File =
        create(directory, fileName) { output ->
            require(Files.isRegularFile(source.toPath(), LinkOption.NOFOLLOW_LINKS)) { "The saved image is unavailable" }
            source.inputStream().use { input ->
                val expected = input.channel.size()
                require(expected > 0) { "The saved image is empty" }
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var copied = 0L
                while (true) {
                    coroutineContext.ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    copied += count
                    check(copied <= expected) { "The saved image changed while it was being shared" }
                    output.write(buffer, 0, count)
                }
                check(copied == expected) { "The saved image changed while it was being shared" }
            }
        }

    /** Call immediately after chooser launch, before any suspension. Never performs disk I/O or throws. */
    fun markPublished(file: File) {
        // A chooser callback on Main must not wait behind an unrelated disk scan or deletion.
        // Registry insertion/removal remains serialized; this only marks the request's volatile state.
        file.parentFile?.toPath()?.toAbsolutePath()?.let { directory ->
            active[directory]?.takeIf { it.file == file }?.published = true
        }
    }

    /** Every caller releases its request in a finally block, including cancelled UI dispatcher delivery. */
    suspend fun finish(file: File) {
        withContext(NonCancellable + Dispatchers.IO) {
            synchronized(ownership) {
                val directory = file.parentFile?.toPath()?.toAbsolutePath() ?: return@synchronized
                val request = active[directory]?.takeIf { it.file == file } ?: return@synchronized
                try {
                    if (!request.published) discardOwned(request.file)
                } finally {
                    // A failed removal has ended too. Keep its ownership marker so a later explicit
                    // cleanup can retry, instead of permanently treating the abandoned copy as active.
                    active.remove(directory)
                }
            }
        }
    }

    /** A published URI is retained even when cancellation reaches an older caller's cleanup path. */
    suspend fun discardUnpublished(file: File) {
        finish(file)
    }

    /** A batch failure must still release every request; retain every cleanup failure for the caller. */
    suspend fun finishAll(files: Iterable<File>) {
        var failure: Throwable? = null
        files.forEach { file ->
            runCatching { finish(file) }.exceptionOrNull()?.let { cleanup ->
                val previous = failure
                if (previous == null) failure = cleanup else retainCleanupFailure(previous, cleanup)
            }
        }
        failure?.let { throw it }
    }

    /** Caller supplies the validated app-private exports root, never a project/artwork root. */
    fun summary(
        exportsRoot: File,
        boundary: File,
    ): SharedCopySummary =
        synchronized(ownership) {
            SharedCopyDirectories.inspect(exportsRoot, boundary, active.keys)
        }

    /** Explicit user cleanup; active writers and pending chooser handoffs remain registered throughout. */
    suspend fun removeCompleted(
        exportsRoot: File,
        boundary: File,
    ): SharedCopyCleanupResult =
        withContext(NonCancellable + Dispatchers.IO) {
            synchronized(ownership) {
                SharedCopyDirectories.remove(exportsRoot, boundary, active.keys)
            }
        }

    internal fun requireValidName(fileName: String) {
        require(fileName.isNotBlank() && fileName.length <= 180 && fileName !in setOf(".", "..", OWNER_MARKER)) {
            "Invalid share filename"
        }
        require(fileName.none { it == '/' || it == '\\' || it.code < 32 }) { "Share filename must not contain a path" }
    }

    private fun discardOwned(file: File) {
        val directory = requireNotNull(file.parentFile)
        StorageFileTree.requireUnlinked(file, requireNotNull(directory.parentFile))
        val marker = File(directory, OWNER_MARKER)
        StorageFileTree.requireUnlinked(marker, requireNotNull(directory.parentFile))
        if (Files.exists(directory.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            Files.newDirectoryStream(directory.toPath()).use { children ->
                if (children.any { it.fileName.toString() !in setOf(file.name, OWNER_MARKER) }) {
                    throw IOException("The shared-copy directory contains other files")
                }
            }
        }
        Files.deleteIfExists(file.toPath())
        Files.deleteIfExists(marker.toPath())
        // Never recurse: an unexpected sibling is not owned by this request.
        Files.deleteIfExists(directory.toPath())
    }
}
