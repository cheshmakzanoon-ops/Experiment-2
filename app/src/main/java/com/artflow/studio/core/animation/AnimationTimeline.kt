package com.artflow.studio.core.animation

import com.artflow.studio.domain.model.animation.AnimationFrame
import com.artflow.studio.domain.model.animation.AnimationSettings
import com.artflow.studio.domain.model.layer.Layer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Timeline operations for frame-by-frame animation (Phase 43).
 *
 * All of it is pure list maths over [AnimationFrame]s plus playback arithmetic, which means the
 * timeline behaviour (durations, looping, ping-pong, onion-skin ranges) is verified by unit tests
 * rather than by hand on a device.
 */
object AnimationTimeline {
    /** A timeline plus the settings that describe how it plays. */
    data class State(
        val frames: List<AnimationFrame> = emptyList(),
        val activeIndex: Int = 0,
        val settings: AnimationSettings = AnimationSettings(),
        val isPlaying: Boolean = false,
    ) {
        val activeFrame: AnimationFrame? get() = frames.getOrNull(activeIndex)
        val frameCount: Int get() = frames.size
        val canGoPrevious: Boolean get() = activeIndex > 0
        val canGoNext: Boolean get() = activeIndex < frames.lastIndex

        /** Total clip length in milliseconds. */
        val durationMs: Long get() = frames.sumOf { it.durationMs.toLong() }
    }

    /** Creates the first frame from a starting layer stack. */
    fun initialState(
        layers: List<Layer>,
        settings: AnimationSettings = AnimationSettings(),
    ): State {
        val frame =
            AnimationFrame(
                id = 1L,
                name = "Frame 1",
                layers = layers,
                durationMs = settings.frameDurationMs,
                createdAt = System.currentTimeMillis(),
            )
        return State(frames = listOf(frame), activeIndex = 0, settings = settings)
    }

    /** Appends a blank frame after the active one, copying nothing. */
    fun addBlankFrame(
        state: State,
        backgroundLayerName: String = "Background",
    ): State {
        val nextId = (state.frames.maxOfOrNull { it.id } ?: 0L) + 1
        val blank =
            AnimationFrame(
                id = nextId,
                name = "Frame ${state.frames.size + 1}",
                layers =
                    listOf(
                        Layer(
                            id = 1L,
                            name = backgroundLayerName,
                            index = 0,
                        ),
                    ),
                durationMs = state.settings.frameDurationMs,
                createdAt = System.currentTimeMillis(),
            )
        return insert(state, blank, state.activeIndex + 1)
    }

    /**
     * Duplicates the active frame. Layer and stroke ids are remapped so the two frames do not
     * share identity (which would break per-frame undo and layer selection).
     */
    fun duplicateFrame(state: State): State {
        val source = state.activeFrame ?: return state
        val nextFrameId = (state.frames.maxOfOrNull { it.id } ?: 0L) + 1
        val nextLayerId = (source.layers.maxOfOrNull { it.id } ?: 0L) + 1
        val nextStrokeId =
            source.layers
                .flatMap { it.strokes }
                .maxOfOrNull { it.id }
                ?.plus(1) ?: 1L

        var layerIdCursor = nextLayerId
        var strokeIdCursor = nextStrokeId
        val remapped =
            source.layers.map { layer ->
                val newLayerId = layerIdCursor++
                layer.copy(
                    id = newLayerId,
                    strokes =
                        layer.strokes.map { stroke ->
                            stroke.copy(id = strokeIdCursor++, layerId = newLayerId)
                        },
                )
            }

        val duplicate =
            source.copy(
                id = nextFrameId,
                name = "${source.name} copy",
                layers = remapped,
                createdAt = System.currentTimeMillis(),
            )
        return insert(state, duplicate, state.activeIndex + 1)
    }

    /** Inserts [frame] at [index], keeping the active index on the newly inserted frame. */
    fun insert(
        state: State,
        frame: AnimationFrame,
        index: Int,
    ): State {
        val frames = state.frames.toMutableList()
        val target = index.coerceIn(0, frames.size)
        frames.add(target, frame)
        return state.copy(frames = frames, activeIndex = target)
    }

