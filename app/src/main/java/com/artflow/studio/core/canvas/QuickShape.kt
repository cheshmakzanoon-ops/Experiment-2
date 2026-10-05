package com.artflow.studio.core.canvas

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * QuickShape: recognises a freehand stroke the artist holds at the end and returns a clean
 * replacement path — a straight line, a polyline, an ellipse/circle, or a triangle, rectangle or
 * other simple polygon for closed loops. Keeping the pen down then adjusts the snapped shape.
 */
object QuickShape {
    enum class Kind(
        val label: String,
    ) {
        LINE("line"),
        POLYLINE("polyline"),
        ELLIPSE("ellipse"),
        POLYGON("polygon"),
    }

    /**
     * A recognised shape: the outline to stroke and the nodes Edit Shape lets the artist drag (a
     * line's ends, the corners of a polyline or polygon, and an ellipse's four axis ends in turn).
     */
    data class Result(
        val kind: Kind,
        val points: List<Pair<Float, Float>>,
        val nodes: List<Pair<Float, Float>> = emptyList(),
    )

    /** Returns the recognised shape, or null when the stroke is too short or too irregular. */
    fun recognize(points: List<Pair<Float, Float>>): Result? {
        if (points.size < 3) return null
        val first = points.first()
        val last = points.last()
        val length = pathLength(points)
        if (length < MIN_LENGTH) return null
        val chord = hypot(last.first - first.first, last.second - first.second)
        if (chord / length > LINE_STRAIGHTNESS) return Result(Kind.LINE, line(first, last), listOf(first, last))
        if (chord < length * CLOSED_RATIO) return ellipse(points) ?: polygon(points, closed = true)
        return polygon(points, closed = false)
    }

    /**
     * Follows the pen after the shape snapped: a line's end goes to [to] (snapping to 45° steps when
     * close), and other shapes turn and scale about their centre so the point that was under the pen
     * at [from] follows it to [to].
     */
    fun adjust(
        shape: Result,
        from: Pair<Float, Float>,
        to: Pair<Float, Float>,
    ): Result {
        if (shape.kind == Kind.LINE) {
            val end = snapAngle(shape.points.first(), to)
            return Result(Kind.LINE, line(shape.points.first(), end), listOf(shape.points.first(), end))
        }
        val points = shape.points
        val cx = points.sumOf { it.first.toDouble() }.toFloat() / points.size
        val cy = points.sumOf { it.second.toDouble() }.toFloat() / points.size
        val before = hypot(from.first - cx, from.second - cy)
        if (before < 1f) return shape
        val scale = hypot(to.first - cx, to.second - cy) / before
        val turn = atan2(to.second - cy, to.first - cx) - atan2(from.second - cy, from.first - cx)
        val c = cos(turn) * scale
        val s = sin(turn) * scale
        val move = { (x, y): Pair<Float, Float> -> (cx + (x - cx) * c - (y - cy) * s) to (cy + (x - cx) * s + (y - cy) * c) }
        return shape.copy(points = shape.points.map(move), nodes = shape.nodes.map(move))
    }

    /** The node of [shape] within [radius] of [point] (the nearest when several are), or null. */
    fun nodeAt(
        shape: Result,
        point: Pair<Float, Float>,
        radius: Float,
    ): Int? =
        shape.nodes.indices
            .minByOrNull { hypot(shape.nodes[it].first - point.first, shape.nodes[it].second - point.second) }
            ?.takeIf { hypot(shape.nodes[it].first - point.first, shape.nodes[it].second - point.second) <= radius }

    /**
     * Edit Shape: moves node [index] to [to] and redraws the outline through the nodes. An
     * ellipse keeps the opposite end of the dragged axis in place, so the drag both resizes and
     * turns it, and keeps the other axis's length.
     */
    fun moveNode(
        shape: Result,
        index: Int,
        to: Pair<Float, Float>,
    ): Result {
        if (index !in shape.nodes.indices) return shape
        val nodes = shape.nodes.toMutableList()
        if (shape.kind == Kind.ELLIPSE && nodes.size == ELLIPSE_NODES) {
            val fixed = nodes[(index + 2) % ELLIPSE_NODES]
            val cx = (fixed.first + to.first) / 2f
            val cy = (fixed.second + to.second) / 2f
            val reach = hypot(to.first - cx, to.second - cy)
            if (reach < MIN_RADIUS) return shape
            val centre = (nodes[0].first + nodes[2].first) / 2f to (nodes[0].second + nodes[2].second) / 2f
            val side = nodes[(index + 1) % ELLIPSE_NODES]
            val other = hypot(side.first - centre.first, side.second - centre.second)
            // The other axis is perpendicular, turning the same way the nodes are numbered.
            val ux = (to.first - cx) / reach
            val uy = (to.second - cy) / reach
            nodes[index] = to
            nodes[(index + 1) % ELLIPSE_NODES] = (cx - uy * other) to (cy + ux * other)
            nodes[(index + 3) % ELLIPSE_NODES] = (cx + uy * other) to (cy - ux * other)
        } else {
            nodes[index] = to
        }
        return Result(shape.kind, outline(shape.kind, nodes), nodes)
    }

