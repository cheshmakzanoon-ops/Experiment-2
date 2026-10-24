package com.artflow.studio.domain.model.brush

/**
 * Binary32 fused multiply-add without Android's API 33 Math.fma dependency.
 * A product of two finite Float values is exact in Double. TwoSum recovers the residual
 * of adding the third value; a final midpoint correction avoids double rounding to Float.
 * No allocation, arbitrary-precision arithmetic or platform-specific native code is needed.
 */
internal object FloatFma {
    fun compute(
        a: Float,
        b: Float,
        c: Float,
    ): Float {
        val product = a.toDouble() * b.toDouble()
        val addend = c.toDouble()
        val sum = product + addend
        val rounded = sum.toFloat()
        if (!sum.isFinite()) return rounded

        val recoveredAddend = sum - product
        val residual = (product - (sum - recoveredAddend)) + (addend - recoveredAddend)
        if (residual == 0.0) return rounded
        if (!rounded.isFinite()) return correctOverflow(sum, residual, rounded)

        val roundedDouble = rounded.toDouble()
        if (sum == roundedDouble) return rounded
        val upward = sum > roundedDouble
        val neighbor = adjacent(rounded, upward)
        val neighborDouble =
            if (neighbor.isFinite()) {
                neighbor.toDouble()
            } else if (upward) {
                FLOAT_LIMIT
            } else {
                -FLOAT_LIMIT
            }
        val midpoint = (roundedDouble + neighborDouble) * 0.5
        val crossesMidpoint = if (upward) residual > 0.0 else residual < 0.0
        return if (sum == midpoint && crossesMidpoint) neighbor else rounded
    }

    private fun correctOverflow(
        sum: Double,
        residual: Double,
        rounded: Float,
    ): Float {
        val positive = sum > 0.0
        val boundary = (Float.MAX_VALUE.toDouble() + FLOAT_LIMIT) * if (positive) 0.5 else -0.5
        val towardFinite = if (positive) residual < 0.0 else residual > 0.0
        return if (sum == boundary && towardFinite) {
            if (positive) Float.MAX_VALUE else -Float.MAX_VALUE
        } else {
            rounded
        }
    }

    private fun adjacent(
        value: Float,
        upward: Boolean,
    ): Float {
        if (value == 0f) return if (upward) Float.MIN_VALUE else -Float.MIN_VALUE
        val increment = if ((value > 0f) == upward) 1 else -1
        return Float.fromBits(value.toRawBits() + increment)
    }

    /** 2^128: the virtual finite neighbor that defines rounding overflow beyond Float.MAX_VALUE. */
    private const val FLOAT_LIMIT = 3.402823669209385e38
}
