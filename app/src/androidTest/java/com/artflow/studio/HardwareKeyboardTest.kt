package com.artflow.studio

import android.view.KeyEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.artflow.studio.core.canvas.KeyChord
import com.artflow.studio.core.canvas.KeyboardShortcuts
import com.artflow.studio.core.canvas.ShortcutAction
import com.artflow.studio.presentation.ui.AndroidKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HardwareKeyboardTest {
    private fun press(
        keyCode: Int,
        meta: Int = 0,
    ) = KeyEvent(0L, 0L, KeyEvent.ACTION_DOWN, keyCode, 0, meta)

    @Test
    fun ctrlAndMetaBothCountAsThePrimaryModifier() {
        assertEquals(KeyChord("z", primary = true), AndroidKeys.chord(press(KeyEvent.KEYCODE_Z, KeyEvent.META_CTRL_ON)))
        assertEquals(KeyChord("z", primary = true), AndroidKeys.chord(press(KeyEvent.KEYCODE_Z, KeyEvent.META_META_ON)))
        assertEquals(
            KeyChord("z", primary = true, shift = true),
            AndroidKeys.chord(press(KeyEvent.KEYCODE_Z, KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON)),
        )
    }

    @Test
    fun keyCodesMapToTheTokensTheTableUses() {
        assertEquals("[", AndroidKeys.chord(press(KeyEvent.KEYCODE_LEFT_BRACKET))?.key)
        assertEquals("]", AndroidKeys.chord(press(KeyEvent.KEYCODE_RIGHT_BRACKET))?.key)
        assertEquals("=", AndroidKeys.chord(press(KeyEvent.KEYCODE_PLUS))?.key)
        assertEquals("backspace", AndroidKeys.chord(press(KeyEvent.KEYCODE_DEL))?.key)
        assertEquals("delete", AndroidKeys.chord(press(KeyEvent.KEYCODE_FORWARD_DEL))?.key)
        assertEquals("left", AndroidKeys.chord(press(KeyEvent.KEYCODE_DPAD_LEFT))?.key)
        assertEquals("0", AndroidKeys.chord(press(KeyEvent.KEYCODE_0))?.key)
        assertNull(AndroidKeys.chord(press(KeyEvent.KEYCODE_F5)))
    }

    @Test
    fun aKeyPressResolvesToItsAction() {
        val undo = AndroidKeys.chord(press(KeyEvent.KEYCODE_Z, KeyEvent.META_CTRL_ON))
        assertEquals(ShortcutAction.UNDO, undo?.let(KeyboardShortcuts::actionFor))
        val size = AndroidKeys.chord(press(KeyEvent.KEYCODE_RIGHT_BRACKET))
        assertEquals(ShortcutAction.SIZE_UP, size?.let(KeyboardShortcuts::actionFor))
    }

    @Test
    fun theSystemShortcutListCoversEveryBinding() {
        val listed = AndroidKeys.helperGroups().flatMap { it.items }
        assertNotNull(listed.firstOrNull { it.label == "Undo" && it.keycode == KeyEvent.KEYCODE_Z })
        assertTrue(listed.size >= KeyboardShortcuts.bindings.size)
        val ctrlShiftZ = listed.first { it.label == "Redo" && it.keycode == KeyEvent.KEYCODE_Z }
        assertEquals(KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON, ctrlShiftZ.modifiers)
    }
}
