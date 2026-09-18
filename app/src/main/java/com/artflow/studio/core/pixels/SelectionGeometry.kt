package com.artflow.studio.core.pixels

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Canvas-bounded rasterization. Double intermediates prevent finite Float inputs from overflowing. */
internal object SelectionGeometry {
    fun rectangle(
        width: Int,
        height: Int,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        checkActive: () -> Unit,
    ): SelectionMask {
        checkActive()
        requireFinite(left, top, right, bottom)
        val mask = SelectionMask(width, height)
        if (left == right || top == bottom) return mask
        val x0 = lower(min(left, right).toDouble(), width)
        val x1 = upper(max(left, right).toDouble(), width)
        val y0 = lower(min(top, bottom).toDouble(), height)
        val y1 = upper(max(top, bottom).toDouble(), height)
        for (y in y0 until y1) {
            checkActive()
            java.util.Arrays.fill(mask.coverage, y * width + x0, y * width + x1, 255.toByte())
        }
        return mask
    }

    fun ellipse(
        width: Int,
        height: Int,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        checkActive: () -> Unit,
    ): SelectionMask {
        checkActive()
        requireFinite(left, top, right, bottom)
        val mask = SelectionMask(width, height)
        val xMin = min(left, right).toDouble()
        val xMax = max(left, right).toDouble()
        val yMin = min(top, bottom).toDouble()
        val yMax = max(top, bottom).toDouble()
        val spanX = xMax - xMin
        val spanY = yMax - yMin
        if (spanX == 0.0 || spanY == 0.0) return mask
        // Clip the original endpoints, never center +/- radius: that subtraction can
        // lose a small, entirely off-canvas lower bound beside a huge upper bound.
        if (lower(xMin, width) >= upper(xMax, width)) return mask
        val y0 = lower(yMin, height)
        val y1 = upper(yMax, height)
        val samples = IntArray(width)
        for (y in y0 until y1) {
            checkActive()
            samples.fill(0)
            repeat(4) { subrow ->
                val scanY = y + (subrow + 0.5) / 4.0
                if (scanY > yMin && scanY < yMax) {
                    val extent = sqrt((4.0 * ((scanY - yMin) / spanY) * ((yMax - scanY) / spanY)).coerceIn(0.0, 1.0))
                    if (extent <= 0.5) {
                        // Near the top/bottom, preserve the small nonzero half-width.
                        val center = (xMin + xMax) / 2.0
                        val halfWidth = spanX / 2.0 * extent
                        accumulateSpan(samples, center - halfWidth, center + halfWidth)
                    } else {
                        // Near the middle, preserve small edge offsets using
                        // 1 - sqrt(1 - d*d) = d*d / (1 + sqrt(1 - d*d)).
                        val d = (scanY - (yMin + yMax) / 2.0) / (spanY / 2.0)
                        val inset = spanX / 2.0 * d * d / (1.0 + extent)
                        accumulateSpan(samples, xMin + inset, xMax - inset)
                    }
                }
            }
            for (x in 0 until width) mask.coverage[y * width + x] = ((samples[x] * 255 + 8) / 16).toByte()
        }
        return mask
    }

    fun polygon(
        width: Int,
        height: Int,
        points: List<Pair<Float, Float>>,
        checkActive: () -> Unit,
    ): SelectionMask {
        checkPoints(points, checkActive)
        val mask = SelectionMask(width, height)
        if (points.size < 3) return mask
        val y0 = lower(points.minOf { it.second }.toDouble(), height)
        val y1 = upper(points.maxOf { it.second }.toDouble(), height)
        val intersections = DoubleArray(points.size)
        val samples = IntArray(width)
        for (y in y0 until y1) {
            checkActive()
            samples.fill(0)
            repeat(4) { subrow ->
                val scanY = y + (subrow + 0.5) / 4.0
                val count = intersections(points, scanY, intersections, checkActive)
                java.util.Arrays.sort(intersections, 0, count)
                var index = 0
                while (index + 1 < count) {
                    accumulateSpan(samples, intersections[index], intersections[index + 1])
                    index += 2
                }
            }
            for (x in 0 until width) mask.coverage[y * width + x] = ((samples[x] * 255 + 8) / 16).toByte()
        }
        return mask
    }

