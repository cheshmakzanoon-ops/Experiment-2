package com.artflow.studio.data

import android.content.Context
import com.artflow.studio.core.color.ColorProfile
import com.artflow.studio.core.export.ProcreateReader
import com.artflow.studio.core.export.ProcreateWriter
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.core.pixels.SelectionMask
import com.artflow.studio.data.export.LayerImports
import com.artflow.studio.data.local.ProjectStorage
import com.artflow.studio.data.repository.canvas.CanvasRepositoryImpl
import com.artflow.studio.domain.model.layer.Layer
import com.artflow.studio.domain.repository.canvas.LayerImportDestination
import com.artflow.studio.domain.repository.canvas.PreparedImportLayer
import com.artflow.studio.domain.repository.canvas.PreparedLayerImport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import java.io.IOException
import java.util.zip.ZipInputStream

/** Exercises the production publication boundary, including interleaving at preparation suspension points. */
@OptIn(ExperimentalCoroutinesApi::class)
class AtomicLayerImportTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var repository: CanvasRepositoryImpl

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        repository = CanvasRepositoryImpl(ProjectStorage(mock(Context::class.java)), dispatcher)
    }

    @After
    fun tearDown() {
        repository.dispose()
        Dispatchers.resetMain()
    }

    @Test
    fun completeStackAndBackgroundPublishAsOneUndoAndRedoStep() =
        runTest {
            repository.createCanvas(4, 4, 72)
            val before = repository.getAllLayers()
            val history = repository.undoDepth
            val imported = repository.importLayers { plan(it).copy(backgroundColor = RED) }
            assertEquals(history + 1, repository.undoDepth)
            assertEquals(3, repository.getAllLayers().size)
            assertEquals(RED, repository.getBackgroundColor())
            assertEquals(2, imported.size)
            val pixels = requireNotNull(repository.layerPixels(imported.last().id)).pixels.copyOf()
            assertTrue(repository.undo())
            assertEquals(before, repository.getAllLayers())
            assertEquals(0xFFFFFFFF.toInt(), repository.getBackgroundColor())
            assertTrue(repository.redo())
            assertArrayEquals(pixels, requireNotNull(repository.layerPixels(imported.last().id)).pixels)
            assertEquals(RED, repository.getBackgroundColor())
        }

    @Test
    fun latePreparationFailurePreservesContentProfileAndBothHistoryStacks() =
        runTest {
            repository.createCanvas(4, 4, 72)
            repository.addLayer("Prior edit")
            repository.undo()
            val before = state()
            val failure =
                runCatching {
                    repository.importLayers { destination ->
                        plan(destination)
                        throw IOException("Later imported mask could not be decoded")
                    }
                }.exceptionOrNull()
            assertTrue(failure is IOException)
            assertEquals(before, state())
        }

    @Test
    fun aLateProcreateMaskReadFailurePreservesExistingArtworkAndHistory() =
        runTest {
            repository.createCanvas(4, 4, 72)
            repository.setLayerPixels(repository.getActiveLayerId(), PixelBuffer.filled(4, 4, BLUE), "Existing paint")
            repository.addLayer("Undo this")
            repository.undo()
            val before = state()
            val document = documentWithUnreadableLastMask()
            val failure = runCatching { LayerImports.importProcreate(repository, document) }.exceptionOrNull()
            assertTrue("Expected the late mask read to fail with IOException, got $failure", failure is IOException)
            assertEquals("Imported mask storage became unavailable", failure?.message)
            assertEquals(before, state())
        }

    @Test
    fun procreateInsertionRetainsTheUsersTransparentLayers() =
        runTest {
            repository.createCanvas(4, 4, 72)
            val blank = repository.getActiveLayerId()
            repository.setLayerName(blank, "Reserved for highlights")
            val document = documentWithUnreadableLastMask(fail = false)
            val depth = repository.undoDepth
            LayerImports.importProcreate(repository, document)
            assertTrue(repository.getAllLayers().any { it.id == blank && it.name == "Reserved for highlights" })
            assertEquals(depth + 1, repository.undoDepth)
        }

    @Test
    fun cancellationDuringPreparationCannotPublishAnyLayers() =
        runTest {
            repository.createCanvas(4, 4, 72)
            val before = state()
            val entered = CompletableDeferred<Unit>()
            val waiting = CompletableDeferred<Unit>()
            val job =
                launch {
                    repository.importLayers { destination ->
                        val prepared = plan(destination)
                        entered.complete(Unit)
                        waiting.await()
                        prepared
                    }
                }
            entered.await()
            job.cancelAndJoin()
            assertEquals(before, state())
        }

    @Test
    fun changingProjectDuringPreparationRejectsTheStaleImport() =
        runTest {
            repository.createCanvas(4, 4, 72)
            val entered = CompletableDeferred<Unit>()
            val resume = CompletableDeferred<Unit>()
            val task =
                async {
                    runCatching {
                        repository.importLayers { destination ->
                            entered.complete(Unit)
                            resume.await()
                            plan(destination)
                        }
                    }
                }
            entered.await()
            repository.createCanvas(4, 4, 72)
            repository.addLayer("New project's work")
            val before = state()
            resume.complete(Unit)
            assertTrue(task.await().exceptionOrNull() is IllegalStateException)
            assertEquals(before, state())
        }

    @Test
    fun switchingAnimationFramesWithoutEditingRejectsTheStaleImport() =
        runTest {
            repository.createCanvas(4, 4, 72)
            repository.addFrame(false)
            val entered = CompletableDeferred<Unit>()
            val resume = CompletableDeferred<Unit>()
            val task =
                async {
                    runCatching {
                        repository.importLayers { destination ->
                            entered.complete(Unit)
                            resume.await()
                            plan(destination)
                        }
                    }
                }
            entered.await()
            val revision = repository.contentRevision
            repository.selectFrame(0)
            assertEquals(revision, repository.contentRevision)
            val before = state()
            resume.complete(Unit)
            assertTrue(task.await().exceptionOrNull() is IllegalStateException)
            assertEquals(before, state())
        }

    @Test
    fun ordinaryEditsDuringPreparationArePreservedAndTheImportIsRejected() =
        runTest {
            repository.createCanvas(4, 4, 72)
            val entered = CompletableDeferred<Unit>()
            val resume = CompletableDeferred<Unit>()
            val task =
                async {
                    runCatching {
                        repository.importLayers { destination ->
                            entered.complete(Unit)
                            resume.await()
                            plan(destination)
                        }
                    }
                }
            entered.await()
            repository.setLayerName(repository.getActiveLayerId(), "Keep this edit")
            val before = state()
            resume.complete(Unit)
            assertTrue(task.await().exceptionOrNull() is IllegalStateException)
            assertEquals(before, state())
        }

    @Test
    fun invalidLateLayerCannotLeaveAValidPrefixInTheCanvas() =
        runTest {
            repository.createCanvas(4, 4, 72)
            val before = state()
            val failure =
                runCatching {
                    repository.importLayers { destination ->
                        val valid = plan(destination)
                        valid.copy(layers = valid.layers + PreparedImportLayer(Layer(3, "Invalid", 2), PixelBuffer(1, 1)))
                    }
                }.exceptionOrNull()
            assertTrue(failure is IllegalArgumentException)
            assertEquals(before, state())
        }

    @Test
    fun aProfileChangeCannotRelabelExistingPixelsWithoutConvertingThem() =
        runTest {
            repository.createCanvas(4, 4, 72)
            repository.setLayerPixels(repository.getActiveLayerId(), PixelBuffer.filled(4, 4, RED), "Existing")
            val before = state()
            val failure =
                runCatching {
                    repository.importLayers { plan(it).copy(colorProfile = ColorProfile.DISPLAY_P3) }
                }.exceptionOrNull()
            assertTrue(failure is IllegalStateException)
            assertEquals(before, state())
        }

    @Test
    fun insertingAnImagePreservesTheCallersPixelsAndAddsOneUndoStep() =
        runTest {
            repository.createCanvas(4, 4, 72)
            val source = PixelBuffer.filled(2, 2, RED)
            val depth = repository.undoDepth
            val id = LayerImports.insertImage(repository, source)
            source.pixels.fill(0)
            val placed = requireNotNull(repository.layerPixels(id))
            assertEquals(RED, placed.getSafe(1, 1))
            assertEquals(depth + 1, repository.undoDepth)
            assertTrue(repository.undo())
            assertFalse(repository.getAllLayers().any { it.id == id })
        }

    private fun plan(destination: LayerImportDestination): PreparedLayerImport =
        PreparedLayerImport(
            listOf(
                PreparedImportLayer(Layer(1, "First", 0), PixelBuffer.filled(destination.width, destination.height, RED)),
                PreparedImportLayer(Layer(2, "Second", 1), PixelBuffer.filled(destination.width, destination.height, BLUE)),
            ),
        )

    private suspend fun state(): List<Any?> =
        listOf(
            repository.frames(),
            repository.getAllLayers().associate { it.id to repository.layerPixels(it.id)?.pixels?.toList() },
            repository.getColorProfile(),
            repository.getBackgroundColor(),
            repository.contentRevision,
            repository.undoDepth,
            repository.redoDepth,
            repository.historyMark,
            repository.getActiveLayerId(),
        )

    private fun documentWithUnreadableLastMask(fail: Boolean = true): ProcreateReader.Document {
        val pixels = PixelBuffer.filled(4, 4, RED)
        val bytes =
            ProcreateWriter.write(
                4,
                4,
                listOf(
                    // The writer omits all-white masks; nonwhite coverage guarantees a real tile read.
                    ProcreateWriter.Layer("Last", pixels, mask = SelectionMask(4, 4, ByteArray(16) { 128.toByte() })),
                    ProcreateWriter.Layer("First", pixels),
                ),
                pixels,
                background = RED,
                colorProfile = ColorProfile.DISPLAY_P3,
            )
        val files = linkedMapOf<String, ByteArray>()
        ZipInputStream(bytes.inputStream()).use { zip ->
            generateSequence { zip.nextEntry }.forEach { files[it.name] = zip.readBytes() }
        }
        var unreadableMask: String? = null
        val document =
            ProcreateReader.open(
                object : ProcreateReader.Files {
                    override val names = files.keys

                    override fun read(name: String): ByteArray? {
                        if (unreadableMask != null && name.startsWith("$unreadableMask/")) {
                            throw IOException("Imported mask storage became unavailable")
                        }
                        return files[name]
                    }
                },
            ) { null }
        val maskUuid = requireNotNull(document.layers().first { it.name == "Last" }.maskUuid)
        assertTrue("The failure fixture must contain a stored mask tile", files.keys.any { it.startsWith("$maskUuid/") })
        if (fail) unreadableMask = maskUuid
        return document
    }

    private companion object {
        val RED = 0xFFFF0000.toInt()
        val BLUE = 0xFF0000FF.toInt()
    }
}
