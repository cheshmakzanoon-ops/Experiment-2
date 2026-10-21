package com.artflow.studio.core.animation

import com.artflow.studio.domain.model.animation.AnimationSettings

/**
 * One frame policy for the editor, onion skins and encoded output. All indices refer to the source
 * timeline; holds belong to those source frames, including on the reverse leg of a ping-pong clip.
 * Pinned and hidden frames never consume a playback interval.
 */
object AnimationPlayback {
    enum class Role(
        val label: String,
    ) {
        ANIMATED("Animated"),
        BACKGROUND("Background"),
        FOREGROUND("Foreground"),
    }

    fun role(
        frameIds: List<Long>,
        settings: AnimationSettings,
        index: Int,
    ): Role {
        val id = frameIds.getOrNull(index) ?: return Role.ANIMATED
        return when {
            id in settings.foregroundFrameIds -> Role.FOREGROUND
            id in settings.backgroundFrameIds -> Role.BACKGROUND
            frameIds.size > 1 && settings.backgroundFrame && index == 0 -> Role.BACKGROUND
            frameIds.size > 1 && settings.foregroundFrame && index == frameIds.lastIndex -> Role.FOREGROUND
            else -> Role.ANIMATED
        }
    }

    /** Replace legacy first/last flags with stable IDs before an insertion or reorder changes them. */
    fun materializePins(
        frameIds: List<Long>,
        settings: AnimationSettings,
    ): AnimationSettings =
        settings.copy(
            backgroundFrame = false,
            foregroundFrame = false,
            backgroundFrameIds = frameIds.filterIndexed { index, _ -> role(frameIds, settings, index) == Role.BACKGROUND }.toSet(),
            foregroundFrameIds = frameIds.filterIndexed { index, _ -> role(frameIds, settings, index) == Role.FOREGROUND }.toSet(),
            hiddenFrameIds = settings.hiddenFrameIds.intersect(frameIds.toSet()),
        )

    fun withRole(
        frameIds: List<Long>,
        settings: AnimationSettings,
        index: Int,
        role: Role,
    ): AnimationSettings {
        val id = frameIds.getOrNull(index) ?: return settings
        val pinned = materializePins(frameIds, settings)
        return pinned.copy(
            backgroundFrameIds = (pinned.backgroundFrameIds - id) + if (role == Role.BACKGROUND) setOf(id) else emptySet(),
            foregroundFrameIds = (pinned.foregroundFrameIds - id) + if (role == Role.FOREGROUND) setOf(id) else emptySet(),
        )
    }

    /** Source indices that run forward through the selected range. */
    fun forwardIndices(
        frameIds: List<Long>,
        settings: AnimationSettings,
    ): List<Int> {
        if (frameIds.isEmpty()) return emptyList()
        val start = settings.playbackRangeStart.coerceIn(0, frameIds.lastIndex)
        val end =
            if (settings.playbackRangeEnd < 0) {
                frameIds.lastIndex
            } else {
                settings.playbackRangeEnd.coerceIn(start, frameIds.lastIndex)
            }
        return (start..end).filter { index ->
            frameIds[index] !in settings.hiddenFrameIds && role(frameIds, settings, index) == Role.ANIMATED
        }
    }

    fun forwardIndices(state: AnimationTimeline.State): List<Int> = forwardIndices(state.frames.map { it.id }, state.settings)

    /** One encoded cycle; a finite bounce includes the final first-frame hold before stopping. */
    fun cycleIndices(
        frameIds: List<Long>,
        settings: AnimationSettings,
    ): List<Int> {
        val forward = forwardIndices(frameIds, settings)
        if (!settings.pingPong || forward.size < 2) return forward
        val returning = forward.dropLast(1).asReversed()
        // A looping file's next cycle supplies the first frame. A finite file must hold it here.
        return forward + if (settings.loop) returning.dropLast(1) else returning
    }

    fun cycleIndices(state: AnimationTimeline.State): List<Int> = cycleIndices(state.frames.map { it.id }, state.settings)

    fun durationMs(state: AnimationTimeline.State): Long = cycleIndices(state).sumOf { state.frames[it].durationMs.toLong() }

    /**
     * Bottom-to-top composition order. The selected frame remains editable when hidden; hidden
     * pins are otherwise absent. Multiple pins keep their timeline stacking order.
     */
    fun compositeIndices(
        frameIds: List<Long>,
        settings: AnimationSettings,
        activeIndex: Int,
    ): List<Int> {
        if (activeIndex !in frameIds.indices) return emptyList()

        fun pinned(desiredRole: Role): List<Int> =
            frameIds.indices.filter { index ->
                role(frameIds, settings, index) == desiredRole &&
                    (index == activeIndex || frameIds[index] !in settings.hiddenFrameIds)
            }
        val primary = if (role(frameIds, settings, activeIndex) == Role.ANIMATED) listOf(activeIndex) else emptyList()
        return pinned(Role.BACKGROUND) + primary + pinned(Role.FOREGROUND)
    }

    /** Visible moving frames around the current drawing, independent of the playback crop. */
    fun ghostIndices(state: AnimationTimeline.State): Pair<List<Int>, List<Int>> {
        val ids = state.frames.map { it.id }
        if (state.activeIndex !in ids.indices || role(ids, state.settings, state.activeIndex) != Role.ANIMATED) {
            return emptyList<Int>() to emptyList()
        }
        val count = state.settings.onionSkinFrames.coerceIn(0, AnimationSettings.MAX_ONION_SKIN_FRAMES)
        val candidates = ids.indices.filter { ids[it] !in state.settings.hiddenFrameIds && role(ids, state.settings, it) == Role.ANIMATED }
        return candidates.filter { it < state.activeIndex }.asReversed().take(count) to
            candidates.filter { it > state.activeIndex }.take(count)
    }
}