    private fun intersections(
        points: List<Pair<Float, Float>>,
        scanY: Double,
        into: DoubleArray,
        checkActive: () -> Unit,
    ): Int {
        var count = 0
        for (index in points.indices) {
            if (index % 1024 == 0) checkActive()
            val a = points[index]
            val b = points[(index + 1) % points.size]
            val ax = a.first.toDouble()
            val ay = a.second.toDouble()
            val bx = b.first.toDouble()
            val by = b.second.toDouble()
            val crossesScanline = (ay <= scanY) != (by <= scanY)
            if (crossesScanline) {
                // Float*Float products fit in Double's significand. The line constant avoids
                // catastrophic cancellation from interpolating between huge opposite endpoints.
                into[count++] = (ax * by - bx * ay + (bx - ax) * scanY) / (by - ay)
            }
        }
        return count
    }

    private fun accumulateSpan(
        samples: IntArray,
        left: Double,
        right: Double,
    ) {
        val limit = samples.size * 4
        val start = ceil(left * 4 - 0.5).coerceIn(0.0, limit.toDouble()).toInt()
        val end = ceil(right * 4 - 0.5).coerceIn(0.0, limit.toDouble()).toInt()
        if (start >= end) return
        for (x in start / 4..(end - 1) / 4) {
            samples[x] += min(end, (x + 1) * 4) - max(start, x * 4)
        }
    }

    fun stroke(
        width: Int,
        height: Int,
        points: List<Pair<Float, Float>>,
        radius: Float,
        checkActive: () -> Unit,
    ): SelectionMask {
        checkPoints(points, checkActive)
        require(radius.isFinite() && radius > 0f) { "Selection radius must be positive and finite" }
        val mask = SelectionMask(width, height)
        if (points.isEmpty()) return mask
        if (points.size == 1) rasterSegment(mask, points[0], points[0], radius.toDouble(), checkActive)
        for (index in 1 until points.size) {
            checkActive()
            rasterSegment(mask, points[index - 1], points[index], radius.toDouble(), checkActive)
        }
        return mask
    }

    private fun rasterSegment(
        mask: SelectionMask,
        a: Pair<Float, Float>,
        b: Pair<Float, Float>,
        radius: Double,
        checkActive: () -> Unit,
    ) {
        val ax = a.first.toDouble()
        val ay = a.second.toDouble()
        val bx = b.first.toDouble()
        val by = b.second.toDouble()
        val dx = bx - ax
        val dy = by - ay
        val length = hypot(dx, dy)
        val cross = ax * by - bx * ay
        val y0 = lower(min(ay, by) - radius, mask.height)
        val y1 = upper(max(ay, by) + radius, mask.height)
        for (y in y0 until y1) {
            checkActive()
            val py = y + 0.5
            val lowY = max(min(ay, by), py - radius)
            val highY = min(max(ay, by), py + radius)
            if (lowY > highY) continue
            val firstX = if (dy == 0.0) ax else (cross + dx * lowY) / dy
            val lastX = if (dy == 0.0) bx else (cross + dx * highY) / dy
            val x0 = lower(min(firstX, lastX) - radius, mask.width)
            val x1 = upper(max(firstX, lastX) + radius, mask.width)
            for (x in x0 until x1) {
                val px = x + 0.5
                val projection = (px - ax) * dx + (py - ay) * dy
                val distance =
                    when {
                        length == 0.0 || projection <= 0.0 -> hypot(px - ax, py - ay)
                        projection >= length * length -> hypot(px - bx, py - by)
                        else -> abs(dy * px - dx * py - cross) / length
                    }
                val coverage = ((radius - distance).coerceIn(0.0, 1.0) * 255).toInt()
                val index = y * mask.width + x
                if (coverage > (mask.coverage[index].toInt() and 0xFF)) mask.coverage[index] = coverage.toByte()
            }
        }
    }

    private fun checkPoints(
        points: List<Pair<Float, Float>>,
        checkActive: () -> Unit,
    ) {
        checkActive()
        for (index in points.indices) {
            if (index % 1024 == 0) checkActive()
            val point = points[index]
            require(point.first.isFinite() && point.second.isFinite()) { "Selection points must be finite" }
        }
    }

    private fun requireFinite(vararg values: Float) {
        require(values.all { it.isFinite() }) { "Selection coordinates must be finite" }
    }

    private fun lower(
        value: Double,
        limit: Int,
    ): Int = floor(value).coerceIn(0.0, limit.toDouble()).toInt()

    private fun upper(
        value: Double,
        limit: Int,
    ): Int = ceil(value).coerceIn(0.0, limit.toDouble()).toInt()
}
