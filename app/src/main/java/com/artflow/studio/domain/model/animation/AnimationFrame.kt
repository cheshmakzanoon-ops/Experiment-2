package com.artflow.studio.domain.model.animation

import com.artflow.studio.domain.model.layer.Layer
import kotlinx.serialization.Serializable

/**
 * One frame of an animation (Phase 43).
 *
 * A frame owns a complete layer stack, which is what makes flipbook animation work in a painting
 * app: every frame can have its own background, line art and colour layers. Layers that should
 * persist across frames are copied at frame-creation time by [com.artflow.studio.core.animation.AnimationTimeline].
 */
@Serializable
data class AnimationFrame(
    val id: Long,
    val name: String,
    val layers: List<Layer>,
    /** How long this frame is held, in milliseconds. */
    val durationMs: Int = DEFAULT_DURATION_MS,
    /** Marks a frame as a key pose in the timeline UI. */
    val isKeyframe: Boolean = false,
    val createdAt: Long = 0L
) {
    val layerCount: Int get() = layers.size

    /** Total pixel/vector content of the frame, used for the "empty frame" indicator. */
    fun isEmpty(): Boolean = layers.all { it.strokes.isEmpty() && it.rasterFile == null }

    companion object {
        const val DEFAULT_DURATION_MS = 100

        /** Duration bounds the timeline UI enforces (1 fps .. 60 fps at the extremes). */
        const val MIN_DURATION_MS = 16
        const val MAX_DURATION_MS = 10_000
    }
}

/** Playback and onion-skinning configuration (Phases 43-44). */
@Serializable
data class AnimationSettings(
    val fps: Int = 12,
    val loop: Boolean = true,
    val onionSkinEnabled: Boolean = false,
    /** How many frames before/after the current one are shown as ghosts. */
    val onionSkinFrames: Int = 1,
    val onionSkinOpacity: Float = 0.3f,
    /** Tint used for previous frames (default red). */
    val onionSkinPreviousColor: Int = 0xFFFF0000.toInt(),
    /** Tint used for following frames (default green). */
    val onionSkinNextColor: Int = 0xFF00FF00.toInt(),
    /** Inclusive playback range; `playbackRangeEnd < 0` means "to the last frame". */
    val playbackRangeStart: Int = 0,
    val playbackRangeEnd: Int = -1,
    /** Ping-pong playback instead of looping from the start. */
    val pingPong: Boolean = false
) {
    /** Milliseconds a single frame is displayed, derived from [fps]. */
    val frameDurationMs: Int get() = (1000f / fps.coerceIn(1, 60)).toInt().coerceAtLeast(16)

    companion object {
        const val MIN_FPS = 1
        const val MAX_FPS = 60
        const val MAX_ONION_SKIN_FRAMES = 5
    }
}

/** Export formats for animation (Phase 44). */
enum class AnimationExportFormat(val displayName: String, val extension: String) {
    GIF("Animated GIF", "gif"),
    MP4("MP4 video", "mp4"),
    FRAME_SEQUENCE("PNG frame sequence", "zip"),
    APNG("Animated PNG (frame sequence)", "zip")
}

/** A timelapse recording entry: one committed action at a point in time (Phase 45). */
@Serializable
data class TimelapseEntry(
    /** Milliseconds since the recording started. */
    val timestampMs: Long,
    /** Which frame the action happened on. */
    val frameIndex: Int,
    /** Human-readable label ("Brush stroke", "Fill", "New layer"). */
    val label: String,
    /** Number of pixels the action touched, used to skip no-op entries. */
    val affectedPixels: Int = 0
)

/** Timelapse recording state (Phase 45). */
@Serializable
data class TimelapseRecording(
    val isRecording: Boolean = false,
    val startedAt: Long = 0L,
    val entries: List<TimelapseEntry> = emptyList(),
    /** Speed multiplier applied on export (2 = twice as fast). */
    val speedMultiplier: Float = 4f,
    val resolutionScale: Float = 0.5f,
    val includeWatermark: Boolean = false,
    val watermarkText: String = "Made with ArtFlow"
) {
    val durationMs: Long get() = entries.lastOrNull()?.timestampMs ?: 0L
}
