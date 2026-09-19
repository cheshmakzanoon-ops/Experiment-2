package com.artflow.studio

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.artflow.studio.core.pixels.LayerMaskSource
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.data.local.ProjectStorage
import com.artflow.studio.domain.model.brush.BrushParams
import com.artflow.studio.domain.model.brush.StrokeDestination
import com.artflow.studio.domain.repository.canvas.CanvasRepository
import com.artflow.studio.presentation.ui.MainActivity
import com.artflow.studio.presentation.ui.components.canvas.ArtFlowCanvasView
import com.artflow.studio.presentation.ui.components.canvas.EditorInput
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/** Exercises the actual GL surface, not just an activity containing the gallery. */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class EditorRenderingTest {
    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @Inject lateinit var repository: CanvasRepository

    @Inject lateinit var storage: ProjectStorage

    @Before
    fun setUp() {
        hiltRule.inject()
    }

    @Test
    fun editorRetainsArtworkAcrossRedrawContextLossAndReattachment() {
        val projectId = 9_000_001L
        storage.deleteProjectFiles(projectId)
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        var view: ArtFlowCanvasView? = null
        try {
            runBlocking(Dispatchers.Main) {
                repository.loadOrCreate(projectId, 32, 32, 72)
                repository.setLayerPixels(repository.getActiveLayerId(), PixelBuffer.filled(32, 32, Color.RED), "Rendering fixture")
            }
            scenario.onActivity { activity ->
                val container = activity.findViewById<FrameLayout>(android.R.id.content)
                container.removeAllViews()
                val canvas =
                    ArtFlowCanvasView(activity).apply {
                        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                        attachToCanvas(32, 32, 72, Color.WHITE)
                        setActiveLayerId(repository.getActiveLayerId())
                        setEditorInput(EditorInput())
                        setCheckerboardVisible(true)
                        setOnionSkinEnabled(false)
                    }
                view = canvas
                container.addView(canvas)
            }
            val canvas = requireNotNull(view)
            awaitColor(canvas, Color.RED)
            repeat(3) {
                scenario.onActivity { canvas.requestRender() }
                SystemClock.sleep(150)
                assertEquals(Color.RED, centerPixel(canvas))
            }
            scenario.onActivity {
                canvas.preserveEGLContextOnPause = false
                canvas.pauseRendering()
                canvas.resumeRendering()
            }
            awaitColor(canvas, Color.RED)
            scenario.onActivity { activity ->
                val container = activity.findViewById<FrameLayout>(android.R.id.content)
                container.removeView(canvas)
                container.addView(canvas)
            }
            awaitColor(canvas, Color.RED)
            scenario.onActivity { canvas.requestRender() }
            SystemClock.sleep(150)
            assertEquals(Color.RED, centerPixel(canvas, saveEvidence = true))
        } finally {
            scenario.close()
            runBlocking(Dispatchers.Main) { repository.dispose() }
            storage.deleteProjectFiles(projectId)
        }
    }

    @Test
    fun realPointerMaskStrokePreviewsAndUndoesOnTheGlSurface() {
        val projectId = 9_000_002L
        storage.deleteProjectFiles(projectId)
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            val layer =
                runBlocking(Dispatchers.Main) {
                    repository.loadOrCreate(projectId, 32, 32, 72)
                    repository.setLayerPixels(repository.getActiveLayerId(), PixelBuffer.filled(32, 32, Color.BLUE), "Background fixture")
                    val ink = repository.addLayer("Ink").id
                    repository.setLayerPixels(ink, PixelBuffer.filled(32, 32, Color.RED), "Ink fixture")
                    assertTrue(repository.createLayerMask(ink, LayerMaskSource.HIDE_ALL))
                    ink
                }
            val canvas =
                installCanvas(
                    scenario,
                    layer,
                    32,
                    EditorInput(
                        brushParams = BrushParams(size = 24f, pressureToSize = 0f, pressureToOpacity = 0f),
                        strokeDestination = StrokeDestination.MASK_REVEAL,
                    ),
                )
            awaitColor(canvas, Color.BLUE)
            val down = SystemClock.uptimeMillis()
            scenario.onActivity { pointer(canvas, down, MotionEvent.ACTION_DOWN) }
            // Require the real GL surface to show the provisional mask BEFORE pointer-up.
            awaitColor(canvas, Color.RED)
            runBlocking(Dispatchers.Main) {
                assertTrue(
                    repository
                        .exportSnapshot(false, false, false)
                        .frames
                        .single()
                        .pixels
                        .all { it == Color.BLUE },
                )
            }
            scenario.onActivity { pointer(canvas, down, MotionEvent.ACTION_UP) }
            awaitColor(canvas, Color.RED)
            runBlocking(Dispatchers.Main) { assertTrue(repository.undo()) }
            awaitColor(canvas, Color.BLUE)
        } finally {
            scenario.close()
            runBlocking(Dispatchers.Main) { repository.dispose() }
            storage.deleteProjectFiles(projectId)
        }
    }

    @Test
    fun redrawsCompleteWhileNewInvalidationsKeepArriving() {
        val projectId = 9_000_003L
        storage.deleteProjectFiles(projectId)
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        var producer: Job? = null
        try {
            val layer =
                runBlocking(Dispatchers.Main) {
                    repository.loadOrCreate(projectId, 1024, 1024, 72)
                    val id = repository.getActiveLayerId()
                    repository.setLayerPixels(id, PixelBuffer.filled(1024, 1024, Color.BLUE), "Background fixture")
                    id
                }
            val canvas = installCanvas(scenario, layer, 1024, EditorInput())
            awaitColor(canvas, Color.BLUE)
            producer =
                CoroutineScope(Dispatchers.Main).launch {
                    while (isActive) {
                        repository.requestPreviewRefresh()
                        delay(2)
                    }
                }
            runBlocking(Dispatchers.Main) {
                repository.setLayerPixels(layer, PixelBuffer.filled(1024, 1024, Color.RED), "Redraw fixture")
            }
            // The producer is deliberately still running. Cancellation of every in-flight frame
            // used to starve this update until input stopped. This is not a frame-rate benchmark.
            awaitColor(canvas, Color.RED)
        } finally {
            runBlocking { producer?.cancelAndJoin() }
            scenario.close()
            runBlocking(Dispatchers.Main) { repository.dispose() }
            storage.deleteProjectFiles(projectId)
        }
    }

    private fun installCanvas(
        scenario: ActivityScenario<MainActivity>,
        layer: Long,
        size: Int,
        input: EditorInput,
    ): ArtFlowCanvasView {
        var view: ArtFlowCanvasView? = null
        scenario.onActivity { activity ->
            val container = activity.findViewById<FrameLayout>(android.R.id.content)
            container.removeAllViews()
            val canvas =
                ArtFlowCanvasView(activity).apply {
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    attachToCanvas(size, size, 72, Color.WHITE)
                    setActiveLayerId(layer)
                    setEditorInput(input)
                    setOnionSkinEnabled(false)
                }
            view = canvas
            container.addView(canvas)
        }
        return requireNotNull(view)
    }

    private fun pointer(
        view: ArtFlowCanvasView,
        down: Long,
        action: Int,
    ) {
        val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, view.width / 2f, view.height / 2f, 0)
        try {
            assertTrue(view.onTouchEvent(event))
        } finally {
            event.recycle()
        }
    }

    private fun awaitColor(
        view: ArtFlowCanvasView,
        expected: Int,
    ) {
        val deadline = SystemClock.elapsedRealtime() + 20_000
        var color: Int? = null
        while (SystemClock.elapsedRealtime() < deadline) {
            if (view.width > 0 && view.height > 0) {
                color = centerPixel(view)
                if (color == expected) return
            }
            SystemClock.sleep(100)
        }
        assertEquals("The GL surface must display the committed artwork", expected, color)
    }

    private fun centerPixel(
        view: ArtFlowCanvasView,
        saveEvidence: Boolean = false,
    ): Int? {
        if (view.width <= 0 || view.height <= 0 || !view.holder.surface.isValid) return null
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        val completed = CountDownLatch(1)
        var status = PixelCopy.ERROR_UNKNOWN
        try {
            try {
                PixelCopy.request(view, bitmap, { result ->
                    status = result
                    completed.countDown()
                }, Handler(Looper.getMainLooper()))
            } catch (surfaceChanged: IllegalArgumentException) {
                // Detaching a SurfaceView invalidates its old surface before the new one exists.
                // awaitColor still requires the exact rendered colour before its hard deadline.
                return null
            }
            assertTrue("PixelCopy did not finish", completed.await(10, TimeUnit.SECONDS))
            if (status != PixelCopy.SUCCESS) return null
            if (saveEvidence) {
                val directory = TestEvidence.directory()
                File(directory, "editor-gl-recovered.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            return bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
        } finally {
            bitmap.recycle()
        }
    }
}
