package com.artflow.studio.core.canvas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardShortcutsTest {
    @Test
    fun noChordIsBoundTwice() {
        val chords = KeyboardShortcuts.bindings.map { it.chord }
        assertEquals(chords.toSet().size, chords.size)
    }

    @Test
    fun everyActionHasABinding() {
        val unbound = ShortcutAction.entries.filter { KeyboardShortcuts.chordsFor(it).isEmpty() }
        assertTrue("unbound actions: $unbound", unbound.isEmpty())
    }

    @Test
    fun keyTokensAreLowerCaseSingleKeysOrNames() {
        val names = setOf("tab", "enter", "escape", "backspace", "delete", "left", "right", "up", "down")
        KeyboardShortcuts.bindings.forEach {
            val key = it.chord.key
            assertTrue("bad token '$key'", key in names || (key.length == 1 && key == key.lowercase()))
        }
    }

    @Test
    fun commonEditingShortcutsResolve() {
        fun action(
            key: String,
            primary: Boolean = false,
            shift: Boolean = false,
            alt: Boolean = false,
        ) = KeyboardShortcuts.actionFor(KeyChord(key, primary, shift, alt))
        assertEquals(ShortcutAction.UNDO, action("z", primary = true))
        assertEquals(ShortcutAction.REDO, action("z", primary = true, shift = true))
        assertEquals(ShortcutAction.REDO, action("y", primary = true))
        assertEquals(ShortcutAction.TOOL_ERASER, action("e"))
        assertEquals(ShortcutAction.MERGE_DOWN, action("e", primary = true))
        assertEquals(ShortcutAction.EXPORT, action("e", primary = true, shift = true))
        assertEquals(ShortcutAction.SIZE_UP, action("]"))
        assertEquals(ShortcutAction.OPACITY_UP, action("]", alt = true))
        assertEquals(ShortcutAction.ZOOM_IN, action("=", primary = true, shift = true))
        assertEquals(ShortcutAction.SAVE, action("s", primary = true))
        assertEquals(ShortcutAction.TOOL_SMUDGE, action("s"))
    }

    @Test
    fun unboundChordsAndExtraModifiersDoNotResolve() {
        assertNull(KeyboardShortcuts.actionFor(KeyChord("q")))
        assertNull(KeyboardShortcuts.actionFor(KeyChord("z", primary = true, alt = true)))
        assertNull(KeyboardShortcuts.actionFor(KeyChord("b", primary = true)))
    }

    @Test
    fun focusNavigationKeysAreNeverStolen() {
        val plain = setOf("tab", "left", "right", "up", "down", "enter", "space")
        KeyboardShortcuts.bindings.filter { it.chord.key in plain }.forEach {
            assertTrue("${it.chord} needs a modifier", it.chord.primary || it.chord.alt)
        }
    }

    @Test
    fun onlyAdjustmentsAndNavigationRepeatWhileHeld() {
        val repeating = ShortcutAction.entries.filter { it.repeats }.toSet()
        assertTrue(ShortcutAction.SIZE_UP in repeating && ShortcutAction.PAN_LEFT in repeating && ShortcutAction.UNDO in repeating)
        assertTrue(ShortcutAction.SAVE !in repeating && ShortcutAction.TOGGLE_INTERFACE !in repeating && ShortcutAction.PASTE !in repeating)
    }
}
