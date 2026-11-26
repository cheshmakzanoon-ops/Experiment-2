package com.artflow.studio.data.repository.canvas

import android.content.Context
import com.artflow.studio.core.export.PngCodec
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.data.local.CanvasDocument
import com.artflow.studio.data.local.ProjectStorage
import com.artflow.studio.domain.model.layer.Layer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

/** A parseable manifest is not a usable saved artwork when its referenced pixels cannot load. */
@OptIn(ExperimentalCoroutinesApi::class)
class DamagedPrimaryRecoveryTest {
    @get:Rule val temporary = TemporaryFolder()

    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var storage: ProjectStorage
    private lateinit var repository: CanvasRepositoryImpl

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        val context = mock(Context::class.java)
        `when`(context.filesDir).thenReturn(temporary.newFolder("files"))
        storage = ProjectStorage(context, dispatcher)
        repository = CanvasRepositoryImpl(storage)
    }

    @After
    fun tearDown() {
        if (::repository.isInitialized) repository.dispose()
        Dispatchers.resetMain()
    }

    @Test
    fun missingPrimaryRasterStillOffersOlderRecovery() =
        runTest {
            assertOlderRecoveryProtected(Layer(1, "Damaged save", 0, rasterFile = "layers/1/missing.png"))
        }

    @Test
    fun invalidNonemptyPrimaryRasterStillOffersOlderRecovery() =
        runTest {
            val invalid = storage.writeRaster(PROJECT, 1, 2, byteArrayOf(1, 2, 3, 4))
            assertOlderRecoveryProtected(Layer(1, "Damaged save", 0, rasterFile = invalid))
        }

    @Test
    fun invalidNonemptyPrimaryPhysicalPlaneStillOffersOlderRecovery() =
        runTest {
            val invalid = storage.writeAuxiliaryBinary(PROJECT, 1, "phys", 2, "phys", byteArrayOf(1, 2, 3, 4))
            assertOlderRecoveryProtected(Layer(1, "Damaged save", 0, physicalPaintFile = invalid))
        }

    @Test
    fun damagedPrimaryWithoutAutosavePropagatesFailureAndKeepsThePreviousCanvas() =
        runTest {
            repository.loadOrCreate(OTHER_PROJECT, SIZE, SIZE, 72)
            repository.addLayer("Unsaved other artwork")
            storage.saveDocument(PROJECT, document(Layer(1, "Damaged save", 0, rasterFile = "layers/1/missing.png")))
            val original = storage.documentFile(PROJECT).readBytes()

            assertTrue(runCatching { repository.loadOrCreate(PROJECT, SIZE, SIZE, 72) }.isFailure)

            assertTrue(repository.getAllLayers().any { it.name == "Unsaved other artwork" })
            assertTrue(repository.hasUnsavedChanges())
            assertArrayEquals(original, storage.documentFile(PROJECT).readBytes())
            assertFalse(repository.hasRecovery(PROJECT))
        }

    @Test
    fun readableNewerPrimaryDoesNotOfferAnOlderAutosave() =
        runTest {
            storage.saveAutosave(PROJECT, document(Layer(1, "Older recovery", 0)))
            storage.saveDocument(PROJECT, document(Layer(1, "Newer save", 0)))

            repository.loadOrCreate(PROJECT, SIZE, SIZE, 72)

            assertTrue(repository.getAllLayers().any { it.name == "Newer save" })
            assertFalse(repository.hasRecovery(PROJECT))
            assertFalse(repository.hasUnresolvedRecovery(PROJECT))
        }

    private suspend fun assertOlderRecoveryProtected(primaryLayer: Layer) {
        val pixels = PixelBuffer(SIZE, SIZE).apply { fill(RECOVERY_COLOR) }
        val recoveryRaster = storage.writeRaster(PROJECT, 1, 1, PngCodec.encode(pixels))
        storage.saveAutosave(PROJECT, document(Layer(1, "Recovered artwork", 0, rasterFile = recoveryRaster)))
        storage.saveDocument(PROJECT, document(primaryLayer))
        val primary = storage.documentFile(PROJECT)
        val recovery = storage.autosaveFile(PROJECT)
        val savedBytes = primary.readBytes()
        val recoveryBytes = recovery.readBytes()
        val recoveryTime = recovery.lastModified()
        assertTrue(primary.lastModified() > recoveryTime)
        assertFalse("Metadata alone cannot detect the damaged primary asset", storage.hasUnsavedRecovery(PROJECT))

        assertNotNull(repository.loadOrCreate(PROJECT, SIZE, SIZE, 72))
        assertTrue(repository.hasRecovery(PROJECT))
        assertTrue(repository.hasUnresolvedRecovery(PROJECT))
        assertTrue(runCatching { repository.saveCanvas(PROJECT) }.exceptionOrNull() is IllegalStateException)
        repository.addLayer("Placeholder edit must not replace recovery")
        repository.autosave(PROJECT)
        assertArrayEquals(savedBytes, primary.readBytes())
        assertArrayEquals(recoveryBytes, recovery.readBytes())
        assertTrue(recovery.lastModified() == recoveryTime)

        assertNotNull(repository.recoverAutosave(PROJECT))
        assertFalse(repository.hasUnresolvedRecovery(PROJECT))
        assertTrue(repository.getAllLayers().single().name == "Recovered artwork")
        assertArrayEquals(pixels.pixels, repository.layerPixels(repository.getActiveLayerId())!!.pixels)
        assertNotNull(repository.saveCanvas(PROJECT))
        assertFalse(repository.hasRecovery(PROJECT))
        assertFalse(recovery.exists())
        assertNotNull(repository.loadCanvas(PROJECT))
        assertArrayEquals(pixels.pixels, repository.layerPixels(repository.getActiveLayerId())!!.pixels)
    }

    private fun document(layer: Layer) =
        CanvasDocument(
            width = SIZE,
            height = SIZE,
            dpi = 72,
            backgroundColor = -1,
            activeLayerId = 1,
            nextLayerId = 2,
            layers = listOf(layer),
        )

    private companion object {
        const val PROJECT = 91L
        const val OTHER_PROJECT = 92L
        const val SIZE = 16
        const val RECOVERY_COLOR = -0x7f9fc0
    }
}
