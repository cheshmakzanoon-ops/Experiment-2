package com.artflow.studio.core.canvas

import com.artflow.studio.core.canvas.PointerGestureRouter.Action
import com.artflow.studio.core.canvas.PointerGestureRouter.Event
import com.artflow.studio.core.canvas.PointerGestureRouter.Gesture
import com.artflow.studio.core.canvas.PointerGestureRouter.Pointer
import com.artflow.studio.core.canvas.PointerGestureRouter.Route
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
    fun threeFingerTapRequestsRedoAndFourFingerTapIsReported() {
        for (count in 3..4) {
            val pointers = (0 until count).map { Pointer(it, it * 30f, 5f) }
            send(Event.DOWN, pointers.first())
            for (size in 2..count) send(Event.POINTER_DOWN, *pointers.take(size).toTypedArray(), changed = size - 1)
            for (size in count downTo 2) send(Event.POINTER_UP, *pointers.take(size).toTypedArray(), changed = size - 1)
            assertEquals(count, send(Event.UP, pointers.first()).historyPointers)
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
        // A finger landing straight after the pen lifts is a palm; a fresh touch later is accepted.
        time += 2000
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

    @Test
    fun threeFingerSwipeDownFiresOnceWithoutPanning() {
        val fingers = (0 until 3).map { Pointer(it, 100f + it * 40f, 100f) }
        send(Event.DOWN, fingers[0])
        send(Event.POINTER_DOWN, *fingers.take(2).toTypedArray(), changed = 1)
        send(Event.POINTER_DOWN, *fingers.toTypedArray(), changed = 2)
        val actions = (1..8).map { step -> send(Event.MOVE, *fingers.map { it.copy(y = it.y + step * 20f) }.toTypedArray()).action }
        assertEquals(1, actions.count { it == Action.THREE_FINGER_SWIPE_DOWN })
        assertFalse(Action.NAVIGATE in actions)
        assertEquals(0, send(Event.UP, fingers[0]).historyPointers)
    }

    @Test
    fun threeFingerScrubClearsAfterRepeatedReversals() {
        val fingers = (0 until 3).map { Pointer(it, 300f + it * 40f, 300f) }
        send(Event.DOWN, fingers[0])
        send(Event.POINTER_DOWN, *fingers.take(2).toTypedArray(), changed = 1)
        send(Event.POINTER_DOWN, *fingers.toTypedArray(), changed = 2)
        val offsets = listOf(0f, 80f, 0f, 80f, 0f, 80f, 0f)
        val actions = offsets.map { dx -> send(Event.MOVE, *fingers.map { it.copy(x = it.x + dx) }.toTypedArray()).action }
        assertTrue(Action.THREE_FINGER_SCRUB in actions)
        assertFalse(Action.THREE_FINGER_SWIPE_DOWN in actions)
    }

    @Test
    fun stillFingersAreAHoldUntilTheyMoveAndAHoldIsNotATap() {
        send(Event.DOWN, finger)
        send(Event.POINTER_DOWN, finger, other, changed = 1)
        assertEquals(2, router.heldFingers())
        router.consumeTap()
        send(Event.POINTER_UP, finger, other, changed = 1)
        assertEquals("A hold that undid must not also tap", 0, send(Event.UP, finger).historyPointers)

        send(Event.DOWN, finger)
        send(Event.POINTER_DOWN, finger, other, changed = 1)
        send(Event.MOVE, finger.copy(x = 80f), other)
        assertEquals("Moving turns the hold into navigation", 0, router.heldFingers())
    }

    /** Feeds one scripted touch to a router and logs what it reported, in order. */
    private class Touch(
        val router: PointerGestureRouter,
    ) {
        private var time = 5_000L
        val log = mutableListOf<Any>()

        fun route(
            event: Event,
            vararg pointers: Pointer,
            changed: Int = 0,
        ) {
            log += router.route(event, pointers.toList(), changed, time++, cancelled = false)
        }

        fun tap(count: Int) {
            val fingers = (0 until count).map { Pointer(it, it * 30f, 5f) }
            route(Event.DOWN, fingers.first())
            for (size in 2..count) route(Event.POINTER_DOWN, *fingers.take(size).toTypedArray(), changed = size - 1)
            for (size in count downTo 2) route(Event.POINTER_UP, *fingers.take(size).toTypedArray(), changed = size - 1)
            route(Event.UP, fingers.first())
        }

        fun threeFingersDown(fingers: List<Pointer>) {
            route(Event.DOWN, fingers[0])
            route(Event.POINTER_DOWN, fingers[0], fingers[1], changed = 1)
            route(Event.POINTER_DOWN, fingers[0], fingers[1], fingers[2], changed = 2)
        }
    }

    private fun threeFingers(
        x: Float,
        y: Float,
    ) = (0 until 3).map { Pointer(it, x + it * 40f, y) }

    /** One scripted touch per gesture, the same stream the router sees on a device. */
    private val scripts: Map<Gesture, (Touch) -> Unit> =
        mapOf(
            Gesture.TWO_FINGER_TAP_UNDO to { touch -> touch.tap(2) },
            Gesture.THREE_FINGER_TAP_REDO to { touch -> touch.tap(3) },
            Gesture.FOUR_FINGER_TAP_FULL_SCREEN to { touch -> touch.tap(4) },
            Gesture.HOLD_RAPID_UNDO_REDO to { touch ->
                touch.route(Event.DOWN, finger)
                touch.route(Event.POINTER_DOWN, finger, other, changed = 1)
                touch.log += touch.router.heldFingers()
            },
            Gesture.PINCH_NAVIGATION to { touch ->
                touch.route(Event.DOWN, finger)
                touch.route(Event.POINTER_DOWN, finger, other, changed = 1)
                touch.route(Event.MOVE, finger.copy(x = -30f), other.copy(x = 140f))
            },
            Gesture.QUICK_PINCH_FIT to { touch -> touch.log += touch.router.quickPinchRecognised(1f, 0.5f, 120L) },
            Gesture.THREE_FINGER_SWIPE_COPY_PASTE to { touch ->
                val fingers = threeFingers(100f, 100f)
                touch.threeFingersDown(fingers)
                for (step in 1..8) touch.route(Event.MOVE, *fingers.map { it.copy(y = it.y + step * 20f) }.toTypedArray())
            },
            Gesture.THREE_FINGER_SCRUB_CLEAR to { touch ->
                val fingers = threeFingers(300f, 300f)
                touch.threeFingersDown(fingers)
                for (dx in listOf(0f, 80f, 0f, 80f, 0f, 80f, 0f)) {
                    touch.route(Event.MOVE, *fingers.map { it.copy(x = it.x + dx) }.toTypedArray())
                }
            },
        )

    /** What each gesture's stream must report when it is recognised. */
    private val recognised: Map<Gesture, Any> =
        mapOf(
            Gesture.TWO_FINGER_TAP_UNDO to Route(Action.FINISH_NAVIGATION, historyPointers = 2),
            Gesture.THREE_FINGER_TAP_REDO to Route(Action.FINISH_NAVIGATION, historyPointers = 3),
            Gesture.FOUR_FINGER_TAP_FULL_SCREEN to Route(Action.FINISH_NAVIGATION, historyPointers = 4),
            Gesture.HOLD_RAPID_UNDO_REDO to 2,
            Gesture.PINCH_NAVIGATION to Route(Action.NAVIGATE),
            Gesture.QUICK_PINCH_FIT to true,
            Gesture.THREE_FINGER_SWIPE_COPY_PASTE to Route(Action.THREE_FINGER_SWIPE_DOWN),
            Gesture.THREE_FINGER_SCRUB_CLEAR to Route(Action.THREE_FINGER_SCRUB),
        )

    private fun playWith(
        enabled: Set<Gesture>,
        gesture: Gesture,
    ): List<Any> {
        val touch = Touch(PointerGestureRouter(24f, 320, enabled))
        scripts.getValue(gesture)(touch)
        return touch.log
    }

    @Test
    fun everyGestureIsRecognisedWhenItIsOn() {
        for (gesture in Gesture.entries) {
            assertTrue("$gesture should be recognised", recognised.getValue(gesture) in playWith(Gesture.entries.toSet(), gesture))
        }
    }

    @Test
    fun aGestureThatIsOffIsNeverRecognisedAndDoesNotSwallowItsTouch() {
        for (gesture in Gesture.entries) {
            val log = playWith(Gesture.entries.toSet() - gesture, gesture)
            assertFalse("$gesture must stay off", recognised.getValue(gesture) in log)
        }
    }

    @Test
    fun switchingOneGestureOffChangesOnlyThatGesture() {
        val all = Gesture.entries.toSet()
        for (off in Gesture.entries) {
            for (gesture in Gesture.entries) {
                val withAll = playWith(all, gesture)
                val withOneOff = playWith(all - off, gesture)
                if (gesture == off) {
                    assertNotEquals("$off off should change its own stream", withAll, withOneOff)
                } else {
                    assertEquals("$off off must not change $gesture", withAll, withOneOff)
                }
            }
        }
    }

    @Test
    fun aDisabledTapDoesNotBlockAQuickPinchOnTheSameTouch() {
        val touch = Touch(PointerGestureRouter(24f, 320, Gesture.entries.toSet() - Gesture.TWO_FINGER_TAP_UNDO))
        touch.tap(2)
        assertEquals(Route(Action.FINISH_NAVIGATION, historyPointers = 0), touch.log.last())
        assertTrue(touch.router.quickPinchRecognised(1f, 0.5f, 120L))
    }

    @Test
    fun changingTheEnabledSetTakesEffectOnTheNextTouch() {
        val router = PointerGestureRouter(24f, 320, emptySet())
        val off = Touch(router)
        off.tap(2)
        assertEquals(Route(Action.FINISH_NAVIGATION, historyPointers = 0), off.log.last())
        router.enabled = setOf(Gesture.TWO_FINGER_TAP_UNDO)
        val on = Touch(router)
        on.tap(2)
        assertEquals(Route(Action.FINISH_NAVIGATION, historyPointers = 2), on.log.last())
    }

    @Test
    fun aRestingPalmJustAfterThePenLiftsStartsNothing() {
        send(Event.DOWN, pen)
        assertEquals(Action.END_TOOL, send(Event.UP, pen).action)
        assertEquals(Action.IGNORE, send(Event.DOWN, finger).action)
        assertEquals(Action.IGNORE, send(Event.MOVE, finger.copy(x = 60f)).action)
        assertEquals("A resting palm holds no colour", 0, router.heldFingers())
        assertEquals(Action.IGNORE, send(Event.UP, finger).action)
        time += 2000
        assertEquals("The window passes once the palm is gone", Action.START_TOOL, send(Event.DOWN, finger).action)
    }

    @Test
    fun aPalmThatStaysDownPastTheWindowStillDrawsNothing() {
        send(Event.DOWN, pen)
        send(Event.UP, pen)
        send(Event.DOWN, finger)
        time += 2000
        assertEquals(Action.IGNORE, send(Event.MOVE, finger.copy(x = 60f)).action)
        assertEquals(Action.IGNORE, send(Event.UP, finger).action)
    }

    @Test
    fun aSecondFingerAfterAPalmStillUndoes() {
        send(Event.DOWN, pen)
        send(Event.UP, pen)
        send(Event.DOWN, finger)
        assertEquals(Action.REBASE_NAVIGATION, send(Event.POINTER_DOWN, finger, other, changed = 1).action)
        assertEquals(2, router.heldFingers())
        send(Event.POINTER_UP, finger, other, changed = 1)
        assertEquals("A two-finger tap after a palm still undoes once", 2, send(Event.UP, finger).historyPointers)
    }

    @Test
    fun aPalmThatBringsAThirdContactCannotScrubOrRedo() {
        send(Event.DOWN, pen)
        send(Event.UP, pen)
        send(Event.DOWN, finger)
        assertEquals(Action.REBASE_NAVIGATION, send(Event.POINTER_DOWN, finger, other, changed = 1).action)
        val third = Pointer(15, 60f, 60f)
        assertEquals(Action.IGNORE, send(Event.POINTER_DOWN, finger, other, third, changed = 2).action)
        val trio = listOf(finger, other, third)
        val actions =
            listOf(0f, 80f, 0f, 80f, 0f, 80f, 0f).map { dx ->
                send(Event.MOVE, *trio.map { it.copy(x = it.x + dx) }.toTypedArray()).action
            }
        assertFalse("A palm cannot clear the layer", Action.THREE_FINGER_SCRUB in actions)
        assertEquals("A third contact is never a tap", 0, send(Event.UP, finger, other, third).historyPointers)
    }

    @Test
    fun aSecondContactAfterThePalmWindowStaysRejected() {
        send(Event.DOWN, pen)
        send(Event.UP, pen)
        send(Event.DOWN, finger)
        time += 2000
        assertEquals(Action.IGNORE, send(Event.POINTER_DOWN, finger, other, changed = 1).action)
        assertEquals(0, router.heldFingers())
        assertEquals(0, send(Event.UP, finger).historyPointers)
    }

    @Test
    fun aPenTakesOverARejectedPalm() {
        send(Event.DOWN, pen)
        send(Event.UP, pen)
        send(Event.DOWN, finger)
        val route = send(Event.POINTER_DOWN, finger, pen, changed = 1)
        assertEquals(Action.START_TOOL, route.action)
        assertEquals(1, route.index)
    }

    @Test
    fun aFingerStartsAToolWhenNoPenHasLifted() {
        assertEquals(Action.START_TOOL, send(Event.DOWN, finger).action)
    }
}