    /** The stroke path through [nodes] for a shape of [kind]. */
    fun outline(
        kind: Kind,
        nodes: List<Pair<Float, Float>>,
    ): List<Pair<Float, Float>> =
        when {
            nodes.isEmpty() -> emptyList()
            kind == Kind.ELLIPSE && nodes.size == ELLIPSE_NODES -> ellipseThrough(nodes)
            else -> {
                val path = if (kind == Kind.POLYGON) nodes + nodes.first() else nodes
                path.zipWithNext().flatMap { (a, b) -> line(a, b).dropLast(1) } + path.last()
            }
        }

    /** An ellipse outline starting at the first node, from its four axis ends. */
    private fun ellipseThrough(nodes: List<Pair<Float, Float>>): List<Pair<Float, Float>> {
        val cx = (nodes[0].first + nodes[2].first) / 2.0
        val cy = (nodes[0].second + nodes[2].second) / 2.0
        val ru = hypot(nodes[0].first - cx, nodes[0].second - cy)
        val rv = hypot(nodes[1].first - cx, nodes[1].second - cy)
        val angle = atan2(nodes[0].second - cy, nodes[0].first - cx)
        return ellipseOutline(cx, cy, ru, rv, angle, 0.0)
    }

    private fun ellipseOutline(
        cx: Double,
        cy: Double,
        ru: Double,
        rv: Double,
        angle: Double,
        start: Double,
    ): List<Pair<Float, Float>> {
        val cosA = cos(angle)
        val sinA = sin(angle)
        val circumference = PI * (3 * (ru + rv) - sqrt((3 * ru + rv) * (ru + 3 * rv)))
        val steps = max(MIN_ELLIPSE_STEPS, (circumference / SAMPLE_SPACING).toInt())
        return List(steps + 1) { i ->
            val t = start + 2 * PI * i / steps
            val u = ru * cos(t)
            val v = rv * sin(t)
            (cx + u * cosA - v * sinA).toFloat() to (cy + u * sinA + v * cosA).toFloat()
        }
    }

    private fun snapAngle(
        origin: Pair<Float, Float>,
        to: Pair<Float, Float>,
    ): Pair<Float, Float> {
        val dx = to.first - origin.first
        val dy = to.second - origin.second
        val length = hypot(dx, dy)
        val angle = atan2(dy, dx)
        val step = (PI / 4).toFloat()
        val snapped = Math.round(angle / step) * step
        if (abs(angle - snapped) > ANGLE_SNAP) return to
        return (origin.first + cos(snapped) * length) to (origin.second + sin(snapped) * length)
    }

    /** Corner-based shapes: few straight sides meeting at clear corners; open strokes become polylines. */
    private fun polygon(
        points: List<Pair<Float, Float>>,
        closed: Boolean,
    ): Result? {
        val xs = points.map { it.first }
        val ys = points.map { it.second }
        val diagonal = hypot(xs.max() - xs.min(), ys.max() - ys.min())
        var corners = simplify(points, max(MIN_CORNER_TOLERANCE, diagonal * CORNER_TOLERANCE))
        if (closed) corners = corners.dropLast(1)
        if (corners.size !in 3..5 || !sharpCorners(corners, closed) || crosses(corners, closed)) return null
        if (closed && corners.size == 4) corners = squared(corners)
        val kind = if (closed) Kind.POLYGON else Kind.POLYLINE
        return Result(kind, outline(kind, corners), corners)
    }

