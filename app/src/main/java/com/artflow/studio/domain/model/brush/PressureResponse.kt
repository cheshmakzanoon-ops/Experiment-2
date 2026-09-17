package com.artflow.studio.domain.model.brush

import kotlinx.serialization.Serializable

/**
 * A continuous, monotone custom pressure response with fixed endpoints (0, 0) and (1, 1).
 * The three editable outputs are at 25%, 50% and 75% input pressure. Fixed input positions
 * make interpolation unambiguous and avoid the loops/vertical tangents of unconstrained curves.
 */
@Serializable
data class PressureResponse(
    val low: Float = 0.25f,
    val middle: Float = 0.5f,
    val high: Float = 0.75f,
) {
    init {
        require(low.isFinite() && middle.isFinite() && high.isFinite()) { "Pressure controls must be finite" }
        require(low in 0f..middle && middle <= high && high <= 1f) { "Pressure controls must increase within 0..1" }
    }

    fun map(pressure: Float): Float {
        require(pressure.isFinite()) { "Pressure must be finite" }
        val position = pressure.coerceIn(0f, 1f) * 4f
        return when {
            position <= 1f -> low * position
            position <= 2f -> low + (middle - low) * (position - 1f)
            position <= 3f -> middle + (high - middle) * (position - 2f)
            else -> high + (1f - high) * (position - 3f)
        }
    }
}
