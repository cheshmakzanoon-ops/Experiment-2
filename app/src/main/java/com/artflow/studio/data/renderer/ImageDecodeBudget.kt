package com.artflow.studio.data.renderer

import com.artflow.studio.core.canvas.CanvasOperations
import com.artflow.studio.core.pixels.ImportMemoryBudget
import java.io.ByteArrayOutputStream
import java.io.InputStream

/** Preallocation limits for lossy photo/picker imports, never for editable document rasters. */
internal object ImageDecodeBudget {
    // A high-depth software bitmap (8 bytes), its ARGB copy (4), and the returned IntArray (4)
    // can coexist. Reserve decoder/row workspace separately; other app work retains heap headroom.
    private const val BYTES_PER_OUTPUT_PIXEL = 16L
    private const val DECODER_RESERVE_BYTES = 1024 * 1024L
    private const val BYTES_PER_SOURCE_ROW_PIXEL = 16L
    private const val MAX_ENCODED_BYTES = 64 * 1024 * 1024L
    const val MEMORY_MESSAGE = "Not enough memory to import this image. Save your artwork and try a smaller image."

    data class Plan(
        val sampleSize: Int,
        val width: Int,
        val height: Int,
    ) {
        /** Reject an unexpected decoder size before creating either a software copy or IntArray. */
        fun checkDecodedSize(
            decodedWidth: Int,
            decodedHeight: Int,
        ) {
            require(
                CanvasOperations.isSizeSafe(decodedWidth, decodedHeight) &&
                    maxOf(decodedWidth, decodedHeight) <= maxOf(width, height) &&
                    decodedWidth.toLong() * decodedHeight <= width.toLong() * height,
            ) { "The image decoder exceeded the safe import size" }
        }
    }

    fun currentBytes(): Long = ImportMemoryBudget.currentBytes()

    /** No more than a third of the heap or half of its presently available headroom. */
    fun availableBytes(
        maximum: Long,
        committed: Long,
        free: Long,
    ): Long = ImportMemoryBudget.availableBytes(maximum, committed, free)

    /**
     * Bounds and pixels must come from the same bytes, including non-seekable content providers.
     * ByteArrayOutputStream growth plus its returned copy can retain up to three source lengths.
     */
    fun readEncoded(
        input: InputStream,
        memoryBudgetBytes: Long,
    ): ByteArray {
        require(memoryBudgetBytes > DECODER_RESERVE_BYTES + 4) { MEMORY_MESSAGE }
        val limit = minOf(MAX_ENCODED_BYTES, (memoryBudgetBytes - DECODER_RESERVE_BYTES) / 4).toInt()
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val count = input.read(buffer, 0, minOf(buffer.size, limit - output.size() + 1))
            if (count < 0) return output.toByteArray()
            if (count == 0) {
                // InputStream implementations must make progress; avoid an unbounded zero-read loop.
                val value = input.read()
                if (value < 0) return output.toByteArray()
                require(output.size() < limit) { "This image file is too large to import with the available memory" }
                output.write(value)
            } else {
                require(count <= limit - output.size()) { "This image file is too large to import with the available memory" }
                output.write(buffer, 0, count)
            }
        }
    }

    fun plan(
        width: Int,
        height: Int,
        maxDimension: Int,
        memoryBudgetBytes: Long,
    ): Plan {
        require(width > 0 && height > 0 && maxDimension > 0) { "Image dimensions must be positive" }
        val reserve = DECODER_RESERVE_BYTES + maxOf(width, height).toLong() * BYTES_PER_SOURCE_ROW_PIXEL
        require(memoryBudgetBytes > reserve) { MEMORY_MESSAGE }
        val pixelBudget = (memoryBudgetBytes - reserve) / BYTES_PER_OUTPUT_PIXEL
        require(pixelBudget > 0) { MEMORY_MESSAGE }
        val maximumEdge = minOf(maxDimension, CanvasOperations.MAX_DIMENSION)
        var sample = sampleSize(width, height, maximumEdge)
        while (true) {
            val sampledWidth = sampledSize(width, sample)
            val sampledHeight = sampledSize(height, sample)
            if (CanvasOperations.isSizeSafe(sampledWidth, sampledHeight) && sampledWidth.toLong() * sampledHeight <= pixelBudget) {
                return Plan(sample, sampledWidth, sampledHeight)
            }
            sample = nextSample(sample)
        }
    }

    /** BitmapFactory rounds non-power-of-two factors down; ceil division bounds odd dimensions. */
    fun sampleSize(
        width: Int,
        height: Int,
        maxDimension: Int,
    ): Int {
        require(width > 0 && height > 0 && maxDimension > 0) { "Image dimensions must be positive" }
        var sample = 1
        while (sampledSize(width, sample) > maxDimension || sampledSize(height, sample) > maxDimension) {
            sample = nextSample(sample)
        }
        return sample
    }

    private fun sampledSize(
        size: Int,
        sample: Int,
    ): Int = ((size.toLong() + sample - 1) / sample).toInt()

    private fun nextSample(sample: Int): Int {
        require(sample <= Int.MAX_VALUE / 2) { "The image dimensions cannot be safely sampled on this device" }
        return sample * 2
    }
}
