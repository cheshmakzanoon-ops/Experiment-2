package com.artflow.studio.presentation.ui.components.editor

import android.content.Context
import androidx.compose.foundation.interaction.DragInteraction
import com.artflow.studio.data.local.ProjectStorage
import com.artflow.studio.data.repository.canvas.CanvasRepositoryImpl
import com.artflow.studio.domain.repository.canvas.CanvasRepository
import com.artflow.studio.domain.repository.canvas.CanvasRepository.LayerParameter
import com.artflow.studio.presentation.ui.viewmodel.LayerParameterController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock

/** The callback state shared by the actual sliders/overlay reaches the real controller and history. */
@OptIn(ExperimentalCoroutinesApi::class)
class LayerParameterGestureInputTest {
    private lateinit var repository: CanvasRepositoryImpl
    private lateinit var controller: LayerParameterController
    private lateinit var scope: CoroutineScope
    private var ordinaryChanges = 0

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        repository = CanvasRepositoryImpl(ProjectStorage(mock(Context::class.java)))
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        controller = LayerParameterController(repository, {}, { throw it })
    }

    @After
    fun tearDown() {
        controller.cancel()
        scope.cancel()
        repository.dispose()
        Dispatchers.resetMain()
    }

    private fun input(layerId: Long): LayerParameterGestureInput {
        val actions = LayerParameterGestureActions(controller::begin, controller::update, controller::end)
        return LayerParameterGestureInput({ actions }) { value ->
            ordinaryChanges++
            scope.launch { repository.setLayerOpacity(layerId, value) }
        }
    }

    @Test
    fun releasedAndCancelledDragsKeepTheirAcceptedValuesAsSeparateUndoSteps() =
        runTest {
            repository.createCanvas(8, 8, 72)
            val id = repository.getActiveLayerId()
            val input = input(id)
            input.start(id, LayerParameter.OPACITY)
            input.change(0.8f)
            input.change(0.6f)
            input.finish()
            input.start(id, LayerParameter.OPACITY)
            input.change(0.4f)
            input.change(0.2f)
            // Pointer cancellation uses the same sealing callback, retaining the last accepted value.
            input.finish()
            assertEquals(0, ordinaryChanges)
            assertEquals(2, repository.undoDepth)
            assertEquals(0.2f, requireNotNull(repository.getActiveLayer()).opacity, 0f)
            assertTrue(repository.undo())
            assertEquals(0.6f, requireNotNull(repository.getActiveLayer()).opacity, 0f)
            assertTrue(repository.undo())
            assertEquals(1f, requireNotNull(repository.getActiveLayer()).opacity, 0f)
        }

    @Test
    fun aRefusedPointerStartCannotFallBackToAnOrdinarySetter() =
        runTest {
            repository.createCanvas(8, 8, 72)
            val id = repository.getActiveLayerId()
            val edit = requireNotNull(repository.beginRasterEdit(id))
            val input = input(id)
            input.start(id, LayerParameter.OPACITY)
            input.change(0.2f)
            input.finish()
            assertEquals(0, ordinaryChanges)
            assertEquals(0, repository.undoDepth)
            assertEquals(1f, requireNotNull(repository.getActiveLayer()).opacity, 0f)
            repository.cancelRasterEdit(edit)
        }

    @Test
    fun disposedControlCallbacksCannotWriteToTheReplacementControl() =
        runTest {
            repository.createCanvas(8, 8, 72)
            val id = repository.getActiveLayerId()
            val removed = input(id)
            removed.start(id, LayerParameter.OPACITY)
            removed.change(0.6f)
            removed.dispose()
            val replacement = input(id)
            replacement.start(id, LayerParameter.OPACITY)
            removed.finish()
            removed.change(0.1f)
            replacement.change(0.4f)
            replacement.finish()
            assertEquals(0, ordinaryChanges)
            assertEquals(2, repository.undoDepth)
            assertEquals(0.4f, requireNotNull(repository.getActiveLayer()).opacity, 0f)
            assertTrue(repository.undo())
            assertEquals(0.6f, requireNotNull(repository.getActiveLayer()).opacity, 0f)
        }

    @Test
    fun controllerCancellationDropsDelayedSamplesAndNormalEndKeepsTheFinalValue() =
        runTest {
            repository.createCanvas(8, 8, 72)
            val id = repository.getActiveLayerId()
            val queued = mutableListOf<() -> Unit>()
            val old = requireNotNull(controller.begin(id, LayerParameter.OPACITY))
            queued += { controller.update(old, 0.6f) }
            controller.cancel()
            val current = requireNotNull(controller.begin(id, LayerParameter.OPACITY))
            controller.update(current, 0.5f)
            controller.end(old)
            queued.forEach { it() }
            controller.update(current, 0.4f)
            controller.end(current)
            assertEquals(1, repository.undoDepth)
            assertEquals(0.4f, requireNotNull(repository.getActiveLayer()).opacity, 0f)
            assertTrue(repository.undo())
            assertEquals(1f, requireNotNull(repository.getActiveLayer()).opacity, 0f)
        }

    @Test
    fun aLateCancellationAndItsCompletionCannotSealTheNextPointerSession() =
        runTest {
            repository.createCanvas(8, 8, 72)
            val id = repository.getActiveLayerId()
            val input = input(id)
            val interactions = LayerParameterInteractionSource(input)
            input.start(id, LayerParameter.OPACITY)
            val first = DragInteraction.Start()
            interactions.emit(first)
            input.change(0.6f)
            input.finish()

            input.start(id, LayerParameter.OPACITY)
            val second = DragInteraction.Start()
            interactions.emit(second)
            interactions.emit(DragInteraction.Cancel(first))
            interactions.finish()
            input.change(0.4f)
            interactions.emit(DragInteraction.Stop(second))
            interactions.finish()

            assertEquals(0, ordinaryChanges)
            assertEquals(2, repository.undoDepth)
            assertTrue(repository.undo())
            assertEquals(0.6f, requireNotNull(repository.getActiveLayer()).opacity, 0f)
        }

    @Test
    fun aDragFinishAfterTheNextDownDoesNotTurnItsSamplesIntoOrdinaryEdits() =
        runTest {
            repository.createCanvas(8, 8, 72)
            val id = repository.getActiveLayerId()
            val input = input(id)
            val interactions = LayerParameterInteractionSource(input)
            input.start(id, LayerParameter.OPACITY)
            val first = DragInteraction.Start()
            interactions.emit(first)
            input.change(0.6f)
            interactions.emit(DragInteraction.Stop(first))
            input.start(id, LayerParameter.OPACITY)
            interactions.finish()
            input.change(0.4f)
            // A tap has no drag interaction; its value and completion arrive after pointer-up.
            interactions.finish()
            assertEquals(0, ordinaryChanges)
            assertEquals(2, repository.undoDepth)
            assertEquals(0.4f, requireNotNull(repository.getActiveLayer()).opacity, 0f)
        }

    @Test
    fun nonPointerCallbacksRetainTheOrdinarySetterAndItsRepeatCoalescing() =
        runTest {
            repository.createCanvas(8, 8, 72)
            val input = input(repository.getActiveLayerId())
            input.change(0.8f)
            input.change(0.6f)
            input.finish()
            assertEquals(2, ordinaryChanges)
            assertEquals(1, repository.undoDepth)
        }

    @Test
    fun failedStartReportsTheErrorWithoutFallingBackToAnUngroupedEdit() {
        val failures = mutableListOf<String?>()
        val failingRepository =
            mock(CanvasRepository::class.java) { call ->
                check(call.method.name == "beginLayerParameterGesture")
                error("Layer is unavailable")
            }
        val failing = LayerParameterController(failingRepository, {}, { failures += it.message })
        val actions = LayerParameterGestureActions(failing::begin, failing::update, failing::end)
        val input = LayerParameterGestureInput({ actions }) { ordinaryChanges++ }
        input.start(1L, LayerParameter.OPACITY)
        input.change(0.2f)
        input.finish()
        assertEquals(listOf("Layer is unavailable"), failures)
        assertEquals(0, ordinaryChanges)
    }

    @Test
    fun reportedCallbackFailureSealsTheAcceptedValueAndRejectsFurtherSamples() =
        runTest {
            repository.createCanvas(8, 8, 72)
            val failures = mutableListOf<String?>()
            val failing =
                LayerParameterController(repository, { error("Refresh failed") }, { failures += it.message })
            val gesture = requireNotNull(failing.begin(repository.getActiveLayerId(), LayerParameter.OPACITY))
            failing.update(gesture, 0.6f)
            failing.update(gesture, 0.2f)
            assertEquals(listOf("Refresh failed"), failures)
            assertEquals(0.6f, requireNotNull(repository.getActiveLayer()).opacity, 0f)
            assertEquals(1, repository.undoDepth)
        }
}
