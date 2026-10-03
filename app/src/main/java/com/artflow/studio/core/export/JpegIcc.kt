package com.artflow.studio.core.export

/** Embeds an ICC profile in a JPEG as APP2 `ICC_PROFILE` segments, after any JFIF header. */
object JpegIcc {
    fun withProfile(
        jpeg: ByteArray,
        icc: ByteArray,
    ): ByteArray {
        require(jpeg.size >= 4 && jpeg[0] == 0xFF.toByte() && jpeg[1] == 0xD8.toByte()) { "Invalid JPEG header" }
        var insertAt = 2
        if (jpeg[2] == 0xFF.toByte() && jpeg[3] == 0xE0.toByte()) {
            insertAt += 2 + (((jpeg[4].toInt() and 255) shl 8) or (jpeg[5].toInt() and 255))
        }
        require(insertAt <= jpeg.size) { "Invalid JFIF segment" }
        val chunks = icc.toList().chunked(MAX_CHUNK)
        val segments =
            chunks.mapIndexed { index, part ->
                val length = 2 + SIGNATURE.size + 2 + part.size
                byteArrayOf(0xFF.toByte(), 0xE2.toByte(), (length ushr 8).toByte(), length.toByte()) +
                    SIGNATURE +
                    byteArrayOf((index + 1).toByte(), chunks.size.toByte()) +
                    part.toByteArray()
            }
        val profile = segments.fold(ByteArray(0)) { joined, segment -> joined + segment }
        return jpeg.copyOfRange(0, insertAt) + profile + jpeg.copyOfRange(insertAt, jpeg.size)
    }

    private val SIGNATURE = "ICC_PROFILE\u0000".toByteArray(Charsets.US_ASCII)
    private const val MAX_CHUNK = 65_519
}
