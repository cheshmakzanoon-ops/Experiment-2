package com.artflow.studio

import android.graphics.Color
import android.os.Build
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.core.pixels.SelectionMask
import com.artflow.studio.core.text.TextLayout
import com.artflow.studio.core.tool.LiquifyTool
import com.artflow.studio.core.tool.PixelBrushes
import com.artflow.studio.core.tool.ToolType
import com.artflow.studio.data.local.ProjectStorage
import com.artflow.studio.domain.model.brush.BrushParams
import com.artflow.studio.domain.repository.canvas.CanvasRepository
import com.artflow.studio.presentation.ui.components.canvas.ArtFlowCanvasView
import com.artflow.studio.presentation.ui.components.canvas.EditorInput
import com.artflow.studio.presentation.ui.components.canvas.ShapeKind
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject

/** Synthetic MotionEvents drive the real View and document, not a replacement input handler. */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class CanvasInputDeviceTest {
    @get:Rule val hiltRule = HiltAndroidRule(this)

    @Inject lateinit var repository: CanvasRepository

    @Inject lateinit var storage: ProjectStorage
    private val brush =
        EditorInput(
            brushParams = BrushParams(size = 6f, pressureToSize = 0f, pressureToOpacity = 0f, smoothing = 0f),
            brushColor = Color.RED,
            eraserSize = 10f,
        )
    private var eventTime = 0L
    private var downTime = 0L

    @Before fun setUp() {
        hiltRule.inject()
    }

    @Test
    fun penKeepsDrawingWithPalmAndDoesNotAdoptTheRemainingFinger() =
        withCanvas(1) { canvas ->
            val depth = repository.undoDepth
            val pen = Touch(12, 10f, 20f, MotionEvent.TOOL_TYPE_STYLUS)
            val palm = Touch(3, 52f, 52f)
            send(canvas, MotionEvent.ACTION_DOWN, pen)
            send(canvas, MotionEvent.ACTION_POINTER_DOWN, pen, palm, changed = 1)
            send(canvas, MotionEvent.ACTION_MOVE, palm, pen.copy(x = 35f))
            send(canvas, MotionEvent.ACTION_POINTER_UP, palm, pen.copy(x = 45f), changed = 1)
            send(canvas, MotionEvent.ACTION_MOVE, palm.copy(x = 25f))
            send(canvas, MotionEvent.ACTION_UP, palm.copy(x = 25f))
            val pixels = image()
            assertTrue("Pen movement must not freeze while a palm is present", alpha(pixels, 35, 20) > 0)
            assertTrue("The final pointer-up position must be committed", alpha(pixels, 45, 20) > 0)
            assertEquals("A trailing palm must not paint", 0, alpha(pixels, 25, 52))
            assertEquals(depth + 1, repository.undoDepth)
        }

    @Test
    fun actionCancelWithMultiplePointersDiscardsAllProvisionalInk() =
        withCanvas(2) { canvas ->
            val before = image().pixels.copyOf()
            val depth = repository.undoDepth
            val previewBefore = requireNotNull(repository.compositePreview()).pixels.copyOf()
            val pen = Touch(4, 15f, 15f, MotionEvent.TOOL_TYPE_STYLUS)
            val palm = Touch(8, 50f, 50f)
            send(canvas, MotionEvent.ACTION_DOWN, pen)
            send(canvas, MotionEvent.ACTION_POINTER_DOWN, pen, palm, changed = 1)
            send(canvas, MotionEvent.ACTION_MOVE, pen.copy(x = 35f), palm)
            send(canvas, MotionEvent.ACTION_CANCEL, pen.copy(x = 35f), palm)
            send(canvas, MotionEvent.ACTION_UP, palm)
            assertArrayEquals(before, image().pixels)
            assertEquals(depth, repository.undoDepth)
            assertArrayEquals(previewBefore, requireNotNull(repository.compositePreview()).pixels)
        }

    @Test
    fun cancelledPointerFlagsRejectPalmWithoutRejectingPen() {
        assumeTrue("FLAG_CANCELED is reported from Android 13", Build.VERSION.SDK_INT >= 33)
        withCanvas(3) { canvas ->
            val pen = Touch(4, 15f, 15f, MotionEvent.TOOL_TYPE_STYLUS)
            val palm = Touch(8, 50f, 50f)
            send(canvas, MotionEvent.ACTION_DOWN, pen)
            send(canvas, MotionEvent.ACTION_POINTER_DOWN, pen, palm, changed = 1)
            send(canvas, MotionEvent.ACTION_POINTER_UP, pen, palm, changed = 1, flags = MotionEvent.FLAG_CANCELED)
            send(canvas, MotionEvent.ACTION_MOVE, pen.copy(x = 35f))
            send(canvas, MotionEvent.ACTION_UP, pen.copy(x = 45f))
            assertTrue(alpha(image(), 35, 15) > 0)
            val before = image().pixels.copyOf()
            val depth = repository.undoDepth
            send(canvas, MotionEvent.ACTION_DOWN, pen.copy(y = 40f))
            send(canvas, MotionEvent.ACTION_UP, pen.copy(x = 35f, y = 40f), flags = MotionEvent.FLAG_CANCELED)
            assertArrayEquals(before, image().pixels)
            assertEquals(depth, repository.undoDepth)
        }
    }

    @Test
    fun twoFingerNavigationCancelsFingerInkAndHistoryWaitsForFinalUp() =
        withCanvas(4) { canvas ->
            val depth = repository.undoDepth
            var undo = 0
            var redo = 0
            canvas.onUndoRequested = { undo++ }
            canvas.onRedoRequested = { redo++ }
            val a = Touch(1, 16f, 30f)
            val b = Touch(2, 48f, 30f)
            send(canvas, MotionEvent.ACTION_DOWN, a)
            send(canvas, MotionEvent.ACTION_POINTER_DOWN, a, b, changed = 1)
            send(canvas, MotionEvent.ACTION_POINTER_UP, a, b, changed = 1)
            assertEquals(0, undo)
            send(canvas, MotionEvent.ACTION_UP, a)
            assertEquals(1, undo)
            assertEquals(0, redo)
            assertEquals(depth, repository.undoDepth)
            assertTrue(image().pixels.all { it == 0 })

            val c = Touch(3, 32f, 45f)
            send(canvas, MotionEvent.ACTION_DOWN, a)
            send(canvas, MotionEvent.ACTION_POINTER_DOWN, a, b, changed = 1)
            send(canvas, MotionEvent.ACTION_POINTER_DOWN, a, b, c, changed = 2)
            send(canvas, MotionEvent.ACTION_POINTER_UP, a, b, c, changed = 2)
            send(canvas, MotionEvent.ACTION_POINTER_UP, a, b, changed = 1)
            send(canvas, MotionEvent.ACTION_UP, a)
            assertEquals(1, undo)
            assertEquals(1, redo)
        }

    @Test
    fun centeredPinchChangesViewWithoutUndoOrPainting() =
        withCanvas(5) { canvas ->
            var undo = 0
            canvas.onUndoRequested = { undo++ }
            var current = 0f
            canvas.onViewChanged = { scale, _, _, _ -> current = scale }
            canvas.fitToView()
            val initial = current
            // Freeze physical view coordinates: canvas coordinates change as the view is zoomed.
            val left = canvas.canvasToView(20f, 32f)
            val right = canvas.canvasToView(44f, 32f)
            val a = Touch(1, left.first, left.second)
            val b = Touch(2, right.first, right.second)
            send(canvas, MotionEvent.ACTION_DOWN, a, viewCoordinates = true)
            send(canvas, MotionEvent.ACTION_POINTER_DOWN, a, b, changed = 1, viewCoordinates = true)
            val movedA = a.copy(x = a.x - 50f)
            val movedB = b.copy(x = b.x + 50f)
            send(canvas, MotionEvent.ACTION_MOVE, movedA, movedB, viewCoordinates = true)
            send(canvas, MotionEvent.ACTION_POINTER_UP, movedA, movedB, changed = 1, viewCoordinates = true)
            send(canvas, MotionEvent.ACTION_UP, movedA, viewCoordinates = true)
            assertTrue(current > initial)
            assertEquals(0, undo)
            assertTrue(image().pixels.all { it == 0 })
        }

    @Test
    fun physicalEraserRemovesPixelsWithoutChangingTheSelectedBrush() =
        withCanvas(6) { canvas ->
            repository.setLayerPixels(repository.getActiveLayerId(), PixelBuffer.filled(64, 64, Color.BLUE), "Fixture")
            val eraser = Touch(3, 32f, 32f, MotionEvent.TOOL_TYPE_ERASER)
            send(canvas, MotionEvent.ACTION_DOWN, eraser)
            send(canvas, MotionEvent.ACTION_UP, eraser)
            assertTrue(alpha(image(), 32, 32) < 255)
            val pen = eraser.copy(x = 10f, type = MotionEvent.TOOL_TYPE_STYLUS)
            send(canvas, MotionEvent.ACTION_DOWN, pen)
            send(canvas, MotionEvent.ACTION_UP, pen)
            assertTrue("Selected brush is still red after flipping the pen back", ((image().getSafe(10, 32) ushr 16) and 255) > 0)
        }

    @Test
    fun selectionsKeepTheirGeometryAndIncludePointerUpPosition() =
        withCanvas(7) { canvas ->
            for (tool in listOf(ToolType.SELECT_RECTANGLE, ToolType.SELECT_ELLIPSE, ToolType.SELECT_LASSO, ToolType.SELECT_FREEHAND)) {
                canvas.clearSelection()
                canvas.setEditorInput(brush.copy(tool = tool))
                send(canvas, MotionEvent.ACTION_DOWN, Touch(1, 10f, 10f))
                if (tool == ToolType.SELECT_LASSO) {
                    send(canvas, MotionEvent.ACTION_MOVE, Touch(1, 42f, 10f))
                    send(canvas, MotionEvent.ACTION_MOVE, Touch(1, 42f, 42f))
                }
                send(canvas, MotionEvent.ACTION_UP, Touch(1, 10f.takeIf { tool == ToolType.SELECT_LASSO } ?: 42f, 42f))
                await { repository.selection() != null }
                val mask = requireNotNull(repository.selection())
                assertTrue("$tool must produce a nonempty selection", mask.selectedPixelCount() > 10)
                assertTrue("$tool must include the center of the drag", mask.coverageAt(25, 25) > 0)
            }
        }

    @Test
    fun canceledSelectionsAndClearedPendingSelectionsStayCleared() =
        withCanvas(8) { canvas ->
            canvas.setEditorInput(brush.copy(tool = ToolType.SELECT_RECTANGLE))
            send(canvas, MotionEvent.ACTION_DOWN, Touch(1, 10f, 10f))
            send(canvas, MotionEvent.ACTION_MOVE, Touch(1, 42f, 42f))
            canvas.setEditorInput(brush.copy(tool = ToolType.TEXT))
            send(canvas, MotionEvent.ACTION_UP, Touch(1, 42f, 42f))
            delay(100)
            assertNull(repository.selection())
            canvas.setEditorInput(brush.copy(tool = ToolType.SELECT_RECTANGLE))
            send(canvas, MotionEvent.ACTION_DOWN, Touch(1, 10f, 10f))
            send(canvas, MotionEvent.ACTION_UP, Touch(1, 42f, 42f))
            canvas.clearSelection()
            delay(100)
            assertNull("A background selection calculation must not resurrect a cleared selection", repository.selection())
        }

    @Test
    fun canceledCloneTapCannotSetSourceAndPauseCancelsPainting() =
        withCanvas(9) { canvas ->
            var sourceSet = false
            canvas.onCloneSourceChanged = { sourceSet = true }
            canvas.setEditorInput(brush.copy(tool = ToolType.CLONE_STAMP))
            send(canvas, MotionEvent.ACTION_DOWN, Touch(1, 20f, 20f))
            send(canvas, MotionEvent.ACTION_CANCEL, Touch(1, 20f, 20f))
            assertFalse(sourceSet)
            canvas.setEditorInput(brush)
            val depth = repository.undoDepth
            send(canvas, MotionEvent.ACTION_DOWN, Touch(1, 20f, 20f))
            canvas.pauseRendering()
            canvas.resumeRendering()
            send(canvas, MotionEvent.ACTION_UP, Touch(1, 40f, 20f))
            assertEquals(depth, repository.undoDepth)
            assertTrue(image().pixels.all { it == 0 })
        }

    @Test
    fun layerChangeCancelsShapeAndDisablingFingerPaintingCancelsFingerStroke() =
        withCanvas(10) { canvas ->
            val original = repository.getActiveLayerId()
            val second = repository.addLayer("Second").id
            canvas.setActiveLayerId(original)
            canvas.setEditorInput(brush.copy(tool = ToolType.SHAPE))
            val depth = repository.undoDepth
            send(canvas, MotionEvent.ACTION_DOWN, Touch(1, 10f, 10f))
            send(canvas, MotionEvent.ACTION_MOVE, Touch(1, 40f, 40f))
            canvas.setActiveLayerId(second)
            send(canvas, MotionEvent.ACTION_UP, Touch(1, 40f, 40f))
            delay(100)
            assertEquals(depth, repository.undoDepth)
            canvas.setEditorInput(brush)
            send(canvas, MotionEvent.ACTION_DOWN, Touch(1, 20f, 20f))
            canvas.setEditorInput(brush.copy(fingerPainting = false))
            send(canvas, MotionEvent.ACTION_UP, Touch(1, 30f, 20f))
            assertEquals(depth, repository.undoDepth)
        }

    @Test
    fun moveToolUsesTheDragDirectionAndCommitsTheFinalPosition() =
        withCanvas(11) { canvas ->
            val layer = repository.getActiveLayerId()
            val fixture = PixelBuffer(64, 64).apply { pixels[20 * 64 + 20] = Color.RED }
            repository.setLayerPixels(layer, fixture, "Fixture")
            canvas.setEditorInput(brush.copy(tool = ToolType.MOVE))
            val depth = repository.undoDepth
            send(canvas, MotionEvent.ACTION_DOWN, Touch(1, 20f, 20f))
            send(canvas, MotionEvent.ACTION_UP, Touch(1, 30f, 25f))
            await { repository.undoDepth == depth + 1 }
            assertEquals(Color.RED, image().getSafe(30, 25))
            assertEquals(0, image().getSafe(10, 15))
            assertTrue(repository.undo())
            assertArrayEquals(fixture.pixels, image().pixels)
        }

    @Test
    fun outlinedShapesHaveVisibleBordersAndPolygonStaysWithinDragBounds() =
        withCanvas(12) { canvas ->
            for (kind in listOf(ShapeKind.RECTANGLE, ShapeKind.ELLIPSE, ShapeKind.POLYGON)) {
                canvas.setEditorInput(brush.copy(tool = ToolType.SHAPE, shapeKind = kind, shapeFilled = false))
                val depth = repository.undoDepth
                send(canvas, MotionEvent.ACTION_DOWN, Touch(1, 12f, 12f))
                send(canvas, MotionEvent.ACTION_UP, Touch(1, 44f, 44f))
                await { repository.undoDepth == depth + 1 }
                val pixels = image()
                assertTrue("$kind outline must be visible", pixels.pixels.any { (it ushr 24) > 0 })
                assertEquals("$kind outline must not be filled", 0, alpha(pixels, 28, 28))
                for (y in 0 until 64) {
                    for (x in 0 until 64) {
                        if (x !in 10..46 || y !in 10..46) assertEquals("$kind exceeds drag bounds at $x,$y", 0, alpha(pixels, x, y))
                    }
                }
                assertTrue(repository.undo())
            }
        }

    @Test
    fun batchedStylusSamplesRetainTheActualPath() =
        withCanvas(13) { canvas ->
            val pen = Touch(12, 10f, 10f, MotionEvent.TOOL_TYPE_STYLUS)
            send(canvas, MotionEvent.ACTION_DOWN, pen)
            val properties =
                arrayOf(
                    MotionEvent.PointerProperties().apply {
                        id = pen.id
                        toolType = pen.type
                    },
                )

            fun coords(
                x: Float,
                y: Float,
            ): Array<MotionEvent.PointerCoords> {
                val point = canvas.canvasToView(x, y)
                return arrayOf(
                    MotionEvent.PointerCoords().apply {
                        this.x = point.first
                        this.y = point.second
                        pressure = 1f
                    },
                )
            }
            eventTime += 10
            val move =
                MotionEvent.obtain(
                    downTime,
                    eventTime,
                    MotionEvent.ACTION_MOVE,
                    1,
                    properties,
                    coords(12f, 40f),
                    0,
                    0,
                    1f,
                    1f,
                    0,
                    0,
                    InputDevice.SOURCE_STYLUS,
                    0,
                )
            try {
                eventTime += 10
                move.addBatch(eventTime, coords(52f, 40f), 0)
                assertEquals(1, move.historySize)
                assertTrue(canvas.onTouchEvent(move))
            } finally {
                move.recycle()
            }
            send(canvas, MotionEvent.ACTION_UP, pen.copy(x = 52f, y = 40f))
            assertTrue("Batched corner must not be replaced by a diagonal shortcut", alpha(image(), 12, 36) > 0)
        }

    @Test
    fun liquifyCommitsTheCompleteMapEvenWhenItsLastPreviewWasThrottled() =
        withCanvas(14) { canvas ->
            val fixture =
                PixelBuffer(64, 64).apply {
                    for (y in 0 until 64) for (x in 0 until 64) pixels[y * 64 + x] = Color.rgb(x * 4, y * 4, 100)
                }
            repository.setLayerPixels(repository.getActiveLayerId(), fixture, "Liquify fixture")
            val settings = brush.copy(tool = ToolType.LIQUIFY, brushParams = brush.brushParams.copy(size = 30f))
            canvas.setEditorInput(settings)
            // Finger calibration uses the 0.1 contact size sent by this fixture: 0.35 + 0.3 * 0.65.
            val calibrated = settings.liquify.copy(size = 30f, pressure = 0.545f)
            val expectedSession = LiquifyTool.beginSession(15f, 30f, calibrated, 64, 64)
            expectedSession.dragTo(20f, 30f)
            expectedSession.dragTo(32f, 30f)
            val expected = expectedSession.map.apply(fixture)
            assertFalse(fixture.pixels.contentEquals(expected.pixels))
            val depth = repository.undoDepth
            send(canvas, MotionEvent.ACTION_DOWN, Touch(1, 15f, 30f))
            send(canvas, MotionEvent.ACTION_MOVE, Touch(1, 20f, 30f))
            send(canvas, MotionEvent.ACTION_UP, Touch(1, 32f, 30f))
            await { repository.undoDepth == depth + 1 }
            assertArrayEquals(expected.pixels, image().pixels)
            assertTrue(repository.undo())
            assertArrayEquals(fixture.pixels, image().pixels)
        }

    @Test
    fun shapesAndTextPreserveExistingArtworkAndUndoExactly() =
        withCanvas(15) { canvas ->
            val layer = repository.getActiveLayerId()
            val fixture = PixelBuffer.filled(64, 64, Color.BLUE)
            repository.setLayerPixels(layer, fixture, "Background fixture")
            for (text in listOf(false, true)) {
                val depth = repository.undoDepth
                if (text) {
                    canvas.placeText(8f, 8f, "A", TextLayout.TextStyle(fontSize = 18f), Color.RED)
                } else {
                    canvas.placeShape(ShapeKind.RECTANGLE, 12f, 12f, 40f, 40f, true, Color.RED, 1f)
                }
                await { repository.undoDepth == depth + 1 }
                val result = image()
                assertEquals("Existing artwork outside the overlay must survive", Color.BLUE, result.getSafe(60, 60))
                assertTrue("The overlay must actually be rendered", result.pixels.any { ((it ushr 16) and 255) > 0 })
                assertTrue("No transparent holes may replace opaque artwork", result.pixels.all { (it ushr 24) == 255 })
                assertTrue(repository.undo())
                assertArrayEquals(fixture.pixels, image().pixels)
            }
        }

    @Test
    fun shapeSelectionFeatheringPreservesBackdropAndAlphaLock() =
        withCanvas(16) { canvas ->
            val layer = repository.getActiveLayerId()
            repository.setLayerPixels(layer, PixelBuffer.filled(64, 64, Color.BLUE), "Background fixture")
            repository.setSelection(SelectionMask(64, 64, ByteArray(64 * 64) { 128.toByte() }))
            var depth = repository.undoDepth
            canvas.placeShape(ShapeKind.RECTANGLE, 12f, 12f, 40f, 40f, true, Color.RED, 1f)
            await { repository.undoDepth == depth + 1 }
            assertEquals(0xFF80007F.toInt(), image().getSafe(20, 20))
            assertEquals(Color.BLUE, image().getSafe(60, 60))
            repository.clearSelection()
            repository.setLayerPixels(layer, PixelBuffer(64, 64).apply { pixels[20 * 64 + 20] = 0x400000FF }, "Coverage fixture")
            repository.setLayerAlphaLock(layer, true)
            depth = repository.undoDepth
            canvas.placeShape(ShapeKind.RECTANGLE, 12f, 12f, 40f, 40f, true, Color.RED, 1f)
            await { repository.undoDepth == depth + 1 }
            assertEquals(0x40FF0000, image().getSafe(20, 20))
            assertEquals(0, image().getSafe(21, 20))
        }

    @Test
    fun asynchronousShapeStaysBoundToItsOriginalLayer() =
        withCanvas(17) { canvas ->
            val first = repository.getActiveLayerId()
            val second = repository.addLayer("Other destination").id
            canvas.setActiveLayerId(first)
            val depth = repository.undoDepth
            canvas.placeShape(ShapeKind.RECTANGLE, 12f, 12f, 40f, 40f, true, Color.RED, 1f)
            canvas.setActiveLayerId(second)
            await { repository.undoDepth == depth + 1 }
            assertEquals(Color.RED, requireNotNull(repository.layerPixels(first)).getSafe(20, 20))
            assertNull(repository.layerPixels(second))
        }

    @Test
    fun emptyOverlaySelectionDoesNotChangePixelsOrHistory() =
        withCanvas(18) { canvas ->
            repository.setSelection(SelectionMask(64, 64))
            val depth = repository.undoDepth
            canvas.placeShape(ShapeKind.RECTANGLE, 12f, 12f, 40f, 40f, true, Color.RED, 1f)
            delay(200)
            assertEquals(depth, repository.undoDepth)
            assertTrue(image().isEmpty())
        }

    @Test
    fun liquifyReconstructRestoresEarlierArtworkAndRemainsUndoable() =
        withCanvas(19) { canvas ->
            val layer = repository.getActiveLayerId()
            val original = liquifyFixture()
            repository.setLayerPixels(layer, original, "Reconstruction fixture")
            val settings = LiquifyTool.Settings(size = 40f, strength = 1f)
            canvas.setEditorInput(brush.copy(tool = ToolType.LIQUIFY, brushParams = brush.brushParams.copy(size = 40f), liquify = settings))
            var depth = repository.undoDepth
            liquifyDrag(canvas)
            await { repository.undoDepth == depth + 1 }
            val warped = image().copy()
            val initialError = pixelDifference(original, warped)
            assertTrue("The warp fixture must actually change", initialError > 0)
            depth = repository.undoDepth
            canvas.setEditorInput(
                brush.copy(
                    tool = ToolType.LIQUIFY,
                    brushParams = brush.brushParams.copy(size = 40f),
                    liquify = settings.copy(mode = LiquifyTool.Mode.RECONSTRUCT),
                ),
            )
            liquifyDrag(canvas)
            await { repository.undoDepth == depth + 1 }
            assertTrue("Reconstruct must move pixels closer to the pre-liquify image", pixelDifference(original, image()) < initialError)
            assertTrue(repository.undo())
            assertArrayEquals(warped.pixels, image().pixels)
            // Undo is itself a newer revision; a stale reference may never undo that newer decision.
            depth = repository.undoDepth
            liquifyDrag(canvas)
            delay(150)
            assertEquals(depth, repository.undoDepth)
            assertArrayEquals(warped.pixels, image().pixels)
        }

    @Test
    fun reconstructNeverOverwritesAnInterveningEditOrAnotherDocument() =
        withCanvas(20) { canvas ->
            val layer = repository.getActiveLayerId()
            repository.setLayerPixels(layer, liquifyFixture(), "Reconstruction fixture")
            val settings =
                brush.copy(
                    tool = ToolType.LIQUIFY,
                    brushParams = brush.brushParams.copy(size = 40f),
                    liquify = LiquifyTool.Settings(strength = 1f),
                )
            canvas.setEditorInput(settings)
            var depth = repository.undoDepth
            liquifyDrag(canvas)
            await { repository.undoDepth == depth + 1 }
            repository.setLayerPixels(layer, PixelBuffer.filled(64, 64, Color.GREEN), "Newer artwork")
            val changedRevision = repository.contentRevision
            canvas.setEditorInput(settings.copy(liquify = settings.liquify.copy(mode = LiquifyTool.Mode.RECONSTRUCT)))
            depth = repository.undoDepth
            var message = ""
            canvas.onStatusMessage = { message = it }
            liquifyDrag(canvas)
            delay(150)
            assertEquals(depth, repository.undoDepth)
            assertTrue(message.contains("previous liquify"))
            assertTrue(image().pixels.all { it == Color.GREEN })
            // IDs can be reused by a newly loaded document. Revision must still change.
            repository.createCanvas(64, 64, 72)
            assertTrue(repository.contentRevision > changedRevision)
            canvas.setActiveLayerId(repository.getActiveLayerId())
            liquifyDrag(canvas)
            delay(150)
            assertEquals(0, repository.undoDepth)
            assertTrue(image().isEmpty())
        }

    @Test
    fun frozenAndZeroPressureLiquifyGesturesCreateNoUndoEntries() =
        withCanvas(21) { canvas ->
            repository.setLayerPixels(repository.getActiveLayerId(), liquifyFixture(), "Pressure fixture")
            val before = image().pixels.copyOf()
            val depth = repository.undoDepth
            val settings = brush.copy(tool = ToolType.LIQUIFY, brushParams = brush.brushParams.copy(size = 40f))
            canvas.setEditorInput(settings.copy(liquify = LiquifyTool.Settings(freezeMask = BooleanArray(64 * 64) { true })))
            liquifyDrag(canvas)
            delay(150)
            assertEquals(depth, repository.undoDepth)
            canvas.setEditorInput(settings)
            val pen = Touch(4, 18f, 32f, MotionEvent.TOOL_TYPE_STYLUS, pressure = 0f)
            send(canvas, MotionEvent.ACTION_DOWN, pen)
            send(canvas, MotionEvent.ACTION_MOVE, pen.copy(x = 32f))
            send(canvas, MotionEvent.ACTION_UP, pen.copy(x = 40f))
            delay(150)
            assertEquals(depth, repository.undoDepth)
            assertArrayEquals(before, image().pixels)
        }

    @Test
    fun cancellingReconstructKeepsTheLastSuccessfulReference() =
        withCanvas(22) { canvas ->
            repository.setLayerPixels(repository.getActiveLayerId(), liquifyFixture(), "Cancellation fixture")
            val settings =
                brush.copy(
                    tool = ToolType.LIQUIFY,
                    brushParams = brush.brushParams.copy(size = 40f),
                    liquify = LiquifyTool.Settings(strength = 1f),
                )
            canvas.setEditorInput(settings)
            var depth = repository.undoDepth
            liquifyDrag(canvas)
            await { repository.undoDepth == depth + 1 }
            val warped = image().pixels.copyOf()
            depth = repository.undoDepth
            canvas.setEditorInput(settings.copy(liquify = settings.liquify.copy(mode = LiquifyTool.Mode.RECONSTRUCT)))
            val pen = Touch(4, 18f, 32f, MotionEvent.TOOL_TYPE_STYLUS)
            send(canvas, MotionEvent.ACTION_DOWN, pen)
            send(canvas, MotionEvent.ACTION_MOVE, pen.copy(x = 35f))
            send(canvas, MotionEvent.ACTION_CANCEL, pen.copy(x = 35f))
            delay(150)
            assertEquals(depth, repository.undoDepth)
            assertArrayEquals(warped, image().pixels)
            liquifyDrag(canvas)
            await { repository.undoDepth == depth + 1 }
            assertFalse(warped.contentEquals(image().pixels))
        }

    @Test
    fun savedReloadChangesRevisionEvenWhenProjectAndLayerIdsAreReused() =
        withCanvas(23) { _ ->
            val project = repository.projectId()
            val layer = repository.getActiveLayerId()
            repository.setLayerPixels(layer, liquifyFixture(), "Reload fixture")
            repository.saveCanvas(project)
            val before = repository.contentRevision
            repository.loadCanvas(project)
            assertTrue(repository.contentRevision > before)
            assertEquals(layer, repository.getActiveLayerId())
            val session = requireNotNull(repository.beginRasterEdit(layer))
            assertEquals(repository.contentRevision, session.contentRevision)
            repository.cancelRasterEdit(session)
        }

    @Test
    fun retouchToolsHonorLayerAlphaLockFromTheActualDocument() =
        withCanvas(24) { canvas ->
            val layer = repository.getActiveLayerId()
            val fixture =
                PixelBuffer(64, 64).apply {
                    for (y in 0 until height) {
                        for (x in 0 until width) {
                            val alpha =
                                if (x >= 36) {
                                    0
                                } else if (x >= 26) {
                                    64
                                } else {
                                    255
                                }
                            pixels[y * width + x] = (alpha shl 24) or (x * 3 shl 16) or (y * 3)
                        }
                    }
                }
            for (tool in listOf(ToolType.SMUDGE, ToolType.CLONE_STAMP, ToolType.HEALING, ToolType.LIQUIFY)) {
                repository.setLayerPixels(layer, fixture, "Alpha lock fixture")
                repository.setLayerAlphaLock(layer, true)
                canvas.setEditorInput(brush.copy(tool = tool, brushParams = brush.brushParams.copy(size = 24f)))
                if (tool == ToolType.CLONE_STAMP) {
                    send(canvas, MotionEvent.ACTION_DOWN, Touch(1, 12f, 30f))
                    send(canvas, MotionEvent.ACTION_UP, Touch(1, 12f, 30f))
                }
                val depth = repository.undoDepth
                send(canvas, MotionEvent.ACTION_DOWN, Touch(1, 24f, 30f))
                send(canvas, MotionEvent.ACTION_MOVE, Touch(1, 30f, 30f))
                send(canvas, MotionEvent.ACTION_UP, Touch(1, 40f, 30f))
                awaitRasterIdle(layer)
                val result = requireNotNull(repository.layerPixels(layer))
                assertArrayEquals(fixture.pixels.map { it ushr 24 }.toIntArray(), result.pixels.map { it ushr 24 }.toIntArray())
                if (tool == ToolType.SMUDGE || tool == ToolType.CLONE_STAMP || tool == ToolType.LIQUIFY) {
                    assertEquals("The tool must not be disabled to enforce alpha lock", depth + 1, repository.undoDepth)
                }
            }
        }

    @Test
    fun cloneSampleAllLayersSwitchControlsTheCapturedSourceEvenForImmediateRelease() =
        withCanvas(25) { canvas ->
            val layer = repository.getActiveLayerId()
            val overlay = repository.addLayer("Separate green artwork").id
            repository.setLayerPixels(overlay, PixelBuffer.filled(64, 64, Color.GREEN), "Overlay fixture")
            canvas.setActiveLayerId(layer)
            val original =
                PixelBuffer(64, 64).apply {
                    for (y in 0 until height) {
                        for (x in 0 until 32) setUnchecked(x, y, Color.RED)
                    }
                }
            for (allLayers in listOf(false, true)) {
                repository.setLayerPixels(layer, original, "Clone fixture")
                canvas.setEditorInput(
                    brush.copy(
                        tool = ToolType.CLONE_STAMP,
                        brushParams = brush.brushParams.copy(size = 12f),
                        clone = PixelBrushes.CloneSettings(sampleAllLayers = allLayers),
                    ),
                )
                if (!allLayers) {
                    send(canvas, MotionEvent.ACTION_DOWN, Touch(1, 10f, 30f))
                    send(canvas, MotionEvent.ACTION_UP, Touch(1, 10f, 30f))
                }
                val depth = repository.undoDepth
                // No delay: samples and UP must survive asynchronous composite-source capture.
                send(canvas, MotionEvent.ACTION_DOWN, Touch(1, 44f, 30f))
                send(canvas, MotionEvent.ACTION_MOVE, Touch(1, 49f, 30f))
                send(canvas, MotionEvent.ACTION_UP, Touch(1, 54f, 30f))
                await { repository.undoDepth == depth + 1 }
                val result = requireNotNull(repository.layerPixels(layer))
                assertEquals(if (allLayers) Color.GREEN else Color.RED, result.getSafe(50, 30))
                assertEquals(Color.GREEN, requireNotNull(repository.layerPixels(overlay)).getSafe(50, 30))
                assertTrue(repository.undo())
                assertArrayEquals(original.pixels, requireNotNull(repository.layerPixels(layer)).pixels)
            }
        }

    @Test
    fun changingAlphaLockDuringAnOpenToolDiscardsTheStaleEdit() =
        withCanvas(26) { canvas ->
            val layer = repository.getActiveLayerId()
            val original = liquifyFixture()
            repository.setLayerPixels(layer, original, "Lock-change fixture")
            canvas.setEditorInput(brush.copy(tool = ToolType.LIQUIFY))
            send(canvas, MotionEvent.ACTION_DOWN, Touch(1, 15f, 30f))
            send(canvas, MotionEvent.ACTION_MOVE, Touch(1, 22f, 30f))
            repository.setLayerAlphaLock(layer, true)
            val depth = repository.undoDepth
            send(canvas, MotionEvent.ACTION_UP, Touch(1, 32f, 30f))
            awaitRasterIdle(layer)
            assertEquals(depth, repository.undoDepth)
            assertArrayEquals(original.pixels, requireNotNull(repository.layerPixels(layer)).pixels)
        }

    private suspend fun awaitRasterIdle(layerId: Long) {
        // Yield to the completed gesture, then acquire/release a real edit as a settlement barrier.
        delay(1)
        withTimeout(10_000) {
            while (true) {
                val barrier = repository.beginRasterEdit(layerId)
                if (barrier != null) {
                    repository.cancelRasterEdit(barrier)
                    break
                }
                delay(10)
            }
        }
    }

    private fun liquifyDrag(canvas: ArtFlowCanvasView) {
        val pen = Touch(4, 18f, 32f, MotionEvent.TOOL_TYPE_STYLUS)
        send(canvas, MotionEvent.ACTION_DOWN, pen)
        send(canvas, MotionEvent.ACTION_MOVE, pen.copy(x = 32f))
        send(canvas, MotionEvent.ACTION_UP, pen.copy(x = 40f))
    }

    private fun liquifyFixture(): PixelBuffer =
        PixelBuffer(64, 64).apply {
            for (y in 0 until height) for (x in 0 until width) setUnchecked(x, y, Color.rgb(x * 3, y * 3, 40))
        }

    private fun pixelDifference(
        a: PixelBuffer,
        b: PixelBuffer,
    ): Long =
        a.pixels.indices.sumOf { index ->
            val x = a.pixels[index]
            val y = b.pixels[index]
            listOf(0, 8, 16, 24).sumOf { shift -> kotlin.math.abs(((x ushr shift) and 255) - ((y ushr shift) and 255)).toLong() }
        }

    private fun withCanvas(
        id: Int,
        block: suspend (ArtFlowCanvasView) -> Unit,
    ) {
        val projectId = 9_100_000L + id
        storage.deleteProjectFiles(projectId)
        val scenario = ActivityScenario.launch(SelectionCanvasTestActivity::class.java)
        try {
            runBlocking(Dispatchers.Main) {
                repository.loadOrCreate(projectId, 64, 64, 72)
                repository.setLayerPixels(repository.getActiveLayerId(), PixelBuffer(64, 64), "Blank fixture")
            }
            var view: ArtFlowCanvasView? = null
            scenario.onActivity { activity ->
                val container = activity.canvasContainer
                container.removeAllViews()
                view =
                    ArtFlowCanvasView(activity).apply {
                        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                        attachToCanvas(64, 64, 72, Color.WHITE)
                        setActiveLayerId(repository.getActiveLayerId())
                        setEditorInput(brush)
                    }
                container.addView(requireNotNull(view))
            }
            runBlocking(Dispatchers.Main) {
                val canvas = requireNotNull(view)
                await { canvas.width > 0 && canvas.height > 0 }
                canvas.fitToView()
                eventTime = SystemClock.uptimeMillis()
                block(canvas)
            }
        } finally {
            scenario.close()
            runBlocking(Dispatchers.Main) { repository.dispose() }
            storage.deleteProjectFiles(projectId)
        }
    }

    private suspend fun image(): PixelBuffer = requireNotNull(repository.compositeFrame(0, transparentBackground = true))

    private fun alpha(
        pixels: PixelBuffer,
        x: Int,
        y: Int,
    ) = pixels.getSafe(x, y) ushr 24

    private suspend fun await(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (!condition() && SystemClock.elapsedRealtime() < deadline) delay(10)
        assertTrue("Canvas operation did not finish before its deadline", condition())
    }

    private data class Touch(
        val id: Int,
        val x: Float,
        val y: Float,
        val type: Int = MotionEvent.TOOL_TYPE_FINGER,
        val pressure: Float = 1f,
    )

    private fun send(
        canvas: ArtFlowCanvasView,
        action: Int,
        vararg touches: Touch,
        changed: Int = 0,
        flags: Int = 0,
        viewCoordinates: Boolean = false,
    ) {
        eventTime += 10
        if (action == MotionEvent.ACTION_DOWN) downTime = eventTime
        val properties =
            touches
                .map { touch ->
                    MotionEvent.PointerProperties().apply {
                        id = touch.id
                        toolType = touch.type
                    }
                }.toTypedArray()
        val coords =
            touches
                .map { touch ->
                    val point = if (viewCoordinates) touch.x to touch.y else canvas.canvasToView(touch.x, touch.y)
                    MotionEvent.PointerCoords().apply {
                        x = point.first
                        y = point.second
                        pressure = touch.pressure
                        size = 0.1f
                    }
                }.toTypedArray()
        val event =
            MotionEvent.obtain(
                downTime,
                eventTime,
                action or (changed shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                touches.size,
                properties,
                coords,
                0,
                0,
                1f,
                1f,
                0,
                0,
                if (touches.any {
                        it.type == MotionEvent.TOOL_TYPE_STYLUS || it.type == MotionEvent.TOOL_TYPE_ERASER
                    }
                ) {
                    InputDevice.SOURCE_STYLUS
                } else {
                    InputDevice.SOURCE_TOUCHSCREEN
                },
                flags,
            )
        try {
            assertTrue(canvas.onTouchEvent(event))
        } finally {
            event.recycle()
        }
    }
}
