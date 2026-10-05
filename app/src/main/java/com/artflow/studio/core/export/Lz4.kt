package com.artflow.studio.core.export

/**
 * LZ4 block decompression (the raw block format, without frame headers), as USD crate files and
 * Procreate's tiles store data. Every read and write is bounds-checked, so damaged data raises an
 * [IllegalArgumentException] instead of reading or writing out of range.
 */
object Lz4 {
    /**
     * Decodes [length] bytes of [source] at [offset] into [target] at [at]; returns the bytes
     * written. Matches may reach back to [window], so chained blocks can refer to earlier output.
     */
    fun decompress(
        source: ByteArray,
        offset: Int,
        length: Int,
        target: ByteArray,
        at: Int,
        window: Int = at,
    ): Int {
        require(offset >= 0 && length >= 0 && offset + length <= source.size && window in 0..at) { DAMAGED }
        val end = offset + length
        var s = offset
        var d = at
        while (s < end) {
            val token = source[s++].toInt() and 0xFF
            var literals = token ushr 4
            if (literals == LONG) {
                var extra: Int
                do {
                    require(s < end) { DAMAGED }
                    extra = source[s++].toInt() and 0xFF
                    literals += extra
                } while (extra == 255)
            }
            require(literals <= end - s && literals <= target.size - d) { DAMAGED }
            System.arraycopy(source, s, target, d, literals)
            s += literals
            d += literals
            if (s >= end) break
            require(s + 2 <= end) { DAMAGED }
            val distance = (source[s].toInt() and 0xFF) or ((source[s + 1].toInt() and 0xFF) shl 8)
            s += 2
            var match = token and LONG
            if (match == LONG) {
                var extra: Int
                do {
                    require(s < end) { DAMAGED }
                    extra = source[s++].toInt() and 0xFF
                    match += extra
                } while (extra == 255)
            }
            match += MIN_MATCH
            require(distance in 1..d - window && match <= target.size - d) { DAMAGED }
            var from = d - distance
            repeat(match) { target[d++] = target[from++] }
        }
        return d - at
    }

    /**
     * Apple's LZ4 container (Compression framework, as Procreate's ".lz4" tiles use it): "bv41"
     * blocks of LZ4 that may refer back into earlier blocks, "bvx-" stored blocks, and an end
     * marker. Returns the decoded bytes, at most [size].
     */
    fun decompressApple(
        source: ByteArray,
        size: Int,
    ): ByteArray {
        val out = ByteArray(size)
        var s = 0
        var d = 0
        while (s + 4 <= source.size) {
            val magic = String(source, s, 4, Charsets.ISO_8859_1)
            if (magic == "bv4$" || magic == "bvx$") break
            require(s + 8 <= source.size) { DAMAGED }
            val raw = littleInt(source, s + 4)
            require(raw in 0..size - d) { DAMAGED }
            when (magic) {
                "bv41" -> {
                    require(s + 12 <= source.size) { DAMAGED }
                    val packed = littleInt(source, s + 8)
                    require(packed in 0..source.size - s - 12) { DAMAGED }
                    require(decompress(source, s + 12, packed, out, d, window = 0) == raw) { DAMAGED }
                    s += 12 + packed
                }

                "bvx-" -> {
                    require(raw <= source.size - s - 8) { DAMAGED }
                    System.arraycopy(source, s + 8, out, d, raw)
                    s += 8 + raw
                }

                else -> throw IllegalArgumentException("Unsupported compressed block $magic")
            }
            d += raw
        }
        return if (d == size) out else out.copyOf(d)
    }

    fun isApple(source: ByteArray): Boolean =
        source.size >= 4 && source[0] == 'b'.code.toByte() && source[1] == 'v'.code.toByte() && source[2] in APPLE_KINDS

    private fun littleInt(
        bytes: ByteArray,
        at: Int,
    ): Int =
        (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8) or
            ((bytes[at + 2].toInt() and 0xFF) shl 16) or ((bytes[at + 3].toInt() and 0xFF) shl 24)

    private const val DAMAGED = "The compressed data is damaged"
    private const val LONG = 15
    private const val MIN_MATCH = 4
    private val APPLE_KINDS = byteArrayOf('4'.code.toByte(), 'x'.code.toByte()).toSet()
}
