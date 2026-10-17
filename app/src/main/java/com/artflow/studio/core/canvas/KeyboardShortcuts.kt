package com.artflow.studio.core.canvas

/**
 * A hardware-keyboard press reduced to what shortcuts depend on, so the table below is testable on the JVM.
 *
 * [key] is a lower-case token: a letter or digit, one of `[ ] - = , .`, or a name such as `tab`, `enter`,
 * `escape`, `backspace`, `delete`, `left`, `right`, `up` and `down`. [primary] is Ctrl or the Meta (Windows or
 * Command) key, whichever the keyboard has, so one binding serves ChromeOS, Samsung and Bluetooth keyboards.
 * Plain Tab and the arrow keys are left alone, because keyboard users move focus between controls with them.
 */
data class KeyChord(
    val key: String,
    val primary: Boolean = false,
    val shift: Boolean = false,
    val alt: Boolean = false,
)

/** What a shortcut asks the editor to do. [repeats] actions keep firing while the key is held down. */
enum class ShortcutAction(
    val label: String,
    val group: Group,
    val repeats: Boolean = false,
) {
    UNDO("Undo", Group.EDIT, repeats = true),
    REDO("Redo", Group.EDIT, repeats = true),
    CUT("Cut", Group.EDIT),
    COPY("Copy", Group.EDIT),
    COPY_MERGED("Copy canvas", Group.EDIT),
    PASTE("Paste", Group.EDIT),
    CLEAR("Clear", Group.EDIT),

    SELECT_ALL("Select all", Group.SELECTION),
    DESELECT("Deselect", Group.SELECTION),
    INVERT_SELECTION("Invert selection", Group.SELECTION),

    TOOL_BRUSH("Paint", Group.TOOLS),
    TOOL_SMUDGE("Smudge", Group.TOOLS),
    TOOL_ERASER("Erase", Group.TOOLS),
    TOOL_EYEDROPPER("Eyedropper", Group.TOOLS),
    TOOL_SELECT("Selection", Group.TOOLS),
    TOOL_TRANSFORM("Transform", Group.TOOLS),
    SWAP_COLORS("Swap colours", Group.TOOLS),

    SIZE_UP("Bigger brush", Group.BRUSH, repeats = true),
    SIZE_DOWN("Smaller brush", Group.BRUSH, repeats = true),
    OPACITY_UP("More opacity", Group.BRUSH, repeats = true),
    OPACITY_DOWN("Less opacity", Group.BRUSH, repeats = true),

    ZOOM_IN("Zoom in", Group.VIEW, repeats = true),
    ZOOM_OUT("Zoom out", Group.VIEW, repeats = true),
    FIT_TO_SCREEN("Fit to screen", Group.VIEW),
    ACTUAL_SIZE("Actual size", Group.VIEW),
    ROTATE_VIEW_RIGHT("Rotate view right", Group.VIEW, repeats = true),
    ROTATE_VIEW_LEFT("Rotate view left", Group.VIEW, repeats = true),
    PAN_LEFT("Pan left", Group.VIEW, repeats = true),
    PAN_RIGHT("Pan right", Group.VIEW, repeats = true),
    PAN_UP("Pan up", Group.VIEW, repeats = true),
    PAN_DOWN("Pan down", Group.VIEW, repeats = true),
    TOGGLE_INTERFACE("Hide or show the interface", Group.VIEW),

    NEW_LAYER("New layer", Group.LAYERS),
    DUPLICATE_LAYER("Duplicate layer", Group.LAYERS),
    MERGE_DOWN("Merge down", Group.LAYERS),

    SAVE("Save", Group.DOCUMENT),
    EXPORT("Share and export", Group.DOCUMENT),
    CLOSE_PANEL("Close the open panel", Group.DOCUMENT),
    ;

    enum class Group(
        val title: String,
    ) {
        EDIT("Edit"),
        SELECTION("Selection"),
        TOOLS("Tools"),
        BRUSH("Brush"),
        VIEW("View"),
        LAYERS("Layers"),
        DOCUMENT("Document"),
    }
}

