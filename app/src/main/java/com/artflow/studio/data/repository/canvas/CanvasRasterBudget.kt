package com.artflow.studio.data.repository.canvas

import com.artflow.studio.data.export.ArtflowPackage

/**
 * The eager document loader retains one ARGB plane per saved raster or mask, including duplicates.
 * Editing must reserve that same cost before adding a plane, even while copy-on-write shares pixels.
 * The remaining heap is reserved for history, selections, rendering, codecs and other application state.
 */
internal class CanvasRasterBudget(
    val heapBytes: Long = Runtime.getRuntime().maxMemory(),
    private val savedSelectionMetadataBytes: Long = MAX_SAVED_SELECTION_METADATA_BYTES,
) {
    init {
        require(heapBytes > 0) { "Heap budget must be positive" }
        require(savedSelectionMetadataBytes in 1..MAX_SAVED_SELECTION_METADATA_BYTES) { "Invalid saved-selection metadata limit" }
    }

    /** One limit for retained raster and selection arrays across both history stacks. */
    val historyBytes: Long = minOf(MAX_HISTORY_BYTES, heapBytes / 4)

    /**
     * Saved selection slots retain separate repository and UI copies. Reserve their combined cost
     * independently of raster planes and history; temporary replacement/codec buffers use the
     * remaining heap. Check this before copying slots and before decoding a reopened document.
     */
    fun allowsSavedSelections(
        width: Int,
        height: Int,
        count: Int,
    ): Boolean {
        if (width <= 0 || height <= 0 || count < 0) return false
        if (count == 0) return true
        return width.toLong() * height <= heapBytes / SAVED_SELECTION_HEAP_SHARE / SAVED_SELECTION_COPIES / count
    }

    fun requireSavedSelectionsFit(
        width: Int,
        height: Int,
        count: Int,
    ) {
        require(allowsSavedSelections(width, height, count)) { SAVED_SELECTION_LIMIT_MESSAGE }
    }

    /**
     * New slot sets reserve their worst-case encoded size, not their typical compression ratio.
     * Leave 8 MiB of the document limit for other metadata. Existing documents are already bounded
     * by ProjectStorage and use the heap-only check above, so compressible legacy slots still open.
     * A strict removal from those slots is also safe without readmitting their worst-case size.
     */
    fun allowsSavedSelectionEncoding(
        width: Int,
        height: Int,
        count: Int,
    ): Boolean {
        if (width <= 0 || height <= 0 || count < 0) return false
        if (count == 0) return true
        val coverageBytes = width.toLong() * height
        // Reject before adding overhead; all subsequent arithmetic is bounded by 24 MiB.
        val perSlotLimit = (savedSelectionMetadataBytes - 2) / count
        if (coverageBytes > perSlotLimit) return false
        // zlib compressBound for the codec's default window/memory parameters, wrapped stream,
        // no dictionary, and no intermediate flushes. https://github.com/madler/zlib/blob/v1.3.1/compress.c
        val deflatedBytes = coverageBytes + (coverageBytes shr 12) + (coverageBytes shr 14) + (coverageBytes shr 25) + 13
        // SEL2 + width + height is 12 bytes. Base64 is ASCII without JSON escapes; reserve quotes
        // and a separator for every slot plus both array brackets (one separator is spare).
        val encodedBytes = ((12 + deflatedBytes + 2) / 3) * 4
        return encodedBytes + 3 <= perSlotLimit
    }

    fun requireSavedSelectionEncodingFits(
        width: Int,
        height: Int,
        count: Int,
    ) {
        require(allowsSavedSelectionEncoding(width, height, count)) { SAVED_SELECTION_METADATA_LIMIT_MESSAGE }
    }

    fun allows(
        width: Int,
        height: Int,
        planes: Int,
    ): Boolean {
        if (width <= 0 || height <= 0 || planes < 0) return false
        if (planes == 0) return true
        // Division avoids overflow even for hostile dimensions/counts. Each plane uses four bytes.
        return width.toLong() * height <= heapBytes / 3 / 4 / planes
    }

    fun requireFits(
        width: Int,
        height: Int,
        planes: Int,
    ) {
        require(allows(width, height, planes)) { EDIT_LIMIT_MESSAGE }
    }

    companion object {
        private const val MAX_HISTORY_BYTES = 192L * 1024 * 1024
        private const val SAVED_SELECTION_HEAP_SHARE = 8
        private const val SAVED_SELECTION_COPIES = 2
        private const val MAX_SAVED_SELECTION_METADATA_BYTES = ArtflowPackage.MAX_DOCUMENT_BYTES - 8L * 1024 * 1024

        const val EDIT_LIMIT_MESSAGE =
            "This edit would exceed this device's artwork memory limit. Reduce layers, masks, frames or canvas size."
        const val SAVED_SELECTION_LIMIT_MESSAGE =
            "Saved selections would exceed this device's memory limit. Remove a saved selection or use a smaller canvas."
        const val SAVED_SELECTION_METADATA_LIMIT_MESSAGE =
            "Saved selections exceed the artwork's safe storage limit. Remove a saved selection or use a smaller canvas."
    }
}
