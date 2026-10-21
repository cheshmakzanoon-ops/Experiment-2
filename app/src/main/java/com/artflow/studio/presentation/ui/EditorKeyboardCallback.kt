package com.artflow.studio.presentation.ui

import android.view.KeyEvent
import android.view.Window
import com.artflow.studio.core.canvas.KeyboardShortcuts
import com.artflow.studio.core.canvas.ShortcutAction

/**
 * Public window interception keeps editor shortcuts ahead of view dispatch. Text fields keep their
 * own typing and undo, and all unconsumed input and other window callbacks reach the original host.
 */
internal class EditorKeyboardCallback(
    private val delegate: Window.Callback,
    private val textEditorFocused: () -> Boolean,
    private val shortcutHandler: () -> ((ShortcutAction) -> Boolean)?,
) : Window.Callback by delegate {
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && consumesAsShortcut(event)) return true
        return delegate.dispatchKeyEvent(event)
    }

    private fun consumesAsShortcut(event: KeyEvent): Boolean {
        val handler = shortcutHandler() ?: return false
        if (textEditorFocused()) return false
        val action = AndroidKeys.chord(event)?.let(KeyboardShortcuts::actionFor) ?: return false
        // A held key repeats only the actions made for it; the rest still consume their own repeats.
        return if (event.repeatCount > 0 && !action.repeats) true else handler(action)
    }
}
