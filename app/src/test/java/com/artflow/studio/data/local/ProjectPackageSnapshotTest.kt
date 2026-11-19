package com.artflow.studio.data.local

import android.content.Context
import com.artflow.studio.data.export.ArtflowPackage
import com.artflow.studio.domain.model.layer.Layer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
import java.io.IOException
import java.io.InputStream

class ProjectPackageSnapshotTest {
    @get:Rule val temporary = TemporaryFolder()

    private lateinit var storage: ProjectStorage

    @Before
    fun setUp() {
        storage = newStorage("source")
    }

    @Test
    fun `new save and pruning cannot change the manifest or pixels of a package snapshot`() =
        runBlocking {
            val firstPixels = byteArrayOf(1, 2, 3, 4)
            val firstRaster = storage.writeRaster(PROJECT, 1, 1, firstPixels)
            storage.saveDocument(PROJECT, document(firstRaster))
            // Compatible unknown metadata must survive byte-for-byte, including original whitespace.
            val saved =
                storage
                    .documentFile(PROJECT)
                    .readText()
                    .trimEnd()
                    .removeSuffix("}")
            val originalManifest = " \n$saved,\"compatibleMetadata\":{\"label\":\"preserve\"}}\n".toByteArray()
            storage.documentFile(PROJECT).writeBytes(originalManifest)
            val packageFile = File(temporary.newFolder("export"), "snapshot.artflow")
            val target = newStorage("target")
            var nextRaster = ""

            withTimeout(TEST_TIMEOUT_MS) {
                storage.withPackageSnapshot(PROJECT) { snapshot ->
                    nextRaster = storage.writeRaster(PROJECT, 1, 1, byteArrayOf(5, 6, 7, 8))
                    storage.saveDocument(PROJECT, document(nextRaster).copy(width = 128))
                    assertEquals(0, storage.pruneRasters(PROJECT, emptySet()))

                    ArtflowPackage.writeToFile("Snapshot", packageFile, snapshot.sources)
                    ArtflowPackage.openFile(packageFile).use { opened ->
                        assertArrayEquals(originalManifest, opened.readBytes(ArtflowPackage.DOCUMENT))
                        assertTrue(firstRaster in opened.entries)
                        assertFalse(nextRaster in opened.entries)
                        target.importProjectStream(8, opened)
                    }
                    assertEquals(64, target.loadDocument(8)!!.width)
                    assertArrayEquals(firstPixels, target.readRaster(8, firstRaster))
                    assertEquals(64, snapshot.document.width)
                }
            }

            assertEquals(1, storage.pruneRasters(PROJECT, emptySet()))
            assertFalse(storage.rasterExists(PROJECT, firstRaster))
            assertTrue(storage.rasterExists(PROJECT, nextRaster))
            assertEquals(128, storage.loadDocument(PROJECT)!!.width)
        }

    @Test
    fun `finishing one of two concurrent exports does not release the other's raster lease`() =
        runBlocking {
            val raster = storage.writeRaster(PROJECT, 1, 1, byteArrayOf(1, 2, 3, 4))
            storage.saveDocument(PROJECT, document(raster))
            val firstReady = CompletableDeferred<Unit>()
            val secondReady = CompletableDeferred<Unit>()
            val finishFirst = CompletableDeferred<Unit>()
            val finishSecond = CompletableDeferred<Unit>()

            withTimeout(TEST_TIMEOUT_MS) {
                val first =
                    async {
                        storage.withPackageSnapshot(PROJECT) {
                            firstReady.complete(Unit)
                            finishFirst.await()
                        }
                    }
                firstReady.await()
                val second =
                    async {
                        storage.withPackageSnapshot(PROJECT) { snapshot ->
                            secondReady.complete(Unit)
                            finishSecond.await()
                            assertArrayEquals(
                                byteArrayOf(1, 2, 3, 4),
                                snapshot.sources
                                    .single { it.path == raster }
                                    .openStream()
                                    .use { it.readBytes() },
                            )
                        }
                    }
                secondReady.await()
                storage.saveDocument(PROJECT, document(null))
                finishFirst.complete(Unit)
                first.await()

                assertEquals(0, storage.pruneRasters(PROJECT, emptySet()))
                assertTrue(storage.rasterExists(PROJECT, raster))
                finishSecond.complete(Unit)
                second.await()
            }

            assertEquals(1, storage.pruneRasters(PROJECT, emptySet()))
            assertFalse(storage.rasterExists(PROJECT, raster))
        }

    @Test
    fun `cancelled export releases its raster lease`() =
        runBlocking {
            val raster = storage.writeRaster(PROJECT, 1, 1, byteArrayOf(1, 2, 3, 4))
            storage.saveDocument(PROJECT, document(raster))
            storage.saveModel(PROJECT, "retained model")
            val ready = CompletableDeferred<Unit>()
            lateinit var modelStream: InputStream

            withTimeout(TEST_TIMEOUT_MS) {
                val export =
                    launch {
                        storage.withPackageSnapshot(PROJECT) { snapshot ->
                            modelStream = snapshot.sources.single { it.path == ProjectStorage.MODEL_NAME }.openStream()
                            ready.complete(Unit)
                            awaitCancellation()
                        }
                    }
                ready.await()
                storage.saveDocument(PROJECT, document(null))
                assertEquals(0, storage.pruneRasters(PROJECT, emptySet()))
                export.cancelAndJoin()
            }

            assertEquals(1, storage.pruneRasters(PROJECT, emptySet()))
            assertFalse(storage.rasterExists(PROJECT, raster))
            assertTrue(runCatching { modelStream.read() }.exceptionOrNull() is IOException)
        }

