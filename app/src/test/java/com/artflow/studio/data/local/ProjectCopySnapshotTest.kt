package com.artflow.studio.data.local

import android.content.Context
import com.artflow.studio.domain.model.layer.Layer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Gallery duplication can overlap the previous editor's admitted background autosave. */
class ProjectCopySnapshotTest {
    @get:Rule val temporary = TemporaryFolder()

    private lateinit var storage: ProjectStorage

    @Before
    fun setUp() {
        val context = mock(Context::class.java)
        `when`(context.filesDir).thenReturn(temporary.newFolder("files"))
        storage = ProjectStorage(context)
    }

    @Test
    fun `source save and pruning cannot remove either captured copy generation`() =
        runBlocking {
            val savedPixels = byteArrayOf(1, 2, 3)
            val recoveryPixels = byteArrayOf(4, 5, 6)
            val savedRaster = storage.writeRaster(PROJECT, 1, 1, savedPixels)
            val recoveryRaster = storage.writeRaster(PROJECT, 1, 1, recoveryPixels)
            storage.saveDocument(PROJECT, document("Saved", savedRaster))
            storage.saveAutosave(PROJECT, document("Recovered", recoveryRaster))
            val savedBytes = storage.documentFile(PROJECT).readBytes()
            val recoveryBytes = storage.autosaveFile(PROJECT).readBytes()

            val copied =
                storage.copyProject(PROJECT, COPY) {
                    runBlocking {
                        withTimeout(TIMEOUT_MS) {
                            storage.saveDocument(PROJECT, document("Later save"))
                            storage.discardAutosave(PROJECT)
                            assertEquals(0, storage.pruneRasters(PROJECT, emptySet()))
                        }
                    }
                }

            assertEquals("Saved", copied!!.layers.single().name)
            assertArrayEquals(savedBytes, storage.documentFile(COPY).readBytes())
            assertArrayEquals(recoveryBytes, storage.autosaveFile(COPY).readBytes())
            assertArrayEquals(savedPixels, storage.readRaster(COPY, savedRaster))
            assertArrayEquals(recoveryPixels, storage.readRaster(COPY, recoveryRaster))
            assertTrue(storage.hasUnsavedRecovery(COPY))
            assertFalse(storage.hasUnsavedRecovery(PROJECT))
            assertEquals(2, storage.pruneRasters(PROJECT, emptySet()))
            // Releasing the source lease never aliases or deletes the destination's bytes.
            assertArrayEquals(savedPixels, storage.readRaster(COPY, savedRaster))
            assertArrayEquals(recoveryPixels, storage.readRaster(COPY, recoveryRaster))
        }

    @Test
    fun `copy preserves compatible metadata bytes in both manifests`() =
        runBlocking {
            storage.saveDocument(PROJECT, document("Saved"))
            storage.saveAutosave(PROJECT, document("Recovered"))
            val saved = storage.documentFile(PROJECT)
            val recovery = storage.autosaveFile(PROJECT)
            val savedBytes = withCompatibleMetadata(saved.readText(), "saved").toByteArray()
            val recoveryBytes = withCompatibleMetadata(recovery.readText(), "recovery").toByteArray()
            val savedTime = saved.lastModified()
            val recoveryTime = recovery.lastModified()
            saved.writeBytes(savedBytes)
            recovery.writeBytes(recoveryBytes)
            assertTrue(saved.setLastModified(savedTime))
            assertTrue(recovery.setLastModified(recoveryTime))

            storage.copyProject(PROJECT, COPY)

            assertArrayEquals(savedBytes, storage.documentFile(COPY).readBytes())
            assertArrayEquals(recoveryBytes, storage.autosaveFile(COPY).readBytes())
            assertTrue(storage.hasUnsavedRecovery(COPY))
        }

    @Test
    fun `autosave-only artwork keeps its sole recovery copy across source replacement`() =
        runBlocking {
            val pixels = byteArrayOf(1, 2, 3)
            val raster = storage.writeRaster(PROJECT, 1, 1, pixels)
            storage.saveAutosave(PROJECT, document("Only recovery", raster))
            val recoveryBytes = storage.autosaveFile(PROJECT).readBytes()

            val copied =
                storage.copyProject(PROJECT, COPY) {
                    runBlocking {
                        withTimeout(TIMEOUT_MS) {
                            storage.saveDocument(PROJECT, document("Later save"))
                            storage.discardAutosave(PROJECT)
                            assertEquals(0, storage.pruneRasters(PROJECT, emptySet()))
                        }
                    }
                }

            assertNull(copied)
            assertNull(storage.loadDocument(COPY))
            assertTrue(storage.hasUnsavedRecovery(COPY))
            assertArrayEquals(recoveryBytes, storage.autosaveFile(COPY).readBytes())
            assertArrayEquals(pixels, storage.readRaster(COPY, raster))
            assertEquals(1, storage.pruneRasters(PROJECT, emptySet()))
        }

    @Test
    fun `copied previews and model keep their captured bytes across source replacement`() =
        runBlocking {
            storage.saveDocument(PROJECT, document("Saved"))
            val composite = byteArrayOf(1, 2, 3)
            val thumbnail = byteArrayOf(4, 5, 6)
            val model = "v 0 0 0\nv 1 0 0\nv 0 1 0\nf 1 2 3\n"
            storage.saveFlattened(PROJECT, composite)
            storage.saveThumbnail(PROJECT, thumbnail)
            storage.saveModel(PROJECT, model)

            storage.copyProject(PROJECT, COPY) {
                runBlocking {
                    withTimeout(TIMEOUT_MS) {
                        storage.saveFlattened(PROJECT, byteArrayOf(7, 8, 9))
                        storage.saveThumbnail(PROJECT, byteArrayOf(10, 11, 12))
                        storage.saveModel(PROJECT, model.replace("v 1 0 0", "v 2 0 0"))
                    }
                }
            }

            assertArrayEquals(composite, storage.loadFlattenedBytes(COPY))
            assertArrayEquals(thumbnail, storage.thumbnailFile(COPY).readBytes())
            assertEquals(model, storage.loadModel(COPY))
            assertArrayEquals(byteArrayOf(7, 8, 9), storage.loadFlattenedBytes(PROJECT))
        }

