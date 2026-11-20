package com.artflow.studio.core.export

import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Reads this stream to its end, or returns null once it has produced more than [limit] bytes.
 *
 * An archive's declared entry size is only a claim: a deflated entry can expand far past it. The
 * bytes the stream actually yields are what get counted, so the read stops before memory does.
 */
internal fun InputStream.readAtMost(limit: Long): ByteArray? = readAtMost(limit) {}

/** Checks the owner between provider calls, including the single-byte fallback for zero reads. */
internal fun InputStream.readAtMost(
    limit: Long,
    checkActive: () -> Unit,
): ByteArray? {
    require(limit >= 0) { "The input limit must not be negative" }
    checkActive()
    val buffer = ByteArray(minOf(limit, BUFFER_BYTES - 1L).toInt() + 1)
    val out = ByteArrayOutputStream(minOf(limit, buffer.size.toLong()).toInt())
    var total = 0L
    while (true) {
        checkActive()
        val remaining = limit - total
        val requested = if (remaining >= buffer.size) buffer.size else remaining.toInt() + 1
        val read = readWithProgress(buffer, requested, checkActive)
        if (read < 0) return out.toByteArray()
        if (read > remaining) return null
        total += read
        out.write(buffer, 0, read)
    }
}

/** A provider that returns zero still makes one bounded attempt at progress. */
internal fun InputStream.readWithProgress(
    buffer: ByteArray,
    requested: Int,
    checkActive: () -> Unit,
): Int {
    checkActive()
    val read = read(buffer, 0, requested)
    checkActive()
    if (read != 0) return read
    val value = read()
    checkActive()
    if (value < 0) return -1
    buffer[0] = value.toByte()
    return 1
}

private const val BUFFER_BYTES = 64 * 1024
