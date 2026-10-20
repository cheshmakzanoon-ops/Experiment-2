package com.artflow.studio.data

import android.content.Context
import com.artflow.studio.data.local.ProjectStorage
import com.artflow.studio.data.repository.canvas.CanvasRepositoryImpl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.io.File
import java.nio.file.Files

/**
 * A damaged saved document or recovery snapshot must be refused while the artwork already open
 * stays exactly as it was: same canvas, same layers, same history.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CorruptDocumentLoadTest {
    private lateinit var root: File
    private lateinit var storage: ProjectStorage
    private lateinit var repository: CanvasRepositoryImpl

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        root = Files.createTempDirectory("artflow-load").toFile()
        val context = mock(Context::class.java)
        `when`(context.filesDir).thenReturn(root)
        storage = ProjectStorage(context)
        repository = CanvasRepositoryImpl(storage)
    }

    @After
    fun tearDown() {
        repository.dispose()
        Dispatchers.resetMain()
        root.deleteRecursively()
    }

    private fun truncated(file: File) {
        val bytes = file.readBytes()
        file.writeBytes(bytes.copyOf(bytes.size / 2))
    }

    @Test
    fun aDamagedSavedDocumentDoesNotReplaceTheOpenArtwork() =
        runTest {
            repository.loadOrCreate(PROJECT, 8, 6, 72)
            repository.saveCanvas(PROJECT)
            val layers = repository.getAllLayers().map { it.id to it.name }
            val undoDepth = repository.undoDepth

            truncated(storage.documentFile(PROJECT))
            val error = runCatching { repository.loadOrCreate(PROJECT, 32, 32, 300) }.exceptionOrNull()

            assertTrue("a truncated document must be refused, not replaced", error is IllegalArgumentException)
            assertEquals(8, repository.getCanvasSize().width)
            assertEquals(6, repository.getCanvasSize().height)
            assertEquals(layers, repository.getAllLayers().map { it.id to it.name })
            assertEquals(undoDepth, repository.undoDepth)
        }

    @Test
    fun aDamagedRecoverySnapshotIsRefusedAndTheOpenArtworkStays() =
        runTest {
            repository.loadOrCreate(PROJECT, 8, 6, 72)
            repository.autosave(PROJECT)
            val layers = repository.getAllLayers().map { it.id to it.name }

            truncated(storage.autosaveFile(PROJECT))
            val error = runCatching { repository.recoverAutosave(PROJECT) }.exceptionOrNull()

            assertTrue("a truncated snapshot must be refused", error is IllegalArgumentException)
            assertEquals(layers, repository.getAllLayers().map { it.id to it.name })
            assertEquals(8, repository.getCanvasSize().width)
        }

    @Test
    fun aMissingDocumentIsNotTreatedAsCorrupt() =
        runTest {
            val loaded = repository.loadCanvas(PROJECT)

            assertTrue(loaded == null)
        }

    private companion object {
        const val PROJECT = 7L
    }
}
