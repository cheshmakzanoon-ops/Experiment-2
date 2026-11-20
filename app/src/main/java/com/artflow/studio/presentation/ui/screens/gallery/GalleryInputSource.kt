package com.artflow.studio.presentation.ui.screens.gallery

import com.artflow.studio.core.export.readAtMost
import com.artflow.studio.core.export.readWithProgress
import com.artflow.studio.core.pixels.ImportMemoryBudget
import com.artflow.studio.core.pixels.ImportPreparation
import java.io.FilterInputStream
import java.io.InputStream

/** Admits encoded growth before opening palette or ambiguous-archive payload providers. */
internal object GalleryInputSource {
    const val MEMORY_MESSAGE = "This file needs too much memory. Save your artwork and try a smaller file."
    private const val MAX_BYTES = 64L * 1024 * 1024
    private const val HEAD_BYTES = 64

    fun read(
        openStream: () -> InputStream?,
        memoryBudgetBytes: Long = ImportMemoryBudget.currentBytes(),
        checkActive: () -> Unit = {},
    ): ByteArray? =
        ImportPreparation.withAdmission {
            require(memoryBudgetBytes in 0..(Runtime.getRuntime().maxMemory() / 3)) { "Invalid encoded import allowance" }
            checkActive()
            // The growth buffer, returned copy and bounded scratch buffer may coexist.
            val limit = minOf(MAX_BYTES, minOf(memoryBudgetBytes, ImportMemoryBudget.currentBytes()) / 4)
            require(limit > 0) { MEMORY_MESSAGE }
            openStream()?.use { input ->
                checkActive()
                requireNotNull(input.readAtMost(limit, checkActive)) { MEMORY_MESSAGE }
            }
        }

    /** Header classification has a fixed small allowance and does not wait for full-import admission. */
    fun head(
        input: InputStream,
        checkActive: () -> Unit,
    ): ByteArray {
        val buffer = ByteArray(HEAD_BYTES)
        val scratch = ByteArray(HEAD_BYTES)
        var filled = 0
        while (filled < buffer.size) {
            checkActive()
            val read = input.readWithProgress(scratch, buffer.size - filled, checkActive)
            if (read < 0) break
            scratch.copyInto(buffer, filled, 0, read)
            filled += read
        }
        return buffer.copyOf(filled)
    }

    /** The caller retains stream ownership; checks also cover brush readers with synchronous APIs. */
    fun cancellable(
        input: InputStream,
        checkActive: () -> Unit,
    ): InputStream =
        object : FilterInputStream(input) {
            override fun read(): Int {
                checkActive()
                return input.read().also { checkActive() }
            }

            override fun read(
                buffer: ByteArray,
                offset: Int,
                length: Int,
            ): Int {
                checkActive()
                if (length == 0) return 0
                val count = input.read(buffer, offset, length)
                checkActive()
                return if (count != 0) {
                    count
                } else {
                    val value = read()
                    if (value < 0) {
                        -1
                    } else {
                        buffer[offset] = value.toByte()
                        1
                    }
                }
            }
        }
}
