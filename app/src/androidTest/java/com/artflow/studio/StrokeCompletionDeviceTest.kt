package com.artflow.studio

import android.graphics.Color
import android.view.MotionEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.artflow.studio.core.perspective.PerspectiveGuide
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.core.symmetry.SymmetryEngine
import com.artflow.studio.core.tool.ToolType
import com.artflow.studio.domain.model.brush.Stroke
import com.artflow.studio.domain.repository.canvas.CanvasRepository
import com.artflow.studio.presentation.ui.components.canvas.ArtFlowCanvasView
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Lifts go through the real View, input filters, repository commit and stored raster. */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class StrokeCompletionDeviceTest : CanvasInputTestFixture() {
    @Test
    fun assistedStabilizedLiftKeepsItsGuideAndSampleChannelsThroughHistoryAndReopen() =
        withCanvas(126) { canvas ->
            assertTrue(repository.setLayerDrawingAssist(repository.getActiveLayerId(), true))
            canvas.setEditorInput(
                brush.copy(
                    brushParams = brush.brushParams.copy(size = 4f),
                    stabilization = 1f,
                    pressureSmoothing = 1f,
                    snapToGuides = true,
                    symmetry = SymmetryEngine.Settings(type = SymmetryEngine.SymmetryType.HORIZONTAL),
                    quickShape = false,
                ),
            )
            val before = image().pixels.copyOf()
            val depth = repository.undoDepth
            val pen = Touch(4, 10f, 39f, MotionEvent.TOOL_TYPE_STYLUS, pressure = 0.2f)
            val completed =
                captureStroke(canvas) {
                    send(canvas, MotionEvent.ACTION_DOWN, pen)
                    send(canvas, MotionEvent.ACTION_MOVE, pen.copy(x = 32f, pressure = 0.6f))
                    send(canvas, MotionEvent.ACTION_UP, pen.copy(x = 52.75f, pressure = 0.8f, tilt = 0.7f, orientation = 1.2f))
                }
            val endpoint = completed.points.last()
            assertEquals(52.75f, endpoint.x, 0.001f)
            assertTrue("Every completed sample must stay on the accepted symmetry guide", completed.points.all { it.y == 32f })
            assertEquals("Catch-up must retain pressure smoothing", 0.296f, endpoint.pressure, 0.0001f)
            assertEquals(0.7f, endpoint.tiltX, 0f)
            assertEquals(1.2f, endpoint.tiltY, 0f)
            val tail = completed.points.filter { it.x >= 20f }
            assertTrue("The stabilized stroke must actually produce catch-up samples", tail.size > 1)
            assertTrue("Catch-up must retain the accepted sample's event time", tail.all { it.timestamp == endpoint.timestamp })
            assertTrue("Event time must advance from the down sample", endpoint.timestamp > completed.points.first().timestamp)
            val painted = image()
            assertTrue("The snapped lift point must be painted", alpha(painted, 52, 32) > 0)
            assertEquals("Lifting must not add an off-guide tail", 0, alpha(painted, 52, 39))
            assertEquals(depth + 1, repository.undoDepth)
            assertTrue(repository.undo())
            assertArrayEquals(before, image().pixels)
            assertTrue(repository.redo())
            assertArrayEquals(painted.pixels, image().pixels)
            val project = repository.projectId()
            repository.saveCanvas(project)
            repository.loadCanvas(project)
            assertArrayEquals("The accepted tail must survive storage", painted.pixels, image().pixels)
        }

    @Test
    fun motionFilteredBrushAndEraserReachAcceptedGuideEndpointsOnStationaryAndMovedLifts() =
        withCanvas(127) { canvas ->
            val layer = repository.getActiveLayerId()
            assertTrue(repository.setLayerDrawingAssist(layer, true))
            for ((tool, movedLift) in listOf(ToolType.BRUSH to false, ToolType.ERASER to true)) {
                val base = if (tool == ToolType.ERASER) PixelBuffer.filled(64, 64, Color.BLUE) else PixelBuffer(64, 64)
                repository.setLayerPixels(layer, base, "Filtered completion fixture")
                val options =
                    brush.copy(
                        tool = tool,
                        brushParams = brush.brushParams.copy(size = 4f),
                        eraserSize = 4f,
                        motionFiltering = 1f,
                        motionExpression = 0f,
                        pressureSmoothing = 1f,
                        snapToGuides = true,
                        perspective = PerspectiveGuide.Settings(type = PerspectiveGuide.GuideType.GRID),
                        quickShape = false,
                    )
                canvas.setEditorInput(options)
                val depth = repository.undoDepth
                val pen = Touch(4, 10f, 39f, MotionEvent.TOOL_TYPE_STYLUS, pressure = 0.2f)
                val completed =
                    captureStroke(canvas) {
                        send(canvas, MotionEvent.ACTION_DOWN, pen)
                        val moveX = if (movedLift) 45f else 52.75f
                        send(canvas, MotionEvent.ACTION_MOVE, pen.copy(x = moveX, pressure = 0.6f, tilt = 0.5f, orientation = 1f))
                        if (!movedLift) {
                            // Recomposition must not remove the captured filter or reproject an accepted stationary sample.
                            canvas.setEditorInput(options.copy(motionFiltering = 0f, pressureSmoothing = 0f, snapToGuides = false))
                        }
                        send(canvas, MotionEvent.ACTION_UP, pen.copy(x = 52.75f, pressure = 0.8f, tilt = 0.7f, orientation = 1.2f))
                    }
                val endpoint = completed.points.last()
                val filtered = completed.points[completed.points.lastIndex - 1]
                assertEquals("$tool must reach its fractional lift coordinate", 52.75f, endpoint.x, 0.001f)
                assertEquals("$tool must finish at its accepted guide coordinate", 32f, endpoint.y, 0f)
                assertTrue("The fixture must leave visible filtering lag before catch-up", filtered.x < endpoint.x - 1f)
                assertEquals(if (movedLift) 0.296f else 0.24f, endpoint.pressure, 0.0001f)
                assertEquals(filtered.pressure, endpoint.pressure, 0f)
                assertEquals(filtered.tiltX, endpoint.tiltX, 0f)
                assertEquals(filtered.tiltY, endpoint.tiltY, 0f)
                assertEquals("Catch-up must retain the final accepted event time", filtered.timestamp, endpoint.timestamp)
                val pixels = image()
                if (tool == ToolType.BRUSH) {
                    assertTrue(alpha(pixels, 52, 32) > 0)
                    assertEquals(0, alpha(pixels, 52, 39))
                } else {
                    assertTrue(alpha(pixels, 52, 32) < 255)
                    assertEquals(255, alpha(pixels, 52, 39))
                }
                assertEquals(depth + 1, repository.undoDepth)
            }
        }

    @Test
    fun filteredPulledStringAndCancelledStrokesKeepTheirCompletionBoundaries() =
        withCanvas(128) { canvas ->
            val options = brush.copy(stabilization = 1f, motionFiltering = 1f, motionExpression = 0f, pulledString = 1f, quickShape = false)
            canvas.setEditorInput(options)
            val pen = Touch(4, 10f, 32f, MotionEvent.TOOL_TYPE_STYLUS)
            val completed =
                captureStroke(canvas) {
                    send(canvas, MotionEvent.ACTION_DOWN, pen)
                    send(canvas, MotionEvent.ACTION_MOVE, pen.copy(x = 52.75f))
                    canvas.setEditorInput(options.copy(stabilization = 0f, motionFiltering = 0f, pulledString = 0f))
                    send(canvas, MotionEvent.ACTION_UP, pen.copy(x = 52.75f))
                }
            assertEquals("A captured pulled string must not gain a lift catch-up sample", 2, completed.points.size)
            assertTrue(completed.points.last().x < 40f)
            assertEquals("The string deliberately stops behind the pointer", 0, alpha(image(), 52, 32))
            val before = image().pixels.copyOf()
            val depth = repository.undoDepth
            canvas.setEditorInput(options.copy(pulledString = 0f))
            send(canvas, MotionEvent.ACTION_DOWN, pen.copy(y = 48f))
            send(canvas, MotionEvent.ACTION_MOVE, pen.copy(x = 52.75f, y = 48f))
            send(canvas, MotionEvent.ACTION_CANCEL, pen.copy(x = 52.75f, y = 48f))
            assertArrayEquals("Cancelled filtering must not flush provisional ink", before, image().pixels)
            assertEquals(depth, repository.undoDepth)
        }

    /** Observe the real commit without replacing any stroke, rendering or document operation. */
    private fun captureStroke(
        canvas: ArtFlowCanvasView,
        draw: () -> Unit,
    ): Stroke {
        val delegate = canvas.canvasRepository
        var completed: Stroke? = null
        canvas.canvasRepository =
            object : CanvasRepository by delegate {
                override fun endStroke(strokeId: Long) {
                    check(completed == null) { "The gesture committed more than once" }
                    completed = requireNotNull(delegate.activeStroke(strokeId))
                    delegate.endStroke(strokeId)
                }
            }
        return try {
            draw()
            requireNotNull(completed) { "The real gesture did not commit a stroke" }
        } finally {
            canvas.canvasRepository = delegate
        }
    }
}
