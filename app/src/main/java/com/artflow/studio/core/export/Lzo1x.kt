package com.artflow.studio.core.export

/**
 * LZO1X decompression, following lzo1x_decompress_safe: the codec of the ".chunk" tiles in
 * Procreate documents. Every read and write is bounds-checked, so damaged data raises an
 * [IllegalArgumentException] instead of reading or writing out of range.
 */
object Lzo1x {
    /** Decodes [source] into at most [size] bytes; the result is shorter if the stream ends sooner. */
    fun decompress(
        source: ByteArray,
        size: Int,
    ): ByteArray {
        val out = ByteArray(size)
        val written = Decoder(source, out).run()
        return if (written == size) out else out.copyOf(written)
    }

    private class Decoder(
        private val src: ByteArray,
        private val dst: ByteArray,
    ) {
        private var ip = 0
        private var op = 0

        fun run(): Int {
            // Literals of the previous instruction: 0, 1-3 (a short tail) or 4 (a full literal run).
            var state = 0
            if (src.isNotEmpty() && unsigned(src[0]) > FIRST_LITERALS) {
                val count = byte() - FIRST_LITERALS
                literals(count)
                state = if (count < 4) count else 4
            }
            while (true) {
                val t = byte()
                if (t < M4_MARKER && state == 0) {
                    literals((if (t == 0) zeroRun(15) else t) + 3)
                    state = 4
                    continue
                }
                val tail =
                    when {
                        t >= M2_MARKER -> nearMatch(t)
                        t >= M3_MARKER -> midMatch(t)
                        t >= M4_MARKER -> farMatch(t)
                        // A match right after literals: two bytes nearby, or three bytes past the M2 range.
                        state == 4 -> afterLiterals(t, M2_RANGE + 1, 3)
                        else -> afterLiterals(t, 1, 2)
                    }
                if (tail < 0) return op
                literals(tail)
                state = tail
            }
        }

        /** M2: up to 8 bytes from up to 2 KB back. Each match returns the literals that follow it. */
        private fun nearMatch(t: Int): Int {
            match(1 + ((t shr 2) and 7) + (byte() shl 3), (t shr 5) + 1)
            return t and 3
        }

        /** M3: any length from up to 16 KB back. */
        private fun midMatch(t: Int): Int {
            val length = (t and 31).let { if (it == 0) zeroRun(31) else it }
            val word = le16()
            match(1 + (word shr 2), length + 2)
            return word and 3
        }

        /** M4: any length from 16 to 48 KB back; distance zero marks the end of the stream (returns -1). */
        private fun farMatch(t: Int): Int {
            val length = (t and 7).let { if (it == 0) zeroRun(7) else it }
            val word = le16()
            val distance = ((t and 8) shl 11) + (word shr 2)
            if (distance == 0) return -1
            match(distance + M4_BASE, length + 2)
            return word and 3
        }

        private fun afterLiterals(
            t: Int,
            base: Int,
            length: Int,
        ): Int {
            match(base + (t shr 2) + (byte() shl 2), length)
            return t and 3
        }

        private fun byte(): Int {
            require(ip < src.size) { DAMAGED }
            return unsigned(src[ip++])
        }

        private fun le16(): Int = byte() or (byte() shl 8)

        /** A length written as a zero byte per 255, then a final byte, on top of [base]. */
        private fun zeroRun(base: Int): Int {
            var zeros = 0
            while (ip < src.size && src[ip].toInt() == 0) {
                ip++
                require(++zeros <= MAX_ZEROS) { DAMAGED }
            }
            return zeros * 255 + base + byte()
        }

        private fun literals(count: Int) {
            require(ip + count <= src.size && op + count <= dst.size) { DAMAGED }
            System.arraycopy(src, ip, dst, op, count)
            ip += count
            op += count
        }

        /** Copies [length] bytes from [distance] back; the ranges may overlap, repeating a pattern. */
        private fun match(
            distance: Int,
            length: Int,
        ) {
            val from = op - distance
            require(from >= 0 && op + length <= dst.size) { DAMAGED }
            for (k in 0 until length) dst[op + k] = dst[from + k]
            op += length
        }
    }

    private fun unsigned(value: Byte) = value.toInt() and 0xFF

    private const val DAMAGED = "The compressed data is damaged"
    private const val FIRST_LITERALS = 17
    private const val M2_MARKER = 64
    private const val M3_MARKER = 32
    private const val M4_MARKER = 16
    private const val M2_RANGE = 0x800
    private const val M4_BASE = 0x4000
    private const val MAX_ZEROS = 1 shl 20
}
