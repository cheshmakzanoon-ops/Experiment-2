package com.artflow.studio.data.repository

import android.content.Context
import com.artflow.studio.core.render.CustomGrains
import com.artflow.studio.core.render.GrainRegistry
import com.artflow.studio.data.export.ArtflowPackage
import com.artflow.studio.data.local.CanvasDocument
import com.artflow.studio.data.local.CanvasDocumentCodec
import com.artflow.studio.data.local.GrainStorage
import com.artflow.studio.data.local.ProjectStorage
import com.artflow.studio.data.local.dao.ProjectDao
import com.artflow.studio.data.local.database.ArtFlowDatabase
import com.artflow.studio.data.local.jvmGrainImport
import com.artflow.studio.domain.model.layer.Layer
import com.artflow.studio.domain.repository.ProjectRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.any
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.spy
import org.mockito.Mockito.`when`
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class ByteArrayPackageImportTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var cacheDir: File
    private lateinit var filesDir: File
    private lateinit var storage: ProjectStorage
    private lateinit var repository: ProjectRepositoryImpl

    @Before
    fun setUp() {
        cacheDir = temporary.newFolder("cache")
        filesDir = temporary.newFolder("files")
        val context = mock(Context::class.java)
        `when`(context.cacheDir).thenReturn(cacheDir)
        `when`(context.filesDir).thenReturn(filesDir)
        storage = ProjectStorage(context)
        repository = spy(ProjectRepositoryImpl(mock(ProjectDao::class.java), storage, mock(ArtFlowDatabase::class.java)))
    }

    @Test
    fun compatibilityRouteForwardsOneUnexpandedFileAndRemovesItAfterSuccess() =
        runBlocking {
            // A streaming consumer owns validation. Eager package decoding would reject this before the handoff.
            val bytes = byteArrayOf(1, 2, 3, 4)
            var staged: File? = null
            doAnswer { invocation ->
                val file = invocation.getArgument<File>(0)
                staged = file
                assertEquals(cacheDir, file.parentFile)
                assertArrayEquals(bytes, file.readBytes())
                ProjectRepository.ImportedPackage(73)
            }.`when`(repository).importPackageFile(any(File::class.java) ?: File(cacheDir, "stub"))

            assertEquals(73L, repository.importPackage(bytes))
            assertFalse(requireNotNull(staged).exists())
            assertTrue(cacheDir.listFiles().orEmpty().isEmpty())
            assertTrue(filesDir.listFiles().orEmpty().isEmpty())
        }

    @Test
    fun capacityRejectionPrecedesInflatingEvenADamagedGrainAndLeavesNoFiles() =
        runBlocking {
            val files = packageFiles()
            val bytes = storedPackageWithDamagedGrain(files)
            // Reading this package eagerly encounters a CRC failure in the grain payload.
            val eagerFailure = runCatching { ArtflowPackage.read(bytes) }.exceptionOrNull()
            assertTrue(eagerFailure is IllegalArgumentException)
            assertTrue(eagerFailure?.cause is ZipException)
            assertTrue(
                eagerFailure
                    ?.cause
                    ?.message
                    .orEmpty()
                    .contains("CRC"),
            )
            var reads = -1
            val registry = GrainRegistry(retainedByteLimit = TILE_BYTES.toLong(), bufferedByteLimit = 2L * TILE_BYTES)
            doAnswer { invocation ->
                val file = invocation.getArgument<File>(0)
                val counted = CountingZipFile(file)
                try {
                    runBlocking {
                        ArtflowPackage.StreamContents("Capacity", files.keys, counted).use { contents ->
                            jvmGrainImport(registry).use { transaction ->
                                storage.importProjectStream(20, contents, transaction)
                            }
                        }
                    }
                } finally {
                    reads = counted.payloadReads
                }
            }.`when`(repository).importPackageFile(any(File::class.java) ?: File(cacheDir, "stub"))

            val failure = runCatching { repository.importPackage(bytes) }.exceptionOrNull()
            assertEquals(GrainRegistry.CAPACITY_MESSAGE, failure?.message)
            assertEquals(0, reads)
            assertTrue(registry.ids().isEmpty())
            assertFalse(storage.projectDirectoryExists(20))
            assertTrue(cacheDir.listFiles().orEmpty().isEmpty())
            assertFalse(GrainStorage.directory(filesDir).exists())
        }

    @Test
    fun cancellationFromStreamedPublicationIsPreservedAndCleansTheStagedInput() =
        runBlocking {
            val cancelled = CancellationException("Import was cancelled")
            doAnswer { throw cancelled }.`when`(repository).importPackageFile(any(File::class.java) ?: File(cacheDir, "stub"))
            val failure = runCatching { repository.importPackage(byteArrayOf(1)) }.exceptionOrNull()
            assertTrue(failure is CancellationException)
            assertEquals(cancelled.message, failure?.message)
            // JVM coroutine debug recovery copies exceptions and retains the original as cause.
            assertSame(cancelled, failure?.cause ?: failure)
            assertTrue(cacheDir.listFiles().orEmpty().isEmpty())
            assertTrue(filesDir.listFiles().orEmpty().isEmpty())
        }

    @Test
    fun emptyCompatibilityInputIsRejectedBeforeStagingOrPublication() =
        runBlocking {
            val failure = runCatching { repository.importPackage(byteArrayOf()) }.exceptionOrNull()
            assertTrue(failure is IllegalArgumentException)
            assertTrue(cacheDir.listFiles().orEmpty().isEmpty())
            assertTrue(filesDir.listFiles().orEmpty().isEmpty())
        }

    private fun packageFiles(): Map<String, ByteArray> {
        val document =
            CanvasDocument(
                width = 64,
                height = 64,
                dpi = 72,
                backgroundColor = -1,
                activeLayerId = 1,
                nextLayerId = 2,
                layers = listOf(Layer(1, "Ink", 0)),
            )
        return linkedMapOf(
            ArtflowPackage.DOCUMENT to CanvasDocumentCodec.encode(document),
            ArtflowPackage.grainEntryPath("custom-1f") to ByteArray(TILE_BYTES) { 7 },
            ArtflowPackage.grainEntryPath("custom-2f") to ByteArray(TILE_BYTES) { 9 },
        )
    }

    private fun storedPackageWithDamagedGrain(files: Map<String, ByteArray>): ByteArray {
        val output = ByteArrayOutputStream()
        var damagedOffset = -1
        val marker = """{"format":"artflow-package","version":1,"name":"Capacity"}""".toByteArray()
        ZipOutputStream(output).use { zip ->
            (mapOf("artflow-package.json" to marker) + files).forEach { (name, bytes) ->
                val checksum = CRC32()
                checksum.update(bytes)
                val entry =
                    ZipEntry(name).apply {
                        method = ZipEntry.STORED
                        size = bytes.size.toLong()
                        compressedSize = size
                        crc = checksum.value
                    }
                zip.putNextEntry(entry)
                if (name.startsWith("grains/") && damagedOffset < 0) damagedOffset = output.size()
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return output.toByteArray().also { it[damagedOffset] = (it[damagedOffset].toInt() xor 1).toByte() }
    }

    private class CountingZipFile(
        file: File,
    ) : ZipFile(file) {
        var payloadReads = 0

        override fun getInputStream(entry: ZipEntry): InputStream {
            payloadReads++
            return super.getInputStream(entry)
        }
    }

    private companion object {
        const val TILE_BYTES = CustomGrains.TILE * CustomGrains.TILE
    }
}
