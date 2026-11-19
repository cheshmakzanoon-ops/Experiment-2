package com.artflow.studio.core.export

import com.artflow.studio.core.pixels.ImportMemoryBudget
import com.artflow.studio.core.pixels.ImportPreparation
import java.io.ByteArrayOutputStream
import java.io.InputStream

/** Admits the encoded source before opening a provider; the PSD parser separately budgets decoded layers. */
internal object PsdImportSource {
    private const val MAX_SOURCE_BYTES = 256L * 1024 * 1024
    private const val READ_RESERVE_BYTES = 1024L * 1024
    private const val MEMORY_MESSAGE = "Not enough memory to import this PSD. Save your artwork and try a smaller PSD."
    private const val SOURCE_MESSAGE = "This PSD file is too large to import with the available memory (maximum 256 MB)."

    fun read(
        memoryBudgetBytes: Long = ImportMemoryBudget.currentBytes(),
        openStream: () -> InputStream?,
    ): ByteArray =
        try {
            ImportPreparation.withAdmission {
                val budget = minOf(memoryBudgetBytes, ImportMemoryBudget.currentBytes())
                require(budget >= READ_RESERVE_BYTES + 4) { MEMORY_MESSAGE }
                // Capacity growth can retain the old and new arrays; toByteArray also copies the source.
                // Four source lengths plus scratch space are admitted before any of those allocations.
                val limit = minOf(MAX_SOURCE_BYTES, (budget - READ_RESERVE_BYTES) / 4).toInt()
                requireNotNull(openStream()) { "That PSD could not be opened" }.use { readBounded(it, limit) }
            }
        } catch (failure: OutOfMemoryError) {
            // The stream and its admission have unwound before reporting unexpected allocation failure.
            throw IllegalArgumentException(MEMORY_MESSAGE, failure)
        }

    private fun readBounded(
        input: InputStream,
        limit: Int,
    ): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val count = input.read(buffer, 0, minOf(buffer.size, limit - output.size() + 1))
            if (count < 0) return output.toByteArray()
            if (count == 0) {
                // A provider that returns zero must still make progress without an endless read loop.
                val value = input.read()
                if (value < 0) return output.toByteArray()
                require(output.size() < limit) { SOURCE_MESSAGE }
                output.write(value)
            } else {
                require(count <= limit - output.size()) { SOURCE_MESSAGE }
                output.write(buffer, 0, count)
            }
        }
    }
}
