package com.artflow.studio.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.data.local.ProjectStorage
import com.artflow.studio.data.repository.canvas.CanvasRasterBudget
import com.artflow.studio.data.repository.canvas.CanvasRepositoryImpl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real Android PNG/storage round trips at an injected, deterministic decoded-raster limit. */
@RunWith(AndroidJUnit4::class)
class CanvasReopenBudgetDeviceTest {
    private lateinit var storage: ProjectStorage
    private lateinit var repository: CanvasRepositoryImpl

    @Before
    fun setUp() {
        storage = ProjectStorage(ApplicationProvider.getApplicationContext<Context>())
        storage.deleteProjectFiles(PROJECT)
        repository = newRepository()
    }

    @After
    fun tearDown() =
        runBlocking(Dispatchers.Main) {
            repository.dispose()
            storage.deleteProjectFiles(PROJECT)
        }

    @Test
    fun duplicatedFramesAndLayersStayReopenableAndRetainIndependentPixels() =
        runBlocking(Dispatchers.Main) {
            repository.loadOrCreate(PROJECT, SIZE, SIZE, 72)
            assertTrue(repository.setLayerPixels(repository.getActiveLayerId(), PixelBuffer.filled(SIZE, SIZE, RED), "Original"))
            repeat(8) { repository.addFrame(true) }
            val source = repository.getActiveLayerId()
            val duplicate = requireNotNull(repository.duplicateLayer(source))
            val before = repository.frames()
            val history = repository.historyMark
            assertTrue(runCatching { repository.addFrame(true) }.exceptionOrNull() is IllegalArgumentException)
            assertTrue(runCatching { repository.duplicateLayer(duplicate) }.exceptionOrNull() is IllegalArgumentException)
            assertEquals(history, repository.historyMark)
            assertEquals(before, repository.frames())
            assertTrue(repository.setLayerPixels(duplicate, PixelBuffer.filled(SIZE, SIZE, BLUE), "Independent copy"))
            assertNotNull(repository.saveCanvas(PROJECT))
            repository.dispose()
            repository = newRepository()
            assertNotNull(repository.loadCanvas(PROJECT))
            assertEquals(9, repository.frames().size)
            assertEquals(10, repository.frames().sumOf { it.layers.size })
            assertEquals(RED, requireNotNull(repository.compositeFrame(0)).pixels.first())
            assertEquals(RED, requireNotNull(repository.layerPixels(source)).pixels.first())
            assertEquals(BLUE, requireNotNull(repository.layerPixels(duplicate)).pixels.first())
        }

    private fun newRepository(): CanvasRepositoryImpl =
        CanvasRepositoryImpl(storage, Dispatchers.Default, rasterBudget = CanvasRasterBudget(512L * 1024))

    private companion object {
        const val PROJECT = 9_400_085L
        const val SIZE = 64
        val RED = 0xFFFF0000.toInt()
        val BLUE = 0xFF0000FF.toInt()
    }
}
