package com.artflow.studio

import android.graphics.Color
import android.view.MotionEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.artflow.studio.core.lifecycle.retainCleanupFailure
import com.artflow.studio.core.lifecycle.withCleanup
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.core.pixels.Quad
import com.artflow.studio.core.pixels.SelectionMask
import com.artflow.studio.core.pixels.TransformQuad
import com.artflow.studio.core.tool.ToolType
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.CoroutineDispatcher
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.coroutines.CoroutineContext

/** Real transform input with controlled snapshot completion order. */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class CanvasTransformSnapshotDeviceTest : CanvasInputTestFixture() {
    @Test
    fun lateTransformSnapshotsCannotReplaceDraggedPixelsOrClearARetargetedSession() =
        withCanvas(144) { canvas ->
            val bounds = QueuedTransformBoundsDispatcher()
            val previousDispatcher = canvas.transformBoundsDispatcher
            canvas.transformBoundsDispatcher = bounds
            withCleanup(
                cleanup = {
                    withCleanup(
                        cleanup = {
                            canvas.transformBoundsDispatcher = previousDispatcher
                            bounds.drainAll()
                        },
                    ) { canvas.setEditorInput(brush) }
                },
            ) {
                val layer = repository.getActiveLayerId()
                val original =
                    PixelBuffer(64, 64).apply {
                        for (y in 12..35) for (x in 12..35) setUnchecked(x, y, Color.RED)
                        setUnchecked(50, 50, Color.BLUE)
                    }
                assertTrue(repository.setLayerPixels(layer, original, "Delayed transform fixture"))
                repository.setSelection(
                    SelectionMask(64, 64).apply {
                        for (y in 12..35) for (x in 12..35) coverage[y * 64 + x] = 128.toByte()
                    },
                )
                val transform = brush.copy(tool = ToolType.TRANSFORM, transformInterpolation = TransformQuad.Interpolation.BILINEAR)
                var published: Quad? = null
                canvas.onTransformQuadChanged = { published = it }
                canvas.setEditorInput(transform)
                await { bounds.pending > 0 }
                // A real preview invalidation can request the same source while its first bounds
                // calculation is pending. It must not change pixels, selection or undo history.
                val depth = repository.undoDepth
                repository.cancelRasterEdit(requireNotNull(repository.beginRasterEdit(layer)))
                await { bounds.pending >= 2 }
                val late = bounds.takeOldest()
                bounds.runAll()
                await { published != null }
                assertEquals(depth, repository.undoDepth)

                send(canvas, MotionEvent.ACTION_DOWN, Touch(1, 24f, 24f))
                send(canvas, MotionEvent.ACTION_UP, Touch(1, 30f, 27f))
                await { repository.undoDepth == depth + 1 }
                val moved = requireNotNull(published)
                assertFalse(original.pixels.contentEquals(requireNotNull(repository.layerPixels(layer)).pixels))
                late.run()
                assertEquals("Old bounds must not replace the committed placement", moved, published)
                canvas.resetTransform()
                await { repository.undoDepth == depth + 2 }
                assertArrayEquals(original.pixels, requireNotNull(repository.layerPixels(layer)).pixels)
                val project = repository.projectId()
                assertNotNull(repository.saveCanvas(project))
                assertNotNull(repository.loadCanvas(project))
                assertArrayEquals(original.pixels, requireNotNull(repository.layerPixels(layer)).pixels)

                // A stale empty-layer result must not clear the later painted-layer session or
                // publish its "Nothing to transform" failure after the user retargets the tool.
                canvas.setEditorInput(brush)
                bounds.runAll()
                repository.clearSelection()
                val empty = repository.addLayer("Empty delayed target").id
                canvas.setActiveLayerId(empty)
                canvas.setEditorInput(transform)
                await { bounds.pending > 0 }
                val emptyBounds = bounds.takeOldest()
                repository.setActiveLayer(layer)
                canvas.setActiveLayerId(layer)
                bounds.runAll()
                await { published != null }
                val retargeted = requireNotNull(published)
                var failure: String? = null
                canvas.onStatusMessage = { failure = it }
                emptyBounds.run()
                assertEquals(Quad.fromBounds(requireNotNull(original.contentBounds())), retargeted)
                assertEquals("Old empty bounds must not clear the current session", retargeted, published)
                assertNull("Old empty bounds must not report a new failure", failure)
                assertArrayEquals(original.pixels, requireNotNull(repository.layerPixels(layer)).pixels)

                canvas.setEditorInput(brush)
                canvas.setEditorInput(transform)
                await { bounds.pending > 0 }
                canvas.setEditorInput(brush)
                bounds.runAll()
                assertNull("A completed snapshot must not revive a cleared tool", published)
            }
        }

    /** Only snapshot bounds are queued; real input, repositories and rendering keep their dispatchers. */
    private class QueuedTransformBoundsDispatcher : CoroutineDispatcher() {
        private val work = ArrayDeque<Runnable>()
        private val held = mutableSetOf<Runnable>()
        val pending: Int get() = work.size

        override fun dispatch(
            context: CoroutineContext,
            block: Runnable,
        ) {
            work.addLast(block)
        }

        fun takeOldest(): Runnable {
            val task = work.removeFirst()
            held += task
            return Runnable { if (held.remove(task)) task.run() }
        }

        fun runAll() {
            while (work.isNotEmpty()) work.removeFirst().run()
        }

        fun drainAll() {
            val failures = mutableListOf<Throwable>()
            while (work.isNotEmpty() || held.isNotEmpty()) {
                val task = if (work.isNotEmpty()) work.removeFirst() else held.first().also { held.remove(it) }
                runCatching { task.run() }.onFailure(failures::add)
            }
            failures.firstOrNull()?.let { original ->
                failures.drop(1).forEach { retainCleanupFailure(original, it) }
                throw original
            }
        }
    }
}
