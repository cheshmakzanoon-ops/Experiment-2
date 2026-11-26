package com.artflow.studio.presentation.ui

import com.artflow.studio.core.canvas.KeyChord
import com.artflow.studio.core.canvas.ShortcutAction

/** One activity's editor registration; a second window cannot replace its commands or overrides. */
internal class ShortcutHost {
    private class Registration(
        val handler: (ShortcutAction) -> Boolean,
    )

    private var registration: Registration? = null
    val handler: ((ShortcutAction) -> Boolean)? get() = registration?.handler
    var overrides: Map<ShortcutAction, KeyChord> = emptyMap()

    /** Main-thread registration. Disposing an older screen must not detach its replacement. */
    fun register(handler: (ShortcutAction) -> Boolean): () -> Unit {
        val owned = Registration(handler)
        registration = owned
        return { if (registration === owned) registration = null }
    }
}
