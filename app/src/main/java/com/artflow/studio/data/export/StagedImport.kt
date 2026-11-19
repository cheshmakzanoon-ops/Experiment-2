package com.artflow.studio.data.export

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import kotlin.coroutines.coroutineContext

/** Owns a bounded, disk-backed import until its consumer finishes, including cancellation during navigation. */
object StagedImport {
    const val MAX_PACKAGE_BYTES = 512L * 1024 * 1024
    private const val BUFFER_BYTES = 64 * 1024

    suspend fun <T> use(
        directory: File,
        openStream: () -> InputStream?,
        maxBytes: Long = MAX_PACKAGE_BYTES,
        consume: suspend (File) -> T,
    ): T {
        require(maxBytes > 0) { "The import size limit must be positive" }
        var staged: File? = null
        try {
            withContext(Dispatchers.IO) {
                check(directory.isDirectory || directory.mkdirs()) { "Cannot create the import directory" }
                // Store ownership before crossing a dispatcher boundary: cancelled delivery must not leak the file.
                val file = File.createTempFile("artflow-import-", ".zip", directory).also { staged = it }
                requireNotNull(openStream()) { "That file could not be opened" }.use { input ->
                    copyToFile(input, file, maxBytes)
                }
            }
            return consume(requireNotNull(staged))
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                staged?.let { file -> check(!file.exists() || file.delete()) { "Cannot remove the temporary import file" } }
            }
        }
    }

    private suspend fun copyToFile(
        input: InputStream,
        file: File,
        maxBytes: Long,
    ) {
        file.outputStream().use { output ->
            val buffer = ByteArray(BUFFER_BYTES)
            var total = 0L
            while (true) {
                coroutineContext.ensureActive()
                val requested = minOf(buffer.size.toLong(), maxBytes - total).toInt().coerceAtLeast(1)
                val count = input.read(buffer, 0, requested)
                if (count < 0) break
                if (count == 0) continue
                total += count
                require(total <= maxBytes) { "This artwork file is too large to import" }
                output.write(buffer, 0, count)
            }
        }
    }
}
