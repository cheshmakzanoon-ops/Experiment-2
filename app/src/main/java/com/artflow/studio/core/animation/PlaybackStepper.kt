package com.artflow.studio.core.animation

/** Frame-boundary policy shared by preview playback and its deterministic tests. */
object PlaybackStepper {
    data class Cursor(
        val index: Int,
        val direction: Int = 1,
    )

    /** A stopped, non-looping clip restarts at its first frame when Play is pressed at the end. */
    fun start(state: AnimationTimeline.State): Cursor? {
        val range = AnimationTimeline.playbackRange(state)
        if (range.isEmpty() || range.first == range.last) return null
        val index = state.activeIndex.takeIf { it in range } ?: range.first
        val restart = !state.settings.loop && !state.settings.pingPong && index == range.last
        return Cursor(if (restart) range.first else index)
    }

    /**
     * Null ends playback after the current frame's hold. Non-looping ping-pong makes one
     * forward/return pass; looping ping-pong never repeats an endpoint for a second hold.
     */
    fun next(
        state: AnimationTimeline.State,
        cursor: Cursor,
    ): Cursor? {
        val range = AnimationTimeline.playbackRange(state)
        if (range.isEmpty() || range.first == range.last) return null
        if (cursor.index !in range) return Cursor(range.first)
        if (cursor.direction >= 0) {
            return when {
                cursor.index < range.last -> Cursor(cursor.index + 1)
                state.settings.pingPong -> Cursor(range.last - 1, -1)
                state.settings.loop -> Cursor(range.first)
                else -> null
            }
        }
        return when {
            cursor.index > range.first -> Cursor(cursor.index - 1, -1)
            !state.settings.loop -> null
            state.settings.pingPong -> Cursor(range.first + 1)
            else -> Cursor(range.last, -1)
        }
    }
}
