package com.artflow.studio.core.canvas

import com.artflow.studio.core.canvas.StylusDecision.Fire
import com.artflow.studio.core.canvas.StylusDecision.Ignore
import com.artflow.studio.core.canvas.StylusDecision.Sample
import com.artflow.studio.core.canvas.StylusDecision.Use
import com.artflow.studio.core.tool.ToolType
import org.junit.Assert.assertEquals
import org.junit.Test

class StylusActionsTest {
    private val primary = 1 shl 5
    private val secondary = 1 shl 6
    private val none = 0

    private fun mapper(
        primaryAction: StylusAction = StylusAction.EYEDROPPER,
        secondaryAction: StylusAction = StylusAction.NOTHING,
        eraserEnd: StylusEraserEnd = StylusEraserEnd.ERASER,
    ) = StylusButtonMapper(StylusButtons(primaryAction, secondaryAction, eraserEnd))

    @Test
    fun tipWithoutButtonsPaintsWithTheSelectedToolAndMovesDoNothing() {
        val m = mapper()
        assertEquals(Use(ToolType.BRUSH), m.down(none, PointerKind.STYLUS_TIP, ToolType.BRUSH, ToolType.SMUDGE))
        assertEquals(Ignore, m.move(none))
        assertEquals(Ignore, m.move(none))
        m.end()
        assertEquals(Use(ToolType.BRUSH), m.down(none, PointerKind.STYLUS_TIP, ToolType.BRUSH, ToolType.SMUDGE))
    }

    @Test
    fun defaultPrimaryButtonSamplesWhileHeldAndDoesNotRefireOnMoves() {
        val m = mapper()
        assertEquals(Sample, m.down(primary, PointerKind.STYLUS_TIP, ToolType.BRUSH, ToolType.SMUDGE))
        assertEquals(Ignore, m.move(primary))
        assertEquals(Ignore, m.move(primary))
        m.end()
        assertEquals(Use(ToolType.BRUSH), m.down(none, PointerKind.STYLUS_TIP, ToolType.BRUSH, ToolType.SMUDGE))
    }

    @Test
    fun defaultSecondaryButtonIsNothingSoItChangesNoExistingBehaviour() {
        val m = mapper()
        assertEquals(Use(ToolType.BRUSH), m.down(secondary, PointerKind.STYLUS_TIP, ToolType.BRUSH, ToolType.SMUDGE))
        assertEquals(Ignore, m.move(secondary))
    }

    @Test
    fun heldEraserChoosesTheEraserForTheWholeTouchAndTheNextTouchReverts() {
        val m = mapper(primaryAction = StylusAction.ERASER)
        assertEquals(Use(ToolType.ERASER), m.down(primary, PointerKind.STYLUS_TIP, ToolType.BRUSH, ToolType.SMUDGE))
        assertEquals(Ignore, m.move(primary))
        assertEquals("releasing mid-stroke does not change the stroke's tool", Ignore, m.move(none))
        m.end()
        assertEquals(Use(ToolType.BRUSH), m.down(none, PointerKind.STYLUS_TIP, ToolType.BRUSH, ToolType.SMUDGE))
    }

    @Test
    fun heldSmudgeAndPreviousToolUseTheirTools() {
        assertEquals(
            Use(ToolType.SMUDGE),
            mapper(primaryAction = StylusAction.SMUDGE).down(primary, PointerKind.STYLUS_TIP, ToolType.BRUSH, ToolType.ERASER),
        )
        assertEquals(
            Use(ToolType.ERASER),
            mapper(primaryAction = StylusAction.PREVIOUS_TOOL).down(primary, PointerKind.STYLUS_TIP, ToolType.BRUSH, ToolType.ERASER),
        )
    }

    @Test
    fun cancelMidStrokeEndsTheHoldAndAMoveAfterwardsDoesNothing() {
        val m = mapper(primaryAction = StylusAction.ERASER)
        assertEquals(Use(ToolType.ERASER), m.down(primary, PointerKind.STYLUS_TIP, ToolType.BRUSH, ToolType.SMUDGE))
        m.end()
        assertEquals("a move after cancel has no touch to act on", Ignore, m.move(primary))
        assertEquals(Use(ToolType.BRUSH), m.down(none, PointerKind.STYLUS_TIP, ToolType.BRUSH, ToolType.SMUDGE))
    }

