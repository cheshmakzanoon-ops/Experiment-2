package com.artflow.studio.core.canvas

import kotlin.math.hypot
import kotlin.math.max

/** Pointer ownership is independent of Android's per-event pointer-array ordering. */
class PointerGestureRouter(
    private val tapSlop: Float,
    private val tapTimeoutMillis: Long,
) {
    enum class Event { DOWN, POINTER_DOWN, MOVE, POINTER_UP, UP, CANCEL }

    enum class Action { START_TOOL, MOVE_TOOL, END_TOOL, CANCEL, REBASE_NAVIGATION, NAVIGATE, FINISH_NAVIGATION, IGNORE }

    data class Pointer(
        val id: Int,
        val x: Float,
        val y: Float,
        val stylus: Boolean = false,
    )

    data class Route(
        val action: Action,
        val index: Int = -1,
        val cancelTool: Boolean = false,
        val historyPointers: Int = 0,
    )

    private enum class Mode { IDLE, TOOL, NAVIGATION, SUPPRESSED }

    private var mode = Mode.IDLE
    private var owner = -1
    private var pen = false
    private var startedAt = 0L
    private var maxPointers = 0
    private var tapEligible = false
    private val lastPositions = mutableMapOf<Int, Pointer>()
    private val travel = mutableMapOf<Int, Float>()

    /** Discard the current stream; a trailing finger must not start drawing or undo artwork. */
    fun suppress() {
        mode = Mode.SUPPRESSED
        owner = -1
        tapEligible = false
        lastPositions.clear()
        travel.clear()
    }

    /** Include batched movement, including an excursion which returns to its initial position. */
    fun observe(pointers: List<Pointer>) {
        for (pointer in pointers) {
            val previous = lastPositions.put(pointer.id, pointer)
            if (!pointer.x.isFinite() || !pointer.y.isFinite() || pointer.stylus) tapEligible = false
            if (previous != null) {
                val distance = (travel[pointer.id] ?: 0f) + hypot(pointer.x - previous.x, pointer.y - previous.y)
                travel[pointer.id] = distance
                if (!distance.isFinite() || distance > tapSlop) tapEligible = false
            }
        }
        maxPointers = max(maxPointers, pointers.size)
    }

    fun route(
        event: Event,
        pointers: List<Pointer>,
        changedIndex: Int,
        timeMillis: Long,
        cancelled: Boolean = false,
    ): Route {
        if (event == Event.CANCEL || pointers.isEmpty() || changedIndex !in pointers.indices) {
            suppress()
            return Route(Action.CANCEL)
        }
        if (event == Event.DOWN) {
            val cancelPrevious = mode == Mode.TOOL
            suppress()
            startedAt = timeMillis
            maxPointers = 0
            tapEligible = true
            observe(pointers)
            return start(pointers, changedIndex, cancelPrevious)
        }
        observe(pointers)
        if (cancelled) tapEligible = false
        return when (mode) {
            Mode.TOOL -> routeTool(event, pointers, changedIndex, cancelled)
            Mode.NAVIGATION -> routeNavigation(event, pointers, changedIndex, timeMillis, cancelled)
            Mode.SUPPRESSED -> {
                when {
                    event == Event.POINTER_DOWN && pointers[changedIndex].stylus -> start(pointers, changedIndex, false)
                    event == Event.UP -> {
                        mode = Mode.IDLE
                        Route(Action.IGNORE)
                    }
                    else -> Route(Action.IGNORE)
                }
            }
            Mode.IDLE -> Route(Action.IGNORE)
        }
    }

    private fun routeTool(
        event: Event,
        pointers: List<Pointer>,
        changedIndex: Int,
        cancelled: Boolean,
    ): Route {
        val changed = pointers[changedIndex]
        val ownerIndex = pointers.indexOfFirst { it.id == owner }
        return when {
            ownerIndex < 0 -> {
                suppress()
                Route(Action.CANCEL)
            }
            (event == Event.UP || event == Event.POINTER_UP) && changed.id == owner -> {
                mode = if (event == Event.UP) Mode.IDLE else Mode.SUPPRESSED
                owner = -1
                Route(if (cancelled) Action.CANCEL else Action.END_TOOL, ownerIndex)
            }
            event == Event.POINTER_DOWN && changed.stylus && !pen -> start(pointers, changedIndex, true)
            event == Event.POINTER_DOWN && !pen -> {
                mode = Mode.NAVIGATION
                owner = -1
                Route(Action.REBASE_NAVIGATION, cancelTool = true)
            }
            event == Event.MOVE -> Route(Action.MOVE_TOOL, ownerIndex)
            else -> Route(Action.IGNORE)
        }
    }

    private fun routeNavigation(
        event: Event,
        pointers: List<Pointer>,
        changedIndex: Int,
        timeMillis: Long,
        cancelled: Boolean,
    ): Route =
        when {
            cancelled -> {
                suppress()
                Route(Action.CANCEL)
            }
            event == Event.POINTER_DOWN && pointers[changedIndex].stylus -> start(pointers, changedIndex, false)
            event == Event.UP -> {
                mode = Mode.IDLE
                val elapsed = timeMillis - startedAt
                val count = if (tapEligible && elapsed in 0 until tapTimeoutMillis && maxPointers in 2..3) maxPointers else 0
                Route(Action.FINISH_NAVIGATION, historyPointers = count)
            }
            event == Event.POINTER_UP || event == Event.POINTER_DOWN -> Route(Action.REBASE_NAVIGATION)
            event == Event.MOVE -> Route(Action.NAVIGATE)
            else -> Route(Action.IGNORE)
        }

    private fun start(
        pointers: List<Pointer>,
        index: Int,
        cancelPrevious: Boolean,
    ): Route {
        owner = pointers[index].id
        pen = pointers[index].stylus
        if (pen) tapEligible = false
        mode = Mode.TOOL
        return Route(Action.START_TOOL, index, cancelTool = cancelPrevious)
    }
}
