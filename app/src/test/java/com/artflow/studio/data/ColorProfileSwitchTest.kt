package com.artflow.studio.data

import android.content.Context
import com.artflow.studio.core.color.ColorProfile
import com.artflow.studio.core.color.ColorProfiles
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import kotlin.math.abs

/** Switching the profile converts stored colours, so they look the same; real document operations. */
@OptIn(ExperimentalCoroutinesApi::class)
class ColorProfileSwitchTest {
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

    private suspend fun paint(
        layer: Long,
        color: Int,
    ) {
        assertTrue(repository.setLayerPixels(layer, PixelBuffer.filled(8, 6, color), "Fixture"))
    }

    private suspend fun firstPixel(layer: Long): Int = requireNotNull(repository.layerPixels(layer)).pixels.first()

    private fun assertWithinOneCode(
        expected: Int,
        actual: Int,
    ) {
        for (shift in listOf(16, 8, 0)) {
            val difference = abs(((expected shr shift) and 0xFF) - ((actual shr shift) and 0xFF))
            assertTrue("channel at shift $shift differs by $difference", difference <= 1)
        }
    }

    @Test
    fun switchingToDisplayP3KeepsEachColourLookingTheSame() = runTest {
        repository.createCanvas(8, 6, 72)
        val layer = repository.getActiveLayerId()
        paint(layer, red)
        assertTrue(repository.setColorProfile(ColorProfile.DISPLAY_P3, undoable = true))
        val stored = firstPixel(layer)
        assertEquals(ColorProfiles.convert(red, ColorProfile.SRGB, ColorProfile.DISPLAY_P3), stored)
        assertWithinOneCode(red, ColorProfiles.convert(stored, ColorProfile.DISPLAY_P3, ColorProfile.SRGB))
    }

    @Test
    fun aSwitchAcrossSeveralLayersIsOneUndoStep() = runTest {
        repository.createCanvas(8, 6, 72)
        val first = repository.getActiveLayerId()
        val second = repository.addLayer(name = "Second").id
        paint(first, red)
        paint(second, blue)
        val depth = repository.undoDepth
        assertTrue(repository.setColorProfile(ColorProfile.DISPLAY_P3, undoable = true))
        assertEquals(depth + 1, repository.undoDepth)
        assertTrue(repository.undo())
        assertEquals(ColorProfile.SRGB, repository.getColorProfile())
        assertEquals(red, firstPixel(first))
        assertEquals(blue, firstPixel(second))
    }

    @Test
    fun textColourAndBackgroundConvertWithTheLayers() = runTest {
        repository.createCanvas(8, 6, 72)
        assertTrue(repository.setCanvasBackgroundColor(red))
        val text = TextLayerContent("Hi", TextLayout.TextStyle(), red, 0f, 0f)
        val layer = requireNotNull(repository.addTextLayer(text, PixelBuffer.filled(8, 6, blue)))
        assertTrue(repository.setColorProfile(ColorProfile.DISPLAY_P3, undoable = true))
        val converted = repository.getAllLayers().first { it.id == layer }.textContent
        assertEquals(ColorProfiles.convert(red, ColorProfile.SRGB, ColorProfile.DISPLAY_P3), converted?.color)
        assertEquals(ColorProfiles.convert(red, ColorProfile.SRGB, ColorProfile.DISPLAY_P3), repository.getBackgroundColor())
    }
}
