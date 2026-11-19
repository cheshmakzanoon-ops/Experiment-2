package com.artflow.studio.presentation

import android.content.Context
import com.artflow.studio.core.export.PngCodec
import com.artflow.studio.core.export.PsdCodec
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.core.pixels.SelectionMask
import com.artflow.studio.data.local.ProjectStorage
import com.artflow.studio.data.renderer.BitmapPixelBridge
import com.artflow.studio.data.repository.canvas.CanvasRepositoryImpl
import com.artflow.studio.presentation.ui.viewmodel.CanvasImportController
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import java.io.IOException

/** Real codecs and repository state exercise the same request boundary used by image/PSD pickers. */
@OptIn(ExperimentalCoroutinesApi::class)
class CanvasImportControllerTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var repository: CanvasRepositoryImpl
    private val messages = mutableListOf<String>()

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
    fun pickerAlreadyBindsItsDocumentBeforeTheImageDecoderArrives() =
        runTest {
            repository.createCanvas(4, 4, 72)
            val controller = controller(backgroundScope)
            val request = controller.beginImage()
            repository.createCanvas(4, 4, 72)
            paint(BLUE)
            val before = state()
            request.complete { decode(RED) }
            request.job!!.join()
            assertEquals(before, state())
            assertTrue(messages.single().contains("artwork changed"))
        }

    @Test
    fun switchingFramesBeforePsdPickerResultCannotInsertIntoTheNewFrame() =
        runTest {
            repository.createCanvas(4, 4, 72)
            paint(RED)
            repository.addFrame(false)
            paint(BLUE)
            val controller = controller(backgroundScope)
            val request = controller.beginPsd()
            val revision = repository.contentRevision
            repository.selectFrame(0)
            assertEquals(revision, repository.contentRevision)
            val before = state()
            request.complete { psd() }
            request.job!!.join()
            assertEquals(before, state())
            assertTrue(messages.single().contains("artwork changed"))
        }

    @Test
    fun changedSelectionWhilePickerIsOpenRejectsItsImage() =
        runTest {
            repository.createCanvas(4, 4, 72)
            val request = controller(backgroundScope).beginImage()
            repository.setSelection(SelectionMask(4, 4, ByteArray(16) { 127 }))
            val before = state()
            request.complete { decode(RED) }
            request.job!!.join()
            assertEquals(before, state())
            assertTrue(messages.single().contains("artwork changed"))
        }

    @Test
    fun navigationDuringAnActualDelayedImageDecodePreservesTheNewArtwork() =
        runTest {
            repository.createCanvas(4, 4, 72)
            val decoderEntered = CompletableDeferred<Unit>()
            val resume = CompletableDeferred<Unit>()
            val request = controller(backgroundScope).beginImage()
            request.complete {
                decoderEntered.complete(Unit)
                resume.await()
                decode(RED)
            }
            decoderEntered.await()
            repository.createCanvas(4, 4, 72)
            paint(BLUE)
            val before = state()
            resume.complete(Unit)
            request.job!!.join()
            assertEquals(before, state())
            assertTrue(messages.single().contains("artwork changed"))
        }

    @Test
    fun editingDuringDelayedPsdReadPreservesTheEditAndBothHistoryStacks() =
        runTest {
            repository.createCanvas(4, 4, 72)
            val readEntered = CompletableDeferred<Unit>()
            val resume = CompletableDeferred<Unit>()
            val request = controller(backgroundScope).beginPsd()
            request.complete {
                readEntered.complete(Unit)
                resume.await()
                psd()
            }
            readEntered.await()
            paint(BLUE)
            repository.addLayer("Undo this")
            repository.undo()
            val before = state()
            resume.complete(Unit)
            request.job!!.join()
            assertEquals(before, state())
            assertTrue(messages.single().contains("artwork changed"))
        }

    @Test
    fun cancellingAPickerRejectsItsLateResultWithoutStartingItsDecoder() =
        runTest {
            repository.createCanvas(4, 4, 72)
            val before = state()
            val request = controller(backgroundScope).beginImage()
            request.cancel()
            request.complete { error("A cancelled picker must never decode") }
            request.job!!.join()
            assertTrue(request.job!!.isCancelled)
            assertEquals(before, state())
            assertTrue(messages.isEmpty())
        }

    @Test
    fun screenExitDuringNonCooperativeDecoderCannotPublishOrReportSuccess() =
        runTest {
            repository.createCanvas(4, 4, 72)
            val controller = controller(backgroundScope)
            val decoderEntered = CompletableDeferred<Unit>()
            val resume = CompletableDeferred<Unit>()
            val request = controller.beginImage(isPrivate = true)
            request.complete {
                withContext(NonCancellable) {
                    decoderEntered.complete(Unit)
                    resume.await()
                    decode(RED)
                }
            }
            decoderEntered.await()
            controller.cancel()
            repository.createCanvas(4, 4, 72)
            paint(BLUE)
            val before = state()
            resume.complete(Unit)
            request.job!!.join()
            assertTrue(request.job!!.isCancelled)
            assertEquals(before, state())
            assertTrue(messages.isEmpty())
        }

    @Test
    fun oldPrivatePhotoResultCannotSupplyOrCancelANewerOrdinaryPhoto() =
        runTest {
            repository.createCanvas(4, 4, 72)
            val controller = controller(backgroundScope)
            val old = controller.beginImage(isPrivate = true)
            val current = controller.beginImage()
            old.complete { decode(RED) }
            old.cancel()
            current.complete { decode(BLUE) }
            current.job!!.join()
            val imported = repository.getAllLayers().single { it.name == "Photo" }
            assertFalse(imported.isPrivate)
            assertEquals(BLUE, repository.layerPixels(imported.id)!!.getSafe(1, 1))
            assertEquals(2, repository.getAllLayers().size)
            assertEquals(1, repository.undoDepth)
            assertTrue(messages.isEmpty())
        }

    @Test
    fun providerFailureAndUnreadablePsdLeaveExistingPixelsAndRedoUntouched() =
        runTest {
            repository.createCanvas(4, 4, 72)
            paint(BLUE)
            repository.addLayer("Undo this")
            repository.undo()
            val before = state()
            val controller = controller(backgroundScope)
            val image = controller.beginImage()
            image.complete { throw IOException("Provider disconnected") }
            image.job!!.join()
            assertEquals(before, state())
            assertEquals("Provider disconnected", messages.single())
            val psd = controller.beginPsd()
            psd.complete { byteArrayOf(1, 2, 3) }
            psd.job!!.join()
            assertEquals(before, state())
            assertEquals("This file is not a supported PSD document", messages.last())
        }

    @Test
    fun nullDecoderResultsReportFailureAndCompleteTheRequest() =
        runTest {
            repository.createCanvas(4, 4, 72)
            val before = state()
            val controller = controller(backgroundScope)
            val image = controller.beginImage()
            image.complete { null }
            image.job!!.join()
            val psd = controller.beginPsd()
            psd.complete { null }
            psd.job!!.join()
            assertTrue(image.job!!.isCompleted && psd.job!!.isCompleted)
            assertEquals(before, state())
            assertEquals(listOf("That image could not be opened", "That PSD could not be opened (maximum 256 MB)"), messages)
        }

    @Test
    fun importedPrivateImageKeepsPixelsAndPrivacyAcrossUndoRedo() =
        runTest {
            repository.createCanvas(4, 4, 72)
            val request = controller(backgroundScope).beginImage(isPrivate = true)
            request.complete { decode(RED) }
            request.job!!.join()
            val imported = repository.getAllLayers().single { it.isPrivate }
            val pixels = repository.layerPixels(imported.id)!!.pixels.copyOf()
            assertEquals(RED, repository.layerPixels(imported.id)!!.getSafe(1, 1))
            assertEquals(1, repository.undoDepth)
            assertTrue(repository.undo())
            assertEquals(1, repository.getAllLayers().size)
            assertTrue(repository.redo())
            assertTrue(repository.getAllLayers().single { it.id == imported.id }.isPrivate)
            assertArrayEquals(pixels, repository.layerPixels(imported.id)!!.pixels)
            assertTrue(messages.single().startsWith("Private photo added"))
        }

    @Test
    fun actualPsdReadPublishesTheWholeStackInOneUndoStep() =
        runTest {
            repository.createCanvas(4, 4, 72)
            val request = controller(backgroundScope).beginPsd()
            request.complete { psd() }
            request.job!!.join()
            val layers = repository.getAllLayers()
            assertEquals(RED, repository.layerPixels(layers.single { it.name == "Red" }.id)!!.pixels.first())
            assertEquals(BLUE, repository.layerPixels(layers.single { it.name == "Blue" }.id)!!.pixels.first())
            assertEquals(1, repository.undoDepth)
            assertTrue(repository.undo())
            assertEquals(1, repository.getAllLayers().size)
            assertTrue(repository.redo())
            assertEquals(layers, repository.getAllLayers())
            assertEquals("Imported 2 layer(s) from PSD", messages.single())
            runCurrent()
        }

    private fun controller(scope: kotlinx.coroutines.CoroutineScope): CanvasImportController =
        CanvasImportController(repository, scope, messages::add, {}, dispatcher)

    private fun decode(color: Int): PixelBuffer =
        checkNotNull(BitmapPixelBridge.fromEncodedBytes(PngCodec.encode(PixelBuffer.filled(2, 2, color))))

    private fun psd(): ByteArray =
        PsdCodec.write(
            4,
            4,
            listOf(
                PsdCodec.PsdLayer("Red", PixelBuffer.filled(4, 4, RED)),
                PsdCodec.PsdLayer("Blue", PixelBuffer.filled(4, 4, BLUE)),
            ),
            PixelBuffer.filled(4, 4, BLUE),
        )

    private suspend fun paint(color: Int) {
        assertTrue(repository.setLayerPixels(repository.getActiveLayerId(), PixelBuffer.filled(4, 4, color), "Existing artwork"))
    }

    private suspend fun state(): List<Any?> =
        listOf(
            repository.frames(),
            repository.frames().mapIndexed { index, _ -> repository.compositeFrame(index)?.pixels?.toList() },
            repository.getAllLayers().associate { it.id to repository.layerPixels(it.id)?.pixels?.toList() },
            repository.selection()?.coverage?.toList(),
            repository.getColorProfile(),
            repository.getBackgroundColor(),
            repository.contentRevision,
            repository.undoDepth,
            repository.redoDepth,
            repository.historyMark,
            repository.getActiveLayerId(),
            repository.activeFrameIndex(),
        )

    private companion object {
        val RED = 0xFFFF0000.toInt()
        val BLUE = 0xFF0000FF.toInt()
    }
}
