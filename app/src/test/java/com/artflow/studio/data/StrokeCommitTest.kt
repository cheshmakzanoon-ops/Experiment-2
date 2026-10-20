package com.artflow.studio.data

import android.content.Context
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.data.local.ProjectStorage
import com.artflow.studio.data.repository.canvas.CanvasRepositoryImpl
import com.artflow.studio.domain.model.brush.BrushParams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock

/** Stroke commits take a copy of the layer made while the stroke is drawn; these check what lands on the layer. */
@OptIn(ExperimentalCoroutinesApi::class)
class StrokeCommitTest {
    private lateinit var repository: CanvasRepositoryImpl

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

    private suspend fun open(): Long {
        repository.createCanvas(8, 6, 72)
        return repository.getActiveLayerId()
    }

    private suspend fun image(): PixelBuffer = requireNotNull(repository.compositeFrame(0, transparentBackground = true))

    private fun painted(
        pixels: PixelBuffer,
        x: Int,
        y: Int,
    ): Boolean = (pixels.pixels[y * pixels.width + x] ushr 24) != 0

    @Test
    fun aCommittedStrokeKeepsTheStrokesBeneathIt() =
        runTest {
            val layer = open()
            val first = repository.beginStroke(1.5f, 3f, 1f, BrushParams(size = 4f), layer)
            repository.endStroke(first)
            val second = repository.beginStroke(6.5f, 3f, 1f, BrushParams(size = 4f), layer)
            repository.endStroke(second)
            val pixels = image()
            assertTrue("the first stroke survives the second commit", painted(pixels, 1, 3))
            assertTrue("the second stroke lands", painted(pixels, 6, 3))
        }

    @Test
    fun twoStrokesOnOneLayerBothLandWhenTheyEndInTurn() =
        runTest {
            // Both strokes copy the layer before either commits, so the second commit must not reuse the
            // first copy: the first commit changed the layer.
            val layer = open()
            val first = repository.beginStroke(1.5f, 3f, 1f, BrushParams(size = 4f), layer)
            val second = repository.beginStroke(6.5f, 3f, 1f, BrushParams(size = 4f), layer)
            repository.endStroke(first)
            repository.endStroke(second)
            val pixels = image()
            assertTrue("the first stroke is not lost", painted(pixels, 1, 3))
            assertTrue("the second stroke lands", painted(pixels, 6, 3))
        }

    @Test
    fun aCancelledStrokeLeavesTheLayerEmpty() =
        runTest {
            val layer = open()
            val stroke = repository.beginStroke(3f, 3f, 1f, BrushParams(size = 4f), layer)
            repository.continueStroke(stroke, 4f, 3f, 1f)
            repository.cancelStroke(stroke)
            assertTrue("nothing is painted", image().pixels.all { (it ushr 24) == 0 })
        }
}