    /** Ramer–Douglas–Peucker: keeps the points that stray more than [tolerance] from a straight path. */
    private fun simplify(
        points: List<Pair<Float, Float>>,
        tolerance: Float,
    ): List<Pair<Float, Float>> {
        if (points.size < 3) return points
        val first = points.first()
        val last = points.last()
        var farthest = 0
        var distance = 0f
        for (i in 1 until points.size - 1) {
            val d = distanceToSegment(points[i], first, last)
            if (d > distance) {
                distance = d
                farthest = i
            }
        }
        if (distance <= tolerance) return listOf(first, last)
        return simplify(points.subList(0, farthest + 1), tolerance).dropLast(1) + simplify(points.subList(farthest, points.size), tolerance)
    }

    private fun distanceToSegment(
        p: Pair<Float, Float>,
        a: Pair<Float, Float>,
        b: Pair<Float, Float>,
    ): Float {
        val dx = b.first - a.first
        val dy = b.second - a.second
        val lengthSquared = dx * dx + dy * dy
        if (lengthSquared < 1e-6f) return hypot(p.first - a.first, p.second - a.second)
        val t = (((p.first - a.first) * dx + (p.second - a.second) * dy) / lengthSquared).coerceIn(0f, 1f)
        return hypot(p.first - (a.first + t * dx), p.second - (a.second + t * dy))
    }

    /** Every corner turns by more than [MIN_TURN]: gentle bends are curves, not corners. */
    private fun sharpCorners(
        corners: List<Pair<Float, Float>>,
        closed: Boolean,
    ): Boolean {
        val n = corners.size
        val indices = if (closed) 0 until n else 1 until n - 1
        return indices.all { i ->
            val prev = corners[(i - 1 + n) % n]
            val here = corners[i]
            val next = corners[(i + 1) % n]
            val a = atan2(here.second - prev.second, here.first - prev.first)
            val b = atan2(next.second - here.second, next.first - here.first)
            var turn = abs(b - a)
            if (turn > PI) turn = (2 * PI).toFloat() - turn
            turn > MIN_TURN
        }
    }

    /** True when two non-adjacent sides cross (a scribble rather than a shape). */
    private fun crosses(
        corners: List<Pair<Float, Float>>,
        closed: Boolean,
    ): Boolean {
        val sides = (if (closed) corners + corners.first() else corners).zipWithNext()
        for (i in sides.indices) {
            for (j in i + 2 until sides.size) {
                val adjacent = closed && i == 0 && j == sides.size - 1
                if (!adjacent && intersects(sides[i], sides[j])) return true
            }
        }
        return false
    }

    private fun intersects(
        p: Pair<Pair<Float, Float>, Pair<Float, Float>>,
        q: Pair<Pair<Float, Float>, Pair<Float, Float>>,
    ): Boolean {
        fun cross(
            o: Pair<Float, Float>,
            a: Pair<Float, Float>,
            b: Pair<Float, Float>,
        ) = (a.first - o.first) * (b.second - o.second) - (a.second - o.second) * (b.first - o.first)
        val d1 = cross(q.first, q.second, p.first)
        val d2 = cross(q.first, q.second, p.second)
        val d3 = cross(p.first, p.second, q.first)
        val d4 = cross(p.first, p.second, q.second)
        return (d1 > 0f) != (d2 > 0f) && (d3 > 0f) != (d4 > 0f)
    }

    /** A four-sided shape with near-right corners becomes an exact rectangle. */
    private fun squared(corners: List<Pair<Float, Float>>): List<Pair<Float, Float>> {
        val a = corners[0]
        val b = corners[1]
        val c = corners[2]
        val d = corners[3]
        val ux = b.first - a.first
        val uy = b.second - a.second
        val width = (hypot(ux, uy) + hypot(c.first - d.first, c.second - d.second)) / 2f
        val height = (hypot(d.first - a.first, d.second - a.second) + hypot(c.first - b.first, c.second - b.second)) / 2f
        val length = hypot(ux, uy).coerceAtLeast(1e-3f)
        val ex = ux / length
        val ey = uy / length
        // The perpendicular pointing the way the artist drew the second side.
        val side = if ((c.first - b.first) * -ey + (c.second - b.second) * ex >= 0f) 1f else -1f
        val nx = -ey * side
        val ny = ex * side
        val rectangle =
            listOf(
                a,
                (a.first + ex * width) to (a.second + ey * width),
                (a.first + ex * width + nx * height) to (a.second + ey * width + ny * height),
                (a.first + nx * height) to (a.second + ny * height),
            )
        val rightAngles =
            corners.indices.all { i ->
                val prev = corners[(i + 3) % 4]
                val here = corners[i]
                val next = corners[(i + 1) % 4]
                val v1x = prev.first - here.first
                val v1y = prev.second - here.second
                val v2x = next.first - here.first
                val v2y = next.second - here.second
                abs(v1x * v2x + v1y * v2y) / (hypot(v1x, v1y) * hypot(v2x, v2y)).coerceAtLeast(1e-3f) < RIGHT_ANGLE_COSINE
            }
        return if (rightAngles) rectangle else corners
    }

