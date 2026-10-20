package com.artflow.studio.data.export

import com.artflow.studio.core.export.ProcreateReader
import com.artflow.studio.core.export.readAtMost
import com.artflow.studio.data.renderer.BitmapPixelBridge
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile

/**
 * A Procreate document on its way into a new artwork: the file is copied aside so its tiles can
 * be read one layer at a time, and stays open until [close] once the layers are in.
 */
class ProcreateImport private constructor(
    private val file: File,
    private val zip: ZipFile,
    val document: ProcreateReader.Document,
) : Closeable {
    override fun close() {
        zip.close()
        file.delete()
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

        /** Copies [input] into [directory] and opens it as a Procreate document. */
        fun open(
            input: InputStream,
            directory: File,
        ): ProcreateImport {
            val file = File.createTempFile("import", ".procreate", directory)
            var zip: ZipFile? = null
            var opened: ProcreateImport? = null
            try {
                file.outputStream().use { input.copyTo(it) }
                val archive = ZipFile(file).also { zip = it }
                val files = Entries(archive)
                require(ProcreateReader.isProcreate(files)) { "That file is not a Procreate document" }
                return ProcreateImport(file, archive, ProcreateReader.open(files, BitmapPixelBridge::fromEncodedBytes)).also { opened = it }
            } finally {
                if (opened == null) {
                    zip?.close()
                    file.delete()
                }
            }
        }
    }
}
