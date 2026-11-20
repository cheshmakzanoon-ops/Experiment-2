package com.artflow.studio.data.export

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.util.IdentityHashMap

/** Real staged input and cleanup refusal, with failure delivery across both consumer and owner dispatchers. */
class StagedCleanupDeliveryTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun consumerFailureAndCleanupDiagnosticSurviveDispatcherDelivery() = runBlocking { verifyDelivery(IOException("Consumer failed")) }

    @Test
    fun consumerCancellationAndCleanupDiagnosticSurviveDispatcherDelivery() =
        runBlocking { verifyDelivery(CancellationException("Consumer cancelled")) }

    private suspend fun verifyDelivery(original: Exception) {
        val unchanged = File(temporary.root, "existing-artwork").apply { writeBytes(byteArrayOf(7, 19)) }
        var blocked: File? = null
        var sourceClosed = false
        val source =
            object : ByteArrayInputStream(byteArrayOf(1)) {
                override fun close() {
                    sourceClosed = true
                    super.close()
                }
            }
        val failure =
            runCatching {
                withContext(Dispatchers.IO) {
                    StagedImport.use(temporary.root, { source }) { file ->
                        assertTrue(sourceClosed)
                        assertArrayEquals(byteArrayOf(1), file.readBytes())
                        check(file.delete() && file.mkdir())
                        blocked = File(file, "cleanup-blocker").apply { writeBytes(byteArrayOf(11)) }
                        withContext(Dispatchers.Default) { throw original }
                    }
                }
            }.exceptionOrNull()

        assertEquals(original.javaClass, failure?.javaClass)
        assertEquals(original.message, failure?.message)
        val causes = causes(requireNotNull(failure))
        assertTrue(causes.any { it === original })
        assertTrue(
            "The cleanup diagnostic must remain reachable after dispatcher delivery",
            causes.flatMap { it.suppressed.asList() }.any {
                it is IllegalStateException && it.message == "Cannot remove the temporary import file"
            },
        )
        assertTrue(sourceClosed)
        assertArrayEquals(byteArrayOf(11), requireNotNull(blocked).readBytes())
        assertArrayEquals(byteArrayOf(7, 19), unchanged.readBytes())
    }

    private fun causes(failure: Throwable): List<Throwable> {
        val visited = IdentityHashMap<Throwable, Unit>()
        val result = mutableListOf<Throwable>()
        var current: Throwable? = failure
        while (current != null && visited.put(current, Unit) == null) {
            result.add(current)
            current = current.cause
        }
        return result
    }
}
