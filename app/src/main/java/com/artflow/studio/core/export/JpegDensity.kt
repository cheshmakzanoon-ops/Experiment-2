package com.artflow.studio.core.export

/** Writes JFIF density without touching compressed image samples. */
object JpegDensity {
    fun withDpi(
        jpeg: ByteArray,
        dpi: Int,
    ): ByteArray {
        require(dpi in 1..32_767) { "Invalid JPEG resolution" }
        require(jpeg.size >= 4 && jpeg[0] == 0xFF.toByte() && jpeg[1] == 0xD8.toByte()) { "Invalid JPEG header" }
        var end = 2
        val hasApp0 = jpeg.size >= 18 && jpeg[2] == 0xFF.toByte() && jpeg[3] == 0xE0.toByte()
        if (hasApp0 && jpeg.copyOfRange(6, 11).contentEquals(byteArrayOf(74, 70, 73, 70, 0))) {
            val length = ((jpeg[4].toInt() and 255) shl 8) or (jpeg[5].toInt() and 255)
            require(length >= 16 && length + 4 <= jpeg.size) { "Invalid JFIF segment" }
            end = length + 4
        }
        val jfif =
            byteArrayOf(
                0xFF.toByte(),
                0xE0.toByte(),
                0,
                16,
                74,
                70,
                73,
                70,
                0,
                1,
                2,
                1,
                (dpi ushr 8).toByte(),
                dpi.toByte(),
                (dpi ushr 8).toByte(),
                dpi.toByte(),
                0,
                0,
            )
        return jpeg.copyOfRange(0, 2) + jfif + jpeg.copyOfRange(end, jpeg.size)
    }
}
