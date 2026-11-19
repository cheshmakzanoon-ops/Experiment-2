package com.artflow.studio.data.export

import android.content.Context
import com.artflow.studio.core.color.ColorProfile
import com.artflow.studio.core.export.BinaryPlist
import com.artflow.studio.core.export.KeyedArchive
import com.artflow.studio.core.export.ProcreateReader
import com.artflow.studio.core.export.ProcreateWriter
import com.artflow.studio.core.export.PsdCodec
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.core.pixels.SelectionMask
import com.artflow.studio.data.local.ProjectStorage
import com.artflow.studio.data.repository.canvas.CanvasRasterBudget
import com.artflow.studio.data.repository.canvas.CanvasRepositoryImpl
import com.artflow.studio.domain.repository.canvas.CanvasRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.util.zip.ZipInputStream

/** Imports use the editor's complete persisted-plane budget, including existing frames and independent masks. */
@OptIn(ExperimentalCoroutinesApi::class)
class LayerImportCapacityTest {
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
        repository = newRepository()
    }

    @After
    fun tearDown() {
        repository.dispose()
        Dispatchers.resetMain()
    }

    @Test
    fun psdCountsPixelMasksFolderMasksAndFolderSlotsBeforeChangingAnything() =
        runTest {
            openCanvas()
            val before = repository.frames()
            val history = repository.historyMark
            val revision = repository.contentRevision
            val requests = mutableListOf<Pair<Int, Int>>()
            val checked = capacityRecorder(requests)
            val failure = expectFailure { LayerImports.importPsd(checked, psd(twoPixels = true)) }
            assertTrue(failure.message.orEmpty().contains("memory or layer limit"))
            assertEquals(listOf(4 to 3), requests)
            assertEquals(before, repository.frames())
            assertEquals(history, repository.historyMark)
            assertEquals(revision, repository.contentRevision)
        }

    @Test
    fun psdAtTheExactBudgetKeepsItsPixelAndFolderMasksAfterReopening() =
        runTest {
            openCanvas()
            assertEquals(1, LayerImports.importPsd(repository, psd(twoPixels = false)))
            assertEquals(3, repository.getAllLayers().size)
            saveAndReopen()
            val layers = repository.getAllLayers()
            assertEquals(2, layers.count { it.maskFile != null })
            assertEquals(1, layers.count { it.isGroup })
            assertEquals(PAINT, requireNotNull(repository.layerPixels(layers.single { it.name == "Paint" }.id)).pixels.first())
        }

    @Test
    fun aRejectedPsdPixelCommitCannotBeReportedAsAnImportedLayer() =
        runTest {
            openCanvas()
            val rejected =
                object : CanvasRepository by repository {
                    override suspend fun applyRasterEdit(
                        layerId: Long,
                        description: String,
                        edit: (PixelBuffer) -> Unit,
                    ): Boolean = false
                }
            val failure = expectFailure { LayerImports.importPsd(rejected, psd(twoPixels = false)) }
            assertTrue(failure.message.orEmpty().contains("PSD layer \"Paint\" could not be imported"))
        }

    @Test
    fun procreateFallsBackWhenMasksExceedTheActualRemainingBudgetAndReopens() =
        runTest {
            openCanvas()
            val requests = mutableListOf<Pair<Int, Int>>()
            val result =
                LayerImports.importProcreate(
                    capacityRecorder(requests),
                    procreate(withMasks = true),
                    preserveDocumentProfile = true,
                    rasterBudgetBytes = Long.MAX_VALUE,
                )
            assertTrue(result.flattened)
            assertEquals(1, result.layers)
            assertEquals(listOf(4 to 3, 1 to 1), requests)
            assertEquals("Flattened", repository.getAllLayers().single().name)
            saveAndReopen()
            assertEquals(1, repository.getAllLayers().size)
            assertEquals(PAINT, requireNotNull(repository.layerPixels(repository.getActiveLayerId())).pixels.first())
        }

    @Test
    fun procreateIncludesGroupSlotsWhenDecidingWhetherTheLayeredImportFits() =
        runTest {
            openCanvas()
            val requests = mutableListOf<Pair<Int, Int>>()
            val result =
                LayerImports.importProcreate(
                    capacityRecorder(requests, maxAddedLayers = 2),
                    procreate(withMasks = false),
                    rasterBudgetBytes = Long.MAX_VALUE,
                )
            assertTrue(result.flattened)
            assertEquals(listOf(2 to 3, 1 to 1), requests)
            assertFalse(repository.getAllLayers().any { it.isGroup })
        }

    @Test
    fun procreateWithoutReadableLayerNodesUsesItsCompositeAndReopens() =
        runTest {
            openCanvas()
            val document = procreate(withMasks = false, withLayers = false)
            assertTrue(document.layers().isEmpty())
            val requests = mutableListOf<Pair<Int, Int>>()
            val result =
                LayerImports.importProcreate(
                    capacityRecorder(requests),
                    document,
                    preserveDocumentProfile = true,
                    rasterBudgetBytes = Long.MAX_VALUE,
                )
            assertTrue(result.flattened)
            assertEquals(1, result.layers)
            assertEquals(listOf(1 to 1), requests)
            saveAndReopen()
            assertEquals("Flattened", repository.getAllLayers().single().name)
            assertEquals(PAINT, requireNotNull(repository.layerPixels(repository.getActiveLayerId())).pixels.first())
        }

    @Test
    fun procreateRejectsEvenTheFallbackBeforeChangingTheProfileBackgroundOrHistory() =
        runTest {
            openCanvas()
            repeat(3) { repository.addFrame(true) }
            val before = repository.frames()
            val background = repository.getBackgroundColor()
            val profile = repository.getColorProfile()
            val history = repository.historyMark
            val revision = repository.contentRevision
            val failure =
                expectFailure {
                    LayerImports.importProcreate(
                        repository,
                        procreate(withMasks = true),
                        preserveDocumentProfile = true,
                        rasterBudgetBytes = Long.MAX_VALUE,
                    )
                }
            assertTrue(failure.message.orEmpty().contains("memory or layer limit"))
            assertEquals(before, repository.frames())
            assertEquals(background, repository.getBackgroundColor())
            assertEquals(profile, repository.getColorProfile())
            assertEquals(history, repository.historyMark)
            assertEquals(revision, repository.contentRevision)
        }

    @Test
    fun anUnavailableProcreateCompositeDoesNotPartiallyChangeTheDestination() =
        runTest {
            openCanvas()
            val before = repository.frames()
            val background = repository.getBackgroundColor()
            val history = repository.historyMark
            val failure =
                expectFailure {
                    LayerImports.importProcreate(
                        repository,
                        procreate(withMasks = true, includeComposite = false),
                        preserveDocumentProfile = true,
                        rasterBudgetBytes = Long.MAX_VALUE,
                    )
                }
            assertTrue(failure.message.orEmpty().contains("no flattened image"))
            assertEquals(before, repository.frames())
            assertEquals(background, repository.getBackgroundColor())
            assertEquals(ColorProfile.SRGB, repository.getColorProfile())
            assertEquals(history, repository.historyMark)
        }

    private fun capacityRecorder(
        requests: MutableList<Pair<Int, Int>>,
        maxAddedLayers: Int = Int.MAX_VALUE,
    ): CanvasRepository =
        object : CanvasRepository by repository {
            override fun canImportLayers(
                rasterPlanes: Int,
                layers: Int,
            ): Boolean {
                requests += rasterPlanes to layers
                return layers <= maxAddedLayers && repository.canImportLayers(rasterPlanes, layers)
            }
        }

    private fun newRepository(): CanvasRepositoryImpl =
        CanvasRepositoryImpl(storage, dispatcher, rasterBudget = CanvasRasterBudget(SIZE.toLong() * SIZE * 4 * 3 * 4))

    private suspend fun openCanvas() {
        repository.loadOrCreate(PROJECT, SIZE, SIZE, 72)
    }

    private suspend fun saveAndReopen() {
        assertNotNull(repository.saveCanvas(PROJECT))
        repository.dispose()
        repository = newRepository()
        assertNotNull(repository.loadCanvas(PROJECT))
    }

    private suspend fun expectFailure(block: suspend () -> Unit): Throwable {
        val failure = runCatching { block() }.exceptionOrNull()
        assertTrue("Expected a rejected import, got $failure", failure is IllegalStateException)
        return checkNotNull(failure)
    }

    private fun psd(twoPixels: Boolean): ByteArray {
        val pixels = PixelBuffer.filled(SIZE, SIZE, PAINT)
        val mask = PsdCodec.PsdMask(0, 0, SIZE, SIZE, ByteArray(SIZE * SIZE) { 255.toByte() }, 255)
        val layers =
            buildList {
                add(PsdCodec.PsdLayer.groupEnd())
                add(PsdCodec.PsdLayer("Paint", pixels, mask = mask))
                if (twoPixels) add(PsdCodec.PsdLayer("Second", pixels))
                add(PsdCodec.PsdLayer.groupHeader("Folder").copy(mask = mask))
            }
        return PsdCodec.write(SIZE, SIZE, layers, pixels)
    }

    private fun procreate(
        withMasks: Boolean,
        includeComposite: Boolean = true,
        withLayers: Boolean = true,
    ): ProcreateReader.Document {
        val pixels = PixelBuffer.filled(SIZE, SIZE, PAINT)
        val mask = if (withMasks) SelectionMask(SIZE, SIZE, ByteArray(SIZE * SIZE) { 255.toByte() }) else null
        val nodes =
            listOf(
                ProcreateWriter.Group(
                    "Folder",
                    children =
                        listOf(
                            ProcreateWriter.Layer("Paint", pixels, mask = mask),
                            ProcreateWriter.Layer("Second", pixels, mask = mask),
                        ),
                ),
            )
        val bytes =
            ProcreateWriter.write(
                SIZE,
                SIZE,
                if (withLayers) nodes else emptyList(),
                pixels,
                background = BACKGROUND,
                colorProfile = ColorProfile.DISPLAY_P3,
            )
        val files = linkedMapOf<String, ByteArray>()
        ZipInputStream(bytes.inputStream()).use { zip ->
            generateSequence { zip.nextEntry }.forEach { files[it.name] = zip.readBytes() }
        }
        if (!includeComposite) {
            val archive = KeyedArchive(BinaryPlist.read(files.getValue("Document.archive")))
            val root = requireNotNull(archive.root)
            val uuid = requireNotNull(archive.string(archive.map(root["composite"])?.get("UUID")))
            files.keys.removeAll { it.startsWith("$uuid/") }
        }
        return ProcreateReader.open(
            object : ProcreateReader.Files {
                override val names = files.keys

                override fun read(name: String): ByteArray? = files[name]
            },
        ) { null }
    }

    private companion object {
        const val PROJECT = 9_400_086L
        const val SIZE = 64
        val PAINT = 0xFF224488.toInt()
        val BACKGROUND = 0xFF885522.toInt()
    }
}
