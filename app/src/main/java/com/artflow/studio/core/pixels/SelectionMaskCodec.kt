package com.artflow.studio.core.pixels

import java.nio.ByteBuffer
import java.util.Base64
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * Storage form for a saved selection, so Save & Load survives closing the artwork the way it does in
 * Procreate.
 *
 * A mask is a width, a height and one coverage byte per pixel — 4 MB at 2048 × 2048 — so the
 * coverage is deflated before it is Base64 text inside the document. Selections are usually large
 * uniform regions, which compress to a few kilobytes; keeping them raw would make every autosave
 * write megabytes and every corruption check parse them byte by byte.
 *
 * [decode] is strict: a truncated, oversized, foreign or decompression-bomb payload is rejected
 * instead of being stretched over the canvas.
 */
object SelectionMaskCodec {
    /** Four ASCII bytes so corrupted or unrelated text cannot decode as a mask. */
    private val MAGIC = byteArrayOf('S'.code.toByte(), 'E'.code.toByte(), 'L'.code.toByte(), '2'.code.toByte())
    private const val HEADER_BYTES = 4 + 4 + 4

    fun encode(mask: SelectionMask): String {
        val compressed = deflate(mask.coverage)
        val buffer = ByteBuffer.allocate(HEADER_BYTES + compressed.size)
        buffer.put(MAGIC)
        buffer.putInt(mask.width)
        buffer.putInt(mask.height)
        buffer.put(compressed)
        return Base64.getEncoder().encodeToString(buffer.array())
    }

    /**
     * Returns the mask when [text] is a well-formed mask with exactly [width] × [height] coverage,
     * and null otherwise. Saved selections are tied to the canvas they were drawn on, so a mask from
     * another canvas size is not silently cropped or padded.
     */
    fun decode(
        text: String,
        width: Int,
        height: Int,
    ): SelectionMask? {
        val bytes =
            try {
                Base64.getDecoder().decode(text)
            } catch (_: IllegalArgumentException) {
                return null
            }
        if (bytes.size < HEADER_BYTES) return null
        for (i in MAGIC.indices) if (bytes[i] != MAGIC[i]) return null
        val expected = width.toLong() * height
        if (expected <= 0L || expected > Int.MAX_VALUE) return null
        val buffer = ByteBuffer.wrap(bytes)
        buffer.position(MAGIC.size)
        if (buffer.int != width || buffer.int != height) return null
        val coverage = inflate(bytes, HEADER_BYTES, bytes.size - HEADER_BYTES, expected.toInt()) ?: return null
        return SelectionMask(width, height, coverage)
    }

    private fun deflate(coverage: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_SPEED)
        try {
            deflater.setInput(coverage)
            deflater.finish()
            val out = ByteArray(coverage.size + 64)
            val size = deflater.deflate(out)
            return out.copyOf(size)
        } finally {
            deflater.end()
        }
    }

    /** Inflates exactly [size] bytes, refusing anything that ends early or wants to grow further. */
    private fun inflate(
        bytes: ByteArray,
        offset: Int,
        length: Int,
        size: Int,
    ): ByteArray? {
        if (length <= 0) return null
        val inflater = Inflater()
        try {
            inflater.setInput(bytes, offset, length)
            val out = ByteArray(size)
            return if (fill(inflater, out) == size && inflater.finished()) out else null
        } catch (_: DataFormatException) {
            return null
        } finally {
            inflater.end()
        }
    }

    /** Writes until [out] is full or the stream stops producing bytes. */
    private fun fill(
        inflater: Inflater,
        out: ByteArray,
    ): Int {
        var written = 0
        while (written < out.size) {
            val read = inflater.inflate(out, written, out.size - written)
            if (read > 0) {
                written += read
            } else if (inflater.finished() || inflater.needsInput() || inflater.needsDictionary()) {
                // The stream ended early, or asks for a dictionary this format never writes.
                break
            }
        }
        return written
    }
}
