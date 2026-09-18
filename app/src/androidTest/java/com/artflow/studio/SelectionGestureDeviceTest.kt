package com.artflow.studio

import android.graphics.Color
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.core.pixels.SelectionMask
import com.artflow.studio.core.tool.ToolType
import com.artflow.studio.data.local.ProjectStorage
import com.artflow.studio.domain.repository.canvas.CanvasRepository
import com.artflow.studio.presentation.ui.MainActivity
import com.artflow.studio.presentation.ui.components.canvas.ArtFlowCanvasView
import com.artflow.studio.presentation.ui.components.canvas.EditorInput
import com.artflow.studio.presentation.ui.components.canvas.SelectionCombineMode
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject

/** Real MotionEvents and repository state; waiting observes jobs, never replaces the selection implementation. */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class SelectionGestureDeviceTest {
    @get:Rule val hiltRule = HiltAndroidRule(this)

    @Inject lateinit var repository: CanvasRepository

    @Inject lateinit var storage: ProjectStorage

    @Before fun setUp() {
        hiltRule.inject()
    }

    @Test fun uniformCanvasWandDoesNotCrash() =
        withCanvas(1) { canvas ->
            repository.setLayerPixels(repository.getActiveLayerId(), PixelBuffer.filled(64, 64, Color.RED), "Uniform fixture")
            val depth = repository.undoDepth
            tap(canvas, 12f, 30f)
            awaitSelection(canvas)
            assertTrue(requireNotNull(repository.selection()).isFull())
            assertEquals(depth, repository.undoDepth)
        }

    @Test fun wandReplaceCanLeaveThePreviousSelection() =
        withCanvas(2) { canvas ->
            tap(canvas, 12f, 30f)
            awaitSelection(canvas)
            assertHalf(left = true)
            tap(canvas, 52f, 30f)
            awaitSelection(canvas)
            assertHalf(left = false)
        }

    @Test fun addSubtractIntersectAndReplaceAfterEmptyWorkTogether() =
        withCanvas(3) { canvas ->
            tap(canvas, 12f, 30f)
            awaitSelection(canvas)
            mode(canvas, SelectionCombineMode.ADD)
            tap(canvas, 52f, 30f)
            awaitSelection(canvas)
            assertTrue(requireNotNull(repository.selection()).isFull())
            mode(canvas, SelectionCombineMode.SUBTRACT)
            tap(canvas, 12f, 30f)
            awaitSelection(canvas)
            assertHalf(left = false)
            mode(canvas, SelectionCombineMode.INTERSECT)
            tap(canvas, 12f, 30f)
            awaitSelection(canvas)
            assertEquals(0, requireNotNull(repository.selection()).selectedPixelCount())
            mode(canvas, SelectionCombineMode.REPLACE)
            tap(canvas, 12f, 30f)
            awaitSelection(canvas)
            assertHalf(left = true)
        }

    @Test fun panelClearCannotBeUndoneByAPendingWand() =
        withCanvas(4) { canvas ->
            val accepted =
                atCommitBoundary(canvas, beforeCommit = { repository.clearSelection() }) {
                    tap(canvas, 12f, 30f)
                }
            assertFalse(accepted)
            assertNull(repository.selection())
        }

    @Test fun panelSelectAllSupersedesAPendingWand() =
        withCanvas(5) { canvas ->
            val accepted =
                atCommitBoundary(canvas, beforeCommit = { repository.setSelection(SelectionMask(64, 64).apply { selectAll() }) }) {
                    tap(canvas, 12f, 30f)
                }
            assertFalse(accepted)
            assertTrue(requireNotNull(repository.selection()).isFull())
        }

    @Test fun newerWandWinsWhenTwoTapsArriveBeforeAWorkerReturns() =
        withCanvas(6) { canvas ->
            withSamplePaused(canvas, start = { tap(canvas, 12f, 30f) }) {
                tap(canvas, 52f, 30f)
            }
            assertHalf(left = false)
        }

    @Test fun replacementDocumentRejectsOldSelectionDimensions() =
        withCanvas(7) { canvas ->
            withSamplePaused(canvas, start = { tap(canvas, 12f, 30f) }) {
                repository.createCanvas(32, 24, 72)
            }
            assertNull(repository.selection())
            assertEquals(32, repository.getCanvasSize().width)
        }

    @Test fun panelClearSupersedesProvisionalMarquee() =
        withCanvas(8) { canvas ->
            canvas.setEditorInput(EditorInput(tool = ToolType.SELECT_RECTANGLE))
            val accepted =
                atCommitBoundary(canvas, beforeCommit = { repository.clearSelection() }) {
                    drag(canvas, 5f, 5f, 55f, 55f)
                }
            assertFalse(accepted)
            assertNull(repository.selection())
        }

    @Test fun featherCannotOverwriteANewerExplicitSelection() =
        withCanvas(9) { canvas ->
            repository.setSelection(SelectionMask.rectangle(64, 64, 10f, 10f, 30f, 30f))
            val accepted =
                atCommitBoundary(canvas, beforeCommit = { repository.setSelection(SelectionMask(64, 64)) }) {
                    canvas.featherSelection(8)
                }
            assertFalse(accepted)
            assertEquals(0, requireNotNull(repository.selection()).selectedPixelCount())
        }

    @Test fun subpixelWandTapUsesTheContainingPixel() =
        withCanvas(10) { canvas ->
            tap(canvas, 31.75f, 30.25f)
            awaitSelection(canvas)
            assertHalf(left = true)
        }

    @Test fun subpixelTapInTheLastColumnIsNotOutsideTheCanvas() =
        withCanvas(11) { canvas ->
            tap(canvas, 63.75f, 30.25f)
            awaitSelection(canvas)
            assertHalf(left = false)
        }

    @Test fun negativeSubpixelTapDoesNotSelectPixelZero() =
        withCanvas(12) { canvas ->
            tap(canvas, -0.25f, 30f)
            awaitSelection(canvas)
            assertEquals(0, requireNotNull(repository.selection()).selectedPixelCount())
        }

    @Test fun bottomSubpixelTapRemainsInsideTheCanvas() =
        withCanvas(13) { canvas ->
            tap(canvas, 12f, 63.75f)
            awaitSelection(canvas)
            assertHalf(left = true)
        }

    /** Supersede at the actual commit boundary; a fast worker cannot invalidate this schedule. */
    private suspend fun atCommitBoundary(
        canvas: ArtFlowCanvasView,
        beforeCommit: () -> Unit,
        start: () -> Unit,
    ): Boolean {
        val delegate = canvas.canvasRepository
        var accepted: Boolean? = null
        canvas.canvasRepository =
            object : CanvasRepository by delegate {
                override fun commitSelectionEdit(
                    session: CanvasRepository.SelectionEditSession,
                    mask: SelectionMask,
                ): Boolean {
                    check(accepted == null) { "The selection attempted a second commit" }
                    beforeCommit()
                    return delegate.commitSelectionEdit(session, mask).also { accepted = it }
                }
            }
        try {
            start()
            awaitSelection(canvas)
            return requireNotNull(accepted) { "The real selection never reached its commit boundary" }
        } finally {
            canvas.canvasRepository = delegate
        }
    }

    /** Pause only the first real composite snapshot, leaving all selection and repository code intact. */
    private suspend fun withSamplePaused(
        canvas: ArtFlowCanvasView,
        start: () -> Unit,
        whilePaused: suspend () -> Unit,
    ) {
        val delegate = canvas.canvasRepository
        val reachedSample = CompletableDeferred<Unit>()
        val releaseSample = CompletableDeferred<Unit>()
        val exitedSample = CompletableDeferred<Unit>()
        var first = true
        canvas.canvasRepository =
            object : CanvasRepository by delegate {
                override suspend fun compositeBuffer(
                    includeHidden: Boolean,
                    applyAdjustments: Boolean,
                ): PixelBuffer? {
                    val buffer = delegate.compositeBuffer(includeHidden, applyAdjustments)
                    if (first) {
                        first = false
                        reachedSample.complete(Unit)
                        try {
                            releaseSample.await()
                        } finally {
                            exitedSample.complete(Unit)
                        }
                    }
                    return buffer
                }
            }
        try {
            start()
            withTimeout(10_000) { reachedSample.await() }
            whilePaused()
            releaseSample.complete(Unit)
            awaitSelection(canvas)
            withTimeout(10_000) { exitedSample.await() }
        } finally {
            releaseSample.complete(Unit)
            canvas.canvasRepository = delegate
        }
    }

    private fun mode(
        canvas: ArtFlowCanvasView,
        mode: SelectionCombineMode,
    ) {
        canvas.setEditorInput(EditorInput(tool = ToolType.SELECT_MAGIC_WAND, selectionMode = mode, fillTolerance = 0))
    }

    private fun assertHalf(left: Boolean) {
        val mask = requireNotNull(repository.selection())
        assertEquals(64 * 32, mask.selectedPixelCount())
        assertEquals(if (left) 255 else 0, mask.coverageAt(12, 30))
        assertEquals(if (left) 0 else 255, mask.coverageAt(52, 30))
    }

    private fun tap(
        canvas: ArtFlowCanvasView,
        x: Float,
        y: Float,
    ) = drag(canvas, x, y, x, y)

    private fun drag(
        canvas: ArtFlowCanvasView,
        x: Float,
        y: Float,
        endX: Float,
        endY: Float,
    ) {
        val start = canvas.canvasToView(x, y)
        val end = canvas.canvasToView(endX, endY)
        val now = SystemClock.uptimeMillis()
        for ((action, point) in listOf(MotionEvent.ACTION_DOWN to start, MotionEvent.ACTION_UP to end)) {
            val event = MotionEvent.obtain(now, now + 10, action, point.first, point.second, 0)
            try {
                assertTrue(canvas.onTouchEvent(event))
            } finally {
                event.recycle()
            }
        }
    }

    private suspend fun awaitSelection(canvas: ArtFlowCanvasView) {
        val field = ArtFlowCanvasView::class.java.getDeclaredField("selectionJob").apply { isAccessible = true }
        val job = requireNotNull(field.get(canvas) as? Job)
        withTimeout(10_000) { job.join() }
    }

    private fun withCanvas(
        id: Int,
        block: suspend (ArtFlowCanvasView) -> Unit,
    ) {
        val projectId = 9_400_000L + id
        storage.deleteProjectFiles(projectId)
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            runBlocking(Dispatchers.Main) {
                repository.loadOrCreate(projectId, 64, 64, 72)
                val pixels = IntArray(64 * 64) { if (it % 64 < 32) Color.RED else Color.GREEN }
                repository.setLayerPixels(repository.getActiveLayerId(), PixelBuffer(64, 64, pixels), "Two-colour fixture")
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
                        mode(this, SelectionCombineMode.REPLACE)
                    }
                container.addView(requireNotNull(view))
            }
            runBlocking(Dispatchers.Main) {
                val canvas = requireNotNull(view)
                withTimeout(10_000) { while (canvas.width == 0 || canvas.height == 0) delay(10) }
                canvas.fitToView()
                block(canvas)
            }
        } finally {
            scenario.close()
            runBlocking(Dispatchers.Main) { repository.dispose() }
            storage.deleteProjectFiles(projectId)
        }
    }
}