    @Test
    fun `finishing one concurrent copy does not release the other copy's lease`() =
        runBlocking {
            val pixels = byteArrayOf(1, 2, 3)
            val raster = storage.writeRaster(PROJECT, 1, 1, pixels)
            storage.saveDocument(PROJECT, document("Saved", raster))
            val firstEntered = CompletableDeferred<Unit>()
            val secondEntered = CompletableDeferred<Unit>()
            val finishFirst = CountDownLatch(1)
            val finishSecond = CountDownLatch(1)
            try {
                withTimeout(TIMEOUT_MS) {
                    val first = async { storage.copyProject(PROJECT, COPY) { hold(firstEntered, finishFirst) } }
                    firstEntered.await()
                    val second = async { storage.copyProject(PROJECT, SECOND_COPY) { hold(secondEntered, finishSecond) } }
                    secondEntered.await()
                    storage.saveDocument(PROJECT, document("Later save"))
                    finishFirst.countDown()
                    first.await()

                    assertEquals(0, storage.pruneRasters(PROJECT, emptySet()))
                    finishSecond.countDown()
                    second.await()
                }
            } finally {
                finishFirst.countDown()
                finishSecond.countDown()
            }

            assertEquals(1, storage.pruneRasters(PROJECT, emptySet()))
            assertArrayEquals(pixels, storage.readRaster(COPY, raster))
            assertArrayEquals(pixels, storage.readRaster(SECOND_COPY, raster))
        }

    @Test
    fun `cancelled copy rolls back its destination and releases its raster lease`() =
        runBlocking {
            val raster = storage.writeRaster(PROJECT, 1, 1, byteArrayOf(1, 2, 3))
            storage.saveDocument(PROJECT, document("Saved", raster))
            val entered = CompletableDeferred<Unit>()
            val release = CountDownLatch(1)
            try {
                withTimeout(TIMEOUT_MS) {
                    val copy = async { storage.copyProject(PROJECT, COPY) { hold(entered, release) } }
                    entered.await()
                    storage.saveDocument(PROJECT, document("Later save"))
                    assertEquals(0, storage.pruneRasters(PROJECT, emptySet()))
                    copy.cancel()
                    release.countDown()
                    copy.cancelAndJoin()
                }
            } finally {
                release.countDown()
            }

            assertFalse(storage.projectDirectoryExists(COPY))
            assertEquals(1, storage.pruneRasters(PROJECT, emptySet()))
            assertEquals(
                "Later save",
                storage
                    .loadDocument(PROJECT)!!
                    .layers
                    .single()
                    .name,
            )
        }

    @Test
    fun `failed copy rolls back its destination and releases its raster lease`() =
        runBlocking {
            val raster = storage.writeRaster(PROJECT, 1, 1, byteArrayOf(1, 2, 3))
            storage.saveDocument(PROJECT, document("Saved", raster))
            val failure =
                runCatching {
                    storage.copyProject(PROJECT, COPY) { throw IOException("Injected copy failure") }
                }.exceptionOrNull()

            assertTrue(failure is IOException)
            assertFalse(storage.projectDirectoryExists(COPY))
            storage.saveDocument(PROJECT, document("Later save"))
            assertEquals(1, storage.pruneRasters(PROJECT, emptySet()))
        }

    @Test
    fun `invalid captured raster cannot leave a pin or create a destination`() =
        runBlocking {
            val raster = "layers/1/v1-missing.png"
            storage.saveDocument(PROJECT, document("Saved", raster))
            val failure = runCatching { storage.copyProject(PROJECT, COPY) }.exceptionOrNull()

            assertTrue(failure is IllegalArgumentException)
            assertFalse(storage.projectDirectoryExists(COPY))
            storage.resolve(PROJECT, raster).apply {
                parentFile!!.mkdirs()
                writeBytes(byteArrayOf(1, 2, 3))
            }
            storage.saveDocument(PROJECT, document("Later save"))
            assertEquals(1, storage.pruneRasters(PROJECT, emptySet()))
        }

    private fun document(
        name: String,
        raster: String? = null,
    ) = CanvasDocument(
        width = 64,
        height = 64,
        dpi = 72,
        backgroundColor = -1,
        activeLayerId = 1,
        nextLayerId = 2,
        layers = listOf(Layer(id = 1, name = name, index = 0, rasterFile = raster)),
    )

    private fun withCompatibleMetadata(
        document: String,
        label: String,
    ): String = " \n${document.trimEnd().removeSuffix("}")},\"compatibleMetadata\":{\"label\":\"$label\"}}\n"

    private fun hold(
        entered: CompletableDeferred<Unit>,
        release: CountDownLatch,
    ) {
        entered.complete(Unit)
        check(release.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)) { "Copy gate timed out" }
    }

    private companion object {
        const val PROJECT = 7L
        const val COPY = 8L
        const val SECOND_COPY = 9L
        const val TIMEOUT_MS = 10_000L
    }
}
