package com.artflow.studio.core.animation

import com.artflow.studio.domain.model.animation.AnimationSettings

/**
 * How playback continues past the last frame, as one choice: Loop, Ping-pong or One shot.
 *
 * The choice is stored in the two switches that artworks have always saved, [AnimationSettings.loop]
 * and [AnimationSettings.pingPong], so no serialised field is added or renamed. [of] reads the mode
 * from them and [withPlayMode] writes it back, and the two always agree when written through here.
 */
enum class PlayMode(
    val displayName: String,
) {
    /** Forward, then back to the first frame, repeatedly. */
    LOOP("Loop"),

    /** Forward, then back and forth, repeatedly; the end frames are not held twice. */
    PING_PONG("Ping-pong"),

    /** Forward once; the clip stops on its last frame and restarts from the first when Play is pressed. */
    ONE_SHOT("One shot"),
    ;

    companion object {
        /**
         * The panel's mode label. A legacy non-looping bounce is labelled [ONE_SHOT] because it
         * stops; playback preserves its ping-pong direction until a mode is explicitly selected.
         */
        fun of(settings: AnimationSettings): PlayMode =
            when {
                !settings.loop -> ONE_SHOT
                settings.pingPong -> PING_PONG
                else -> LOOP
            }
    }
}

/** These settings with [mode] written into the stored loop and ping-pong switches. */
fun AnimationSettings.withPlayMode(mode: PlayMode): AnimationSettings =
    when (mode) {
        PlayMode.LOOP -> copy(loop = true, pingPong = false)
        PlayMode.PING_PONG -> copy(loop = true, pingPong = true)
        PlayMode.ONE_SHOT -> copy(loop = false, pingPong = false)
    }
