package com.artflow.studio.core.animation

/** Frame-boundary policy shared by preview playback and its deterministic tests. */
object PlaybackStepper {
    data class Cursor(
        val index: Int,
        val direction: Int = 1,
    )

    /** A stopped One shot clip restarts at its first frame when Play is pressed at the end. */
    fun start(state: AnimationTimeline.State): Cursor? {
        val range = AnimationTimeline.playbackRange(state)
        if (range.isEmpty() || range.first == range.last) return null
        val index = state.activeIndex.takeIf { it in range } ?: range.first
        val restart = PlayMode.of(state.settings) == PlayMode.ONE_SHOT && index == range.last
        return Cursor(if (restart) range.first else index)
    }

    /**
     * Null ends playback after the current frame's hold. One shot makes one forward pass; Ping-pong
     * bounces between the ends without repeating an endpoint; Loop returns to the first frame.
     */
    fun next(
        state: AnimationTimeline.State,
        cursor: Cursor,
    ): Cursor? {
        val range = AnimationTimeline.playbackRange(state)
        if (range.isEmpty() || range.first == range.last) return null
        if (cursor.index !in range) return Cursor(range.first)
        val mode = PlayMode.of(state.settings)
        if (cursor.direction >= 0) {
            return when {
                cursor.index < range.last -> Cursor(cursor.index + 1)
                mode == PlayMode.PING_PONG -> Cursor(range.last - 1, -1)
                mode == PlayMode.LOOP -> Cursor(range.first)
                else -> null
            }
        }
        return when {
            cursor.index > range.first -> Cursor(cursor.index - 1, -1)
            mode == PlayMode.ONE_SHOT -> null
            mode == PlayMode.PING_PONG -> Cursor(range.first + 1)
            else -> Cursor(range.last, -1)
        }
    }
}