    /** Deletes the frame at [index]. The last remaining frame can never be deleted. */
    fun deleteFrame(
        state: State,
        index: Int,
    ): State {
        if (state.frames.size <= 1) return state
        if (index !in state.frames.indices) return state
        val frames = state.frames.toMutableList().apply { removeAt(index) }
        val active =
            when {
                index < state.activeIndex -> state.activeIndex - 1
                index == state.activeIndex -> index.coerceAtMost(frames.lastIndex)
                else -> state.activeIndex
            }
        return state.copy(frames = frames, activeIndex = active)
    }

    /** Moves a frame from [from] to [to] and keeps the moved frame active. */
    fun moveFrame(
        state: State,
        from: Int,
        to: Int,
    ): State {
        if (from !in state.frames.indices) return state
        val target = to.coerceIn(0, state.frames.lastIndex)
        if (from == target) return state
        val frames = state.frames.toMutableList()
        val frame = frames.removeAt(from)
        frames.add(target, frame)
        return state.copy(frames = frames, activeIndex = target)
    }

    /** Adds a layer to every frame (used by "add a persistent background"). */
    fun addLayerToAllFrames(
        state: State,
        layerFactory: (frameIndex: Int) -> Layer,
    ): State {
        val frames =
            state.frames.mapIndexed { index, frame ->
                frame.copy(layers = frame.layers + layerFactory(index))
            }
        return state.copy(frames = frames)
    }

    /** Replaces the layer stack of the active frame. */
    fun updateActiveFrameLayers(
        state: State,
        layers: List<Layer>,
    ): State {
        val frame = state.activeFrame ?: return state
        val frames = state.frames.toMutableList()
        frames[state.activeIndex] = frame.copy(layers = layers)
        return state.copy(frames = frames)
    }

    /** Sets the hold duration of a frame. */
    fun setFrameDuration(
        state: State,
        index: Int,
        durationMs: Int,
    ): State {
        if (index !in state.frames.indices) return state
        val frames = state.frames.toMutableList()
        frames[index] =
            frames[index].copy(
                durationMs = durationMs.coerceIn(AnimationFrame.MIN_DURATION_MS, AnimationFrame.MAX_DURATION_MS),
            )
        return state.copy(frames = frames)
    }

    /** Applies the FPS to every frame that still uses the default duration. */
    fun applyFps(
        state: State,
        fps: Int,
    ): State {
        val clamped = fps.coerceIn(AnimationSettings.MIN_FPS, AnimationSettings.MAX_FPS)
        val settings = state.settings.copy(fps = clamped)
        val duration = settings.frameDurationMs
        val frames =
            state.frames.map { frame ->
                if (frame.durationMs == AnimationFrame.DEFAULT_DURATION_MS) frame.copy(durationMs = duration) else frame
            }
        return state.copy(frames = frames, settings = settings)
    }

    /** Selects a frame by index (clamped). */
    fun select(
        state: State,
        index: Int,
    ): State = state.copy(activeIndex = index.coerceIn(0, max(0, state.frames.lastIndex)))

    /** Moves the selection by [delta] frames, wrapping when the animation loops. */
    fun step(
        state: State,
        delta: Int,
        wrap: Boolean = true,
    ): State {
        if (state.frames.isEmpty()) return state
        var index = state.activeIndex + delta
        if (wrap) {
            val count = state.frames.size
            index = ((index % count) + count) % count
        } else {
            index = index.coerceIn(0, state.frames.lastIndex)
        }
        return state.copy(activeIndex = index)
    }