/**
 * The editor's keyboard shortcuts. Procreate's own list is fixed rather than user-editable, and so is this one;
 * tool letters follow the usual painting-app conventions and everything with Ctrl or Meta follows the desktop ones.
 */
object KeyboardShortcuts {
    class Binding(
        val chord: KeyChord,
        val action: ShortcutAction,
    )

    private fun key(
        key: String,
        action: ShortcutAction,
        primary: Boolean = false,
        shift: Boolean = false,
        alt: Boolean = false,
    ) = Binding(KeyChord(key, primary, shift, alt), action)

    val bindings: List<Binding> =
        listOf(
            key("z", ShortcutAction.UNDO, primary = true),
            key("z", ShortcutAction.REDO, primary = true, shift = true),
            key("y", ShortcutAction.REDO, primary = true),
            key("x", ShortcutAction.CUT, primary = true),
            key("c", ShortcutAction.COPY, primary = true),
            key("c", ShortcutAction.COPY_MERGED, primary = true, shift = true),
            key("v", ShortcutAction.PASTE, primary = true),
            key("delete", ShortcutAction.CLEAR),
            key("backspace", ShortcutAction.CLEAR),
            key("a", ShortcutAction.SELECT_ALL, primary = true),
            key("d", ShortcutAction.DESELECT, primary = true),
            key("i", ShortcutAction.INVERT_SELECTION, primary = true, shift = true),
            key("b", ShortcutAction.TOOL_BRUSH),
            key("s", ShortcutAction.TOOL_SMUDGE),
            key("e", ShortcutAction.TOOL_ERASER),
            key("i", ShortcutAction.TOOL_EYEDROPPER),
            key("m", ShortcutAction.TOOL_SELECT),
            key("t", ShortcutAction.TOOL_TRANSFORM, primary = true),
            key("x", ShortcutAction.SWAP_COLORS),
            key("]", ShortcutAction.SIZE_UP),
            key("[", ShortcutAction.SIZE_DOWN),
            key("]", ShortcutAction.OPACITY_UP, alt = true),
            key("[", ShortcutAction.OPACITY_DOWN, alt = true),
            key("=", ShortcutAction.ZOOM_IN, primary = true),
            key("=", ShortcutAction.ZOOM_IN, primary = true, shift = true),
            key("-", ShortcutAction.ZOOM_OUT, primary = true),
            key("0", ShortcutAction.FIT_TO_SCREEN, primary = true),
            key("1", ShortcutAction.ACTUAL_SIZE, primary = true),
            key(".", ShortcutAction.ROTATE_VIEW_RIGHT, primary = true),
            key(",", ShortcutAction.ROTATE_VIEW_LEFT, primary = true),
            key("left", ShortcutAction.PAN_LEFT, primary = true),
            key("right", ShortcutAction.PAN_RIGHT, primary = true),
            key("up", ShortcutAction.PAN_UP, primary = true),
            key("down", ShortcutAction.PAN_DOWN, primary = true),
            key("h", ShortcutAction.TOGGLE_INTERFACE, primary = true),
            key("n", ShortcutAction.NEW_LAYER, primary = true, shift = true),
            key("j", ShortcutAction.DUPLICATE_LAYER, primary = true),
            key("e", ShortcutAction.MERGE_DOWN, primary = true),
            key("s", ShortcutAction.SAVE, primary = true),
            key("e", ShortcutAction.EXPORT, primary = true, shift = true),
            key("escape", ShortcutAction.CLOSE_PANEL),
        )

    private val byChord: Map<KeyChord, ShortcutAction> = bindings.associate { it.chord to it.action }

    /** The action [chord] is bound to, or null when it is not a shortcut. */
    fun actionFor(chord: KeyChord): ShortcutAction? = byChord[chord]

    /** Every chord bound to [action], in table order. */
    fun chordsFor(action: ShortcutAction): List<KeyChord> = bindings.filter { it.action == action }.map { it.chord }
}
