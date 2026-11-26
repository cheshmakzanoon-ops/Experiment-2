package com.artflow.studio

import android.view.MotionEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.artflow.studio.core.render.PhysicalPaintPlane
import com.artflow.studio.core.tool.ToolType
import com.artflow.studio.domain.model.brush.PhysicalPaintMode
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic MotionEvents drive the real View and document, not a replacement input handler. */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class PhysicalTransformDeviceTest : CanvasInputTestFixture() {
    @Test
    fun pointerMoveAndTransformResetPreserveSavedPhysicalPaint() =
        withCanvas(143) { canvas ->
            val layer = repository.getActiveLayerId()
            val params = brush.brushParams.copy(size = 12f, physicalMode = PhysicalPaintMode.WATERCOLOR, waterAmount = 0.9f)
            val stroke = repository.beginStroke(12f, 20f, 1f, params, layer, timestamp = 0L)
            repository.continueStroke(stroke, 28f, 20f, 1f, timestamp = 16L)
            repository.endStroke(stroke)
            val project = repository.projectId()

            suspend fun savedPlane(): PhysicalPaintPlane {
                assertNotNull(repository.saveCanvas(project))
                val saved = requireNotNull(storage.loadDocument(project)).layers.first { it.id == layer }
                return PhysicalPaintPlane.decodeBinary(requireNotNull(storage.readRaster(project, requireNotNull(saved.physicalPaintFile))))
            }

            val originalWetness = savedPlane().wetnessAt(20, 20)
            assertTrue(originalWetness > 0.1f)
            canvas.setEditorInput(brush.copy(tool = ToolType.MOVE))
            val depth = repository.undoDepth
            send(canvas, MotionEvent.ACTION_DOWN, Touch(1, 20f, 20f))
            send(canvas, MotionEvent.ACTION_UP, Touch(1, 40f, 20f))
            await { repository.undoDepth == depth + 1 }
            val moved = savedPlane()
            assertEquals(0f, moved.wetnessAt(20, 20), 0f)
            assertEquals(originalWetness, moved.wetnessAt(40, 20), 0.01f)

            var ready = false
            canvas.onTransformQuadChanged = { ready = it != null }
            canvas.setEditorInput(brush.copy(tool = ToolType.TRANSFORM))
            await { ready }
            canvas.transformActiveLayer(rotation = 90f)
            await { repository.undoDepth == depth + 2 }
            canvas.resetTransform()
            await { repository.undoDepth == depth + 3 }
            val reset = savedPlane()
            assertEquals(moved.wetnessAt(40, 20), reset.wetnessAt(40, 20), 0f)
            assertNotNull(repository.loadCanvas(project))
            assertEquals(originalWetness, savedPlane().wetnessAt(40, 20), 0.01f)
        }
}
