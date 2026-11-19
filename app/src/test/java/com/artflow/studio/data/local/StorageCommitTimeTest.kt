package com.artflow.studio.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException

class StorageCommitTimeTest {
    @Test
    fun `coarse timestamp rounding still preserves strict order`() {
        var modified = 1000L
        val coarse =
            object : File("coarse-clock") {
                override fun lastModified() = modified

                override fun setLastModified(time: Long): Boolean {
                    modified = time / 1000 * 1000
                    return true
                }
            }

        StorageCommitTime.ensureAfter(coarse, 1234L)

        assertEquals(2000L, modified)
        assertTrue(modified > 1234L)
    }

    @Test
    fun `a refused timestamp update is reported`() {
        val refused =
            object : File("refused-clock") {
                override fun lastModified() = 1000L

                override fun setLastModified(time: Long) = false
            }

        assertTrue(runCatching { StorageCommitTime.ensureAfter(refused, 1000L) }.exceptionOrNull() is IOException)
    }

    @Test
    fun `reported success with unchanged timestamp is rejected`() {
        val unchanged =
            object : File("unchanged-clock") {
                override fun lastModified() = 1000L

                override fun setLastModified(time: Long) = true
            }

        assertTrue(runCatching { StorageCommitTime.ensureAfter(unchanged, 1000L) }.exceptionOrNull() is IOException)
    }

    @Test
    fun `already ordered timestamps are not rewritten`() {
        val ordered =
            object : File("ordered-clock") {
                override fun lastModified() = 2000L

                override fun setLastModified(time: Long): Boolean = error("No timestamp update is required")
            }

        StorageCommitTime.ensureAfter(ordered, 1000L)
    }
}
