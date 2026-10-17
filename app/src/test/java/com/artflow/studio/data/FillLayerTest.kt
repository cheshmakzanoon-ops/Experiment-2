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
 * Layer > Fill Layer through the real repository. The layer menu calls [ClipboardController.fill],
 * so these tests run that controller and then its queued edits against [CanvasRepositoryImpl].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FillLayerTest {
    private lateinit var repository: CanvasRepositoryImpl
    private val red = 0xFFFF0000.toInt()
    private val blue = 0xFF0000FF.toInt()

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

    private suspend fun openLayer(): Long {
        repository.createCanvas(8, 6, 72)
        return repository.getActiveLayerId()
    }

    private suspend fun pixels(layer: Long): IntArray = requireNotNull(repository.layerPixels(layer)).pixels.copyOf()

    /** Runs Fill Layer for [layer] the way the menu does: the controller queues the edit, then the queue runs. */
    private suspend fun fillLayer(
        layer: Long,
        colour: Int,
    ) {
        val queued = mutableListOf<suspend () -> Unit>()
        ClipboardController(repository) { queued += it }.fill(colour, layer)
        queued.forEach { it() }
    }

    private suspend fun paintLayer(
        layer: Long,
        buffer: PixelBuffer,
    ) {
        assertTrue(repository.setLayerPixels(layer, buffer, "Fixture"))
    }

    @Test
    fun fillCoversEveryPixelOfATransparentLayer() =
        runTest {
            val layer = openLayer()
            assertTrue(pixels(layer).all { it ushr 24 == 0 })
            fillLayer(layer, red)
            assertTrue(pixels(layer).all { it == red })
        }

    @Test
    fun alphaLockKeepsTransparentPixelsTransparentAndEachPixelsAlpha() =
        runTest {
            val layer = openLayer()
            val fixture =
                PixelBuffer(8, 6).also {
                    it.pixels[0] = red
                    it.pixels[1] = 0
                    it.pixels[2] = 0x80FF0000.toInt()
                }
            paintLayer(layer, fixture)
            assertTrue(repository.setLayerAlphaLock(layer, true))
            fillLayer(layer, blue)
            val after = pixels(layer)
            assertEquals(blue, after[0])
            assertEquals(0, after[1])
            assertEquals(0x800000FF.toInt(), after[2])
        }

    @Test
    fun selectionRestrictsTheFill() =
        runTest {
            val layer = openLayer()
            paintLayer(layer, PixelBuffer.filled(8, 6, red))
            val selected = SelectionMask(8, 6).also { it.coverage[5] = 255.toByte() }
            repository.setSelection(selected)
            fillLayer(layer, blue)
            val after = pixels(layer)
            assertEquals(blue, after[5])
            assertTrue(after.indices.filter { it != 5 }.all { after[it] == red })
        }

    @Test
    fun undoRestoresThePixelsBeforeTheFill() =
        runTest {
            val layer = openLayer()
            paintLayer(layer, PixelBuffer.filled(8, 6, red))
            fillLayer(layer, blue)
            assertTrue(pixels(layer).all { it == blue })
            assertTrue(repository.undo())
            assertTrue(pixels(layer).all { it == red })
        }
}
