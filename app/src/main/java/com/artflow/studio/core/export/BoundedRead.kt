package com.artflow.studio.core.export

import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Reads this stream to its end, or returns null once it has produced more than [limit] bytes.
 *
 * An archive's declared entry size is only a claim: a deflated entry can expand far past it. The
 * bytes the stream actually yields are what get counted, so the read stops before memory does.
 */
internal fun InputStream.readAtMost(limit: Long): ByteArray? {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(BUFFER_BYTES)
    var total = 0L
    while (true) {
        val read = read(buffer)
        if (read < 0) return out.toByteArray()
        total += read
        if (total > limit) return null
        out.write(buffer, 0, read)
    }
}

private const val BUFFER_BYTES = 64 * 1024
