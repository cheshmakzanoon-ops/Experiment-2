package com.artflow.studio.data.local

import android.content.Context
import com.artflow.studio.core.render.CustomGrains
import com.artflow.studio.core.render.GrainRegistry
import com.artflow.studio.data.export.ArtflowPackage
import com.artflow.studio.domain.model.layer.Layer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.io.File
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

class ProjectStorageGrainBudgetTest {
    @get:Rule val temporary = TemporaryFolder()

    private lateinit var filesDir: File
    private lateinit var storage: ProjectStorage

    @Before
    fun setUp() {
        filesDir = temporary.newFolder("files")
        val context = mock(Context::class.java)
        `when`(context.filesDir).thenReturn(filesDir)
        storage = ProjectStorage(context)
    }

    @Test
    fun streamedBatchIsAdmittedBeforeOpeningAnyPayloadOrCreatingProjectFiles() =
        runBlocking {
            val registry = registry()
            val files = packageFiles(linkedMapOf(FIRST to byteArrayOf(1), SECOND to byteArrayOf(2)))
            val archive = temporary.newFile("capacity.artflow").apply { writeBytes(ArtflowPackage.write("Capacity", files)) }
            val counted = CountingZipFile(archive)
            ArtflowPackage.StreamContents("Capacity", files.keys, counted).use { contents ->
                jvmGrainImport(registry).use { transaction ->
                    val failure = runCatching { storage.importProjectStream(PROJECT, contents, transaction) }.exceptionOrNull()
                    assertTrue(failure is IllegalArgumentException)
                    assertEquals(GrainRegistry.CAPACITY_MESSAGE, failure?.message)
                }
                assertEquals(0, counted.payloadReads)
            }
            assertFalse(storage.projectDirectoryExists(PROJECT))
            assertFalse(GrainStorage.directory(filesDir).exists())
            assertEquals(emptyList<String>(), registry.ids())
        }

    @Test
    fun inMemoryPackageCannotPublishFilesWhenTheGrainBatchExceedsCapacity() =
        runBlocking {
            val registry = registry()
            jvmGrainImport(registry).use { transaction ->
                val files = packageFiles(linkedMapOf(FIRST to byteArrayOf(1), SECOND to byteArrayOf(2)))
                val failure = runCatching { storage.importProject(PROJECT, files, transaction) }.exceptionOrNull()
                assertEquals(GrainRegistry.CAPACITY_MESSAGE, failure?.message)
            }
            assertFalse(storage.projectDirectoryExists(PROJECT))
            assertFalse(GrainStorage.directory(filesDir).exists())
            assertEquals(emptyList<String>(), registry.ids())
        }

    @Test
    fun anAdmittedStreamedTextureStaysProvisionalUntilTheCallerCommits() =
        runBlocking {
            val registry = registry()
            val bytes = ByteArray(TILE_BYTES) { (it * 13).toByte() }
            val files = packageFiles(mapOf(FIRST to bytes))
            val archive = temporary.newFile("accepted.artflow").apply { writeBytes(ArtflowPackage.write("Accepted", files)) }
            val grainFile = requireNotNull(GrainStorage.fileFor(GrainStorage.directory(filesDir), FIRST))
            ArtflowPackage.openFile(archive).use { contents ->
                jvmGrainImport(registry).use { transaction ->
                    storage.importProjectStream(PROJECT, contents, transaction)
                    assertArrayEquals(bytes, registry.get(FIRST)!!.values)
                    assertArrayEquals(bytes, grainFile.readBytes())
                }
            }
            assertNull(registry.get(FIRST))
            assertFalse(grainFile.exists())
            jvmGrainImport(registry).use { next -> next.prepare(listOf(SECOND)) }
        }

    @Test
    fun aNoncanonicalGrainEntryIsRejectedBeforeProjectPublication() =
        runBlocking {
            val registry = registry()
            val files = packageFiles(emptyMap()) + ("grains/$FIRST" to ByteArray(TILE_BYTES))
            jvmGrainImport(registry).use { transaction ->
                val failure = runCatching { storage.importProject(PROJECT, files, transaction) }.exceptionOrNull()
                assertTrue(failure is IllegalArgumentException)
                assertTrue(failure?.message.orEmpty().contains("Invalid brush grain"))
                transaction.prepare(listOf(SECOND))
            }
            assertFalse(storage.projectDirectoryExists(PROJECT))
            assertEquals(emptyList<String>(), registry.ids())
        }

    private fun registry(): GrainRegistry = GrainRegistry(retainedByteLimit = TILE_BYTES.toLong(), bufferedByteLimit = TILE_BYTES * 2L)

    private fun packageFiles(grains: Map<String, ByteArray>): Map<String, ByteArray> {
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
        return mapOf(ArtflowPackage.DOCUMENT to CanvasDocumentCodec.encode(document)) +
            grains.mapKeys { (id, _) -> ArtflowPackage.grainEntryPath(id) }
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
        const val PROJECT = 20L
        const val FIRST = "custom-b30001"
        const val SECOND = "custom-b30002"
        const val TILE_BYTES = CustomGrains.TILE * CustomGrains.TILE
    }
}
