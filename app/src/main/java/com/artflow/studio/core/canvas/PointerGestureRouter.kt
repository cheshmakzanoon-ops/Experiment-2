package com.artflow.studio.core.canvas

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/** Pointer ownership is independent of Android's per-event pointer-array ordering. */
class PointerGestureRouter(
    private val tapSlop: Float,
    private val tapTimeoutMillis: Long,
) {
    enum class Event { DOWN, POINTER_DOWN, MOVE, POINTER_UP, UP, CANCEL }

    enum class Action {
        START_TOOL,
        MOVE_TOOL,
        END_TOOL,
        CANCEL,
        REBASE_NAVIGATION,
        NAVIGATE,
        FINISH_NAVIGATION,
        IGNORE,

        /** Three fingers dragged down together: Procreate's Copy & Paste menu. */
        THREE_FINGER_SWIPE_DOWN,

        /** Three fingers rubbed side to side: clear the layer. */
        THREE_FINGER_SCRUB,
    }

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

    private enum class Mode { IDLE, TOOL, NAVIGATION, SUPPRESSED, REJECTED }

    private var mode = Mode.IDLE
    private var owner = -1
    private var pen = false
    private var startedAt = 0L

    /** When the pen last lifted; a finger that lands soon after is most likely the resting palm. */
    private var penLiftedAt: Long? = null
    private var maxPointers = 0
    private var tapEligible = false
    private val lastPositions = mutableMapOf<Int, Pointer>()
    private val travel = mutableMapOf<Int, Float>()
    private val firstPositions = mutableMapOf<Int, Pointer>()
    private var multiGestureFired = false
    private var scrubDirection = 0
    private var scrubAnchorX = 0f
    private var scrubReversals = 0

    /** Discard the current stream; a trailing finger must not start drawing or undo artwork. */
    fun suppress() {
        mode = Mode.SUPPRESSED
        owner = -1
        tapEligible = false
        lastPositions.clear()
        travel.clear()
        firstPositions.clear()
        multiGestureFired = false
        scrubDirection = 0
        scrubReversals = 0
    }

    /** Two or three fingers resting still since the touch began, else 0: Procreate's rapid undo and redo. */
    fun heldFingers(): Int = if (mode == Mode.NAVIGATION && tapEligible && maxPointers in 2..3) maxPointers else 0

    /** A hold that already undid or redid must not also count as a tap when the fingers lift. */
    fun consumeTap() {
        tapEligible = false
    }

    /** Include batched movement, including an excursion which returns to its initial position. */
    fun observe(pointers: List<Pointer>) {
        for (pointer in pointers) {
            val previous = lastPositions.put(pointer.id, pointer)
            firstPositions.putIfAbsent(pointer.id, pointer)
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
        if (pointers[changedIndex].stylus && (event == Event.UP || event == Event.POINTER_UP)) penLiftedAt = timeMillis
        if (event == Event.DOWN) {
            val cancelPrevious = mode == Mode.TOOL
            val palm = !pointers[changedIndex].stylus && recentlyLifted(timeMillis)
            suppress()
            startedAt = timeMillis
            maxPointers = 0
            tapEligible = true
            observe(pointers)
            if (palm) {
                mode = Mode.REJECTED
                return Route(if (cancelPrevious) Action.CANCEL else Action.IGNORE)
            }
            return start(pointers, changedIndex, cancelPrevious)
        }
        observe(pointers)
        if (cancelled) tapEligible = false
        return when (mode) {
            Mode.TOOL -> routeTool(event, pointers, changedIndex, cancelled)
            Mode.NAVIGATION -> routeNavigation(event, pointers, changedIndex, timeMillis, cancelled)
            Mode.REJECTED -> routeRejected(event, pointers, changedIndex)
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

    private fun recentlyLifted(timeMillis: Long): Boolean {
        val lifted = penLiftedAt ?: return false
        val elapsed = timeMillis - lifted
        return elapsed in 0 until PALM_WINDOW_MILLIS
    }

    /**
     * A finger that landed just after the pen lifted is ignored, so a resting palm draws nothing and starts no
     * colour hold. A second finger still makes it a gesture (undo, redo, navigation), and the pen can take over.
     */
    private fun routeRejected(
        event: Event,
        pointers: List<Pointer>,
        changedIndex: Int,
    ): Route =
        when {
            event == Event.POINTER_DOWN && pointers[changedIndex].stylus -> start(pointers, changedIndex, false)
            event == Event.POINTER_DOWN -> {
                mode = Mode.NAVIGATION
                owner = -1
                Route(Action.REBASE_NAVIGATION)
            }
            event == Event.UP -> {
                mode = Mode.IDLE
                Route(Action.IGNORE)
            }
            else -> Route(Action.IGNORE)
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
                val count = if (tapEligible && elapsed in 0 until tapTimeoutMillis && maxPointers in 2..4) maxPointers else 0
                Route(Action.FINISH_NAVIGATION, historyPointers = count)
            }
            event == Event.POINTER_UP || event == Event.POINTER_DOWN -> Route(Action.REBASE_NAVIGATION)
            event == Event.MOVE && pointers.size >= 3 -> routeThreeFinger(pointers)
            event == Event.MOVE -> Route(Action.NAVIGATE)
            else -> Route(Action.IGNORE)
        }

    /** Three-finger moves never pan the canvas; they can only trigger one swipe or scrub per touch. */
    private fun routeThreeFinger(pointers: List<Pointer>): Route {
        if (multiGestureFired || pointers.size < 3) return Route(Action.IGNORE)
        var dx = 0f
        var dy = 0f
        var meanX = 0f
        pointers.forEach { pointer ->
            val first = firstPositions[pointer.id] ?: pointer
            dx += pointer.x - first.x
            dy += pointer.y - first.y
            meanX += pointer.x
        }
        dx /= pointers.size
        dy /= pointers.size
        meanX /= pointers.size
        if (dy > swipeDistance && abs(dx) < dy * 0.6f) {
            multiGestureFired = true
            return Route(Action.THREE_FINGER_SWIPE_DOWN)
        }
        val step = meanX - scrubAnchorX
        val direction = if (step > 0f) 1 else -1
        when {
            scrubDirection == 0 -> {
                scrubDirection = direction
                scrubAnchorX = meanX
            }
            direction == scrubDirection -> scrubAnchorX = meanX
            abs(step) > scrubDistance -> {
                scrubDirection = direction
                scrubAnchorX = meanX
                scrubReversals++
            }
        }
        if (scrubReversals >= SCRUB_REVERSALS) {
            multiGestureFired = true
            return Route(Action.THREE_FINGER_SCRUB)
        }
        return Route(Action.IGNORE)
    }

    private val swipeDistance get() = tapSlop * 4f
    private val scrubDistance get() = tapSlop * 2f

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

    private companion object {
        const val SCRUB_REVERSALS = 3

        /** How long after a pen lift a finger is treated as a palm; longer than a typical pen-to-hand move. */
        const val PALM_WINDOW_MILLIS = 1_500L
    }
}
