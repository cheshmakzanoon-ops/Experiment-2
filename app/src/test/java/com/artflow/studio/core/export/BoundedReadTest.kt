package com.artflow.studio.core.export

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

class BoundedReadTest {
    /** A stream that never ends, counting the bytes it has been asked for. */
    private class Endless : InputStream() {
        var produced = 0L

        override fun read(): Int {
            produced++
            return 0
        }

        override fun read(
            b: ByteArray,
            off: Int,
            len: Int,
        ): Int {
            b.fill(0, off, off + len)
            produced += len
            return len
        }
    }

    @Test
    fun aStreamThatFitsIsReturnedWhole() {
        val bytes = ByteArray(200_000) { (it % 251).toByte() }
        assertArrayEquals(bytes, ByteArrayInputStream(bytes).readAtMost(bytes.size.toLong()))
    }

    @Test
    fun anEmptyStreamGivesNoBytes() {
        assertArrayEquals(ByteArray(0), ByteArrayInputStream(ByteArray(0)).readAtMost(10))
    }

    @Test
    fun aStreamOneByteOverTheLimitIsRefused() {
        val bytes = ByteArray(100_001)
        assertNull(ByteArrayInputStream(bytes).readAtMost(100_000))
    }

    @Test
    fun anEndlessStreamIsStoppedNearTheLimitInsteadOfBeingReadToEnd() {
        val source = Endless()
        assertNull(source.readAtMost(1_000))
        // The read stops at the first chunk that crosses the limit, so it never runs on for long.
        assertTrue("produced ${source.produced} bytes", source.produced <= 1_000 + 64 * 1024)
    }
}
