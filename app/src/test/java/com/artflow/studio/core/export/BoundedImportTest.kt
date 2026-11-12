package com.artflow.studio.core.export

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * Validates that streams exceeding byte limits are cut off safely without reading unbounded bytes or allocating excessive memory.
 */
class BoundedImportTest {
    @Test
    fun streamWithinLimitIsReadCompletely() {
        val payload = ByteArray(1024) { (it and 0xFF).toByte() }
        val stream = ByteArrayInputStream(payload)
        val read = stream.readAtMost(2048)
        assertNotNull(read)
        assertArrayEquals(payload, read)
    }

    @Test
    fun streamExactlyAtLimitIsReadCompletely() {
        val payload = ByteArray(512) { (it and 0xFF).toByte() }
        val stream = ByteArrayInputStream(payload)
        val read = stream.readAtMost(512)
        assertNotNull(read)
        assertArrayEquals(payload, read)
    }

    @Test
    fun oversizedStreamReturnsNullWithoutConsumingEntireStream() {
        var totalYielded = 0L
        val infiniteStream =
            object : InputStream() {
                override fun read(): Int {
                    totalYielded++
                    return 42
                }

                override fun read(
                    b: ByteArray,
                    off: Int,
                    len: Int,
                ): Int {
                    val count = minOf(len, 64 * 1024)
                    for (i in 0 until count) {
                        b[off + i] = 42
                    }
                    totalYielded += count
                    return count
                }
            }

        val limit = 1024L * 1024L
        val read = infiniteStream.readAtMost(limit)
        assertNull("Oversized stream must return null", read)
        assertTrue(
            "Stream reading must halt as soon as limit is exceeded, yielded=$totalYielded",
            totalYielded <= limit + 128 * 1024,
        )
    }

    private fun assertTrue(
        message: String,
        condition: Boolean,
    ) {
        if (!condition) fail(message)
    }
}