    @Test
    fun `model and previews retain their acquired bytes across atomic replacement`() =
        runBlocking {
            storage.saveDocument(PROJECT, document(null))
            val oldModel = "v 0 0 0\nv 1 0 0\nv 0 1 0\nf 1 2 3\n"
            val newModel = oldModel.replace("v 1 0 0", "v 2 0 0")
            val oldComposite = byteArrayOf(1, 2, 3)
            val oldThumbnail = byteArrayOf(4, 5, 6)
            storage.saveModel(PROJECT, oldModel)
            storage.saveFlattened(PROJECT, oldComposite)
            storage.saveThumbnail(PROJECT, oldThumbnail)
            val output = File(temporary.newFolder("assets-export"), "snapshot.artflow")

            storage.withPackageSnapshot(PROJECT) { snapshot ->
                storage.saveModel(PROJECT, newModel)
                storage.saveFlattened(PROJECT, byteArrayOf(7, 8, 9))
                storage.saveThumbnail(PROJECT, byteArrayOf(10, 11, 12))
                // Inspection may open a source before the writer does; each open needs its own cursor.
                assertEquals(
                    oldModel,
                    snapshot.sources
                        .single { it.path == ProjectStorage.MODEL_NAME }
                        .openStream()
                        .use { it.readBytes().decodeToString() },
                )
                ArtflowPackage.writeToFile("Acquired assets", output, snapshot.sources)
            }

            ArtflowPackage.openFile(output).use { opened ->
                assertEquals(oldModel, opened.readBytes(ProjectStorage.MODEL_NAME).decodeToString())
                assertArrayEquals(oldComposite, opened.readBytes(ProjectStorage.FLATTENED_NAME))
                assertArrayEquals(oldThumbnail, opened.readBytes(ProjectStorage.THUMBNAIL_NAME))
            }
            assertEquals(newModel, storage.loadModel(PROJECT))
            assertArrayEquals(byteArrayOf(7, 8, 9), storage.loadFlattenedBytes(PROJECT))
            assertArrayEquals(byteArrayOf(10, 11, 12), storage.thumbnailFile(PROJECT).readBytes())
        }

    @Test
    fun `failed archive keeps existing export and releases its raster lease`() =
        runBlocking {
            val raster = storage.writeRaster(PROJECT, 1, 1, byteArrayOf(1, 2, 3, 4))
            storage.saveDocument(PROJECT, document(raster))
            val exportDirectory = temporary.newFolder("export")
            val destination = File(exportDirectory, "snapshot.artflow")
            val previousExport = byteArrayOf(9, 8, 7, 6)
            destination.writeBytes(previousExport)

            val failure =
                runCatching {
                    storage.withPackageSnapshot(PROJECT) { snapshot ->
                        storage.saveDocument(PROJECT, document(null))
                        ArtflowPackage.writeToFile(
                            "Snapshot",
                            destination,
                            snapshot.sources +
                                ArtflowPackage.EntrySource("layers/99/v1-failure.png") {
                                    throw IOException("Source read failed")
                                },
                        )
                    }
                }.exceptionOrNull()

            assertTrue(failure is IOException)
            assertArrayEquals(previousExport, destination.readBytes())
            assertEquals(listOf(destination.name), exportDirectory.listFiles()!!.map { it.name })
            assertEquals(1, storage.pruneRasters(PROJECT, emptySet()))
            assertFalse(storage.rasterExists(PROJECT, raster))
        }

    @Test
    fun `failed snapshot acquisition releases paths pinned before source validation`() =
        runBlocking {
            val missing = "layers/1/v1-missing.png"
            storage.saveDocument(PROJECT, document(missing))

            val failure =
                runCatching {
                    storage.withPackageSnapshot(PROJECT) { error("Invalid snapshot must never reach its callback") }
                }.exceptionOrNull()
            assertTrue(failure is IllegalArgumentException)

            storage.resolve(PROJECT, missing).apply {
                parentFile!!.mkdirs()
                writeBytes(byteArrayOf(1))
            }
            storage.saveDocument(PROJECT, document(null))
            assertEquals(1, storage.pruneRasters(PROJECT, emptySet()))
            assertFalse(storage.rasterExists(PROJECT, missing))
        }

    private fun newStorage(directory: String): ProjectStorage {
        val context = mock(Context::class.java)
        `when`(context.filesDir).thenReturn(temporary.newFolder(directory))
        return ProjectStorage(context)
    }

    private fun document(raster: String?) =
        CanvasDocument(
            width = 64,
            height = 64,
            dpi = 72,
            backgroundColor = -1,
            activeLayerId = 1,
            nextLayerId = 2,
            layers = listOf(Layer(id = 1, name = "Snapshot", index = 0, rasterFile = raster)),
        )

    companion object {
        private const val PROJECT = 7L
        private const val TEST_TIMEOUT_MS = 10_000L
    }
}
