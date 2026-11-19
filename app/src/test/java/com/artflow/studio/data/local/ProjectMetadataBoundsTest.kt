package com.artflow.studio.data.local

import android.content.Context
import com.artflow.studio.core.pixels.SelectionMask
import com.artflow.studio.core.pixels.SelectionMaskCodec
import com.artflow.studio.data.export.ArtflowPackage
import com.artflow.studio.domain.model.layer.Layer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.util.Random

class ProjectMetadataBoundsTest {
    @get:Rule val temporary = TemporaryFolder()

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = mock(Context::class.java)
        `when`(context.filesDir).thenReturn(temporary.newFolder("files"))
    }

    @Test
    fun `oversized explicit save preserves both committed manifests`() =
        runBlocking {
            assertOversizedSavePreservesCommittedMetadata(autosave = false)
        }

    @Test
    fun `oversized autosave preserves both committed manifests`() =
        runBlocking {
            assertOversizedSavePreservesCommittedMetadata(autosave = true)
        }

    @Test
    fun `document at byte limit can be saved autosaved reopened and imported`() =
        runBlocking {
            val document = documentWithSelection()
            val bytes = CanvasDocumentCodec.encode(document)
            val storage = ProjectStorage(context, documentByteLimit = bytes.size.toLong())

            storage.saveDocument(PROJECT, document)
            storage.saveAutosave(PROJECT, document)

            assertArrayEquals(bytes, storage.documentFile(PROJECT).readBytes())
            assertArrayEquals(bytes, storage.autosaveFile(PROJECT).readBytes())
            assertEquals(document, storage.loadDocument(PROJECT))
            assertEquals(document, storage.loadAutosave(PROJECT))
            assertEquals(document, storage.decodePackagedDocument(bytes))
            assertEquals(document, storage.importProject(IMPORTED_PROJECT, mapOf(ProjectStorage.DOCUMENT_NAME to bytes)))
        }

    @Test
    fun `same byte limit rejects oversized ordinary autosave and packaged metadata`() =
        runBlocking {
            val bytes = CanvasDocumentCodec.encode(documentWithSelection())
            val storage = ProjectStorage(context, documentByteLimit = bytes.size.toLong() - 1)
            // Deliberately install an unreadable fixture without using the guarded save API.
            storage.documentFile(PROJECT).writeBytes(bytes)
            storage.autosaveFile(PROJECT).writeBytes(bytes)

            assertTrue(runCatching { storage.loadDocument(PROJECT) }.exceptionOrNull() is IllegalArgumentException)
            assertTrue(runCatching { storage.loadAutosave(PROJECT) }.exceptionOrNull() is IllegalArgumentException)
            assertTrue(runCatching { storage.decodePackagedDocument(bytes) }.exceptionOrNull() is IllegalArgumentException)
            assertTrue(
                runCatching {
                    storage.importProject(IMPORTED_PROJECT, mapOf(ProjectStorage.DOCUMENT_NAME to bytes))
                }.exceptionOrNull() is IllegalArgumentException,
            )
            assertArrayEquals(bytes, storage.documentFile(PROJECT).readBytes())
            assertArrayEquals(bytes, storage.autosaveFile(PROJECT).readBytes())
        }

    @Test
    fun `invalid headers cannot replace either committed manifest`() =
        runBlocking {
            val storage = ProjectStorage(context)
            val document = document()
            storage.saveDocument(PROJECT, document)
            storage.saveAutosave(PROJECT, document)
            val bytes = CanvasDocumentCodec.encode(document)
            val invalid =
                listOf(
                    document.copy(version = CanvasDocument.CURRENT_VERSION + 1),
                    document.copy(width = 0),
                    document.copy(height = 0),
                    document.copy(dpi = 0),
                )

            invalid.forEach { candidate ->
                assertTrue(runCatching { storage.saveDocument(PROJECT, candidate) }.exceptionOrNull() is IllegalArgumentException)
                assertTrue(runCatching { storage.saveAutosave(PROJECT, candidate) }.exceptionOrNull() is IllegalArgumentException)
                assertArrayEquals(bytes, storage.documentFile(PROJECT).readBytes())
                assertArrayEquals(bytes, storage.autosaveFile(PROJECT).readBytes())
            }
        }

    @Test
    fun `invalid structure cannot replace either committed manifest`() =
        runBlocking {
            val storage = ProjectStorage(context)
            val document = document()
            storage.saveDocument(PROJECT, document)
            storage.saveAutosave(PROJECT, document)
            val bytes = CanvasDocumentCodec.encode(document)
            val invalid = document.copy(layers = emptyList())

            assertTrue(runCatching { storage.saveDocument(PROJECT, invalid) }.exceptionOrNull() is IllegalArgumentException)
            assertTrue(runCatching { storage.saveAutosave(PROJECT, invalid) }.exceptionOrNull() is IllegalArgumentException)
            assertArrayEquals(bytes, storage.documentFile(PROJECT).readBytes())
            assertArrayEquals(bytes, storage.autosaveFile(PROJECT).readBytes())
        }

    @Test
    fun `injected metadata limit can only narrow the production limit`() {
        assertTrue(
            runCatching {
                ProjectStorage(context, documentByteLimit = ArtflowPackage.MAX_DOCUMENT_BYTES + 1)
            }.exceptionOrNull() is IllegalArgumentException,
        )
        assertTrue(runCatching { ProjectStorage(context, documentByteLimit = 0) }.exceptionOrNull() is IllegalArgumentException)
    }

    private suspend fun assertOversizedSavePreservesCommittedMetadata(autosave: Boolean) {
        val oversized = documentWithSelection()
        val storage = ProjectStorage(context, documentByteLimit = CanvasDocumentCodec.encode(oversized).size.toLong() - 1)
        val saved = document()
        val recovery = saved.copy(trackedMs = 10)
        storage.saveDocument(PROJECT, saved)
        storage.saveAutosave(PROJECT, recovery)
        val savedFile = storage.documentFile(PROJECT)
        val recoveryFile = storage.autosaveFile(PROJECT)
        val savedBytes = savedFile.readBytes()
        val recoveryBytes = recoveryFile.readBytes()
        val savedTime = savedFile.lastModified()
        val recoveryTime = recoveryFile.lastModified()

        val failure =
            runCatching {
                if (autosave) storage.saveAutosave(PROJECT, oversized) else storage.saveDocument(PROJECT, oversized)
            }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertArrayEquals(savedBytes, savedFile.readBytes())
        assertArrayEquals(recoveryBytes, recoveryFile.readBytes())
        assertEquals(savedTime, savedFile.lastModified())
        assertEquals(recoveryTime, recoveryFile.lastModified())
        assertTrue(storage.hasUnsavedRecovery(PROJECT))
        assertEquals(saved, storage.loadDocument(PROJECT))
        assertEquals(recovery, storage.loadAutosave(PROJECT))
        assertEquals(
            setOf(ProjectStorage.DOCUMENT_NAME, ProjectStorage.AUTOSAVE_NAME),
            storage
                .projectDir(PROJECT)
                .listFiles()!!
                .map { it.name }
                .toSet(),
        )
    }

    private fun documentWithSelection(): CanvasDocument {
        // A real, poorly compressible saved selection exercises metadata growth without allocating
        // a production-sized 32 MiB document in each test. The layer name also includes UTF-8 text.
        val coverage = ByteArray(64 * 64).also { Random(7L).nextBytes(it) }
        val encoded = SelectionMaskCodec.encode(SelectionMask(64, 64, coverage))
        return document().copy(savedSelections = listOf(encoded))
    }

    private fun document() =
        CanvasDocument(
            width = 64,
            height = 64,
            dpi = 72,
            backgroundColor = -1,
            activeLayerId = 1,
            nextLayerId = 2,
            layers = listOf(Layer(id = 1, name = "Artwork 🎨", index = 0)),
            savedAt = 1_700_000_000_000L,
        )

    private companion object {
        const val PROJECT = 7L
        const val IMPORTED_PROJECT = 8L
    }
}
