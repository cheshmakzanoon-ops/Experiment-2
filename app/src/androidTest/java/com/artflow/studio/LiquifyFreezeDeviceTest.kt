package com.artflow.studio

import android.view.MotionEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.artflow.studio.core.tool.LiquifyTool
import com.artflow.studio.core.tool.ToolType
import com.artflow.studio.presentation.ui.components.canvas.ArtFlowCanvasView
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Freeze coverage survives accepted Liquify strokes while remaining outside artwork/history. */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class LiquifyFreezeDeviceTest : CanvasInputTestFixture() {
    @Test
    fun freezeAndUnfreezeTapsProtectLaterStrokesWithoutAddingHistory() =
        withCanvas(123) { canvas ->
            val layer = repository.getActiveLayerId()
            val original = liquifyFixture()
            repository.setLayerPixels(layer, original, "Freeze fixture")
            val before = repository.undoDepth
            canvas.setEditorInput(liquifyInput(LiquifyTool.Mode.FREEZE, 24f))
            tap(canvas)
            await { canvas.hasLiquifyFrozenAreas }
            awaitRasterIdle(layer)
            assertEquals(before, repository.undoDepth)
            assertArrayEquals(original.pixels, requireNotNull(repository.layerPixels(layer)).pixels)

            canvas.setEditorInput(liquifyInput(LiquifyTool.Mode.PUSH))
            repeat(2) { index ->
                liquifyDrag(canvas)
                await { repository.undoDepth == before + index + 1 }
                assertEquals(original.getSafe(32, 32), requireNotNull(repository.layerPixels(layer)).getSafe(32, 32))
                assertTrue(canvas.hasLiquifyFrozenAreas)
            }
            assertTrue(pixelDifference(original, requireNotNull(repository.layerPixels(layer))) > 0L)
            val frozenResult = requireNotNull(repository.layerPixels(layer)).copy()

            canvas.setEditorInput(liquifyInput(LiquifyTool.Mode.UNFREEZE, 24f))
            tap(canvas)
            await { !canvas.hasLiquifyFrozenAreas }
            awaitRasterIdle(layer)
            assertEquals(before + 2, repository.undoDepth)
            assertArrayEquals(frozenResult.pixels, requireNotNull(repository.layerPixels(layer)).pixels)
            canvas.setEditorInput(liquifyInput(LiquifyTool.Mode.PUSH))
            liquifyDrag(canvas)
            await { repository.undoDepth == before + 3 }
            assertNotEquals(original.getSafe(32, 32), requireNotNull(repository.layerPixels(layer)).getSafe(32, 32))
            assertTrue(repository.undo())
            assertArrayEquals(frozenResult.pixels, requireNotNull(repository.layerPixels(layer)).pixels)
        }

    @Test
    fun cancelledMaskStrokesAndLateCompletionCannotReplaceAcceptedCoverage() =
        withCanvas(124) { canvas ->
            val layer = repository.getActiveLayerId()
            repository.setLayerPixels(layer, liquifyFixture(), "Freeze cancellation fixture")
            val original = requireNotNull(repository.layerPixels(layer)).copy()
            val before = repository.undoDepth
            canvas.setEditorInput(liquifyInput(LiquifyTool.Mode.FREEZE, 24f))
            cancelledTap(canvas)
            awaitRasterIdle(layer)
            assertFalse(canvas.hasLiquifyFrozenAreas)
            tap(canvas)
            await { canvas.hasLiquifyFrozenAreas }
            awaitRasterIdle(layer)
            val percent = canvas.liquifyFrozenPercent

            canvas.setEditorInput(liquifyInput(LiquifyTool.Mode.UNFREEZE, 24f))
            cancelledTap(canvas)
            awaitRasterIdle(layer)
            assertEquals(percent, canvas.liquifyFrozenPercent)
            assertEquals(before, repository.undoDepth)
            assertArrayEquals(original.pixels, requireNotNull(repository.layerPixels(layer)).pixels)

            canvas.clearLiquifyFreeze()
            canvas.setEditorInput(liquifyInput(LiquifyTool.Mode.FREEZE, 24f))
            tap(canvas)
            // The released gesture's coroutine has not run yet. Clearing invalidates its owner.
            canvas.clearLiquifyFreeze()
            awaitRasterIdle(layer)
            assertFalse(canvas.hasLiquifyFrozenAreas)
            assertEquals(before, repository.undoDepth)
        }

    @Test
    fun resetToolChangesAndLayerChangesClearTemporaryCoverage() =
        withCanvas(125) { canvas ->
            val layer = repository.getActiveLayerId()
            repository.setLayerPixels(layer, liquifyFixture(), "Freeze lifetime fixture")
            val before = repository.undoDepth
            canvas.setEditorInput(liquifyInput(LiquifyTool.Mode.FREEZE, 24f))
            tap(canvas)
            await { canvas.hasLiquifyFrozenAreas }
            awaitRasterIdle(layer)
            canvas.resetLiquifyState()
            assertFalse(canvas.hasLiquifyFrozenAreas)
            assertEquals(before, repository.undoDepth)

            tap(canvas)
            await { canvas.hasLiquifyFrozenAreas }
            awaitRasterIdle(layer)
            canvas.setEditorInput(brush)
            assertFalse(canvas.hasLiquifyFrozenAreas)

            canvas.setEditorInput(liquifyInput(LiquifyTool.Mode.FREEZE, 24f))
            tap(canvas)
            await { canvas.hasLiquifyFrozenAreas }
            awaitRasterIdle(layer)
            val other = repository.addLayer("Other layer").id
            canvas.setActiveLayerId(other)
            assertFalse(canvas.hasLiquifyFrozenAreas)
            repository.setActiveLayer(layer)
            canvas.setActiveLayerId(layer)
            assertFalse(canvas.hasLiquifyFrozenAreas)
        }

    private fun liquifyInput(
        mode: LiquifyTool.Mode,
        size: Float = 40f,
    ) = brush.copy(
        tool = ToolType.LIQUIFY,
        brushParams = brush.brushParams.copy(size = size),
        liquify = LiquifyTool.Settings(mode = mode, strength = 1f),
    )

    private fun tap(canvas: ArtFlowCanvasView) {
        val pen = Touch(4, 32f, 32f, MotionEvent.TOOL_TYPE_STYLUS)
        send(canvas, MotionEvent.ACTION_DOWN, pen)
        send(canvas, MotionEvent.ACTION_UP, pen)
    }

    private fun cancelledTap(canvas: ArtFlowCanvasView) {
        val pen = Touch(4, 32f, 32f, MotionEvent.TOOL_TYPE_STYLUS)
        send(canvas, MotionEvent.ACTION_DOWN, pen)
        send(canvas, MotionEvent.ACTION_CANCEL, pen)
    }
}
