package com.artflow.studio.data.export

import com.artflow.studio.core.export.ProcreateReader
import com.artflow.studio.core.export.readAtMost
import com.artflow.studio.data.renderer.BitmapPixelBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipFile
import kotlin.coroutines.coroutineContext

/**
 * A Procreate document on its way into a new artwork: the file is copied aside so its tiles can
 * be read one layer at a time, and stays open until [close] once the layers are in.
 */
class ProcreateImport private constructor(
    private val file: File,
    private val zip: ZipFile,
    val document: ProcreateReader.Document,
) : Closeable {
    private val closed = AtomicBoolean()

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            zip.close()
        } finally {
            releaseStagedFile(file)
        }
    }

    /** Also releases a claimed archive when cancellation prevents the editor job from starting. */
    internal fun closeWhenCompleted(job: Job) {
        job.invokeOnCompletion {
            try {
                close()
            } catch (error: IOException) {
                Timber.e(error, "Could not remove a completed Procreate import")
            } catch (error: SecurityException) {
                Timber.e(error, "Could not access a completed Procreate import")
            }
        }
    }

    private class Entries(
        private val zip: ZipFile,
    ) : ProcreateReader.Files {
        override val names: Collection<String> =
            zip
                .entries()
                .asSequence()
                .map { it.name }
                .toList()

        override fun read(name: String): ByteArray? {
            val entry = zip.getEntry(name) ?: return null
            require(entry.size in 0..MAX_ENTRY_BYTES) { "This Procreate document has an entry too large to read" }
            // The declared size is checked above, but the bytes actually inflated are what must stay bounded.
            val bytes = zip.getInputStream(entry).use { it.readAtMost(MAX_ENTRY_BYTES) }
            require(bytes != null) { "This Procreate document has an entry too large to read" }
            return bytes
        }
    }

    companion object {
        private const val MAX_ENTRY_BYTES = 256L * 1024 * 1024
        private const val MAX_FILE_BYTES = 512L * 1024 * 1024
        private const val MAX_ARCHIVE_ENTRIES = 10_000
        private const val STAGING_PREFIX = "artflow-procreate-import-"
        private val stagingLock = Any()
        private val activeFiles = mutableSetOf<File>()

        /** Files with our private prefix and no live owner were abandoned by an earlier process. */
        private fun createStagedFile(directory: File): File =
            synchronized(stagingLock) {
                val base = directory.canonicalFile
                base.listFiles()?.forEach { candidate ->
                    if (candidate.name.startsWith(STAGING_PREFIX) && candidate.name.endsWith(".procreate") && candidate !in activeFiles) {
                        check(!candidate.exists() || candidate.delete()) { "Cannot remove an abandoned Procreate import" }
                    }
                }
                File.createTempFile(STAGING_PREFIX, ".procreate", base).also { activeFiles += it }
            }

        private fun releaseStagedFile(file: File) {
            synchronized(stagingLock) {
                activeFiles -= file
                if (file.exists() && !file.delete()) throw IOException("Cannot remove the temporary Procreate import")
            }
        }

        /**
         * Owns the archive across cancellable IO delivery and project creation. The consumer calls
         * its transfer callback only after another owner has accepted the archive; otherwise every exit closes it.
         */
        suspend fun useOpened(
            directory: File,
            openStream: () -> InputStream?,
            consume: suspend (document: ProcreateImport, transfer: () -> Unit) -> Unit,
        ) {
            var owned: ProcreateImport? = null
            val consumed =
                runCatching {
                    withContext(Dispatchers.IO) {
                        val openingContext = coroutineContext
                        // Capture ownership before provider closing or dispatcher delivery can fail.
                        requireNotNull(openStream()) { "That file could not be opened" }.use { input ->
                            owned = open(input, directory) { openingContext.ensureActive() }
                        }
                    }
                    consume(requireNotNull(owned)) { owned = null }
                }
            val cleanupFailure =
                withContext(NonCancellable + Dispatchers.IO) {
                    runCatching { owned?.close() }.exceptionOrNull()
                }
            if (cleanupFailure != null) {
                val original = consumed.exceptionOrNull()
                if (original == null) throw cleanupFailure
                original.addSuppressed(cleanupFailure)
            }
            consumed.getOrThrow()
        }

        /** Copies [input] into [directory] and opens it as a Procreate document. */
        fun open(
            input: InputStream,
            directory: File,
        ): ProcreateImport = open(input, directory) {}

        private fun open(
            input: InputStream,
            directory: File,
            checkActive: () -> Unit,
        ): ProcreateImport {
            checkActive()
            val file = createStagedFile(directory)
            var zip: ZipFile? = null
            val opened =
                runCatching {
                    copyBounded(input, file, checkActive)
                    checkActive()
                    val archive = ZipFile(file).also { zip = it }
                    require(archive.size() <= MAX_ARCHIVE_ENTRIES) { "This Procreate document has too many entries" }
                    val files = Entries(archive)
                    require(ProcreateReader.isProcreate(files)) { "That file is not a Procreate document" }
                    ProcreateImport(file, archive, ProcreateReader.open(files, BitmapPixelBridge::fromEncodedBytes))
                }
            val failure = opened.exceptionOrNull() ?: return opened.getOrThrow()
            runCatching { zip?.close() }.exceptionOrNull()?.let(failure::addSuppressed)
            runCatching { releaseStagedFile(file) }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }

        private fun copyBounded(
            input: InputStream,
            file: File,
            checkActive: () -> Unit,
        ) {
            file.outputStream().use { out ->
                val buffer = ByteArray(8192)
                var total = 0L
                while (true) {
                    checkActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= MAX_FILE_BYTES) { "This Procreate document exceeds the maximum file size" }
                    out.write(buffer, 0, count)
                }
                out.flush()
            }
        }
    }
}
