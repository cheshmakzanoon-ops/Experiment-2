package com.artflow.studio

import android.graphics.Color
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.core.pixels.SelectionMask
import com.artflow.studio.core.tool.GradientTool
import com.artflow.studio.core.tool.ToolType
import com.artflow.studio.data.local.ProjectStorage
import com.artflow.studio.domain.repository.canvas.CanvasRepository
import com.artflow.studio.presentation.ui.MainActivity
import com.artflow.studio.presentation.ui.components.canvas.ArtFlowCanvasView
import com.artflow.studio.presentation.ui.components.canvas.EditorInput
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject

/** Real pointer gestures exercise the public canvas, layer policy and undo boundary together. */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class FillGestureDeviceTest {
    @get:Rule val hiltRule = HiltAndroidRule(this)

    @Inject lateinit var repository: CanvasRepository

    @Inject lateinit var storage: ProjectStorage
    private val brush = EditorInput(brushColor = Color.RED)
    private var eventTime = 0L
    private var downTime = 0L

    @Before fun setUp() {
        hiltRule.inject()
    }

    @Test fun bucketPreservesLayerAlphaAndUndo() = protectedFill(1, ToolType.PAINT_BUCKET)

    @Test fun gradientPreservesLayerAlphaAndUndo() = protectedFill(2, ToolType.GRADIENT)

    private fun protectedFill(
        id: Int,
        tool: ToolType,
    ) = withCanvas(id) { canvas ->
        val layer = repository.getActiveLayerId()
        val fixture =
            PixelBuffer(
                64,
                64,
                IntArray(64 * 64) { index ->
                    when (index % 64) {
                        in 0..20 -> 0x400000FF
                        in 21..41 -> 0x800000FF.toInt()
                        else -> 0
                    }
                },
            )
        repository.setLayerPixels(layer, fixture, "Coverage fixture")
        repository.setLayerAlphaLock(layer, true)
        canvas.setEditorInput(settings(tool))
        val depth = repository.undoDepth
        trigger(canvas, tool)
        await { repository.undoDepth > depth }
        assertEquals(depth + 1, repository.undoDepth)
        val actual = requireNotNull(repository.layerPixels(layer))
        actual.pixels.indices.forEach { index ->
            assertEquals("Coverage at $index", fixture.pixels[index] ushr 24, actual.pixels[index] ushr 24)
        }
        assertEquals(0x40FF0000, actual.pixels[10])
        repository.undo()
        assertArrayEquals(fixture.pixels, requireNotNull(repository.layerPixels(layer)).pixels)
    }

    @Test fun emptySelectionBucketIsNotAnUndoStep() = unchangedFill(3, ToolType.PAINT_BUCKET, true)

    @Test fun emptySelectionGradientIsNotAnUndoStep() = unchangedFill(4, ToolType.GRADIENT, true)

    @Test fun identicalBucketIsNotAnUndoStep() = unchangedFill(5, ToolType.PAINT_BUCKET, false)

    @Test fun identicalGradientIsNotAnUndoStep() = unchangedFill(6, ToolType.GRADIENT, false)

    private fun unchangedFill(
        id: Int,
        tool: ToolType,
        emptySelection: Boolean,
    ) = withCanvas(id) { canvas ->
        val layer = repository.getActiveLayerId()
        val fixture = PixelBuffer.filled(64, 64, Color.RED)
        repository.setLayerPixels(layer, fixture, "Unchanged fixture")
        if (emptySelection) repository.setSelection(SelectionMask(64, 64))
        canvas.setEditorInput(settings(tool))
        val depth = repository.undoDepth
        val revision = repository.contentRevision
        var status: String? = null
        canvas.onStatusMessage = { status = it }
        trigger(canvas, tool)
        await { status != null }
        assertTrue(requireNotNull(status).endsWith("did not change any pixels"))
        assertEquals(depth, repository.undoDepth)
        assertEquals(revision, repository.contentRevision)
        assertArrayEquals(fixture.pixels, requireNotNull(repository.layerPixels(layer)).pixels)
        val nextSession = requireNotNull(repository.beginRasterEdit(layer))
        repository.cancelRasterEdit(nextSession)
    }

    @Test fun bucketKeepsItsCapturedColourAfterRelease() = capturedSettings(7, ToolType.PAINT_BUCKET)

    @Test fun gradientKeepsItsCapturedStopsAfterRelease() = capturedSettings(8, ToolType.GRADIENT)

    private fun capturedSettings(
        id: Int,
        tool: ToolType,
    ) = withCanvas(id) { canvas ->
        val layer = repository.getActiveLayerId()
        canvas.setEditorInput(settings(tool))
        val depth = repository.undoDepth
        trigger(canvas, tool)
        canvas.setEditorInput(settings(tool, Color.GREEN))
        await { repository.undoDepth > depth }
        assertEquals(depth + 1, repository.undoDepth)
        assertTrue(requireNotNull(repository.layerPixels(layer)).pixels.all { it == Color.RED })
    }

    @Test fun bucketCannotCommitUnderAnObsoleteAlphaLock() = stalePolicy(9, ToolType.PAINT_BUCKET)

    @Test fun gradientCannotCommitUnderAnObsoleteAlphaLock() = stalePolicy(10, ToolType.GRADIENT)

    private fun stalePolicy(
        id: Int,
        tool: ToolType,
    ) = withCanvas(id) { canvas ->
        val layer = repository.getActiveLayerId()
        canvas.setEditorInput(settings(tool))
        var status: String? = null
        canvas.onStatusMessage = { status = it }
        trigger(canvas, tool)
        // The main dispatcher cannot publish the worker result until this policy change completes.
        repository.setLayerAlphaLock(layer, true)
        val depth = repository.undoDepth
        await { status != null }
        assertTrue(requireNotNull(status).startsWith("The document changed"))
        assertEquals(depth, repository.undoDepth)
        assertTrue(requireNotNull(repository.layerPixels(layer)).isEmpty())
    }

    private fun settings(
        tool: ToolType,
        color: Int = Color.RED,
    ) = brush.copy(
        tool = tool,
        brushColor = color,
        fillTolerance = 255,
        fillContiguous = false,
        gradient =
            GradientTool.Gradient(
                "Solid",
                GradientTool.GradientType.LINEAR,
                listOf(GradientTool.Stop(0f, color), GradientTool.Stop(1f, color)),
            ),
    )

    private fun trigger(
        canvas: ArtFlowCanvasView,
        tool: ToolType,
    ) {
        val start = Touch(1, 8f, 30f)
        val end = if (tool == ToolType.GRADIENT) start.copy(x = 55f) else start
        send(canvas, MotionEvent.ACTION_DOWN, start)
        send(canvas, MotionEvent.ACTION_UP, end)
    }

    private fun withCanvas(
        id: Int,
        block: suspend (ArtFlowCanvasView) -> Unit,
    ) {
        val projectId = 9_300_000L + id
        storage.deleteProjectFiles(projectId)
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            runBlocking(Dispatchers.Main) {
                repository.loadOrCreate(projectId, 64, 64, 72)
                repository.setLayerPixels(repository.getActiveLayerId(), PixelBuffer(64, 64), "Blank fixture")
            }
            var view: ArtFlowCanvasView? = null
            scenario.onActivity { activity ->
                val container = activity.findViewById<FrameLayout>(android.R.id.content)
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
