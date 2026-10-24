package com.artflow.studio.domain.model.brush

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * Imported pressure control points, including non-zero endpoints and descending responses.
 * Existing graphs retain linear interpolation. Newly imported native graphs opt into the
 * statically recovered spline/table contract; pressure response over time is independent.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class PressureMapping(
    val points: List<Point>,
    /** Omit the legacy default even in signed documents that encode other default fields. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val interpolation: Interpolation = Interpolation.LINEAR,
) {
    @Serializable
    enum class Interpolation { LINEAR, NATIVE_SPLINE_1024 }

    @Serializable
    data class Point(
        val input: Float,
        val output: Float,
    )

    // Derived data is outside the constructor, equality and serialized document. Copies prepare
    // their own table; publication is synchronized only once, never on the steady-state paint path.
    @Transient
    @Volatile
    private var preparedTable: FloatArray? = null

    init {
        require(points.size in 2..MAX_POINTS) { "A pressure mapping needs 2 to $MAX_POINTS points" }
        require(points.all { it.input in 0f..1f && it.output in 0f..1f }) { "Pressure points must be finite and within 0..1" }
        require(points.zipWithNext().all { (a, b) -> a.input < b.input }) { "Pressure inputs must increase strictly" }
    }

    fun map(pressure: Float): Float {
        require(pressure.isFinite()) { "Pressure must be finite" }
        if (interpolation == Interpolation.NATIVE_SPLINE_1024) return NativePressureSpline.lookup(table(), pressure)
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

    private fun table(): FloatArray =
        preparedTable ?: synchronized(this) {
            preparedTable ?: NativePressureSpline.prepare(points).also { preparedTable = it }
        }

    companion object {
        const val MAX_POINTS = 16
    }
}

/** Separate channel curves follow the imported overall mapping, rather than replacing each other. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ImportedPressure(
    val overall: PressureMapping? = null,
    val size: PressureMapping? = null,
    val opacity: PressureMapping? = null,
    /** Preserve older signed documents' canonical JSON when no distinct flow curve was stored. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val flow: PressureMapping? = null,
)
