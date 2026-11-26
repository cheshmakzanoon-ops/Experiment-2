package com.artflow.studio

import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewGroup
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.artflow.studio.core.canvas.MotionFilter
import com.artflow.studio.core.canvas.PalmRejection
import com.artflow.studio.domain.repository.canvas.CanvasRepository
import com.artflow.studio.presentation.ui.components.canvas.ArtFlowCanvasView
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Lifecycle boundaries and acquired sample times through the real canvas and raster repository. */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class CanvasInputLifecycleDeviceTest : CanvasInputTestFixture() {
    @Test
    fun pauseAndDetachForgetPenStreamsThatNeverDeliverTheirTerminalEvents() =
        withCanvas(129) { canvas ->
            canvas.setEditorInput(brush.copy(quickShape = false, touchHoldEyedropper = false))
            val pen = Touch(4, 8f, 8f, MotionEvent.TOOL_TYPE_STYLUS)
            val depth = repository.undoDepth
            send(canvas, MotionEvent.ACTION_HOVER_ENTER, pen, hover = true)
            send(canvas, MotionEvent.ACTION_DOWN, pen)
            canvas.pauseRendering()
            // Even queued input received while paused must not become a live pen after resume.
            send(canvas, MotionEvent.ACTION_HOVER_MOVE, pen, hover = true)
            send(canvas, MotionEvent.ACTION_DOWN, pen)
            canvas.resumeRendering()
            await { canvas.isPreviewAvailable }
            paintWithBroadFinger(canvas, 20f)
            assertEquals(depth + 1, repository.undoDepth)
            assertTrue("A pen lost during pause must not reject future large fingers", alpha(image(), 40, 20) > 0)
            assertEquals("The interrupted pen stroke remains cancelled", 0, alpha(image(), 8, 8))

            send(canvas, MotionEvent.ACTION_HOVER_ENTER, pen, hover = true)
            send(canvas, MotionEvent.ACTION_DOWN, pen)
            val parent = canvas.parent as ViewGroup
            parent.removeView(canvas)
            assertFalse(canvas.isAttachedToWindow)
            // Reattaching the same view must not retain a contact acquired while detached, either.
            send(canvas, MotionEvent.ACTION_HOVER_ENTER, pen, hover = true)
            send(canvas, MotionEvent.ACTION_DOWN, pen)
            parent.addView(canvas)
            await { canvas.isPreviewAvailable }
            canvas.fitToView()
            paintWithBroadFinger(canvas, 44f)
            assertEquals(depth + 2, repository.undoDepth)
            assertTrue("A detached pen stream must not reject future large fingers", alpha(image(), 40, 44) > 0)
            assertEquals(0, alpha(image(), 8, 8))
        }

    @Test
    fun previewBlockingObservesPenReleasesAndKeepsLiveHoverPalmProtection() =
        withCanvas(130) { canvas ->
            canvas.setEditorInput(brush.copy(quickShape = false, touchHoldEyedropper = false))
            val pen = Touch(4, 8f, 8f, MotionEvent.TOOL_TYPE_STYLUS)
            val depth = repository.undoDepth
            send(canvas, MotionEvent.ACTION_HOVER_ENTER, pen, hover = true)
            withUnavailablePreview(canvas) {}
            canvas.cancelActiveGesture()
            canvas.resumeRendering() // A redundant resume is not a lifecycle transition.
            paintWithBroadFinger(canvas, 8f)
            assertEquals("Preview blocking and tool cancellation must preserve a live hover", depth, repository.undoDepth)
            assertTrue(image().pixels.all { it == 0 })
            send(canvas, MotionEvent.ACTION_HOVER_EXIT, pen, hover = true)

            withUnavailablePreview(canvas) { send(canvas, MotionEvent.ACTION_DOWN, pen) }
            paintWithBroadFinger(canvas, 8f)
            assertEquals("A pen acquired during preview loss must still reject a palm", depth, repository.undoDepth)
            send(canvas, MotionEvent.ACTION_CANCEL, pen)

            val endings = listOf(MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_UP)
            endings.forEachIndexed { index, ending ->
                send(canvas, MotionEvent.ACTION_HOVER_ENTER, pen, hover = true)
                send(canvas, MotionEvent.ACTION_DOWN, pen)
                withUnavailablePreview(canvas) {
                    send(canvas, MotionEvent.ACTION_HOVER_EXIT, pen, hover = true)
                    if (ending == MotionEvent.ACTION_POINTER_UP) {
                        val trailing = Touch(9, 56f, 56f)
                        send(canvas, MotionEvent.ACTION_POINTER_DOWN, pen, trailing, changed = 1)
                        send(canvas, ending, trailing, pen, changed = 1)
                        send(canvas, MotionEvent.ACTION_UP, trailing)
                    } else {
                        send(canvas, ending, pen)
                    }
                }
                val y = 16f + index * 16f
                paintWithBroadFinger(canvas, y)
                assertEquals("Blocked terminal action $ending must release pen contact", depth + index + 1, repository.undoDepth)
                assertTrue("A released pen must not permanently reject large fingers", alpha(image(), 40, y.toInt()) > 0)
                assertEquals("An interrupted pen must never be committed", 0, alpha(image(), 8, 8))
            }
        }

    @Test
    fun delayedDownSeedsMotionFilteringWithItsAcquisitionTime() =
        withCanvas(131) { canvas ->
            canvas.setEditorInput(
                brush.copy(motionFiltering = 1f, motionExpression = 0f, quickShape = false, touchHoldEyedropper = false),
            )
            val emitted = mutableListOf<Pair<Float, Float>>()
            canvas.canvasRepository =
                object : CanvasRepository by repository {
                    override fun continueStroke(
                        strokeId: Long,
                        x: Float,
                        y: Float,
                        pressure: Float,
                        tiltX: Float,
                        tiltY: Float,
                        timestamp: Long,
                    ) {
                        emitted.add(x to y)
                        repository.continueStroke(strokeId, x, y, pressure, tiltX, tiltY, timestamp)
                    }
                }
            val depth = repository.undoDepth
            val pen = Touch(4, 10f, 20f, MotionEvent.TOOL_TYPE_STYLUS)
            // Delivery occurs now, but the digitizer acquired the complete batch five seconds ago.
            eventTime = SystemClock.uptimeMillis() - 5_000L
            send(canvas, MotionEvent.ACTION_DOWN, pen)
            val acquiredDown = eventTime
            eventTime += 190L // send() adds 10 ms: the first sample is 200 ms after DOWN.
            send(canvas, MotionEvent.ACTION_MOVE, pen.copy(x = 30f))
            val acquiredMove = eventTime
            // The next sample releases the first spline span without ending/catching up the stroke.
            send(canvas, MotionEvent.ACTION_MOVE, pen.copy(x = 40f))

            val expected = MotionFilter(1f, 0f).apply { start(pen.x, pen.y, acquiredDown) }.add(30f, 20f, acquiredMove)
            assertTrue("The real spline must have emitted its first span", emitted.isNotEmpty())
            assertEquals("Initial filtering uses the 200 ms acquisition interval", expected.first, emitted.last().first, 0.001f)
            assertEquals(expected.second, emitted.last().second, 0.001f)
            assertEquals("The stroke is still provisional", depth, repository.undoDepth)
            send(canvas, MotionEvent.ACTION_UP, pen.copy(x = 40f))
            assertEquals(depth + 1, repository.undoDepth)
            assertTrue(alpha(image(), 30, 20) > 0)
        }

    private fun paintWithBroadFinger(
        canvas: ArtFlowCanvasView,
        y: Float,
    ) {
        // Exceed the short post-contact router window: this specifically checks stale pen state.
        eventTime += 1_000L
        val finger = Touch(9, 12f, y, size = PalmRejection.PALM_CONTACT_SIZE + 0.1f)
        send(canvas, MotionEvent.ACTION_DOWN, finger)
        send(canvas, MotionEvent.ACTION_MOVE, finger.copy(x = 40f))
        send(canvas, MotionEvent.ACTION_UP, finger.copy(x = 50f))
    }

    private suspend fun withUnavailablePreview(
        canvas: ArtFlowCanvasView,
        block: () -> Unit,
    ) {
        // Hold the GL consumer independently of the view's lifecycle reset, then use the actual
        // CPU-preview failure path. An older retained texture cannot mark this generation ready.
        canvas.onPause()
        try {
            canvas.renderer.invalidatePrimaryPreview()
            assertFalse(canvas.isPreviewAvailable)
            block()
        } finally {
            canvas.renderer.setComposite(requireNotNull(repository.compositePreview()))
            canvas.onResume()
            canvas.requestRender()
        }
        await { canvas.isPreviewAvailable }
    }
}
