package com.artflow.studio.core.export

import com.artflow.studio.core.pixels.ImportMemoryBudget

/** Admission for encoded brush input, retained decoded data and temporary image work. */
internal class BrushDecodeBudget(
    memoryBudgetBytes: Long = ImportMemoryBudget.currentBytes(),
) {
    private val bytes = minOf(memoryBudgetBytes, ImportMemoryBudget.currentBytes())
    private var retained = BOOKKEEPING_BYTES
    private var tileBytes = 0L
    private val tileLimit = minOf(16L * MIB, Runtime.getRuntime().maxMemory() / 32, bytes / 4)

    init {
        require(memoryBudgetBytes in 1..(Runtime.getRuntime().maxMemory() / 3)) { "Invalid brush decoding allowance" }
        require(bytes > BOOKKEEPING_BYTES) { MEMORY_MESSAGE }
    }

    /** Stream growth and the returned copy can coexist with up to three encoded source lengths. */
    val encodedLimit: Int get() = minOf(64L * MIB, (bytes - BOOKKEEPING_BYTES) / 4).toInt()

    fun retainInput(size: Int) {
        require(size in 0..encodedLimit) { MEMORY_MESSAGE }
        retain(size.toLong())
    }

    fun retainPath(characters: Int) = retain(512L + characters.toLong() * 8)

    /**
     * ABR text reserves UTF-16 decoding, trimming and key-normalisation copies plus map bookkeeping.
     * Sixteen bytes per code unit covers those copies; 512 bytes covers strings and their entries.
     * Keep charges for temporary, empty and duplicate candidates too: descriptor scans may overlap,
     * so releasing them would allow repeated decoding work to escape the input and memory limits.
     */
    fun retainText(characters: Int) {
        require(characters >= 0) { MEMORY_MESSAGE }
        retain(512L + characters.toLong() * 16L)
    }

    /** Reserve ByteArrayOutputStream's growth buffer and returned copy before inflation. */
    fun entryLimit(formatLimit: Int): Int = minOf(formatLimit.toLong(), (bytes - retained) / 3).toInt()

    fun retainEntry(size: Int) = retain(size.toLong())

    /** Keep the complete decoded allowance charged after extracting settings and their copied fields. */
    fun metadataAllowance(encodedSize: Int): Long {
        val allowance = minOf(8L * MIB, Runtime.getRuntime().maxMemory() / 8, encodedSize.toLong() * 32 + 64 * 1024)
        retain(allowance * 2)
        return allowance
    }

    /** A fixed tile is admitted before its decoder runs, even for many tiny source tips. */
    fun retainTile() {
        require(TILE_BYTES <= tileLimit - tileBytes) { MEMORY_MESSAGE }
        retain(TILE_BYTES)
        tileBytes += TILE_BYTES
    }

    /** ABR source values are all retained by the reader; its raw plane coexists with the next values. */
    fun retainTip(
        pixels: Long,
        bytesPerSample: Int,
        includeTile: Boolean,
    ) {
        if (includeTile) retainTile()
        require(pixels > 0 && bytesPerSample in 1..2 && pixels * (bytesPerSample + 1) <= bytes - retained) { MEMORY_MESSAGE }
        retained += pixels
    }

    /** Tile box filtering has a FloatArray and coverage copies in addition to the platform image. */
    fun imageAllowance(): Long {
        val remaining = bytes - retained - TILE_BYTES * 6
        require(remaining > 0) { MEMORY_MESSAGE }
        return remaining
    }

    private fun retain(count: Long) {
        require(count >= 0 && count <= bytes - retained) { MEMORY_MESSAGE }
        retained += count
    }

    companion object {
        const val MEMORY_MESSAGE = "These brushes need too much memory. Import fewer brushes or smaller brush images."
        private const val MIB = 1024L * 1024
        private const val BOOKKEEPING_BYTES = 64L * 1024
        private const val TILE_BYTES = 256L * 256
    }
}
