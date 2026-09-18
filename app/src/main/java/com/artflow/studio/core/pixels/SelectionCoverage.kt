package com.artflow.studio.core.pixels

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Linear-time selection filters. Temporary memory does not depend on the requested radius. */
internal object SelectionCoverage {
    fun feather(
        source: SelectionMask,
        radius: Int,
        checkActive: () -> Unit,
    ): SelectionMask {
        checkActive()
        if (radius <= 0) return source.copy()
        val width = source.width
        val height = source.height
        // Long arithmetic is intentional: Int.MAX_VALUE is a valid, very wide box window.
        val divisor = radius.toLong() * 2 + 1
        val horizontal = FloatArray(source.coverage.size)
        for (y in 0 until height) {
            checkActive()
            val row = y * width
            val first = source.coverageAt(0, y)
            val last = source.coverageAt(width - 1, y)
            var sum = radius.toDouble() * first + max(0L, radius.toLong() - width + 1) * last
            for (x in 0..min(radius, width - 1)) sum += source.coverageAt(x, y)
            for (x in 0 until width) {
                horizontal[row + x] = (sum / divisor).toFloat()
                val leaving = (x.toLong() - radius).coerceIn(0L, width - 1L).toInt()
                val entering = (x.toLong() + radius + 1).coerceIn(0L, width - 1L).toInt()
                sum += source.coverageAt(entering, y) - source.coverageAt(leaving, y)
            }
        }
        checkActive()
        val out = SelectionMask(width, height)
        for (x in 0 until width) {
            checkActive()
            var sum =
                radius.toDouble() * horizontal[x] +
                    max(0L, radius.toLong() - height + 1) * horizontal[(height - 1) * width + x].toDouble()
            for (y in 0..min(radius, height - 1)) sum += horizontal[y * width + x]
            for (y in 0 until height) {
                out.coverage[y * width + x] = (sum / divisor).roundToInt().coerceIn(0, 255).toByte()
                val leaving = (y.toLong() - radius).coerceIn(0L, height - 1L).toInt()
                val entering = (y.toLong() + radius + 1).coerceIn(0L, height - 1L).toInt()
                sum += horizontal[entering * width + x].toDouble() - horizontal[leaving * width + x]
            }
        }
        checkActive()
        return out
    }

    fun expand(
        source: SelectionMask,
        amount: Int,
        checkActive: () -> Unit,
    ): SelectionMask {
        checkActive()
        if (amount == 0) return source.copy()
        val width = source.width
        val height = source.height
        val growing = amount > 0
        // Beyond this radius a grow is full/empty, while a shrink is always empty.
        val radius = min(abs(amount.toLong()), max(width, height).toLong()).toInt()
        val required = if (growing) 1 else radius * 2 + 1
        val horizontal = ByteArray(source.coverage.size)
        for (y in 0 until height) {
            checkActive()
            val row = y * width
            var count = 0
            for (x in 0..min(radius, width - 1)) {
                if (source.coverageAt(x, y) >= 128) count++
            }
            for (x in 0 until width) {
                horizontal[row + x] = if (count >= required) 1 else 0
                if (source.coverageAt(x - radius, y) >= 128) count--
                if (source.coverageAt(x + radius + 1, y) >= 128) count++
            }
        }
        checkActive()
        val out = SelectionMask(width, height)
        for (x in 0 until width) {
            checkActive()
            var count = 0
            for (y in 0..min(radius, height - 1)) count += horizontal[y * width + x]
            for (y in 0 until height) {
                out.coverage[y * width + x] = if (count >= required) 255.toByte() else 0
                if (y - radius >= 0) count -= horizontal[(y - radius) * width + x]
                if (y + radius + 1 < height) count += horizontal[(y + radius + 1) * width + x]
            }
        }
        checkActive()
        return out
    }
}
