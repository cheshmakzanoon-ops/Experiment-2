package com.artflow.studio.core.animation

/** Frame-boundary policy shared by preview playback and its deterministic tests. */
object PlaybackStepper {
    data class Cursor(
        val index: Int,
        val direction: Int = 1,
    )

    /** A stopped One shot clip restarts at its first frame when Play is pressed at the end. */
    fun start(state: AnimationTimeline.State): Cursor? {
        val indices = AnimationPlayback.forwardIndices(state)
        if (indices.size < 2) return null
        val index = state.activeIndex.takeIf { it in indices } ?: indices.first()
        val restart = !state.settings.loop && !state.settings.pingPong && index == indices.last()
        return Cursor(if (restart) indices.first() else index)
    }

    /**
     * Null ends playback after the current frame's hold. Ping-pong reverses direction; Loop repeats
     * the clip. Legacy non-looping ping-pong retains one bounce and the final first-frame hold.
     */
    fun next(
        state: AnimationTimeline.State,
        cursor: Cursor,
    ): Cursor? {
        val indices = AnimationPlayback.forwardIndices(state)
        if (indices.size < 2) return null
        val position = indices.indexOf(cursor.index)
        if (position < 0) return Cursor(indices.first())
        val settings = state.settings
        if (cursor.direction >= 0) {
            return when {
                position < indices.lastIndex -> Cursor(indices[position + 1])
                settings.pingPong -> Cursor(indices[indices.lastIndex - 1], -1)
                settings.loop -> Cursor(indices.first())
                else -> null
            }
        }
        return when {
            position > 0 -> Cursor(indices[position - 1], -1)
            !settings.loop -> null
            settings.pingPong -> Cursor(indices[1])
            else -> Cursor(indices.last(), -1)
        }
    }
}
