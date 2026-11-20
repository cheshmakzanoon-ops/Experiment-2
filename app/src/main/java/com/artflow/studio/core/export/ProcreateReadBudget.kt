package com.artflow.studio.core.export

import com.artflow.studio.core.canvas.CanvasOperations
import com.artflow.studio.core.pixels.ImportMemoryBudget

/** Admission before Procreate entry growth, metadata decoding and raster calibration. */
internal class ProcreateReadBudget(
    memoryBudgetBytes: Long = ImportMemoryBudget.currentBytes(),
    checkActive: () -> Unit = {},
) {
    @Volatile private var openingCheck = checkActive
    private val bytes: Long
    val metadataEntryBytes: Long
    val decodedMetadataBytes: Long
    val thumbnailEntryBytes: Long
    val chunkEntryBytes: Long
    private var archiveIndexBytes = 0L
    private val retainedBytes: Long
        get() = metadataEntryBytes + decodedMetadataBytes + AUXILIARY_BYTES + archiveIndexBytes

    init {
        require(memoryBudgetBytes in 1..(Runtime.getRuntime().maxMemory() / 3)) { "Invalid Procreate memory allowance" }
        bytes = minOf(memoryBudgetBytes, ImportMemoryBudget.currentBytes())
        metadataEntryBytes = minOf(32L * MIB, bytes / 16)
        decodedMetadataBytes = minOf(64L * MIB, Runtime.getRuntime().maxMemory() / 8, bytes / 4)
        thumbnailEntryBytes = minOf(16L * MIB, bytes / 16)
        chunkEntryBytes = minOf(64L * MIB, bytes / 16)
        // Keep the original metadata, its entire decoded allowance, ZIP/index/node bookkeeping,
        // and calibration grids reserved while reading later entries. This is conservative:
        // many decoded archive objects become unreachable after their document fields are copied.
        require(retainedBytes < bytes && decodedMetadataBytes > 0) { MEMORY_MESSAGE }
    }

    fun ensureActive() = openingCheck()

    /** Later editor reads belong to their new owner, not to the completed opening coroutine. */
    fun finishOpening() {
        openingCheck = {}
    }

    val thumbnailDecodeBytes: Long get() = bytes - retainedBytes

    /** ZipFile allocates its directory before entry reads; reserve its names and tile index too. */
    fun reserveArchiveIndex(
        directoryBytes: Long,
        entryCount: Int,
    ) {
        ensureActive()
        require(directoryBytes in 0..(bytes / 8) && entryCount >= 0) { INDEX_MESSAGE }
        val indexBytes = directoryBytes * 8 + entryCount.toLong() * 512
        require(indexBytes <= bytes / 4) { INDEX_MESSAGE }
        archiveIndexBytes = indexBytes
        require(retainedBytes < bytes) { MEMORY_MESSAGE }
    }

    fun entryLimit(name: String): Long =
        when (name) {
            "Document.archive" -> metadataEntryBytes
            "QuickLook/Thumbnail.png" -> thumbnailEntryBytes
            else -> chunkEntryBytes
        }

    fun checkEntry(
        name: String,
        size: Long,
    ) {
        ensureActive()
        require(size in 0..entryLimit(name)) { "This Procreate document has an entry too large for the available memory" }
    }

    /** Admission covers both stored and upright planes and the decoder's output/copy pair. */
    fun requireRaster(
        width: Int,
        height: Int,
        tileSize: Int,
    ) {
        ensureActive()
        require(CanvasOperations.isSizeSafe(width, height)) { MEMORY_MESSAGE }
        require(tileSize in 16..4096) { "Invalid Procreate tile size" }
        val rasterBytes = width.toLong() * height * Int.SIZE_BYTES
        val tileBytes = tileSize.toLong() * tileSize * Int.SIZE_BYTES
        // ByteArrayOutputStream can hold its growth buffer and returned copy together.
        require(retainedBytes + rasterBytes * 2 + tileBytes * 2 + chunkEntryBytes * 3 <= bytes) { MEMORY_MESSAGE }
    }

    /** The PNG header is inspected before the platform decoder or a supplied test decoder runs. */
    fun requireThumbnail(
        width: Int,
        height: Int,
        encodedBytes: Int,
    ) {
        ensureActive()
        require(CanvasOperations.isSizeSafe(width, height)) { MEMORY_MESSAGE }
        val decoderBytes = width.toLong() * height * 16 + maxOf(width, height).toLong() * 16 + MIB
        require(retainedBytes + encodedBytes.toLong() * 3 + decoderBytes <= bytes) { MEMORY_MESSAGE }
    }

    fun requireCalibration(
        width: Int,
        height: Int,
        tileSize: Int,
        thumbnailBytes: Long,
    ) {
        ensureActive()
        val rasterBytes = width.toLong() * height * Int.SIZE_BYTES
        val tileBytes = tileSize.toLong() * tileSize * Int.SIZE_BYTES
        require(thumbnailBytes >= 0 && retainedBytes + rasterBytes + tileBytes * 2 + chunkEntryBytes * 3 + thumbnailBytes <= bytes) {
            MEMORY_MESSAGE
        }
    }

    companion object {
        const val MEMORY_MESSAGE = "This Procreate document needs more memory. Reduce its dimensions or layers in the source application."
        private const val INDEX_MESSAGE = "This Procreate document has an archive index too large for the available memory"
        private const val MIB = 1024 * 1024L
        private const val AUXILIARY_BYTES = MIB
    }
}
