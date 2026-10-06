package com.artflow.studio.data

import android.content.Context
import com.artflow.studio.core.pixels.PixelBuffer
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock

/** Layer Select picks the topmost visible layer with paint under the finger. */
@OptIn(ExperimentalCoroutinesApi::class)
class LayerSelectTest {
    private lateinit var repository: CanvasRepositoryImpl

    @Before
    fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        repository = CanvasRepositoryImpl(ProjectStorage(mock(Context::class.java)))
    }

    @After
    fun cleanup() {
        repository.dispose()
        Dispatchers.resetMain()
    }

    @Test
    fun picksTheTopmostVisibleLayerPaintedThere() =
        runTest {
            repository.createCanvas(8, 6, 72)
            val bottom = repository.getActiveLayerId()
            assertTrue(repository.setLayerPixels(bottom, painted(1 to 1, 5 to 5), "Paint"))
            val top = repository.addLayer(null, null, 1f).id
            assertTrue(repository.setLayerPixels(top, painted(1 to 1), "Paint"))

            assertEquals(top, repository.layerAt(1, 1))
            assertEquals(bottom, repository.layerAt(5, 5))
            assertNull(repository.layerAt(3, 3))
            assertNull(repository.layerAt(-1, 0))

            assertTrue(repository.setLayerVisibility(top, false))
            assertEquals(bottom, repository.layerAt(1, 1))
        }

    private fun painted(vararg points: Pair<Int, Int>): PixelBuffer =
        PixelBuffer(8, 6).apply { points.forEach { (x, y) -> setSafe(x, y, 0xFFFF0000.toInt()) } }
}
