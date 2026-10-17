package com.artflow.studio.core.canvas

import kotlin.math.roundToInt

/**
 * Pinch-rotate snapping. While a pinch turns the canvas, it holds at 0, 90, 180 or 270 degrees once it
 * comes within [ENTER_DEGREES] of one, and lets go only after it has moved more than [EXIT_DEGREES] away.
 * The wider exit band stops the view from chattering at the edge of a snap.
 */
class RotationSnap {
    /** The rotation to show, and whether this step is the moment the canvas entered a snap. */
    data class Step(
        val degrees: Float,
        val entered: Boolean,
    )

    private var held: Float? = null

    /** Feeds the accumulated, unsnapped [rotationDegrees] and returns what to show. */
    fun update(rotationDegrees: Float): Step {
        val raw = ViewMath.normalizeDegrees(rotationDegrees)
        val current = held
        if (current != null) {
            if (ViewMath.angularDistance(raw, current) <= EXIT_DEGREES) return Step(current, entered = false)
            held = null
            return Step(raw, entered = false)
        }
        val nearest = nearestRightAngle(raw)
        if (ViewMath.angularDistance(raw, nearest) > ENTER_DEGREES) return Step(raw, entered = false)
        held = nearest
        return Step(nearest, entered = true)
    }

    /** Forgets any snap, for example when a new pinch starts. */
    fun reset() {
        held = null
    }

    companion object {
        /** The canvas snaps when a pinch brings it this close to a right angle. */
        const val ENTER_DEGREES = 4f

        /** The canvas lets go only once a pinch has moved this far from the snapped angle. */
        const val EXIT_DEGREES = 6f

        /** The nearest of 0, 90, 180 and 270 degrees to [degrees], in [0, 360). */
        fun nearestRightAngle(degrees: Float): Float = ViewMath.normalizeDegrees((degrees / RIGHT_ANGLE).roundToInt() * RIGHT_ANGLE)

        private const val RIGHT_ANGLE = 90f
    }
}
