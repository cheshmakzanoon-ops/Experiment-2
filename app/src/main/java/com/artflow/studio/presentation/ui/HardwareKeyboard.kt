package com.artflow.studio.presentation.ui

import android.view.KeyEvent
import android.view.KeyboardShortcutGroup
import android.view.KeyboardShortcutInfo
import com.artflow.studio.core.canvas.KeyChord
import com.artflow.studio.core.canvas.KeyboardShortcuts
import com.artflow.studio.core.canvas.ShortcutAction

/**
 * Where the open editor registers to receive keyboard shortcuts; null while no editor is on screen.
 * Set and read on the main thread only.
 */
object ShortcutHost {
    var handler: ((ShortcutAction) -> Boolean)? = null
}

/** Android key codes on one side, the platform-free tokens of [KeyChord] on the other. */
internal object AndroidKeys {
    private val tokens: Map<Int, String> =
        buildMap {
            for (offset in 0..('z' - 'a')) put(KeyEvent.KEYCODE_A + offset, ('a' + offset).toString())
            for (digit in 0..9) put(KeyEvent.KEYCODE_0 + digit, digit.toString())
            put(KeyEvent.KEYCODE_LEFT_BRACKET, "[")
            put(KeyEvent.KEYCODE_RIGHT_BRACKET, "]")
            put(KeyEvent.KEYCODE_MINUS, "-")
            put(KeyEvent.KEYCODE_NUMPAD_SUBTRACT, "-")
            put(KeyEvent.KEYCODE_EQUALS, "=")
            put(KeyEvent.KEYCODE_PLUS, "=")
            put(KeyEvent.KEYCODE_NUMPAD_ADD, "=")
            put(KeyEvent.KEYCODE_COMMA, ",")
            put(KeyEvent.KEYCODE_PERIOD, ".")
            put(KeyEvent.KEYCODE_TAB, "tab")
            put(KeyEvent.KEYCODE_ENTER, "enter")
            put(KeyEvent.KEYCODE_NUMPAD_ENTER, "enter")
            put(KeyEvent.KEYCODE_ESCAPE, "escape")
            put(KeyEvent.KEYCODE_DEL, "backspace")
            put(KeyEvent.KEYCODE_FORWARD_DEL, "delete")
            put(KeyEvent.KEYCODE_DPAD_LEFT, "left")
            put(KeyEvent.KEYCODE_DPAD_RIGHT, "right")
            put(KeyEvent.KEYCODE_DPAD_UP, "up")
            put(KeyEvent.KEYCODE_DPAD_DOWN, "down")
        }

    /** The canonical key for a token, for the system's shortcut list (the first code that maps to it). */
    private val keyCodes: Map<String, Int> =
        tokens.entries
            .sortedBy { it.key }
            .groupBy({ it.value }, { it.key })
            .mapValues { (token, codes) -> if (token == "=") KeyEvent.KEYCODE_EQUALS else codes.first() }

    /** The chord a key press makes, or null for a key no shortcut uses. */
    fun chord(event: KeyEvent): KeyChord? {
        val token = tokens[event.keyCode] ?: return null
        return KeyChord(
            key = token,
            primary = event.isCtrlPressed || event.isMetaPressed,
            shift = event.isShiftPressed,
            alt = event.isAltPressed,
        )
    }

    /** The shortcut groups Android shows in its keyboard shortcuts helper (Meta + /). */
    fun helperGroups(): List<KeyboardShortcutGroup> =
        ShortcutAction.Group.entries.mapNotNull { group ->
            val infos =
                KeyboardShortcuts.bindings
                    .filter { it.action.group == group }
                    .mapNotNull { binding ->
                        val code = keyCodes[binding.chord.key] ?: return@mapNotNull null
                        var modifiers = 0
                        if (binding.chord.primary) modifiers = modifiers or KeyEvent.META_CTRL_ON
                        if (binding.chord.shift) modifiers = modifiers or KeyEvent.META_SHIFT_ON
                        if (binding.chord.alt) modifiers = modifiers or KeyEvent.META_ALT_ON
                        KeyboardShortcutInfo(binding.action.label, code, modifiers)
                    }
            infos.takeIf { it.isNotEmpty() }?.let { KeyboardShortcutGroup(group.title, it) }
        }
}
