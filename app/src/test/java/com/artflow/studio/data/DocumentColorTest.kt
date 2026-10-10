package com.artflow.studio.data

import android.content.Context
import com.artflow.studio.core.color.ColorProfile
import com.artflow.studio.core.color.ColorProfiles
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

/** Interface colours are sRGB; the canvas stores its own space, so colours must convert at the boundary. */
@OptIn(ExperimentalCoroutinesApi::class)
class DocumentColorTest {
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
    fun anSrgbCanvasStoresInterfaceColoursUnchanged() =
        runTest {
            repository.createCanvas(8, 6, 72)
            assertEquals(red, repository.documentColor(red))
            assertEquals(red, repository.displayColor(red))
        }

    @Test
    fun aDisplayP3CanvasStoresTheConvertedColourAndShowsItBack() =
        runTest {
            repository.createCanvas(8, 6, 72)
            assertTrue(repository.setColorProfile(ColorProfile.DISPLAY_P3, undoable = true))
            val stored = repository.documentColor(red)
            assertEquals(ColorProfiles.convert(red, ColorProfile.SRGB, ColorProfile.DISPLAY_P3), stored)
            assertWithinOneCode(red, repository.displayColor(stored))
        }

    @Test
    fun aBackgroundSetInSrgbLooksTheSameOnADisplayP3Canvas() =
        runTest {
            repository.createCanvas(8, 6, 72)
            assertTrue(repository.setColorProfile(ColorProfile.DISPLAY_P3, undoable = true))
            assertTrue(repository.setCanvasBackgroundColor(blue))
            assertEquals(repository.documentColor(blue), repository.getBackgroundColor())
            assertWithinOneCode(blue, repository.displayColor(repository.getBackgroundColor()))
        }

    @Test
    fun clearingACanvasOnDisplayP3ConvertsTheClearColour() =
        runTest {
            repository.createCanvas(8, 6, 72)
            assertTrue(repository.setColorProfile(ColorProfile.DISPLAY_P3, undoable = true))
            repository.clearCanvas(red)
            assertEquals(repository.documentColor(red), repository.getBackgroundColor())
        }
}
