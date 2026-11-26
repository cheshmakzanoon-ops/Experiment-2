package com.artflow.studio.presentation.ui

import com.artflow.studio.core.canvas.KeyChord
import com.artflow.studio.core.canvas.KeyboardShortcuts
import com.artflow.studio.core.canvas.ShortcutAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ShortcutHostTest {
    @Test
    fun registrationsAndOverridesStayWithTheirOwnWindow() {
        val first = ShortcutHost()
        val second = ShortcutHost()
        val firstActions = mutableListOf<ShortcutAction>()
        val secondActions = mutableListOf<ShortcutAction>()
        first.register { firstActions.add(it) }
        second.register { secondActions.add(it) }
        val chord = KeyChord("k", primary = true)
        first.overrides = mapOf(ShortcutAction.UNDO to chord)
        second.overrides = mapOf(ShortcutAction.REDO to chord)

        assertTrue(requireNotNull(first.handler).invoke(requireNotNull(KeyboardShortcuts.actionFor(chord, first.overrides))))
        assertEquals(listOf(ShortcutAction.UNDO), firstActions)
        assertTrue(secondActions.isEmpty())
        assertTrue(requireNotNull(second.handler).invoke(requireNotNull(KeyboardShortcuts.actionFor(chord, second.overrides))))
        assertEquals(listOf(ShortcutAction.REDO), secondActions)
        assertEquals(mapOf(ShortcutAction.UNDO to chord), first.overrides)
    }

    @Test
    fun disposingAnOlderRegistrationCannotDetachAReplacementEvenWhenItsHandlerIsReused() {
        val host = ShortcutHost()
        val handler: (ShortcutAction) -> Boolean = { true }
        val oldRelease = host.register(handler)
        val currentRelease = host.register(handler)
        oldRelease()
        assertSame(handler, host.handler)
        currentRelease()
        assertNull(host.handler)
    }

    @Test
    fun disposalIsIdempotentAndNeverDetachesAnotherWindow() {
        val first = ShortcutHost()
        val second = ShortcutHost()
        val handler: (ShortcutAction) -> Boolean = { true }
        first.register(handler)
        val release = second.register(handler)
        release()
        release()
        assertNull(second.handler)
        assertSame(handler, first.handler)
    }
}
