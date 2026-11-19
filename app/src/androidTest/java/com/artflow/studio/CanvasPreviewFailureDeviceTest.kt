package com.artflow.studio

import android.graphics.Color
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.data.local.ProjectStorage
import com.artflow.studio.data.renderer.opengl.CanvasPreviewAvailability
import com.artflow.studio.data.renderer.opengl.OpenGLCanvasRenderer
import com.artflow.studio.domain.model.brush.BrushParams
import com.artflow.studio.domain.repository.canvas.CanvasRepository
import com.artflow.studio.presentation.ui.components.canvas.ArtFlowCanvasView
import com.artflow.studio.presentation.ui.components.canvas.EditorInput
import com.artflow.studio.presentation.ui.components.canvas.GestureControls
import com.artflow.studio.presentation.ui.components.canvas.ShapeEditState
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject

/** Real GL failures and pointer gestures; the test never marks a fake preview as available. */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class CanvasPreviewFailureDeviceTest {
    @get:Rule
    val hilt = HiltAndroidRule(this)

    @Inject lateinit var repository: CanvasRepository

    @Inject lateinit var storage: ProjectStorage

    private var downTime = 0L
    private var eventTime = 0L
    private val brush = BrushParams(size = 6f, pressureToSize = 0f, pressureToOpacity = 0f, smoothing = 0f)

    @Before
    fun setup() {
        hilt.inject()
    }

    @Test
    fun aLostPreviewCancelsPaintingBlocksNewInputAndAllowsSaveAndRecovery() =
        withCanvas(1) { canvas, budget ->
            val original = image().pixels.copyOf()
            val depth = repository.undoDepth
            send(canvas, MotionEvent.ACTION_DOWN, 10f, 20f)
            send(canvas, MotionEvent.ACTION_MOVE, 48f, 20f)
            // A pointer stroke remains provisional until UP. Observe the same pixels the
            // renderer receives, while separately protecting the committed artwork.
            assertFalse(original.contentEquals(requireNotNull(repository.compositePreview()).pixels))
            assertArrayEquals(original, requireNotNull(repository.compositeBuffer()).pixels)
            failPreview(canvas, budget)
            await { !canvas.isPreviewAvailable && requireNotNull(repository.compositePreview()).pixels.contentEquals(original) }
            assertArrayEquals(original, image().pixels)
            assertEquals(depth, repository.undoDepth)

            send(canvas, MotionEvent.ACTION_UP, 48f, 20f)
            send(canvas, MotionEvent.ACTION_DOWN, 12f, 30f)
            send(canvas, MotionEvent.ACTION_MOVE, 46f, 30f)
            send(canvas, MotionEvent.ACTION_UP, 46f, 30f)
            assertFalse(canvas.colorDrop(0f, 0f))
            assertArrayEquals(original, image().pixels)
            assertEquals(depth, repository.undoDepth)
            var toolbarAccessible = false
            canvas.onToolStripToggle = { toolbarAccessible = true }
            canvas.performClick()
            assertTrue(toolbarAccessible)
            assertNotNull(repository.saveCanvas(repository.projectId()))
            assertTrue(storage.documentFile(repository.projectId()).isFile)

            recoverPreview(canvas, budget)
            send(canvas, MotionEvent.ACTION_UP, 46f, 30f) // An old trailing pointer cannot commit after recovery.
            assertEquals(depth, repository.undoDepth)
            send(canvas, MotionEvent.ACTION_DOWN, 10f, 40f)
            send(canvas, MotionEvent.ACTION_MOVE, 48f, 40f)
            send(canvas, MotionEvent.ACTION_UP, 48f, 40f)
            await { repository.undoDepth == depth + 1 }
            assertFalse(original.contentEquals(image().pixels))
        }

    @Test
    fun aLostPreviewRestoresTheOriginalShapeInsteadOfCommittingAnUnseenNodeDrag() =
        withCanvas(2) { canvas, budget ->
            val original = beginShapeNodeDrag(canvas)
            failPreview(canvas, budget)
            await { !canvas.isPreviewAvailable && image().pixels.contentEquals(original) }
            val depth = repository.undoDepth
            send(canvas, MotionEvent.ACTION_UP, 24f, 35f)
            assertArrayEquals(original, image().pixels)
            assertEquals(depth, repository.undoDepth)
            recoverPreview(canvas, budget)
            assertArrayEquals(original, image().pixels)
        }

    @Test
    fun aLatePreviewFailureCannotRedoAnotherDocumentsHistoryWithTheSameNumericMark() =
        withCanvas(3, useDepthHistoryMark = true) { canvas, budget ->
            beginShapeNodeDrag(canvas)
            val oldMark = canvas.canvasRepository.historyMark
            val replacement = repository.projectId() + 100
            try {
                repository.loadOrCreate(replacement, 64, 64, 72)
                val layer = repository.getActiveLayerId()
                repository.setLayerPixels(layer, PixelBuffer.filled(64, 64, Color.GREEN), "Other document")
                repository.setLayerPixels(layer, PixelBuffer.filled(64, 64, Color.RED), "Other redo candidate")
                assertTrue(repository.undo())
                assertEquals("Exercise the history-marker collision explicitly", oldMark, canvas.canvasRepository.historyMark)
                val expected = image().pixels.copyOf()
                val expectedUndo = repository.undoDepth
                val expectedRedo = repository.redoDepth
                failPreview(canvas, budget)
                await { !canvas.isPreviewAvailable }
                delay(100) // Let the queued main-thread failure callback execute against the new document.
                assertEquals(replacement, repository.projectId())
                assertEquals(expectedUndo, repository.undoDepth)
                assertEquals(expectedRedo, repository.redoDepth)
                assertArrayEquals("A late GL failure must not restore this document's red redo entry", expected, image().pixels)
            } finally {
                storage.deleteProjectFiles(replacement)
            }
        }

    private suspend fun beginShapeNodeDrag(canvas: ArtFlowCanvasView): IntArray {
        var offer: ShapeEditState? = null
        var snapped = false
        canvas.onShapeEditChanged = { offer = it }
        canvas.onStatusMessage = { if (it.startsWith("QuickShape:")) snapped = true }
        canvas.setEditorInput(
            EditorInput(brushParams = brush, gestures = GestureControls(quickShapeDelayMs = 50), quickShape = true),
        )
        send(canvas, MotionEvent.ACTION_DOWN, 10f, 20f)
        for (x in 14..50 step 4) send(canvas, MotionEvent.ACTION_MOVE, x.toFloat(), 20f)
        await { snapped }
        send(canvas, MotionEvent.ACTION_UP, 50f, 20f)
        await { offer != null }
        val original = image().pixels.copyOf()
        val depth = repository.undoDepth
        canvas.beginShapeEdit()
        val node = requireNotNull(offer).nodes.first()
        send(canvas, MotionEvent.ACTION_DOWN, node.first, node.second)
        send(canvas, MotionEvent.ACTION_MOVE, node.first + 10f, node.second + 15f)
        assertEquals("Dragging temporarily removes the original shape", depth - 1, repository.undoDepth)
        return original
    }

    /** Run the genuine GL draw on its surface thread while the main callback remains queued. */
    private suspend fun failPreview(
        canvas: ArtFlowCanvasView,
        budget: AtomicInteger,
    ) {
        val pixels = requireNotNull(repository.compositeBuffer())
        budget.set(0)
        canvas.renderer.setComposite(pixels)
        val drawn = CountDownLatch(1)
        canvas.queueEvent {
            try {
                canvas.renderer.onDrawFrame(null)
            } finally {
                drawn.countDown()
            }
        }
        assertTrue("The GL failure did not execute", drawn.await(10, TimeUnit.SECONDS))
        assertEquals(CanvasPreviewAvailability.UNAVAILABLE, canvas.renderer.previewAvailability)
        delay(10)
    }

    private suspend fun recoverPreview(
        canvas: ArtFlowCanvasView,
        budget: AtomicInteger,
    ) {
        budget.set(64)
        canvas.renderer.setComposite(requireNotNull(repository.compositeBuffer()))
        canvas.requestRender()
        await { canvas.isPreviewAvailable }
    }

    private fun withCanvas(
        id: Int,
        useDepthHistoryMark: Boolean = false,
        block: suspend (ArtFlowCanvasView, AtomicInteger) -> Unit,
    ) {
        val projectId = 9_760_000L + id
        storage.deleteProjectFiles(projectId)
        val scenario = ActivityScenario.launch(SelectionCanvasTestActivity::class.java)
        val budget = AtomicInteger(64)
        try {
            runBlocking(Dispatchers.Main) {
                repository.loadOrCreate(projectId, 64, 64, 72)
                repository.setLayerPixels(repository.getActiveLayerId(), PixelBuffer.filled(64, 64, Color.BLUE), "Saved picture")
            }
            lateinit var canvas: ArtFlowCanvasView
            scenario.onActivity { activity ->
                canvas =
                    ArtFlowCanvasView(activity).apply {
                        renderer = OpenGLCanvasRenderer { budget.get() }
                        if (useDepthHistoryMark) {
                            // CanvasRepository's fallback history marker is a depth; document
                            // identity must protect rollback even when a marker value is reused.
                            canvasRepository =
                                object : CanvasRepository by repository {
                                    override val historyMark: Long get() = repository.undoDepth.toLong()
                                }
                        }
                        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                        attachToCanvas(64, 64, 72, Color.WHITE)
                        setActiveLayerId(repository.getActiveLayerId())
                        setEditorInput(EditorInput(brushParams = brush, quickShape = false))
                    }
                activity.canvasContainer.addView(canvas)
            }
            runBlocking(Dispatchers.Main) {
                await { canvas.width > 0 && canvas.height > 0 && canvas.isPreviewAvailable }
                canvas.fitToView()
                eventTime = SystemClock.uptimeMillis()
                block(canvas, budget)
            }
        } finally {
            scenario.close()
            runBlocking(Dispatchers.Main) { repository.dispose() }
            storage.deleteProjectFiles(projectId)
        }
    }

    private suspend fun image(): PixelBuffer = requireNotNull(repository.compositeFrame(repository.activeFrameIndex(), true))

    private suspend fun await(condition: suspend () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (!condition() && SystemClock.elapsedRealtime() < deadline) delay(10)
        assertTrue("The canvas preview operation did not complete", condition())
    }

    private fun send(
        canvas: ArtFlowCanvasView,
        action: Int,
        x: Float,
        y: Float,
    ) {
        eventTime += 20
        if (action == MotionEvent.ACTION_DOWN) downTime = eventTime
        val point = canvas.canvasToView(x, y)
        val properties =
            MotionEvent.PointerProperties().apply {
                id = 1
                toolType = MotionEvent.TOOL_TYPE_STYLUS
            }
        val coordinates =
            MotionEvent.PointerCoords().apply {
                this.x = point.first
                this.y = point.second
                pressure = 1f
                size = 1f
            }
        val event =
            MotionEvent.obtain(
                downTime,
                eventTime,
                action,
                1,
                arrayOf(properties),
                arrayOf(coordinates),
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
            canvas.onTouchEvent(event)
        } finally {
            event.recycle()
        }
    }
}