    /**
     * The next frame index during playback, honouring the playback range, looping and ping-pong.
     *
     * @param direction +1 for forward, -1 for backwards during a ping-pong return leg.
     */
    fun nextPlaybackIndex(
        state: State,
        direction: Int,
    ): Pair<Int, Int> {
        val range = playbackRange(state)
        if (range.isEmpty()) return state.activeIndex to direction
        var index = state.activeIndex
        var nextDirection = direction

        when {
            direction >= 0 && index >= range.last -> {
                when {
                    state.settings.pingPong -> {
                        index = (range.last - 1).coerceAtLeast(range.first)
                        nextDirection = -1
                    }
                    state.settings.loop -> index = range.first
                    else -> return index to direction
                }
            }
            direction < 0 && index <= range.first -> {
                when {
                    state.settings.pingPong -> {
                        index = (range.first + 1).coerceAtMost(range.last)
                        nextDirection = 1
                    }
                    state.settings.loop -> index = range.last
                    else -> return index to direction
                }
            }
            else -> index += direction
        }
        return index.coerceIn(range.first, range.last) to nextDirection
    }

    /** Inclusive playback range, defaulting to the whole timeline. */
    fun playbackRange(state: State): IntRange {
        if (state.frames.isEmpty()) return IntRange.EMPTY
        val start = state.settings.playbackRangeStart.coerceIn(0, state.frames.lastIndex)
        val end =
            if (state.settings.playbackRangeEnd < 0) {
                state.frames.lastIndex
            } else {
                state.settings.playbackRangeEnd.coerceIn(start, state.frames.lastIndex)
            }
        return start..end
    }

    /** Frame index displayed at [elapsedMs] of playback, respecting per-frame durations. */
    fun frameIndexAtElapsed(
        state: State,
        elapsedMs: Long,
    ): Int {
        val range = playbackRange(state)
        if (range.isEmpty()) return 0
        val total = (range.first..range.last).sumOf { state.frames[it].durationMs.toLong() }
        if (total <= 0) return range.first
        var position = if (state.settings.loop) elapsedMs.mod(total) else elapsedMs.coerceAtMost(total - 1)
        for (index in range) {
            val duration = state.frames[index].durationMs.toLong()
            if (position < duration) return index
            position -= duration
        }
        return range.last
    }

    /**
     * Onion-skin frames: `previous` are the frames before the active one, `next` the ones after.
     * `alpha` is already scaled by the configured opacity, so the renderer can use it directly.
     */
    data class OnionSkin(
        val previous: List<Pair<Int, Float>>,
        val next: List<Pair<Int, Float>>,
    )

    fun onionSkin(state: State): OnionSkin {
        if (!state.settings.onionSkinEnabled || state.frames.isEmpty()) return OnionSkin(emptyList(), emptyList())
        val count = state.settings.onionSkinFrames.coerceIn(0, AnimationSettings.MAX_ONION_SKIN_FRAMES)
        if (count == 0) return OnionSkin(emptyList(), emptyList())

        val baseOpacity = state.settings.onionSkinOpacity.coerceIn(0.05f, 1f)
        val previous = mutableListOf<Pair<Int, Float>>()
        val next = mutableListOf<Pair<Int, Float>>()

        for (offset in 1..count) {
            val before = state.activeIndex - offset
            if (before >= 0) {
                // Older frames fade out faster.
                previous += before to (baseOpacity / offset)
            }
            val after = state.activeIndex + offset
            if (after <= state.frames.lastIndex) {
                next += after to (baseOpacity / offset)
            }
        }
        return OnionSkin(previous, next)
    }

    /** Frame names for the timeline strip; ensures they stay unique after edits. */
    fun normalizeNames(state: State): State {
        val frames =
            state.frames.mapIndexed { index, frame ->
                val expected = "Frame ${index + 1}"
                // Only rename untouched default names so user names survive.
                if (frame.name.startsWith("Frame ") && frame.name != expected) {
                    frame.copy(name = expected)
                } else {
                    frame
                }
            }
        return state.copy(frames = frames)
    }

    /** Total number of stroke objects across all frames (used by the memory readout). */
    fun totalStrokeCount(state: State): Int = state.frames.sumOf { frame -> frame.layers.sumOf { it.strokes.size } }

    /** Number of frames that still have no content. */
    fun emptyFrameIndices(state: State): List<Int> = state.frames.mapIndexedNotNull { index, frame -> index.takeIf { frame.isEmpty() } }

