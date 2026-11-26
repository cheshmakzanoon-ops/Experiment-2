package com.artflow.studio

import android.graphics.Color
import android.view.MotionEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.core.tool.PixelBrushes
import com.artflow.studio.core.tool.ToolType
import com.artflow.studio.presentation.ui.components.canvas.ArtFlowCanvasView
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Source choices and separate strokes through the real input and raster transaction path. */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class CloneInputDeviceTest : CanvasInputTestFixture() {
    @Test
    fun alignedAndUnalignedStrokesSampleDifferentOriginsAndRemainUndoable() =
        withCanvas(119) { canvas ->
            val layer = repository.getActiveLayerId()
            val original = sourceBands()
            for (aligned in listOf(true, false)) {
                repository.setLayerPixels(layer, original, "Clone alignment fixture")
                canvas.setEditorInput(cloneInput(aligned))
                canvas.requestCloneSourceSelection()
                tap(canvas, 8f, 16f)
                val depth = repository.undoDepth
                tap(canvas, 40f, 16f)
                await { repository.undoDepth == depth + 1 }
                assertEquals(Color.RED, requireNotNull(repository.layerPixels(layer)).getSafe(40, 16))
                tap(canvas, 56f, 16f)
                await { repository.undoDepth == depth + 2 }
                assertEquals(if (aligned) Color.GREEN else Color.RED, requireNotNull(repository.layerPixels(layer)).getSafe(56, 16))
                assertTrue(repository.undo())
                assertEquals(0, requireNotNull(repository.layerPixels(layer)).getSafe(56, 16))
                assertTrue(repository.undo())
                assertArrayEquals(original.pixels, requireNotNull(repository.layerPixels(layer)).pixels)
            }
        }

    @Test
    fun reselectingSourceCanBeCancelledWithoutPaintingOrLosingTheExistingOffset() =
        withCanvas(120) { canvas ->
            val layer = repository.getActiveLayerId()
            repository.setLayerPixels(layer, sourceBands(), "Clone reselection fixture")
            canvas.setEditorInput(cloneInput())
            var choices = 0
            canvas.onCloneSourceChanged = { choices++ }
            tap(canvas, 8f, 16f)
            val depth = repository.undoDepth
            tap(canvas, 40f, 16f)
            await { repository.undoDepth == depth + 1 }
            val beforeChoice = requireNotNull(repository.layerPixels(layer))
            canvas.requestCloneSourceSelection()
            val newSource = Touch(1, 24f, 16f, MotionEvent.TOOL_TYPE_STYLUS)
            send(canvas, MotionEvent.ACTION_DOWN, newSource)
            send(canvas, MotionEvent.ACTION_CANCEL, newSource)
            assertEquals(1, choices)
            assertTrue(canvas.isChoosingCloneSource)
            assertArrayEquals(beforeChoice.pixels, requireNotNull(repository.layerPixels(layer)).pixels)
            assertEquals(depth + 1, repository.undoDepth)
            canvas.cancelCloneSourceSelection()
            assertFalse(canvas.isChoosingCloneSource)
            tap(canvas, 56f, 16f)
            await { repository.undoDepth == depth + 2 }
            assertEquals(Color.GREEN, requireNotNull(repository.layerPixels(layer)).getSafe(56, 16))

            canvas.requestCloneSourceSelection()
            tap(canvas, -4f, 16f)
            assertTrue(canvas.isChoosingCloneSource)
            assertEquals(1, choices)
            tap(canvas, 24f, 16f)
            assertFalse(canvas.isChoosingCloneSource)
            assertEquals(2, choices)
            assertEquals(depth + 2, repository.undoDepth)
            tap(canvas, 40f, 48f)
            await { repository.undoDepth == depth + 3 }
            assertEquals(Color.GREEN, requireNotNull(repository.layerPixels(layer)).getSafe(40, 48))
        }

    @Test
    fun cancelledFirstStampCannotAnchorTheNextStrokeEvenDuringCompositeCapture() =
        withCanvas(121) { canvas ->
            val layer = repository.getActiveLayerId()
            for (allLayers in listOf(false, true)) {
                val original = sourceBands()
                repository.setLayerPixels(layer, original, "Clone cancellation fixture")
                canvas.setEditorInput(cloneInput().copy(clone = PixelBrushes.CloneSettings(sampleAllLayers = allLayers)))
                canvas.requestCloneSourceSelection()
                tap(canvas, 8f, 16f)
                val depth = repository.undoDepth
                val pen = Touch(1, 40f, 16f, MotionEvent.TOOL_TYPE_STYLUS)
                send(canvas, MotionEvent.ACTION_DOWN, pen)
                send(canvas, MotionEvent.ACTION_CANCEL, pen)
                awaitRasterIdle(layer)
                assertEquals(depth, repository.undoDepth)
                assertArrayEquals(original.pixels, requireNotNull(repository.layerPixels(layer)).pixels)
                tap(canvas, 56f, 16f)
                await { repository.undoDepth == depth + 1 }
                assertEquals(Color.RED, requireNotNull(repository.layerPixels(layer)).getSafe(56, 16))
            }
        }

    @Test
    fun validIdenticalStrokeEstablishesAlignmentWithoutAddingHistory() =
        withCanvas(122) { canvas ->
            val layer = repository.getActiveLayerId()
            val original = sourceBands()
            repository.setLayerPixels(layer, original, "Clone identical fixture")
            canvas.setEditorInput(cloneInput())
            tap(canvas, 8f, 16f)
            val depth = repository.undoDepth
            tap(canvas, 8f, 16f)
            awaitRasterIdle(layer)
            // Zero offset remains zero; the transparent destination samples itself, not the red origin.
            tap(canvas, 40f, 16f)
            awaitRasterIdle(layer)
            assertEquals(depth, repository.undoDepth)
            assertArrayEquals(original.pixels, requireNotNull(repository.layerPixels(layer)).pixels)
        }

    private fun cloneInput(aligned: Boolean = true) =
        brush.copy(
            tool = ToolType.CLONE_STAMP,
            brushParams = brush.brushParams.copy(size = 4f),
            clone = PixelBrushes.CloneSettings(aligned = aligned, hardness = 1f),
        )

    private fun sourceBands() =
        PixelBuffer(64, 64).apply {
            for (y in 0 until height) {
                for (x in 0 until 32) setUnchecked(x, y, if (x < 16) Color.RED else Color.GREEN)
            }
        }

    private fun tap(
        canvas: ArtFlowCanvasView,
        x: Float,
        y: Float,
    ) {
        val pen = Touch(1, x, y, MotionEvent.TOOL_TYPE_STYLUS)
        send(canvas, MotionEvent.ACTION_DOWN, pen)
        send(canvas, MotionEvent.ACTION_UP, pen)
    }
}
