package com.artflow.studio.data.local

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.io.File
import java.security.MessageDigest

/** Uses real source, atomic staging and destination files; no encoded-file-sized test arrays. */
class EncodedExportPublicationTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var storage: ProjectStorage
    private val projectId = 37L

    @Before
    fun setUp() {
        val context = mock(Context::class.java)
        `when`(context.filesDir).thenReturn(temporary.newFolder("files"))
        storage = ProjectStorage(context)
    }

    @Test
    fun publishesManyChunksExactlyAndKeepsSourceOwnedByTheEncoder() =
        runBlocking {
            val source = temporary.newFile("encoded.mp4")
            val block = ByteArray(8192) { (it * 31).toByte() }
            source.outputStream().use { out ->
                repeat(1025) { out.write(block) }
                out.write(byteArrayOf(11, 21, 31))
            }
            val expected = digest(source)
            storage.saveExport(projectId, "Video.mp4", byteArrayOf(1, 2, 3))

            val result = storage.saveExportFromFile(projectId, "Video.mp4", source)

            assertEquals(8_396_803L, result.length())
            assertEquals(source.length(), result.length())
            assertArrayEquals(expected, digest(result))
            assertArrayEquals(expected, digest(source))
            assertEquals(listOf(result.name), storage.exportsDir(projectId).list()!!.toList())
        }

    @Test
    fun changedOrEmptyEncodedFilesCannotReplaceThePreviousExport() =
        runBlocking {
            val prior = byteArrayOf(9, 8, 7, 6)
            val target = storage.saveExport(projectId, "Video.mp4", prior)
            val source = temporary.newFile("encoded.mp4").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
            for (reportedSize in listOf(0L, 3L, 5L)) {
                val changingSource =
                    object : File(source.path) {
                        override fun length(): Long = reportedSize
                    }
                val result = runCatching { storage.saveExportFromFile(projectId, target.name, changingSource) }
                assertTrue("Source length $reportedSize must be refused", result.isFailure)
                assertArrayEquals(prior, target.readBytes())
                assertEquals(listOf(target.name), storage.exportsDir(projectId).list()!!.toList())
            }
            assertArrayEquals(byteArrayOf(1, 2, 3, 4), source.readBytes())
        }

    @Test
    fun cancellationAfterSourceAdmissionPreservesPreviousExportAndRemovesStaging() =
        runBlocking {
            val prior = byteArrayOf(9, 8, 7, 6)
            val target = storage.saveExport(projectId, "Video.mp4", prior)
            val source = temporary.newFile("encoded.mp4").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
            lateinit var operation: Deferred<File>
            val cancellingSource =
                object : File(source.path) {
                    override fun length(): Long {
                        operation.cancel(CancellationException("Leave editor during publication"))
                        return super.length()
                    }
                }
            operation = async(start = CoroutineStart.LAZY) { storage.saveExportFromFile(projectId, target.name, cancellingSource) }
            operation.start()
            val failure = runCatching { operation.await() }.exceptionOrNull()

            assertTrue(failure is CancellationException)
            assertArrayEquals(prior, target.readBytes())
            assertEquals(listOf(target.name), storage.exportsDir(projectId).list()!!.toList())
            assertTrue(source.isFile)
        }

    @Test
    fun invalidOutputNameCannotPublishOutsideExports() =
        runBlocking {
            val source = temporary.newFile("encoded.mp4").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            for (name in listOf("../escaped.mp4", "/absolute.mp4", "..", "", "bad\u0000name.mp4")) {
                assertTrue(runCatching { storage.saveExportFromFile(projectId, name, source) }.isFailure)
            }
            assertFalse(File(storage.exportsDir(projectId).parentFile, "escaped.mp4").exists())
            assertTrue(storage.listExports(projectId).isEmpty())
        }

    private fun digest(file: File): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest()
    }
}
