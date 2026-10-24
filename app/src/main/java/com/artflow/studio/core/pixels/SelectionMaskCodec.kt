package com.artflow.studio.core.pixels

import java.nio.ByteBuffer
import java.util.Base64

/**
 * Storage form for a saved selection, so Save & Load survives closing the artwork the way it does in
 * Procreate.
 *
 * A mask is a width, a height and one coverage byte per pixel; JSON arrays would multiply that by
 * four and make every document save slower, so the bytes are packed into a small header and the
 * result is Base64 text inside the document. [decode] is strict: a truncated, oversized or
 * foreign blob is rejected instead of being stretched over the canvas.
 */
object SelectionMaskCodec {
    /** Four ASCII bytes so a corrupted or unrelated string cannot decode as a mask. */
    private val MAGIC = byteArrayOf('S'.code.toByte(), 'E'.code.toByte(), 'L'.code.toByte(), '1'.code.toByte())
    private const val HEADER_BYTES = 4 + 4 + 4

    fun encode(mask: SelectionMask): String {
        val buffer = ByteBuffer.allocate(HEADER_BYTES + mask.coverage.size)
        buffer.put(MAGIC)
        buffer.putInt(mask.width)
        buffer.putInt(mask.height)
        buffer.put(mask.coverage)
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
        val buffer = ByteBuffer.wrap(bytes)
        buffer.position(MAGIC.size)
        val storedWidth = buffer.int
        val storedHeight = buffer.int
        val expected = width.toLong() * height
        if (storedWidth != width || storedHeight != height) return null
        if (expected > Int.MAX_VALUE) return null
        if (bytes.size != HEADER_BYTES + expected.toInt()) return null
        val coverage = ByteArray(expected.toInt())
        buffer.get(coverage)
        return SelectionMask(width, height, coverage)
    }
}
