package com.artflow.studio.core.canvas

import com.artflow.studio.core.tool.ToolType

/** What an assignable stylus button does, from Prefs > Stylus. */
enum class StylusAction {
    /** Samples the colour under the pen while the button is held. This is the primary button's default. */
    EYEDROPPER,

    /** Erases while the button is held; the stroke then returns to the selected tool. */
    ERASER,

    /** Smudges while the button is held. */
    SMUDGE,

    /** Uses the tool selected before the current one while the button is held. */
    PREVIOUS_TOOL,

    /** Opens the QuickMenu once per press. */
    QUICK_MENU,

    /** Undoes once per press. */
    UNDO,

    /** The button does nothing. This is the secondary button's default. */
    NOTHING,
    ;

    val isOneShot: Boolean get() = this == QUICK_MENU || this == UNDO
}

/** What the eraser end of the stylus does: erase, or paint with the selected tool like the tip. */
enum class StylusEraserEnd {
    ERASER,
    NOTHING,
}

/** The stylus assignments, as Prefs stores them. */
data class StylusButtons(
    val primary: StylusAction = StylusAction.EYEDROPPER,
    val secondary: StylusAction = StylusAction.NOTHING,
    val eraserEnd: StylusEraserEnd = StylusEraserEnd.ERASER,
)

/** The kind of pointer that started a touch, from Android's tool type. */
enum class PointerKind { FINGER, STYLUS_TIP, STYLUS_ERASER }

/** What the canvas should do with a stylus touch. */
sealed interface StylusDecision {
    /** Start the touch with [tool]. */
    data class Use(
        val tool: ToolType,
    ) : StylusDecision

    /** Sample the colour under the pen for as long as the touch lasts. */
    data object Sample : StylusDecision

    /** Run [action] once and do not paint this touch. */
    data class Fire(
        val action: StylusAction,
    ) : StylusDecision

    /** No change: the touch is not being followed. */
    data object Ignore : StylusDecision
}

/**
 * Maps the stylus side buttons and eraser end to what a touch does.
 *
 * The buttons are read when the touch starts: a held hold-style button (eyedropper, eraser, smudge,
 * previous tool) chooses the tool for the whole touch, so a stroke never changes tool halfway, and
 * the next touch reads the buttons again, which is the revert on release. A one-shot button (undo,
 * QuickMenu) fires once per press, on the press edge at any point in the touch, and never on the
 * moves in between. When two buttons are held, the primary button's action wins unless it is
 * Nothing. Ending or cancelling a touch clears the state, so the next touch starts clean.
 */
class StylusButtonMapper(
    private val buttons: StylusButtons = StylusButtons(),
) {
    private var following = false
    private var pressed = 0

    /** The tool a pointer of [kind] paints with when no button is held. */
    fun toolFor(
        kind: PointerKind,
        currentTool: ToolType,
    ): ToolType = if (kind == PointerKind.STYLUS_ERASER && buttons.eraserEnd == StylusEraserEnd.ERASER) ToolType.ERASER else currentTool

    /** A touch starts with the buttons in [buttonState] (Android's MotionEvent.buttonState). */
    fun down(
        buttonState: Int,
        kind: PointerKind,
        currentTool: ToolType,
        previousTool: ToolType,
    ): StylusDecision {
        following = true
        pressed = buttonState and BUTTON_MASK
        if (kind == PointerKind.FINGER) return StylusDecision.Use(currentTool)
        val action = heldAction(pressed)
        if (action != null && action.isOneShot) return StylusDecision.Fire(action)
        return when (action) {
            StylusAction.EYEDROPPER -> StylusDecision.Sample
            StylusAction.ERASER -> StylusDecision.Use(ToolType.ERASER)
            StylusAction.SMUDGE -> StylusDecision.Use(ToolType.SMUDGE)
            StylusAction.PREVIOUS_TOOL -> StylusDecision.Use(previousTool)
            else -> StylusDecision.Use(toolFor(kind, currentTool))
        }
    }

    /** Buttons changed while the touch is down. Only a new press of a one-shot button produces a decision. */
    fun move(buttonState: Int): StylusDecision {
        val now = buttonState and BUTTON_MASK
        val newlyPressed = now and pressed.inv()
        pressed = now
        if (!following) return StylusDecision.Ignore
        val action = oneShotPressed(newlyPressed) ?: return StylusDecision.Ignore
        return StylusDecision.Fire(action)
    }

    /** The touch has been lifted or cancelled; the next touch starts again from the buttons it sees. */
    fun end() {
        following = false
        pressed = 0
    }

    /** The action of the first held button that is not Nothing, primary first. */
    private fun heldAction(state: Int): StylusAction? =
        listOf(BUTTON_PRIMARY to buttons.primary, BUTTON_SECONDARY to buttons.secondary)
            .firstOrNull { (bit, action) -> (state and bit) != 0 && action != StylusAction.NOTHING }
            ?.second

    private fun oneShotPressed(newlyPressed: Int): StylusAction? =
        listOf(BUTTON_PRIMARY to buttons.primary, BUTTON_SECONDARY to buttons.secondary)
            .firstOrNull { (bit, action) -> (newlyPressed and bit) != 0 && action.isOneShot }
            ?.second

    private companion object {
        /** MotionEvent.BUTTON_STYLUS_PRIMARY and BUTTON_STYLUS_SECONDARY. */
        const val BUTTON_PRIMARY = 1 shl 5
        const val BUTTON_SECONDARY = 1 shl 6
        const val BUTTON_MASK = BUTTON_PRIMARY or BUTTON_SECONDARY
    }
}
