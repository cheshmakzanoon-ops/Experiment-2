package com.artflow.studio.domain.model.brush

/**
 * Natural cubic spline and sampled lookup reconstructed from the pinned native reference.
 * See docs/NATIVE_PRESSURE_SPLINE.md for provenance, rounding and endpoint conventions.
 * This mode is explicit so existing ArtFlow graphs retain their original linear behavior.
 */
internal object NativePressureSpline {
    const val RESOLUTION = 1024
    private const val LAST_INDEX = RESOLUTION - 1

    fun prepare(points: List<PressureMapping.Point>?): FloatArray {
        if (points == null) return FloatArray(RESOLUTION) { 1f }
        val nodes = sortedPoints(points)
        if (nodes.size < 2) return FloatArray(RESOLUTION) { it.toFloat() / LAST_INDEX }
        val x = FloatArray(nodes.size) { nodes[it].input * RESOLUTION }
        val y = FloatArray(nodes.size) { nodes[it].output * RESOLUTION }
        val derivatives = secondDerivatives(x, y)
        val values = FloatArray(RESOLUTION)
        for (interval in 0 until nodes.lastIndex) {
            val width = x[interval + 1] - x[interval]
            val scale = width * width / 6f
            for (sample in x[interval].toInt() until x[interval + 1].toInt()) {
                val b = (sample.toFloat() - x[interval]) / width
                val a = 1f - b
                val linear = FloatFma.compute(a, y[interval], y[interval + 1] * b)
                val left = FloatFma.compute(a * a, a, -a)
                val right = FloatFma.compute(b * b, b, -b)
                val curvature = FloatFma.compute(left, derivatives[interval], derivatives[interval + 1] * right)
                values[sample] = FloatFma.compute(scale, curvature, linear)
            }
        }
        var prefix = 0
        while (prefix.toFloat() < x.first()) values[prefix++] = y.first()
        for (sample in x.last().toInt() until RESOLUTION) values[sample] = y.last()
        for (sample in values.indices) values[sample] = bounded(values[sample] / LAST_INDEX)
        return values
    }

    fun lookup(
        table: FloatArray,
        pressure: Float,
    ): Float {
        require(pressure.isFinite()) { "Pressure must be finite" }
        require(table.size == RESOLUTION) { "A native curve table needs $RESOLUTION samples" }
        val position = pressure.coerceIn(0f, 1f) * LAST_INDEX
        val lower = position.toInt()
        val fraction = position - lower
        val upper = if (fraction == 0f) lower else lower + 1
        return FloatFma.compute(table[lower], 1f - fraction, table[upper] * fraction)
    }

    private fun sortedPoints(points: List<PressureMapping.Point>): List<PressureMapping.Point> {
        require(points.size <= PressureMapping.MAX_POINTS) { "Too many pressure points" }
        require(points.all { it.input in 0f..1f && it.output in 0f..1f }) { "Pressure points must be finite and within 0..1" }
        var firstSeen = false
        var lastSeen = false
        val sorted =
            points.sortedWith(compareBy<PressureMapping.Point> { it.input }.thenBy { it.output }).filter { point ->
                when (point.input) {
                    0f -> (!firstSeen).also { firstSeen = true }
                    1f -> (!lastSeen).also { lastSeen = true }
                    else -> true
                }
            }
        require(sorted.zipWithNext().all { (a, b) -> a.input < b.input }) { "Pressure inputs must increase strictly" }
        return sorted
    }

    private fun secondDerivatives(
        x: FloatArray,
        y: FloatArray,
    ): FloatArray {
        val lower = FloatArray(x.size)
        val diagonal = FloatArray(x.size)
        val upper = FloatArray(x.size)
        val rhs = FloatArray(x.size)
        diagonal[0] = 1f
        diagonal[x.lastIndex] = 1f
        for (index in 1 until x.lastIndex) {
            val before = x[index] - x[index - 1]
            val after = x[index + 1] - x[index]
            lower[index] = before / 6f
            diagonal[index] = (x[index + 1] - x[index - 1]) / 3f
            upper[index] = after / 6f
            rhs[index] = (y[index + 1] - y[index]) / after - (y[index] - y[index - 1]) / before
        }
        for (index in 1 until x.size) {
            val factor = -lower[index] / diagonal[index - 1]
            diagonal[index] = FloatFma.compute(factor, upper[index - 1], diagonal[index])
            lower[index] = 0f
            rhs[index] = FloatFma.compute(factor, rhs[index - 1], rhs[index])
        }
        for (index in x.lastIndex - 1 downTo 0) {
            val factor = -upper[index] / diagonal[index + 1]
            diagonal[index] = FloatFma.compute(factor, lower[index + 1], diagonal[index])
            upper[index] = 0f
            rhs[index] = FloatFma.compute(factor, rhs[index + 1], rhs[index])
        }
        return FloatArray(x.size) { rhs[it] / diagonal[it] }
    }

    /** Native preparation clamps NaN to zero as well as saturating infinities and spline overshoot. */
    private fun bounded(value: Float): Float =
        when {
            value.isNaN() || value <= 0f -> 0f
            value >= 1f -> 1f
            else -> value
        }
}
