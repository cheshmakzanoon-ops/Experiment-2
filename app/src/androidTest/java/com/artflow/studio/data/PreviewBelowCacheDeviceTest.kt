package com.artflow.studio.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.data.local.ProjectStorage
import com.artflow.studio.data.repository.canvas.CanvasRepositoryImpl
import com.artflow.studio.domain.model.brush.BrushParams
import com.artflow.studio.domain.model.layer.BlendMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Live frames that reuse the layers below the stroke must match a full redraw exactly. */
@RunWith(AndroidJUnit4::class)
class PreviewBelowCacheDeviceTest {
    private lateinit var storage: ProjectStorage
    private lateinit var repository: CanvasRepositoryImpl
    private val projectId = 9_400_002L

    @Before
    fun setUp() {
        storage = ProjectStorage(ApplicationProvider.getApplicationContext<Context>())
        repository = CanvasRepositoryImpl(storage)
        storage.deleteProjectFiles(projectId)
    }

    @After
    fun cleanUp() =
        runBlocking(Dispatchers.Main) {
            repository.dispose()
            storage.deleteProjectFiles(projectId)
        }

    private fun gradient(seed: Int): PixelBuffer =
        PixelBuffer(SIZE, SIZE).also { buffer ->
            for (y in 0 until SIZE) {
                for (x in 0 until SIZE) {
                    val alpha = (x * 2 + y + seed * 40) % 256
                    buffer.pixels[y * SIZE + x] = (alpha shl 24) or ((x * 3 + seed * 50) % 256 shl 16) or ((y * 5) % 256 shl 8) or seed * 60
                }
            }
        }

    @Test
    fun framesMatchAFullRedrawWhilePaintingAMiddleLayer() =
        runBlocking(Dispatchers.Main) {
            repository.loadOrCreate(projectId, SIZE, SIZE, 72)
            repository.setLayerPixels(repository.getActiveLayerId(), gradient(0), "Below 1")
            val second = repository.addLayer("Below 2").id
            repository.setLayerPixels(second, gradient(1), "Below 2")
            repository.setLayerBlendMode(second, BlendMode.MULTIPLY)
            repository.setLayerOpacity(second, 0.7f)
            val ink = repository.addLayer("Ink").id
            val above = repository.addLayer("Above").id
            repository.setLayerPixels(above, gradient(2), "Above")
            repository.setLayerBlendMode(above, BlendMode.SCREEN)
            repository.setActiveLayer(ink)
            repository.setStrokeColor(0xC0204080.toInt())
            repeat(2) { round ->
                requireNotNull(repository.compositePreviewFrame())
                val params = BrushParams(size = 10f, opacity = 0.8f, textureId = "paper", blendTexture = true)
                val stroke = repository.beginStroke(20f + round * 30f, 30f, 0.6f, params, ink, false)
                for (sample in 1..STEPS) {
                    repository.continueStroke(stroke, 20f + round * 30f + sample * 6f, 30f + (sample % 5) * 9f, 0.3f + sample * 0.03f)
                    val frame = requireNotNull(repository.compositePreviewFrame())
                    val full = requireNotNull(repository.compositePreview())
                    assertArrayEquals("round $round sample $sample", full.pixels, frame.buffer.pixels)
                }
                repository.endStroke(stroke)
            }
        }

    private companion object {
        const val SIZE = 192
        const val STEPS = 14
    }
}
