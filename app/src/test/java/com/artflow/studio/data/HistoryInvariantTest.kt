package com.artflow.studio.data

import android.content.Context
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.data.local.ProjectStorage
import com.artflow.studio.data.repository.canvas.CanvasRepositoryImpl
import com.artflow.studio.domain.model.animation.AnimationFrame
import com.artflow.studio.domain.model.brush.BrushParams
import com.artflow.studio.domain.model.layer.AdjustmentType
import com.artflow.studio.domain.model.layer.BlendMode
import com.artflow.studio.domain.model.layer.FilterType
import com.artflow.studio.domain.repository.canvas.CanvasSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import kotlin.random.Random

/**
 * Seeded random editing sessions against the real repository.
 *
 * Every step either changes the artwork or leaves it exactly as it was. A change adds one undo step,
 * or extends the previous step when it is a quick opacity change to the same layer. A step that leaves
 * the artwork unchanged adds none. Undoing or redoing a step restores the exact state before or after
 * it. Undoing everything returns to the starting document and redoing everything returns to the final
 * one. Extra undo and redo calls are harmless.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HistoryInvariantTest {
    private lateinit var repository: CanvasRepositoryImpl

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        repository = CanvasRepositoryImpl(ProjectStorage(mock(Context::class.java)))
    }

    @After
    fun tearDown() {
        repository.dispose()
        Dispatchers.resetMain()
    }

    /** Everything a user or a saved file can observe about the document. */
    private data class Observed(
        val size: CanvasSize,
        val background: Int,
        val profile: String,
        val activeLayer: Long,
        val activeFrame: Int,
        val frames: List<AnimationFrame>,
        val pixels: List<Pair<Long, List<Int>?>>,
    )

    private suspend fun observe(): Observed {
        val layers = repository.getAllLayers()
        return Observed(
            size = repository.getCanvasSize(),
            background = repository.getBackgroundColor(),
            profile = repository.getColorProfile().name,
            activeLayer = repository.getActiveLayerId(),
            activeFrame = repository.activeFrameIndex(),
            frames = repository.frames(),
            pixels = layers.map { layer -> layer.id to repository.layerPixels(layer.id)?.pixels?.toList() },
        )
    }

    /** Applies one random operation and returns its name. Operations may fail; a failure must change nothing. */
    private suspend fun randomStep(
        random: Random,
        index: Int,
    ): String {
        val ids = repository.getAllLayers().map { it.id }
        if (ids.isEmpty()) return "none"
        return when (val choice = random.nextInt(24)) {
            in 0..5 -> structureStep(random, index, ids, choice)
            in 6..11 -> propertyStep(random, ids, choice)
            in 12..15 -> editStep(random, ids, choice)
            else -> canvasStep(random, ids, choice)
        }
    }

    private suspend fun structureStep(
        random: Random,
        index: Int,
        ids: List<Long>,
        choice: Int,
    ): String {
        fun pick(): Long = ids[random.nextInt(ids.size)]
        return when (choice) {
            0 -> "addLayer".also { repository.addLayer(name = "L$index") }
            1 -> "removeLayer".also { repository.removeLayer(pick()) }
            2 -> "duplicateLayer".also { repository.duplicateLayer(pick()) }
            3 -> "reorderLayer".also { repository.reorderLayer(pick(), random.nextInt(ids.size)) }
            4 -> "groupLayers".also { if (ids.size >= 2) repository.groupLayers(ids.shuffled(random).take(2)) }
            else -> "mergeLayerDown".also { repository.mergeLayerDown(pick()) }
        }
    }

    private suspend fun propertyStep(
        random: Random,
        ids: List<Long>,
        choice: Int,
    ): String {
        fun pick(): Long = ids[random.nextInt(ids.size)]
        return when (choice) {
            6 -> "setLayerOpacity".also { repository.setLayerOpacity(pick(), random.nextInt(5) / 4f) }
            7 ->
                "setLayerBlendMode".also {
                    repository.setLayerBlendMode(pick(), BlendMode.entries[random.nextInt(BlendMode.entries.size)])
                }
            8 -> "setLayerVisibility".also { repository.setLayerVisibility(pick(), random.nextBoolean()) }
            9 -> "setLayerLock".also { repository.setLayerLock(pick(), random.nextBoolean()) }
            10 -> "setLayerName".also { repository.setLayerName(pick(), "n${random.nextInt(4)}") }
            else -> {
                val color = random.nextInt()
                "setLayerPixels".also { repository.setLayerPixels(pick(), PixelBuffer.filled(8, 6, color), "fill") }
            }
        }
    }

    private suspend fun editStep(
        random: Random,
        ids: List<Long>,
        choice: Int,
    ): String {
        fun pick(): Long = ids[random.nextInt(ids.size)]
        return when (choice) {
            12 -> "committedStroke".also { paintStroke(random, pick(), commit = true) }
            13 -> "cancelledStroke".also { paintStroke(random, pick(), commit = false) }
            14 -> "cancelledRasterEdit".also { repository.beginRasterEdit(pick())?.let { repository.cancelRasterEdit(it) } }
            else -> "cancelledSelection".also { repository.cancelSelectionEdit(repository.beginSelectionEdit()) }
        }
    }

    private suspend fun canvasStep(
        random: Random,
        ids: List<Long>,
        choice: Int,
    ): String {
        val color = random.nextInt()
        return when (choice) {
            16 -> "rotateCanvas".also { repository.rotateCanvas(90) }
            17 -> "flipCanvas".also { repository.flipCanvas(random.nextBoolean()) }
            18 ->
                "addAdjustmentLayer".also {
                    repository.addAdjustmentLayer(AdjustmentType.entries[random.nextInt(AdjustmentType.entries.size)])
                }
            19 ->
                "addFilterLayer".also {
                    repository.addFilterLayer(FilterType.entries[random.nextInt(FilterType.entries.size)])
                }
            20 -> "linkLayers".also { if (ids.size >= 2) repository.linkLayers(ids.shuffled(random).take(2)) }
            21 -> "setCanvasBackgroundColor".also { repository.setCanvasBackgroundColor(color) }
            22 -> "addFrame".also { repository.addFrame(duplicateCurrent = random.nextBoolean()) }
            else -> "clearCanvas".also { repository.clearCanvas(color) }
        }
    }

    /** A stroke with three points. When [commit] is false it is cancelled mid-gesture. */
    private suspend fun paintStroke(
        random: Random,
        layerId: Long,
        commit: Boolean,
    ) {
        val stroke = repository.beginStroke(random.nextInt(8).toFloat(), 2f, 1f, BrushParams(size = 2f), layerId)
        repository.continueStroke(stroke, 4f, 3f, 1f)
        repository.continueStroke(stroke, 6f, 4f, 0.5f)
        if (commit) repository.endStroke(stroke) else repository.cancelStroke(stroke)
    }

    /** Runs one seeded session and returns how many of its steps changed the artwork. */
    private suspend fun runSession(seed: Int): Int {
        val random = Random(seed)
        repository.createCanvas(8, 6, 72)
        val initial = observe()
        var changes = 0
        repeat(STEPS) { index ->
            val before = observe()
            val depthBefore = repository.undoDepth
            val label = randomStep(random, index)
            val after = observe()
            val depthAfter = repository.undoDepth
            val message = "seed $seed step $index ($label)"
            if (after == before) {
                // A flip or rotation is an action in its own right, so it may record a step even when the
                // pixels happen to be identical (a symmetric image). Undoing that step restores the same state.
                if (label in TRANSFORMS) {
                    assertTrue("$message added more than one undo step", depthAfter - depthBefore in 0..1)
                } else {
                    assertEquals("$message left the artwork unchanged but added history", depthBefore, depthAfter)
                }
            } else {
                changes++
                assertEquals("$message changed the artwork but left redo steps", 0, repository.redoDepth)
                val merged = depthAfter == depthBefore && label == "setLayerOpacity"
                assertTrue("$message changed the artwork without one undo step", merged || depthAfter == depthBefore + 1)
            }
            if (depthAfter == depthBefore + 1) {
                assertTrue("$message: undo did not restore the previous state", repository.undo())
                assertEquals("$message: undo state", before, observe())
                assertTrue("$message: redo did not reapply the step", repository.redo())
                assertEquals("$message: redo state", after, observe())
            }
        }
        val final = observe()

        var undone = 0
        while (repository.undo()) check(++undone < MAX_UNDO) { "undo never ran out" }
        assertEquals("seed $seed: undo did not reach the start", 0, repository.undoDepth)
        assertEquals("seed $seed: undo-all state", initial, observe())
        assertFalse(repository.undo())
        assertEquals("seed $seed: undo past the start changed the artwork", initial, observe())

        var redone = 0
        while (repository.redo()) check(++redone < MAX_UNDO) { "redo never ran out" }
        assertEquals("seed $seed: redo did not reach the end", 0, repository.redoDepth)
        assertEquals("seed $seed: redo-all state", final, observe())
        assertFalse(repository.redo())
        assertEquals("seed $seed: redo past the end changed the artwork", final, observe())
        return changes
    }

    @Test
    fun seededSessionsUndoAndRedoToTheirExactStates() =
        runTest {
            var changes = 0
            for (seed in 1..SEEDS) changes += runSession(seed)
            // The check is only meaningful if the random steps actually changed the artwork.
            assertTrue("random sessions changed nothing ($changes changes)", changes > SEEDS * STEPS / 4)
        }

    @Test
    fun aCancelledGestureLeavesNoHistoryAndNoPixels() =
        runTest {
            repository.createCanvas(8, 6, 72)
            val layer = repository.getActiveLayerId()
            val before = observe()

            val stroke = repository.beginStroke(1f, 1f, 1f, BrushParams(size = 3f), layer)
            repository.continueStroke(stroke, 5f, 4f, 1f)
            repository.cancelStroke(stroke)

            assertEquals(0, repository.undoDepth)
            assertEquals(before, observe())
        }

    private companion object {
        val TRANSFORMS = setOf("flipCanvas", "rotateCanvas")
        const val SEEDS = 40
        const val STEPS = 24
        const val MAX_UNDO = 1_000
    }
}
