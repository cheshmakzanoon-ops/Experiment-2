package com.artflow.studio.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.core.pixels.SelectionMask
import com.artflow.studio.data.local.ProjectStorage
import com.artflow.studio.data.repository.canvas.CanvasRepositoryImpl
import com.artflow.studio.domain.model.brush.BrushParams
import com.artflow.studio.domain.model.brush.Stroke
import com.artflow.studio.domain.model.brush.StrokePoint
import com.artflow.studio.domain.model.layer.BlendMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicLong

/** A live preview must show the real layer result without mutating saves, exports or undo history. */
@RunWith(AndroidJUnit4::class)
class StrokePreviewRegressionTest {
    private lateinit var storage: ProjectStorage
    private lateinit var repository: CanvasRepositoryImpl
    private var projectId = 0L
    private val brush = BrushParams(size = 24f, pressureToSize = 0f, pressureToOpacity = 0f)

    @Before
    fun setUp() {
        storage = ProjectStorage(ApplicationProvider.getApplicationContext<Context>())
        repository = CanvasRepositoryImpl(storage)
        projectId = IDS.incrementAndGet()
        storage.deleteProjectFiles(projectId)
    }

    @After
    fun cleanUp() =
        runBlocking(Dispatchers.Main) {
            repository.dispose()
            storage.deleteProjectFiles(projectId)
        }

    private suspend fun open(): Long {
        repository.loadOrCreate(projectId, 32, 32, 72)
        return repository.getActiveLayerId()
    }

    private fun begin(
        layer: Long,
        params: BrushParams = brush,
        eraser: Boolean = false,
    ): Long {
        repository.setStrokeColor(0xFFFF0000.toInt())
        return repository.beginStroke(16f, 16f, 1f, params, layer, eraser)
    }

    private suspend fun preview(): IntArray = requireNotNull(repository.compositePreview()).pixels

    @Test
    fun previewEqualsCommitForPressureGrainOpacitySelectionAndLayerBlend() =
        runBlocking(Dispatchers.Main) {
            val layer = open()
            repository.setLayerPixels(layer, PixelBuffer.filled(32, 32, 0xFF0000FF.toInt()), "Background fixture")
            val ink = repository.addLayer("Ink").id
            repository.setLayerOpacity(ink, 0.6f)
            repository.setLayerBlendMode(ink, BlendMode.MULTIPLY)
            val selection = SelectionMask(32, 32).apply { coverage.fill(128.toByte()) }
            repository.setSelection(selection)
            val params = brush.copy(opacity = 0.5f, textureId = "paper", blendTexture = true, scatter = 0.2f)
            val before = preview()
            val depth = repository.undoDepth
            val id = begin(ink, params)
            repository.continueStroke(id, 22f, 16f, 0.4f)
            val provisional = preview()
            assertFalse(before.contentEquals(provisional))
            assertEquals(depth, repository.undoDepth)
            repository.endStroke(id)
            assertArrayEquals(provisional, preview())
            assertEquals(depth + 1, repository.undoDepth)
        }

    @Test
    fun liveEraserRevealsUnderlyingLayersInsteadOfPaintingInkOnTop() =
        runBlocking(Dispatchers.Main) {
            val background = open()
            repository.setLayerPixels(background, PixelBuffer.filled(32, 32, 0xFF0000FF.toInt()), "Blue background")
            val ink = repository.addLayer("Red ink").id
            repository.setLayerPixels(ink, PixelBuffer.filled(32, 32, 0xFFFF0000.toInt()), "Red top layer")
            val id = begin(ink, eraser = true)
            val provisional = preview()
            assertEquals(0xFF0000FF.toInt(), provisional[16 * 32 + 16])
            repository.endStroke(id)
            assertArrayEquals(provisional, preview())
        }

    @Test
    fun inFlightStrokeIsHiddenByHigherOpaqueLayer() =
        runBlocking(Dispatchers.Main) {
            val lower = open()
            val upper = repository.addLayer("Opaque cover").id
            repository.setLayerPixels(upper, PixelBuffer.filled(32, 32, 0xFF00FF00.toInt()), "Cover")
            val id = begin(lower)
            assertTrue(preview().all { it == 0xFF00FF00.toInt() })
            repository.endStroke(id)
            assertTrue(preview().all { it == 0xFF00FF00.toInt() })
        }

    @Test
    fun cancelledStrokeCannotLeakIntoExportSaveOrUndo() =
        runBlocking(Dispatchers.Main) {
            val layer = open()
            repository.saveCanvas(projectId)
            val before = preview()
            val depth = repository.undoDepth
            val id = begin(layer)
            assertFalse(before.contentEquals(preview()))
            assertTrue(
                repository
                    .exportSnapshot(allFrames = false, includeHidden = false, includeLayers = false)
                    .frames
                    .single()
                    .pixels
                    .all { it == 0 },
            )
            repository.saveCanvas(projectId)
            repository.cancelStroke(id)
            assertArrayEquals(before, preview())
            assertEquals(depth, repository.undoDepth)
            repository.loadCanvas(projectId)
            assertArrayEquals(before, preview())
        }

    @Test
    fun previewHonorsAlphaLockAndLayerMask() =
        runBlocking(Dispatchers.Main) {
            val layer = open()
            repository.setLayerPixels(layer, PixelBuffer.filled(32, 32, 0x400000FF), "Low alpha fixture")
            repository.setLayerAlphaLock(layer, true)
            val mask = SelectionMask(32, 32).apply { coverage.fill(128.toByte()) }
            repository.addLayerMask(layer, mask)
            val id = begin(layer)
            val provisional = preview()
            assertTrue(provisional.all { (it ushr 24) == 32 })
            repository.endStroke(id)
            assertArrayEquals(provisional, preview())
        }

    @Test
    fun legacyVectorStrokesAreBakedBeforeNewPaintInsteadOfCoveringIt() =
        runBlocking(Dispatchers.Main) {
            val layer = open()
            val old =
                Stroke(
                    id = 5L,
                    points = listOf(StrokePoint(16f, 16f, timestamp = 0)),
                    brushParams = brush,
                    layerId = layer,
                    color = 0xFF0000FF.toInt(),
                    timestamp = 0,
                )
            repository.replaceLayerStrokes(layer, listOf(old))
            val id = begin(layer)
            assertEquals(0xFFFF0000.toInt(), preview()[16 * 32 + 16])
            repository.endStroke(id)
            assertEquals(0xFFFF0000.toInt(), preview()[16 * 32 + 16])
            assertTrue(repository.getActiveLayer()!!.strokes.isEmpty())
            repository.undo()
            assertEquals(0xFF0000FF.toInt(), preview()[16 * 32 + 16])
        }

    companion object {
        private val IDS = AtomicLong(9_100_000)
    }
}