    @Test
    fun oneShotUndoFiresOncePerPressNotOnEachMove() {
        val m = mapper(primaryAction = StylusAction.UNDO)
        assertEquals(Fire(StylusAction.UNDO), m.down(primary, PointerKind.STYLUS_TIP, ToolType.BRUSH, ToolType.SMUDGE))
        assertEquals("held and still moving does not fire again", Ignore, m.move(primary))
        assertEquals(Ignore, m.move(none))
        assertEquals("a new press fires again", Fire(StylusAction.UNDO), m.move(primary))
        m.end()
    }

    @Test
    fun oneShotPressedMidStrokeFiresOnceOnTheEdge() {
        val m = mapper(primaryAction = StylusAction.QUICK_MENU)
        assertEquals(Use(ToolType.BRUSH), m.down(none, PointerKind.STYLUS_TIP, ToolType.BRUSH, ToolType.SMUDGE))
        assertEquals(Ignore, m.move(none))
        assertEquals(Fire(StylusAction.QUICK_MENU), m.move(primary))
        assertEquals(Ignore, m.move(primary))
        assertEquals(Ignore, m.move(primary))
        m.end()
    }

    @Test
    fun twoHeldButtonsUsePrimaryUnlessItIsNothing() {
        val both = primary or secondary
        assertEquals(
            "primary wins",
            Sample,
            mapper(StylusAction.EYEDROPPER, StylusAction.ERASER).down(both, PointerKind.STYLUS_TIP, ToolType.BRUSH, ToolType.SMUDGE),
        )
        assertEquals(
            "a Nothing primary lets the secondary act",
            Use(ToolType.ERASER),
            mapper(StylusAction.NOTHING, StylusAction.ERASER).down(both, PointerKind.STYLUS_TIP, ToolType.BRUSH, ToolType.SMUDGE),
        )
        assertEquals(
            "only the secondary held",
            Use(ToolType.ERASER),
            mapper(StylusAction.EYEDROPPER, StylusAction.ERASER).down(secondary, PointerKind.STYLUS_TIP, ToolType.BRUSH, ToolType.SMUDGE),
        )
    }

    @Test
    fun aSecondButtonPressedMidTouchFiresItsOneShotOnce() {
        val m = mapper(primaryAction = StylusAction.EYEDROPPER, secondaryAction = StylusAction.UNDO)
        assertEquals(Use(ToolType.BRUSH), m.down(none, PointerKind.STYLUS_TIP, ToolType.BRUSH, ToolType.SMUDGE))
        assertEquals(Fire(StylusAction.UNDO), m.move(secondary))
        assertEquals(Ignore, m.move(primary or secondary))
        m.end()
    }

    @Test
    fun eraserEndErasesByDefaultAndPaintsWithTheToolWhenSetToNothing() {
        assertEquals(
            Use(ToolType.ERASER),
            mapper().down(none, PointerKind.STYLUS_ERASER, ToolType.BRUSH, ToolType.SMUDGE),
        )
        assertEquals(
            Use(ToolType.BRUSH),
            mapper(eraserEnd = StylusEraserEnd.NOTHING).down(none, PointerKind.STYLUS_ERASER, ToolType.BRUSH, ToolType.SMUDGE),
        )
    }

    @Test
    fun aHeldButtonOverridesTheEraserEnd() {
        assertEquals(
            Sample,
            mapper().down(primary, PointerKind.STYLUS_ERASER, ToolType.BRUSH, ToolType.SMUDGE),
        )
    }

    @Test
    fun hoverShowsTheEraserEndTool() {
        assertEquals(ToolType.ERASER, mapper().toolFor(PointerKind.STYLUS_ERASER, ToolType.BRUSH))
        assertEquals(ToolType.BRUSH, mapper(eraserEnd = StylusEraserEnd.NOTHING).toolFor(PointerKind.STYLUS_ERASER, ToolType.BRUSH))
        assertEquals(ToolType.BRUSH, mapper().toolFor(PointerKind.STYLUS_TIP, ToolType.BRUSH))
    }

    @Test
    fun fingersAreNeverMappedToStylusButtons() {
        assertEquals(
            Use(ToolType.BRUSH),
            mapper(primaryAction = StylusAction.UNDO).down(primary, PointerKind.FINGER, ToolType.BRUSH, ToolType.SMUDGE),
        )
    }
}
