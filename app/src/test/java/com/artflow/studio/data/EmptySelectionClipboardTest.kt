package com.artflow.studio.data

import android.content.Context
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.core.pixels.SelectionMask
import com.artflow.studio.data.local.ProjectStorage
import com.artflow.studio.data.repository.canvas.CanvasRepositoryImpl
import com.artflow.studio.presentation.ui.viewmodel.ClipboardController
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

/**
 * Copy, Cut and Paste with an emptied selection. Procreate treats that as "nothing is selected", so
 * copying must not fill the clipboard with blank pixels and Paste must not add an invisible layer.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EmptySelectionClipboardTest {
    private lateinit var repository: CanvasRepositoryImpl
    private val red = 0xFFFF0000.toInt()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        repository = CanvasRepositoryImpl(ProjectStorage(mock(Context::class.java)))
    }

    @After
    fun tearDown() {
        repository.dispose()
        Dispatchers.resetMain()
    }

    private suspend fun openPaintedLayer(): Long {
        repository.createCanvas(8, 6, 72)
        val layer = repository.getActiveLayerId()
        assertTrue(repository.setLayerPixels(layer, PixelBuffer.filled(8, 6, red), "Fixture"))
        repository.setSelection(SelectionMask(8, 6))
        return layer
    }

    /** Queues one clipboard action on a fresh controller, then runs it as the editor would. */
    private suspend fun runClipboard(action: ClipboardController.() -> Unit): Throwable? {
        val queued = mutableListOf<suspend () -> Unit>()
        ClipboardController(repository) { queued += it }.action()
        return try {
            queued.single().invoke()
            null
        } catch (error: Throwable) {
            error
        }
    }

    @Test
    fun copyWithAnEmptySelectionRefusesAndKeepsTheClipboard() =
        runTest {
            val layer = openPaintedLayer()
            val before = repository.getAllLayers().size

            val copyError = runClipboard { copy() }
            assertEquals("Nothing is selected", copyError?.message)
            assertEquals(before, repository.getAllLayers().size)

            // Nothing was copied, so Paste must report an empty clipboard instead of adding a blank layer.
            val pasteError = runClipboard { paste() }
            assertEquals("Nothing has been copied yet", pasteError?.message)
            assertEquals(before, repository.getAllLayers().size)
            assertTrue(requireNotNull(repository.layerPixels(layer)).pixels.all { it == red })
        }

    @Test
    fun cutWithAnEmptySelectionLeavesThePixelsAlone() =
        runTest {
            val layer = openPaintedLayer()
            val cutError = runClipboard { copy(cut = true) }
            assertEquals("Nothing is selected", cutError?.message)
            assertTrue(requireNotNull(repository.layerPixels(layer)).pixels.all { it == red })
        }

    @Test
    fun copyAllWithAnEmptySelectionRefuses() =
        runTest {
            openPaintedLayer()
            val copyAllError = runClipboard { copyMerged() }
            assertEquals("Nothing is selected", copyAllError?.message)
        }
}