    /** Estimated memory for all frames, in megabytes. */
    fun estimatedMemoryMb(
        state: State,
        width: Int,
        height: Int,
    ): Float {
        val bytesPerLayer = width.toLong() * height * 4
        val layerCount = state.frames.sumOf { it.layerCount }
        return (bytesPerLayer * layerCount) / (1024f * 1024f)
    }

    /** Timeline summary for the info pill. */
    fun describe(state: State): String {
        val seconds = state.durationMs / 1000f
        return "${state.frameCount} frames · ${state.settings.fps} fps · %.1fs".format(seconds)
    }

    /** Playback position in `0..1` for a scrubber. */
    fun progress(
        state: State,
        elapsedMs: Long,
    ): Float {
        val total = state.durationMs
        if (total <= 0) return 0f
        return (elapsedMs.toFloat() / total).coerceIn(0f, 1f)
    }

    /** Frame index closest to a scrubber position in `0..1`. */
    fun frameIndexAtProgress(
        state: State,
        progress: Float,
    ): Int {
        if (state.frames.isEmpty()) return 0
        val clamped = progress.coerceIn(0f, 1f)
        val targetMs = (state.durationMs * clamped)
        var accumulated = 0L
        state.frames.forEachIndexed { index, frame ->
            accumulated += frame.durationMs
            if (accumulated >= targetMs) return index
        }
        return state.frames.lastIndex
    }

    /** Exports the durations of every frame, for the GIF encoder. */
    fun frameDurations(
        state: State,
        overrideFps: Int? = null,
    ): List<Int> {
        val fallback = overrideFps?.let { (1000f / it.coerceIn(1, 60)).roundToInt().coerceAtLeast(16) }
        return state.frames.map { fallback ?: it.durationMs }
    }

    /** Weighted average FPS implied by the frame durations. */
    fun effectiveFps(state: State): Float {
        if (state.frames.isEmpty()) return 0f
        val average = state.frames.map { it.durationMs }.average()
        if (average <= 0.0) return 0f
        return (1000.0 / average).toFloat()
    }

    /** True when every frame has the same duration (so a constant FPS export is lossless). */
    fun hasUniformDurations(state: State): Boolean {
        val durations = state.frames.map { it.durationMs }.distinct()
        return durations.size <= 1
    }

    /** Longest common frame duration, used to pick a sensible FPS for exports. */
    fun suggestedFps(state: State): Int {
        val durations = state.frames.map { it.durationMs }.filter { it > 0 }
        if (durations.isEmpty()) return state.settings.fps
        val median = durations.sorted()[durations.size / 2]
        return (1000f / median).roundToInt().coerceIn(AnimationSettings.MIN_FPS, AnimationSettings.MAX_FPS)
    }

    /** Clamps a scrub position to the nearest frame boundary. */
    fun snapToFrameBoundary(
        state: State,
        progress: Float,
    ): Float {
        val index = frameIndexAtProgress(state, progress)
        val before = state.frames.take(index).sumOf { it.durationMs.toLong() }
        val total = state.durationMs
        if (total <= 0) return 0f
        return (before.toFloat() / total).coerceIn(0f, 1f)
    }

    /** Frame count range used by the timeline UI's quick actions. */
    val FRAME_LIMITS = 1..600

    /** Difference between two frame durations, for the "hold" readout. */
    fun durationDelta(
        a: AnimationFrame,
        b: AnimationFrame,
    ): Int = abs(a.durationMs - b.durationMs)

    /** Ensures a frame index is valid for [state] (used when the frame count shrinks). */
    fun clampIndex(
        state: State,
        index: Int,
    ): Int = index.coerceIn(0, max(0, state.frames.lastIndex))

    /** Smallest sensible duration for a frame given the loop time budget. */
    fun minDurationForLoop(
        totalMs: Long,
        frames: Int,
    ): Int {
        if (frames <= 0) return AnimationFrame.MIN_DURATION_MS
        return max(AnimationFrame.MIN_DURATION_MS, min(AnimationFrame.MAX_DURATION_MS, (totalMs / frames).toInt()))
    }
}
