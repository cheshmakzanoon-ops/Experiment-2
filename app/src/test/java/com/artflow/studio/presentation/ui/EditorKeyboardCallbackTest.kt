package com.artflow.studio.presentation.ui

import android.view.KeyEvent
import android.view.Window
import com.artflow.studio.core.canvas.ShortcutAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`

class EditorKeyboardCallbackTest {
    private val delegate = mock(Window.Callback::class.java)
    private val actions = mutableListOf<ShortcutAction>()

    @Test
    fun anEditorShortcutRunsBeforeTheViewAndKeepsTheOriginalWindowCallbacks() {
        val callback = callback()
        assertTrue(callback.dispatchKeyEvent(key(KeyEvent.KEYCODE_Z, primary = true)))
        assertEquals(listOf(ShortcutAction.UNDO), actions)
        verifyNoInteractions(delegate)
        callback.onWindowFocusChanged(true)
        verify(delegate).onWindowFocusChanged(true)
    }

    @Test
    fun textFieldsKeepTheirOwnTypingAndUndo() {
        val callback = callback(textEditor = true)
        val undo = key(KeyEvent.KEYCODE_Z, primary = true)
        val typing = key(KeyEvent.KEYCODE_B)
        `when`(delegate.dispatchKeyEvent(undo)).thenReturn(true)
        assertTrue(callback.dispatchKeyEvent(undo))
        assertFalse(callback.dispatchKeyEvent(typing))
        assertTrue(actions.isEmpty())
        verify(delegate).dispatchKeyEvent(undo)
        verify(delegate).dispatchKeyEvent(typing)
    }

    @Test
    fun keyReleasesUnknownKeysAndAnUnavailableEditorKeepTheirOriginalDispatchResult() {
        val callback = callback()
        val release = key(KeyEvent.KEYCODE_Z, primary = true, action = KeyEvent.ACTION_UP)
        val unknown = key(KeyEvent.KEYCODE_F5)
        `when`(delegate.dispatchKeyEvent(release)).thenReturn(true)
        assertTrue(callback.dispatchKeyEvent(release))
        assertFalse(callback.dispatchKeyEvent(unknown))
        val closed = EditorKeyboardCallback(delegate, { false }, { null })
        val brush = key(KeyEvent.KEYCODE_B)
        assertFalse(closed.dispatchKeyEvent(brush))
        assertTrue(actions.isEmpty())
        verify(delegate).dispatchKeyEvent(release)
        verify(delegate).dispatchKeyEvent(unknown)
        verify(delegate).dispatchKeyEvent(brush)
    }

    @Test
    fun heldKeysRepeatUndoButDoNotRepeatSave() {
        val callback = callback()
        assertTrue(callback.dispatchKeyEvent(key(KeyEvent.KEYCODE_S, primary = true)))
        assertTrue(callback.dispatchKeyEvent(key(KeyEvent.KEYCODE_S, primary = true, repeats = 2)))
        assertTrue(callback.dispatchKeyEvent(key(KeyEvent.KEYCODE_Z, primary = true)))
        assertTrue(callback.dispatchKeyEvent(key(KeyEvent.KEYCODE_Z, primary = true, repeats = 2)))
        assertEquals(listOf(ShortcutAction.SAVE, ShortcutAction.UNDO, ShortcutAction.UNDO), actions)
        verifyNoInteractions(delegate)
    }

    @Test
    fun anActionDeclinedByTheEditorContinuesToTheWindow() {
        val callback = EditorKeyboardCallback(delegate, { false }, { { false } })
        val undo = key(KeyEvent.KEYCODE_Z, primary = true)
        `when`(delegate.dispatchKeyEvent(undo)).thenReturn(true)
        assertTrue(callback.dispatchKeyEvent(undo))
        verify(delegate).dispatchKeyEvent(undo)
    }

    private fun callback(textEditor: Boolean = false): EditorKeyboardCallback =
        EditorKeyboardCallback(delegate, { textEditor }, {
            {
                actions.add(it)
                true
            }
        })

    private fun key(
        code: Int,
        primary: Boolean = false,
        repeats: Int = 0,
        action: Int = KeyEvent.ACTION_DOWN,
    ): KeyEvent =
        mock(KeyEvent::class.java).also { event ->
            `when`(event.keyCode).thenReturn(code)
            `when`(event.isCtrlPressed).thenReturn(primary)
            `when`(event.repeatCount).thenReturn(repeats)
            `when`(event.action).thenReturn(action)
        }
}
