package com.artflow.studio

import android.graphics.Color
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.data.local.ProjectStorage
import com.artflow.studio.domain.model.brush.BrushParams
import com.artflow.studio.domain.repository.canvas.CanvasRepository
import com.artflow.studio.presentation.ui.components.canvas.ArtFlowCanvasView
import com.artflow.studio.presentation.ui.components.canvas.EditorInput
import dagger.hilt.android.testing.HiltAndroidRule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import javax.inject.Inject

/** Shared real-canvas setup and MotionEvent delivery for device regression tests. */
abstract class CanvasInputTestFixture {
    @get:Rule val hiltRule = HiltAndroidRule(this)

    @Inject lateinit var repository: CanvasRepository

    @Inject lateinit var storage: ProjectStorage
    protected val brush =
        EditorInput(
            brushParams = BrushParams(size = 6f, pressureToSize = 0f, pressureToOpacity = 0f, smoothing = 0f),
            brushColor = Color.RED,
            eraserSize = 10f,
        )
    protected var eventTime = 0L
    protected var downTime = 0L

    @Before fun setUp() {
        hiltRule.inject()
    }

    protected fun sendSmudgeBatch(
        canvas: ArtFlowCanvasView,
        historical: Touch,
        current: Touch,
    ) {
        fun coords(touch: Touch): Array<MotionEvent.PointerCoords> {
            val point = canvas.canvasToView(touch.x, touch.y)
            return arrayOf(
                MotionEvent.PointerCoords().apply {
                    x = point.first
                    y = point.second
                    pressure = touch.pressure
                    setAxisValue(MotionEvent.AXIS_TILT, touch.tilt)
                    setAxisValue(MotionEvent.AXIS_ORIENTATION, touch.orientation)
                },
            )
        }
        eventTime += 10
        val event =
            MotionEvent.obtain(
                downTime,
                eventTime,
                MotionEvent.ACTION_MOVE,
                1,
                arrayOf(
                    MotionEvent.PointerProperties().apply {
                        id = historical.id
                        toolType = historical.type
                    },
                ),
                coords(historical),
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
            event.addBatch(eventTime, coords(current), 0)
            assertEquals(1, event.historySize)
            assertTrue(canvas.onTouchEvent(event))
        } finally {
            event.recycle()
        }
    }

    protected suspend fun awaitRasterIdle(layerId: Long) {
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

    protected fun assertZoomAnchor(
        canvas: ArtFlowCanvasView,
        point: Pair<Float, Float>,
        anchor: Pair<Float, Float>,
    ) {
        val actual = canvas.canvasToView(point.first, point.second)
        assertEquals("Zoom must keep its initial canvas point under the pointer", anchor.first, actual.first, 0.02f)
        assertEquals("Zoom must keep its initial canvas point under the pointer", anchor.second, actual.second, 0.02f)
    }

    protected fun liquifyDrag(canvas: ArtFlowCanvasView) {
        val pen = Touch(4, 18f, 32f, MotionEvent.TOOL_TYPE_STYLUS)
        send(canvas, MotionEvent.ACTION_DOWN, pen)
        send(canvas, MotionEvent.ACTION_MOVE, pen.copy(x = 32f))
        send(canvas, MotionEvent.ACTION_UP, pen.copy(x = 40f))
    }

    protected fun liquifyFixture(): PixelBuffer =
        PixelBuffer(64, 64).apply {
            for (y in 0 until height) for (x in 0 until width) setUnchecked(x, y, Color.rgb(x * 3, y * 3, 40))
        }

    protected fun pixelDifference(
        a: PixelBuffer,
        b: PixelBuffer,
    ): Long =
        a.pixels.indices.sumOf { index ->
            val x = a.pixels[index]
            val y = b.pixels[index]
            listOf(0, 8, 16, 24).sumOf { shift -> kotlin.math.abs(((x ushr shift) and 255) - ((y ushr shift) and 255)).toLong() }
        }

    protected fun maximumChannelDifference(
        left: PixelBuffer,
        right: PixelBuffer,
    ): Int =
        left.pixels.indices.maxOf { i ->
            listOf(0, 8, 16, 24).maxOf { shift ->
                kotlin.math.abs(((left.pixels[i] ushr shift) and 255) - ((right.pixels[i] ushr shift) and 255))
            }
        }

    protected suspend fun linkedFixture(canvas: ArtFlowCanvasView): Pair<Long, Long> {
        val first = repository.getActiveLayerId()
        repository.setLayerPixels(first, PixelBuffer(64, 64).apply { setUnchecked(20, 20, Color.RED) }, "Ink")
        val second = repository.addLayer("Colour").id
        repository.setLayerPixels(second, PixelBuffer(64, 64).apply { setUnchecked(40, 40, Color.BLUE) }, "Colour")
        assertTrue(repository.linkLayers(listOf(first, second)))
        repository.setActiveLayer(first)
        canvas.setActiveLayerId(first)
        // No temporary multi-selection: persisted membership alone drives these edits.
        canvas.setTransformCompanions(emptySet())
        return first to second
    }

    protected fun withCanvas(
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
                await { canvas.width > 0 && canvas.height > 0 && canvas.isPreviewAvailable }
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

    protected suspend fun image(): PixelBuffer = requireNotNull(repository.compositeFrame(0, transparentBackground = true))

    protected fun alpha(
        pixels: PixelBuffer,
        x: Int,
        y: Int,
    ) = pixels.getSafe(x, y) ushr 24

    protected suspend fun await(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (!condition() && SystemClock.elapsedRealtime() < deadline) delay(10)
        assertTrue("Canvas operation did not finish before its deadline", condition())
    }

    protected data class Touch(
        val id: Int,
        val x: Float,
        val y: Float,
        val type: Int = MotionEvent.TOOL_TYPE_FINGER,
        val pressure: Float = 1f,
        val tilt: Float = 0f,
        val orientation: Float = 0f,
    )

    protected fun send(
        canvas: ArtFlowCanvasView,
        action: Int,
        vararg touches: Touch,
        changed: Int = 0,
        flags: Int = 0,
        viewCoordinates: Boolean = false,
        trace: ((MotionEvent) -> Unit)? = null,
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
                        setAxisValue(MotionEvent.AXIS_TILT, touch.tilt)
                        setAxisValue(MotionEvent.AXIS_ORIENTATION, touch.orientation)
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
            trace?.invoke(event)
            assertTrue(canvas.onTouchEvent(event))
        } finally {
            event.recycle()
        }
    }
}
