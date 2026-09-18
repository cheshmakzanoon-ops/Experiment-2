package com.artflow.studio.core.canvas

import com.artflow.studio.core.canvas.PointerGestureRouter.Action
import com.artflow.studio.core.canvas.PointerGestureRouter.Event
import com.artflow.studio.core.canvas.PointerGestureRouter.Pointer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PointerGestureRouterTest {
    private val router = PointerGestureRouter(24f, 320)
    private val finger = Pointer(7, 10f, 10f)
    private val other = Pointer(9, 100f, 10f)
    private val pen = Pointer(12, 50f, 50f, stylus = true)
    private var time = 1000L

    private fun send(
        event: Event,
        vararg pointers: Pointer,
        changed: Int = 0,
        cancelled: Boolean = false,
    ) = router.route(event, pointers.toList(), changed, time++, cancelled)

    @Test
    fun stylusKeepsPaintingWithRestingPalmAndReorderedIndices() {
        send(Event.DOWN, pen)
        assertEquals(Action.IGNORE, send(Event.POINTER_DOWN, pen, finger, changed = 1).action)
        val move = send(Event.MOVE, finger, pen.copy(x = 80f))
        assertEquals(Action.MOVE_TOOL, move.action)
        assertEquals(1, move.index)
        val up = send(Event.POINTER_UP, finger, pen, changed = 1)
        assertEquals(Action.END_TOOL, up.action)
        assertEquals(1, up.index)
        assertEquals(Action.IGNORE, send(Event.MOVE, finger).action)
        assertEquals(Action.IGNORE, send(Event.UP, finger).action)
    }

    @Test
    fun rejectedPalmDoesNotCancelLegitimatePen() {
        send(Event.DOWN, pen)
        send(Event.POINTER_DOWN, pen, finger, changed = 1)
        assertEquals(Action.IGNORE, send(Event.POINTER_UP, pen, finger, changed = 1, cancelled = true).action)
        assertEquals(Action.MOVE_TOOL, send(Event.MOVE, pen).action)
        assertEquals(Action.END_TOOL, send(Event.UP, pen).action)
    }

    @Test
    fun rejectedOwnerCancelsItsStrokeAndSuppressesRemainingFingers() {
        send(Event.DOWN, pen)
        send(Event.POINTER_DOWN, pen, finger, changed = 1)
        assertEquals(Action.CANCEL, send(Event.POINTER_UP, pen, finger, cancelled = true).action)
        assertEquals(Action.IGNORE, send(Event.MOVE, finger).action)
        assertEquals(Action.IGNORE, send(Event.UP, finger).action)
    }

    @Test
    fun fingerStrokeIsCancelledBeforeNavigationAndFinalUpUndoesOnce() {
        send(Event.DOWN, finger)
        val second = send(Event.POINTER_DOWN, finger, other, changed = 1)
        assertEquals(Action.REBASE_NAVIGATION, second.action)
        assertTrue(second.cancelTool)
        send(Event.POINTER_UP, finger, other, changed = 1)
        val up = send(Event.UP, finger)
        assertEquals(Action.FINISH_NAVIGATION, up.action)
        assertEquals(2, up.historyPointers)
        assertEquals(Action.IGNORE, send(Event.UP, finger).action)
    }

    @Test
    fun exactlyThreeFingerTapRequestsRedoButFourDoesNot() {
        for (count in 3..4) {
            val pointers = (0 until count).map { Pointer(it, it * 30f, 5f) }
            send(Event.DOWN, pointers.first())
            for (size in 2..count) send(Event.POINTER_DOWN, *pointers.take(size).toTypedArray(), changed = size - 1)
            for (size in count downTo 2) send(Event.POINTER_UP, *pointers.take(size).toTypedArray(), changed = size - 1)
            assertEquals(if (count == 3) 3 else 0, send(Event.UP, pointers.first()).historyPointers)
        }
    }

    @Test
    fun stationaryCentroidPinchCannotUndo() {
        send(Event.DOWN, finger)
        send(Event.POINTER_DOWN, finger, other, changed = 1)
        send(Event.MOVE, finger.copy(x = -30f), other.copy(x = 140f))
        send(Event.POINTER_UP, finger, other, changed = 1)
        assertEquals(0, send(Event.UP, finger).historyPointers)
    }

    @Test
    fun stationaryCentroidRotationCannotUndo() {
        send(Event.DOWN, finger)
        send(Event.POINTER_DOWN, finger, other, changed = 1)
        send(Event.MOVE, finger.copy(x = 55f, y = -35f), other.copy(x = 55f, y = 55f))
        send(Event.POINTER_UP, finger, other, changed = 1)
        assertEquals(0, send(Event.UP, finger).historyPointers)
    }

    @Test
    fun movementBeforeSecondFingerAndAfterFirstLiftCannotUndo() {
        send(Event.DOWN, finger)
        send(Event.MOVE, finger.copy(x = 60f))
        send(Event.POINTER_DOWN, finger.copy(x = 60f), other, changed = 1)
        send(Event.POINTER_UP, finger.copy(x = 60f), other, changed = 1)
        assertEquals(0, send(Event.UP, finger).historyPointers)
        send(Event.DOWN, finger)
        send(Event.POINTER_DOWN, finger, other, changed = 1)
        send(Event.POINTER_UP, finger, other, changed = 1)
        send(Event.MOVE, finger.copy(x = 60f))
        assertEquals(0, send(Event.UP, finger).historyPointers)
    }

    @Test
    fun batchedExcursionReturningToStartCannotUndo() {
        send(Event.DOWN, finger)
        send(Event.POINTER_DOWN, finger, other, changed = 1)
        router.observe(listOf(finger.copy(x = 70f), other))
        send(Event.MOVE, finger, other)
        send(Event.POINTER_UP, finger, other, changed = 1)
        assertEquals(0, send(Event.UP, finger).historyPointers)
    }

    @Test
    fun navigationRebasesThroughThreeTwoOnePointerTransitions() {
        send(Event.DOWN, finger)
        send(Event.POINTER_DOWN, finger, other, changed = 1)
        val third = Pointer(22, 200f, 10f)
        send(Event.POINTER_DOWN, finger, other, third, changed = 2)
        assertEquals(Action.REBASE_NAVIGATION, send(Event.POINTER_UP, finger, other, third).action)
        assertEquals(Action.NAVIGATE, send(Event.MOVE, other, third).action)
        assertEquals(Action.REBASE_NAVIGATION, send(Event.POINTER_UP, other, third).action)
        assertEquals(Action.FINISH_NAVIGATION, send(Event.UP, third).action)
    }

    @Test
    fun navigationCancellationNeverTriggersHistory() {
        send(Event.DOWN, finger)
        send(Event.POINTER_DOWN, finger, other, changed = 1)
        assertEquals(Action.CANCEL, send(Event.POINTER_UP, finger, other, changed = 1, cancelled = true).action)
        assertEquals(Action.IGNORE, send(Event.UP, finger).action)
    }

    @Test
    fun stylusCanTakeOverFingerOrNavigationWithoutDrawingFromPalm() {
        send(Event.DOWN, finger)
        var route = send(Event.POINTER_DOWN, finger, pen, changed = 1)
        assertEquals(Action.START_TOOL, route.action)
        assertTrue(route.cancelTool)
        assertEquals(1, route.index)
        send(Event.CANCEL, finger, pen)
        send(Event.DOWN, finger)
        send(Event.POINTER_DOWN, finger, other, changed = 1)
        route = send(Event.POINTER_DOWN, finger, other, pen, changed = 2)
        assertEquals(Action.START_TOOL, route.action)
        assertFalse(route.cancelTool)
        assertEquals(2, route.index)
    }

    @Test
    fun missingOwnerCancelsInsteadOfUsingPointerZero() {
        send(Event.DOWN, pen)
        assertEquals(Action.CANCEL, send(Event.MOVE, finger).action)
        assertEquals(Action.IGNORE, send(Event.UP, finger).action)
    }

    @Test
    fun globalCancellationExternalSuppressionAndFreshDownAreSafe() {
        send(Event.DOWN, pen)
        assertEquals(Action.CANCEL, send(Event.CANCEL, pen, finger).action)
        assertEquals(Action.IGNORE, send(Event.UP, finger).action)
        send(Event.DOWN, pen)
        router.suppress()
        assertEquals(Action.IGNORE, send(Event.UP, pen).action)
        assertEquals(Action.START_TOOL, send(Event.DOWN, finger).action)
    }

    @Test
    fun heldOrNonMonotoneTimeGestureCannotUndo() {
        send(Event.DOWN, finger)
        send(Event.POINTER_DOWN, finger, other, changed = 1)
        time += 400
        send(Event.POINTER_UP, finger, other, changed = 1)
        assertEquals(0, send(Event.UP, finger).historyPointers)
        send(Event.DOWN, finger)
        send(Event.POINTER_DOWN, finger, other, changed = 1)
        time -= 800
        send(Event.POINTER_UP, finger, other, changed = 1)
        assertEquals(0, send(Event.UP, finger).historyPointers)
    }

    @Test
    fun newDownCancelsUnterminatedOwner() {
        send(Event.DOWN, pen)
        assertTrue(send(Event.DOWN, finger).cancelTool)
    }
}
