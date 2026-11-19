package com.artflow.studio.data.local

import android.content.Context
import com.artflow.studio.domain.model.layer.Layer
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
import java.io.IOException

class ProjectRecoveryOrderingTest {
    @get:Rule val temporary = TemporaryFolder()

    private lateinit var storage: ProjectStorage

    @Before
    fun setUp() {
        val context = mock(Context::class.java)
        `when`(context.filesDir).thenReturn(temporary.newFolder("files"))
        storage = ProjectStorage(context)
    }

    @Test
    fun `explicit commit supersedes a future dated autosave before cleanup`() =
        runBlocking {
            storage.saveDocument(PROJECT, document("Base"))
            val saved = storage.documentFile(PROJECT)
            // This also occurs when wall-clock time moves backward between saves.
            assertTrue(saved.setLastModified(System.currentTimeMillis() + FUTURE_OFFSET_MS))
            storage.saveAutosave(PROJECT, document("Older recovery"))
            val recovery = storage.autosaveFile(PROJECT)
            val recoveryBytes = recovery.readBytes()
            val recoveryTime = recovery.lastModified()
            assertTrue(storage.hasUnsavedRecovery(PROJECT))

            val committed = document("Newer explicit save")
            storage.saveDocument(PROJECT, committed)
            // Model the exact durable state after commit and before discardAutosave: process death
            // or cancellation at the dispatcher return must not offer the older recovery as newer.
            assertArrayEquals(CanvasDocumentCodec.encode(committed), saved.readBytes())
            assertArrayEquals(recoveryBytes, recovery.readBytes())
            assertTrue(saved.lastModified() > recoveryTime)
            assertFalse(storage.hasUnsavedRecovery(PROJECT))
            assertEquals(
                "Newer explicit save",
                storage
                    .loadDocument(PROJECT)!!
                    .layers
                    .single()
                    .name,
            )
        }

    @Test
    fun `autosave is immediately newer than a future dated saved document`() =
        runBlocking {
            val original = document("Saved artwork")
            storage.saveDocument(PROJECT, original)
            val saved = storage.documentFile(PROJECT)
            val savedBytes = saved.readBytes()
            assertTrue(saved.setLastModified(System.currentTimeMillis() + FUTURE_OFFSET_MS))
            val recovery = document("Recoverable artwork")

            val committed = storage.saveAutosave(PROJECT, recovery)

            assertTrue(committed.lastModified() > saved.lastModified())
            assertArrayEquals(savedBytes, saved.readBytes())
            assertArrayEquals(CanvasDocumentCodec.encode(recovery), committed.readBytes())
            assertTrue(storage.hasUnsavedRecovery(PROJECT))
        }

    @Test
    fun `failed timestamp ordering cannot replace committed bytes`() =
        runBlocking {
            storage.saveDocument(PROJECT, document("Keep this artwork"))
            val target = storage.documentFile(PROJECT)
            val original = target.readBytes()
            val originalTime = target.lastModified()
            val failure =
                runCatching {
                    storage.writeAtomically(target, Long.MAX_VALUE) { it.write(byteArrayOf(1, 2, 3)) }
                }.exceptionOrNull()

            assertTrue(failure is IOException)
            assertArrayEquals(original, target.readBytes())
            assertEquals(originalTime, target.lastModified())
            assertEquals(listOf(ProjectStorage.DOCUMENT_NAME), target.parentFile!!.listFiles()!!.map { it.name })
        }

    @Test
    fun `copy preserves active recovery without post commit timestamp edits`() =
        runBlocking {
            storage.saveDocument(PROJECT, document("Saved"))
            storage.saveAutosave(PROJECT, document("Recoverable"))

            storage.copyProject(PROJECT, COPY)

            assertTrue(storage.hasUnsavedRecovery(COPY))
            assertEquals(
                "Saved",
                storage
                    .loadDocument(COPY)!!
                    .layers
                    .single()
                    .name,
            )
            assertEquals(
                "Recoverable",
                storage
                    .loadAutosave(COPY)!!
                    .layers
                    .single()
                    .name,
            )
        }

    @Test
    fun `copy preserves a retained stale recovery as stale`() =
        runBlocking {
            storage.saveDocument(PROJECT, document("Saved"))
            storage.saveAutosave(PROJECT, document("Older recovery"))
            // Legacy on-disk ordering must survive duplication, including a retained old file.
            val saved = storage.documentFile(PROJECT)
            assertTrue(storage.autosaveFile(PROJECT).setLastModified(saved.lastModified() - 1000L))
            assertFalse(storage.hasUnsavedRecovery(PROJECT))

            storage.copyProject(PROJECT, COPY)

            assertFalse(storage.hasUnsavedRecovery(COPY))
            assertEquals(
                "Saved",
                storage
                    .loadDocument(COPY)!!
                    .layers
                    .single()
                    .name,
            )
            assertEquals(
                "Older recovery",
                storage
                    .loadAutosave(COPY)!!
                    .layers
                    .single()
                    .name,
            )
            assertTrue(storage.documentFile(COPY).lastModified() > storage.autosaveFile(COPY).lastModified())
        }

    private fun document(name: String) =
        CanvasDocument(
            width = 64,
            height = 64,
            dpi = 72,
            backgroundColor = -1,
            activeLayerId = 1,
            nextLayerId = 2,
            layers = listOf(Layer(id = 1, name = name, index = 0)),
        )

    private companion object {
        const val PROJECT = 7L
        const val COPY = 8L
        const val FUTURE_OFFSET_MS = 60_000L
    }
}
