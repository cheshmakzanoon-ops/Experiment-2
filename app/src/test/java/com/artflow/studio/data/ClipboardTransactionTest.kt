package com.artflow.studio.data

import android.content.Context
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.core.pixels.SelectionMask
import com.artflow.studio.core.three.MaterialChannel
import com.artflow.studio.data.local.ProjectStorage
import com.artflow.studio.data.repository.canvas.CanvasRepositoryImpl
import com.artflow.studio.presentation.ui.viewmodel.ClipboardController
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock

/** Exercises the editor controller and real document history, including failed destructive commands. */
@OptIn(ExperimentalCoroutinesApi::class)
class ClipboardTransactionTest {
    private lateinit var repository: CanvasRepositoryImpl
    private lateinit var clipboard: ClipboardController
    private val queued = ArrayDeque<suspend () -> Unit>()
    private val red = 0xFFFF0000.toInt()
    private val blue = 0xFF0000FF.toInt()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        installRepository()
    }

    private fun installRepository(clipboardDispatcher: CoroutineDispatcher = Dispatchers.Default) {
        if (::repository.isInitialized) repository.dispose()
        repository = CanvasRepositoryImpl(ProjectStorage(mock(Context::class.java)), clipboardDispatcher)
        clipboard = ClipboardController(repository) { queued.addLast(it) }
    }

    @After
    fun tearDown() {
        repository.dispose()
        Dispatchers.resetMain()
    }

    private suspend fun fixture(): Long {
        repository.createCanvas(4, 2, 72)
        val source = repository.getActiveLayerId()
        assertTrue(repository.setLayerPixels(source, PixelBuffer.filled(4, 2, red), "Fixture"))
        return source
    }

    private suspend fun execute(action: ClipboardController.() -> Unit): Throwable? {
        clipboard.action()
        return runCatching { queued.removeFirst().invoke() }.exceptionOrNull()
    }

    private suspend fun pixels(layerId: Long): IntArray = requireNotNull(repository.layerPixels(layerId)).pixels

    @Test
    fun pasteUndoesTheWholeNewLayerAndRedoRestoresItsPixels() =
        runTest {
            val source = fixture()
            assertEquals(null, execute { copy() })
            val before = repository.getAllLayers()
            val depth = repository.undoDepth
            assertEquals(null, execute { paste() })
            val pasted = repository.getActiveLayerId()
            assertTrue(pasted != source)
            assertEquals(before.size + 1, repository.getAllLayers().size)
            assertEquals(depth + 1, repository.undoDepth)
            assertArrayEquals(IntArray(8) { red }, pixels(pasted))
            assertTrue(repository.undo())
            assertEquals(before, repository.getAllLayers())
            assertEquals(source, repository.getActiveLayerId())
            assertArrayEquals(IntArray(8) { red }, pixels(source))
            assertTrue(repository.redo())
            assertEquals(pasted, repository.getActiveLayerId())
            assertArrayEquals(IntArray(8) { red }, pixels(pasted))
        }

    @Test
    fun cutAndPasteMovesSoftCoverageInOneStepAndKeepsSourceAlphaLock() =
        runTest {
            val source = fixture()
            repository.setLayerAlphaLock(source, true)
            val selection = SelectionMask(4, 2, byteArrayOf(-1, -128, 0, 0, -1, -128, 0, 0))
            repository.setSelection(selection)
            val before = repository.getAllLayers()
            val depth = repository.undoDepth
            assertEquals(null, execute { cutAndPaste() })
            val pasted = repository.getActiveLayerId()
            val expectedSource = intArrayOf(0x00FF0000, 0x7FFF0000, red, red, 0x00FF0000, 0x7FFF0000, red, red)
            val expectedPaste = intArrayOf(red, 0x80FF0000.toInt(), 0, 0, red, 0x80FF0000.toInt(), 0, 0)
            assertArrayEquals(expectedSource, pixels(source))
            assertArrayEquals(expectedPaste, pixels(pasted))
            assertTrue(repository.getAllLayers().first { it.id == source }.isAlphaLocked)
            assertFalse(requireNotNull(repository.getActiveLayer()).isAlphaLocked)
            assertArrayEquals(selection.coverage, requireNotNull(repository.selection()).coverage)
            assertEquals(depth + 1, repository.undoDepth)
            assertTrue(repository.undo())
            assertEquals(before, repository.getAllLayers())
            assertArrayEquals(IntArray(8) { red }, pixels(source))
            assertTrue(repository.redo())
            assertArrayEquals(expectedSource, pixels(source))
            assertArrayEquals(expectedPaste, pixels(pasted))
        }

    @Test
    fun copyAndPasteKeepsTheSourceAndHasOneUndoStepEvenWhenSourceIsLocked() =
        runTest {
            val source = fixture()
            repository.setLayerLock(source, true)
            val count = repository.getAllLayers().size
            val depth = repository.undoDepth
            assertEquals(null, execute { copyAndPaste() })
            val pasted = repository.getActiveLayerId()
            assertEquals(depth + 1, repository.undoDepth)
            assertArrayEquals(pixels(source), pixels(pasted))
            assertFalse(requireNotNull(repository.getActiveLayer()).isLocked)
            assertTrue(repository.undo())
            assertEquals(count, repository.getAllLayers().size)
            assertArrayEquals(IntArray(8) { red }, pixels(source))
        }

    @Test
    fun layerLimitRefusesAllPasteCommandsWithoutErasingSourceHistoryOrPreviousClipboard() =
        runTest {
            fixture()
            assertEquals(null, execute { copy() })
            val source = requireNotNull(repository.selectMaterialChannel(MaterialChannel.METALLIC))
            repository.setLayerPixels(source, PixelBuffer.filled(4, 2, blue), "Changed source")
            while (repository.getAllLayers().size < ProjectStorage.MAX_LAYERS) repository.addLayer("Spare")
            val spare = repository.getActiveLayerId()
            repository.setActiveLayer(source)
            repository.setLayerName(source, "Undoable rename")
            assertTrue(repository.undo())
            val before = repository.getAllLayers()
            val depth = repository.undoDepth
            val redo = repository.redoDepth
            val mark = repository.historyMark
            listOf<ClipboardController.() -> Unit>({ paste() }, { copyAndPaste() }, { cutAndPaste() }).forEach { action ->
                assertEquals("Maximum project layer count reached", execute(action)?.message)
                assertEquals(before, repository.getAllLayers())
                assertArrayEquals(IntArray(8) { blue }, pixels(source))
                assertEquals(source, repository.getActiveLayerId())
                assertEquals(depth, repository.undoDepth)
                assertEquals(redo, repository.redoDepth)
                assertEquals(mark, repository.historyMark)
            }
            assertTrue(repository.removeLayer(spare))
            assertEquals(null, execute { paste() })
            assertEquals(MaterialChannel.COLOR, requireNotNull(repository.getActiveLayer()).materialChannel)
            assertArrayEquals(IntArray(8) { red }, pixels(repository.getActiveLayerId()))
        }

    @Test
    fun lockedOrProvisionalCutFailsWithoutCreatingHistoryOrChangingTheClipboard() =
        runTest {
            val source = fixture()
            repository.setLayerLock(source, true)
            var mark = repository.historyMark
            val count = repository.getAllLayers().size
            assertEquals("This layer cannot be cut", execute { cutAndPaste() }?.message)
            assertEquals(mark, repository.historyMark)
            assertFalse(clipboard.hasContent.value)
            repository.setLayerLock(source, false)
            val session = requireNotNull(repository.beginRasterEdit(source))
            session.buffer.fill(blue)
            mark = repository.historyMark
            assertEquals("Finish the current edit before using the clipboard", execute { cutAndPaste() }?.message)
            assertEquals(mark, repository.historyMark)
            assertEquals(count, repository.getAllLayers().size)
            assertArrayEquals(IntArray(8) { red }, pixels(source))
            assertFalse(clipboard.hasContent.value)
            repository.cancelRasterEdit(session)
        }

    @Test
    fun emptySelectionRefusesCombinedCommandsWithoutInsertingBlankLayers() =
        runTest {
            val source = fixture()
            repository.setSelection(SelectionMask(4, 2))
            val before = repository.getAllLayers()
            val mark = repository.historyMark
            assertEquals("Nothing is selected", execute { copyAndPaste() }?.message)
            assertEquals("Nothing is selected", execute { cutAndPaste() }?.message)
            assertEquals(before, repository.getAllLayers())
            assertEquals(mark, repository.historyMark)
            assertArrayEquals(IntArray(8) { red }, pixels(source))
            assertFalse(clipboard.hasContent.value)
            assertNotNull(repository.selection())
        }

    @Test
    fun pasteKeepsCopiedMaterialChannelWhenTheActiveChannelChanges() =
        runTest {
            val color = fixture()
            assertEquals(null, execute { copy() })
            val metallic = requireNotNull(repository.selectMaterialChannel(MaterialChannel.METALLIC))
            val gray = 0xFF999999.toInt()
            assertTrue(repository.setLayerPixels(metallic, PixelBuffer.filled(4, 2, gray), "Metallic fixture"))
            assertEquals(null, execute { paste() })
            assertEquals(MaterialChannel.COLOR, requireNotNull(repository.getActiveLayer()).materialChannel)
            assertArrayEquals(IntArray(8) { red }, pixels(repository.getActiveLayerId()))

            repository.setActiveLayer(metallic)
            assertEquals(null, execute { copy() })
            repository.setActiveLayer(color)
            assertEquals(null, execute { paste() })
            val pasted = requireNotNull(repository.getActiveLayer())
            assertEquals(MaterialChannel.METALLIC, pasted.materialChannel)
            assertArrayEquals(IntArray(8) { gray }, pixels(pasted.id))
            val visibleColor = requireNotNull(repository.compositeFrame(0, transparentBackground = true))
            assertArrayEquals(IntArray(8) { red }, visibleColor.pixels)
        }

    @Test
    fun combinedCutAndPasteAndCopyMergedPreserveTheirMaterialMeaning() =
        runTest {
            val color = fixture()
            val metallic = requireNotNull(repository.selectMaterialChannel(MaterialChannel.METALLIC))
            val gray = 0xFF666666.toInt()
            repository.setLayerPixels(metallic, PixelBuffer.filled(4, 2, gray), "Metallic fixture")
            assertEquals(null, execute { cutAndPaste() })
            assertEquals(MaterialChannel.METALLIC, requireNotNull(repository.getActiveLayer()).materialChannel)
            assertArrayEquals(IntArray(8), pixels(metallic))
            repository.setActiveLayer(color)
            assertEquals(null, execute { paste() })
            assertEquals(MaterialChannel.METALLIC, requireNotNull(repository.getActiveLayer()).materialChannel)
            assertArrayEquals(IntArray(8) { gray }, pixels(repository.getActiveLayerId()))

            assertEquals(null, execute { copyMerged() })
            assertEquals(null, execute { paste() })
            assertEquals(MaterialChannel.COLOR, requireNotNull(repository.getActiveLayer()).materialChannel)
            assertArrayEquals(IntArray(8) { red }, pixels(repository.getActiveLayerId()))
        }

    @Test
    fun basicCutRejectsLockedAndEmptySourcesWithoutReplacingThePreviousClipboard() =
        runTest {
            val source = fixture()
            assertEquals(null, execute { copy() })
            repository.setLayerPixels(source, PixelBuffer.filled(4, 2, blue), "New source")
            repository.setLayerLock(source, true)
            var mark = repository.historyMark
            assertEquals("This layer cannot be cut", execute { copy(cut = true) }?.message)
            assertEquals(mark, repository.historyMark)
            assertArrayEquals(IntArray(8) { blue }, pixels(source))
            repository.setLayerLock(source, false)
            repository.setSelection(SelectionMask(4, 2))
            mark = repository.historyMark
            assertEquals("Nothing is selected", execute { copy(cut = true) }?.message)
            assertEquals(mark, repository.historyMark)
            assertArrayEquals(IntArray(8) { blue }, pixels(source))
            repository.clearSelection()
            assertEquals(null, execute { paste() })
            assertArrayEquals(IntArray(8) { red }, pixels(repository.getActiveLayerId()))
        }

    @Test
    fun basicCutCommitsOneUndoStepAndPublishesThePixelsItActuallyRemoved() =
        runTest {
            val source = fixture()
            val depth = repository.undoDepth
            val count = repository.getAllLayers().size
            assertEquals(null, execute { copy(cut = true) })
            assertArrayEquals(IntArray(8), pixels(source))
            assertEquals(depth + 1, repository.undoDepth)
            assertEquals(count, repository.getAllLayers().size)
            assertTrue(repository.undo())
            assertArrayEquals(IntArray(8) { red }, pixels(source))
            assertTrue(repository.redo())
            assertArrayEquals(IntArray(8), pixels(source))
            assertEquals(null, execute { paste() })
            assertArrayEquals(IntArray(8) { red }, pixels(repository.getActiveLayerId()))
        }

    @Test
    fun basicCutCannotEraseNewPixelsOrPublishOldPixelsAfterItsWorkerSnapshotBecomesStale() =
        runTest {
            installRepository(StandardTestDispatcher(testScheduler))
            val source = fixture()
            assertEquals(null, execute { copy() })
            repository.setLayerPixels(source, PixelBuffer.filled(4, 2, blue), "First source")
            // The real snapshot runs immediately; its worker is queued on our scheduler and cannot
            // prepare or commit until runCurrent below explicitly releases it after the newer edit.
            val cut = async(Dispatchers.Main.immediate, start = CoroutineStart.UNDISPATCHED) { execute { copy(cut = true) } }
            assertFalse(cut.isCompleted)
            val green = 0xFF00FF00.toInt()
            val edit =
                async(Dispatchers.Main.immediate, start = CoroutineStart.UNDISPATCHED) {
                    repository.setLayerPixels(source, PixelBuffer.filled(4, 2, green), "Newer source")
                }
            assertTrue(edit.isCompleted)
            assertTrue(edit.await())
            val mark = repository.historyMark
            val depth = repository.undoDepth
            runCurrent()
            assertEquals("The artwork changed; try the command again", cut.await()?.message)
            assertEquals(mark, repository.historyMark)
            assertEquals(depth, repository.undoDepth)
            assertArrayEquals(IntArray(8) { green }, pixels(source))
            assertEquals(null, execute { paste() })
            assertArrayEquals(IntArray(8) { red }, pixels(repository.getActiveLayerId()))
        }

    @Test
    fun basicCutRejectsASelectionChangedWhilePixelsWereBeingPrepared() =
        runTest {
            installRepository(StandardTestDispatcher(testScheduler))
            val source = fixture()
            assertEquals(null, execute { copy() })
            repository.setLayerPixels(source, PixelBuffer.filled(4, 2, blue), "New source")
            val cut = async(Dispatchers.Main.immediate, start = CoroutineStart.UNDISPATCHED) { execute { copy(cut = true) } }
            assertFalse(cut.isCompleted)
            repository.setSelection(SelectionMask(4, 2))
            val mark = repository.historyMark
            runCurrent()
            assertEquals("The selection changed; copy it again", cut.await()?.message)
            assertEquals(mark, repository.historyMark)
            assertArrayEquals(IntArray(8) { blue }, pixels(source))
            repository.clearSelection()
            assertEquals(null, execute { paste() })
            assertArrayEquals(IntArray(8) { red }, pixels(repository.getActiveLayerId()))
        }
}
