package com.artflow.studio.domain.model.brush

import kotlinx.serialization.Serializable

/**
 * Imported pressure control points, including non-zero endpoints and descending responses.
 * ArtFlow interpolates linearly between the stored nodes; the reference app's interpolation
 * and time-dependent response speed are not inferred from the archive alone.
 */
@Serializable
data class PressureMapping(
    val points: List<Point>,
) {
    @Serializable
    data class Point(
        val input: Float,
        val output: Float,
    )

    init {
        require(points.size in 2..MAX_POINTS) { "A pressure mapping needs 2 to $MAX_POINTS points" }
        require(points.all { it.input in 0f..1f && it.output in 0f..1f }) { "Pressure points must be finite and within 0..1" }
        require(points.zipWithNext().all { (a, b) -> a.input < b.input }) { "Pressure inputs must increase strictly" }
    }

    fun map(pressure: Float): Float {
        require(pressure.isFinite()) { "Pressure must be finite" }
        if (pressure <= points.first().input) return points.first().output
        for (index in 1 until points.size) {
            val end = points[index]
            if (pressure <= end.input) {
                val start = points[index - 1]
                val fraction = (pressure - start.input) / (end.input - start.input)
                return start.output + fraction * (end.output - start.output)
            }
        }
        return points.last().output
    }

    companion object {
        const val MAX_POINTS = 16
    }
}

/** Separate channel curves follow the imported overall mapping, rather than replacing each other. */
@Serializable
data class ImportedPressure(
    val overall: PressureMapping? = null,
    val size: PressureMapping? = null,
    val opacity: PressureMapping? = null,
)
