package com.artflow.studio.data

import android.content.Context
import com.artflow.studio.core.pixels.IntBounds
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.core.text.TextLayerContent
import com.artflow.studio.core.text.TextLayout
import com.artflow.studio.data.local.ProjectStorage
import com.artflow.studio.data.repository.canvas.CanvasRepositoryImpl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock

/**
 * Crop, rotate and resize rasterise editable text: the glyphs stay in the layer's pixels, and the layer
 * stops being text. Real document operations; only the Android Context and main dispatcher are replaced.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TextLayerTransformTest {
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

    /** Non-uniform pixels with a partial-alpha pixel, so rotation and crop errors show up. */
    private fun glyphs(): PixelBuffer =
        PixelBuffer(8, 6, IntArray(48) { i -> if (i == 5) 0x80FF0000.toInt() else 0xFF000000.toInt() or (i * 5) })

    private fun content(text: String = "Hi") = TextLayerContent(text, TextLayout.TextStyle(), red, 0f, 0f)

    private suspend fun openWithText(): Long {
        repository.createCanvas(8, 6, 72)
        return requireNotNull(repository.addTextLayer(content(), glyphs()))
    }

    private fun textOf(layerId: Long): TextLayerContent? = repository.getAllLayers().first { it.id == layerId }.textContent

    private fun assertSamePixels(expected: PixelBuffer, actual: PixelBuffer?) {
        assertNotNull(actual)
        assertEquals(expected.width, actual!!.width)
        assertEquals(expected.height, actual.height)
        assertArrayEquals(expected.pixels, actual.pixels)
    }

    @Test
    fun rotationRasterisesEditableText() = runTest {
        val layer = openWithText()
        val before = requireNotNull(repository.layerPixels(layer))
        assertTrue(repository.rotateCanvas(90))
        assertNull(textOf(layer))
        assertSamePixels(before.rotated(90), repository.layerPixels(layer))
    }

    @Test
    fun cropRasterisesTextAndKeepsTheWindow() = runTest {
        val layer = openWithText()
        val before = requireNotNull(repository.layerPixels(layer))
        val window = IntBounds(1, 1, 5, 4)
        assertTrue(repository.cropCanvas(window))
        assertNull(textOf(layer))
        assertSamePixels(before.crop(window), repository.layerPixels(layer))
    }

    @Test
    fun resizeRasterisesText() = runTest {
        val layer = openWithText()
        assertTrue(repository.resizeCanvas(16, 12, true))
        assertNull(textOf(layer))
        assertEquals(16, requireNotNull(repository.layerPixels(layer)).width)
    }

    @Test
    fun undoRestoresEditableTextAfterARotation() = runTest {
        val layer = openWithText()
        val original = requireNotNull(repository.layerPixels(layer))
        val depth = repository.undoDepth
        assertTrue(repository.rotateCanvas(90))
        assertEquals(depth + 1, repository.undoDepth)
        assertTrue(repository.undo())
        assertEquals(depth, repository.undoDepth)
        assertEquals(content(), textOf(layer))
        assertSamePixels(original, repository.layerPixels(layer))
    }

    @Test
    fun aRejectedCropKeepsEditableTextAndAddsNoHistory() = runTest {
        val layer = openWithText()
        val depth = repository.undoDepth
        assertFalse(repository.cropCanvas(IntBounds(100, 100, 200, 200)))
        assertEquals(content(), textOf(layer))
        assertEquals(depth, repository.undoDepth)
    }

    @Test
    fun aRasterisedTextLayerCannotBeEditedAsTextAgain() = runTest {
        val layer = openWithText()
        assertTrue(repository.rotateCanvas(90))
        val rotated = PixelBuffer(6, 8, IntArray(48) { 0xFF0000FF.toInt() })
        assertFalse(repository.setTextLayer(layer, content("Stale"), rotated))
        assertNull(textOf(layer))
    }

    @Test
    fun aStillEditableTextLayerCanBeEdited() = runTest {
        val layer = openWithText()
        assertTrue(repository.setTextLayer(layer, content("Edited"), glyphs()))
        assertEquals(content("Edited"), textOf(layer))
        assertNotNull(repository.layerPixels(layer))
    }
}