    private fun line(
        a: Pair<Float, Float>,
        b: Pair<Float, Float>,
    ): List<Pair<Float, Float>> {
        val steps = max(2, (hypot(b.first - a.first, b.second - a.second) / SAMPLE_SPACING).toInt())
        return List(steps + 1) { i ->
            val t = i.toFloat() / steps
            (a.first + (b.first - a.first) * t) to (a.second + (b.second - a.second) * t)
        }
    }

    /** Principal-axis ellipse fit from the covariance of the stroke samples. */
    private fun ellipse(points: List<Pair<Float, Float>>): Result? {
        val n = points.size
        val cx = points.sumOf { it.first.toDouble() } / n
        val cy = points.sumOf { it.second.toDouble() } / n
        var sxx = 0.0
        var syy = 0.0
        var sxy = 0.0
        points.forEach { (x, y) ->
            val dx = x - cx
            val dy = y - cy
            sxx += dx * dx
            syy += dy * dy
            sxy += dx * dy
        }
        sxx /= n
        syy /= n
        sxy /= n
        val angle = 0.5 * atan2(2 * sxy, sxx - syy)
        val cosA = cos(angle)
        val sinA = sin(angle)
        // Radii from the furthest projection onto each principal axis.
        var ru = 0.0
        var rv = 0.0
        points.forEach { (x, y) ->
            val dx = x - cx
            val dy = y - cy
            ru = max(ru, abs(dx * cosA + dy * sinA))
            rv = max(rv, abs(-dx * sinA + dy * cosA))
        }
        if (ru < MIN_RADIUS || rv < MIN_RADIUS) return null
        // Snap nearly round loops to a perfect circle.
        if (abs(ru - rv) / max(ru, rv) < CIRCLE_SNAP) {
            val r = sqrt(ru * rv)
            ru = r
            rv = r
        }
        // Reject strokes that stray far from the fitted outline (e.g. scribbles).
        var error = 0.0
        points.forEach { (x, y) ->
            val dx = x - cx
            val dy = y - cy
            val u = (dx * cosA + dy * sinA) / ru
            val v = (-dx * sinA + dy * cosA) / rv
            error += abs(sqrt(u * u + v * v) - 1.0)
        }
        if (error / n > MAX_ELLIPSE_ERROR) return null
        val start = atan2(points.first().second - cy, points.first().first - cx) - angle
        val nodes =
            listOf(ru to 0.0, 0.0 to rv, -ru to 0.0, 0.0 to -rv).map { (u, v) ->
                (cx + u * cosA - v * sinA).toFloat() to (cy + u * sinA + v * cosA).toFloat()
            }
        return Result(Kind.ELLIPSE, ellipseOutline(cx, cy, ru, rv, angle, start), nodes)
    }

    private fun pathLength(points: List<Pair<Float, Float>>): Float =
        points.zipWithNext().sumOf { (a, b) -> hypot(b.first - a.first, b.second - a.second).toDouble() }.toFloat()

    private const val MIN_LENGTH = 24f
    private const val LINE_STRAIGHTNESS = 0.9f
    private const val CLOSED_RATIO = 0.2f
    private const val MIN_RADIUS = 6.0
    private const val ELLIPSE_NODES = 4
    private const val MIN_ELLIPSE_STEPS = 24
    private const val CIRCLE_SNAP = 0.12
    private const val MAX_ELLIPSE_ERROR = 0.12
    private const val SAMPLE_SPACING = 3f
    private const val CORNER_TOLERANCE = 0.06f
    private const val MIN_CORNER_TOLERANCE = 4f
    private const val MIN_TURN = 0.6f
    private const val RIGHT_ANGLE_COSINE = 0.26f
    private const val ANGLE_SNAP = 0.07f
}
