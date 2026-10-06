package com.artflow.studio.core.pixels

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sin

/**
 * Destination corners of the transformed content box, in canvas pixels, ordered top-left,
 * top-right, bottom-right, bottom-left of the *source* box. Every transform (move, scale, rotate,
 * flip, distort) is a change of these four points, and pixels are always resampled from the
 * session's original pixels, so repeated edits never accumulate blur.
 */
data class Quad(
    val x0: Float,
    val y0: Float,
    val x1: Float,
    val y1: Float,
    val x2: Float,
    val y2: Float,
    val x3: Float,
    val y3: Float,
) {
    fun x(i: Int): Float =
        when (i) {
            0 -> x0
            1 -> x1
            2 -> x2
            else -> x3
        }

    fun y(i: Int): Float =
        when (i) {
            0 -> y0
            1 -> y1
            2 -> y2
            else -> y3
        }

    val centerX: Float get() = (x0 + x1 + x2 + x3) / 4f
    val centerY: Float get() = (y0 + y1 + y2 + y3) / 4f

    fun map(transform: (Float, Float) -> Pair<Float, Float>): Quad {
        val (a, b) = transform(x0, y0)
        val (c, d) = transform(x1, y1)
        val (e, f) = transform(x2, y2)
        val (g, h) = transform(x3, y3)
        return Quad(a, b, c, d, e, f, g, h)
    }

    fun withCorner(
        i: Int,
        x: Float,
        y: Float,
    ): Quad =
        when (i) {
            0 -> copy(x0 = x, y0 = y)
            1 -> copy(x1 = x, y1 = y)
            2 -> copy(x2 = x, y2 = y)
            else -> copy(x3 = x, y3 = y)
        }

    fun toArray(): FloatArray = floatArrayOf(x0, y0, x1, y1, x2, y2, x3, y3)

    companion object {
        fun fromBounds(bounds: IntBounds): Quad {
            val l = bounds.left.toFloat()
            val t = bounds.top.toFloat()
            val r = bounds.right + 1f
            val b = bounds.bottom + 1f
            return Quad(l, t, r, t, r, b, l, b)
        }
    }
}

object TransformQuad {
    /** What a touch grabbed: a corner (0-3), an edge (0 top, 1 right, 2 bottom, 3 left), the rotation knob or the body. */
    sealed interface Target {
        data class Corner(
            val index: Int,
        ) : Target

        data class Edge(
            val index: Int,
        ) : Target

        data object Rotate : Target

        data object Body : Target
    }

    enum class Mode(
        val displayName: String,
    ) {
        FREEFORM("Freeform"),
        UNIFORM("Uniform"),
        DISTORT("Distort"),
        WARP("Warp"),
    }

    enum class Interpolation(
        val displayName: String,
    ) {
        NEAREST("Nearest neighbour"),
        BILINEAR("Bilinear"),
        BICUBIC("Bicubic"),
        ;

        fun sample(
            buffer: PixelBuffer,
            x: Float,
            y: Float,
        ): Int =
            when (this) {
                NEAREST -> buffer.sampleNearest(x, y)
                BILINEAR -> buffer.sampleBilinear(x, y)
                BICUBIC -> buffer.sampleBicubic(x, y)
            }
    }

    fun translate(
        q: Quad,
        dx: Float,
        dy: Float,
    ): Quad = q.map { x, y -> (x + dx) to (y + dy) }

    /** Whether ([x], [y]) lies inside [q], by counting how many edges a ray from it crosses. */
    fun contains(
        q: Quad,
        x: Float,
        y: Float,
    ): Boolean {
        var inside = false
        for (i in 0 until CORNERS) {
            val j = (i + CORNERS - 1) % CORNERS
            val (xi, yi) = q.x(i) to q.y(i)
            val (xj, yj) = q.x(j) to q.y(j)
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) inside = !inside
        }
        return inside
    }

    /**
     * Procreate's nudge: a tap outside the box moves it one pixel toward the tap, along whichever
     * axis the tap lies further out on. Null for a tap inside the box.
     */
    fun nudgeToward(
        q: Quad,
        x: Float,
        y: Float,
    ): Pair<Float, Float>? {
        if (contains(q, x, y)) return null
        val dx = x - q.centerX
        val dy = y - q.centerY
        return if (abs(dx) >= abs(dy)) sign(dx) to 0f else 0f to sign(dy)
    }

    fun rotate(
        q: Quad,
        degrees: Float,
        cx: Float = q.centerX,
        cy: Float = q.centerY,
    ): Quad {
        val r = Math.toRadians(degrees.toDouble())
        val c = cos(r).toFloat()
        val s = sin(r).toFloat()
        return q.map { x, y ->
            val u = x - cx
            val v = y - cy
            (cx + u * c - v * s) to (cy + u * s + v * c)
        }
    }

    fun scale(
        q: Quad,
        factor: Float,
        cx: Float = q.centerX,
        cy: Float = q.centerY,
    ): Quad = q.map { x, y -> (cx + (x - cx) * factor) to (cy + (y - cy) * factor) }

    /** Mirrors the content inside the same box: swaps which source edge lands on which side. */
    fun flip(
        q: Quad,
        horizontal: Boolean,
    ): Quad =
        if (horizontal) {
            Quad(q.x1, q.y1, q.x0, q.y0, q.x3, q.y3, q.x2, q.y2)
        } else {
            Quad(q.x3, q.y3, q.x2, q.y2, q.x1, q.y1, q.x0, q.y0)
        }

    /** Largest centred placement of the box inside the canvas, keeping its proportions. */
    fun fitTo(
        q: Quad,
        width: Int,
        height: Int,
    ): Quad {
        val minX = min(min(q.x0, q.x1), min(q.x2, q.x3))
        val maxX = max(max(q.x0, q.x1), max(q.x2, q.x3))
        val minY = min(min(q.y0, q.y1), min(q.y2, q.y3))
        val maxY = max(max(q.y0, q.y1), max(q.y2, q.y3))
        val w = maxX - minX
        val h = maxY - minY
        if (w < 1f || h < 1f) return q
        val factor = min(width / w, height / h)
        val moved = translate(q, width / 2f - (minX + maxX) / 2f, height / 2f - (minY + maxY) / 2f)
        return scale(moved, factor, width / 2f, height / 2f)
    }

    /** Canvas position of the rotation knob: beyond the middle of the top edge, [distance] away. */
    fun rotationKnob(
        q: Quad,
        distance: Float,
    ): Pair<Float, Float> {
        val mx = (q.x0 + q.x1) / 2f
        val my = (q.y0 + q.y1) / 2f
        val ex = q.x1 - q.x0
        val ey = q.y1 - q.y0
        val len = hypot(ex, ey).coerceAtLeast(1e-3f)
        // Outward normal of the top edge (pointing away from the box centre).
        var nx = ey / len
        var ny = -ex / len
        if ((mx - q.centerX) * nx + (my - q.centerY) * ny < 0f) {
            nx = -nx
            ny = -ny
        }
        return (mx + nx * distance) to (my + ny * distance)
    }

    fun hit(
        q: Quad,
        x: Float,
        y: Float,
        tolerance: Float,
        knobDistance: Float,
    ): Target {
        val (kx, ky) = rotationKnob(q, knobDistance)
        if (hypot(x - kx, y - ky) <= tolerance) return Target.Rotate
        val corner = (0 until 4).minByOrNull { hypot(x - q.x(it), y - q.y(it)) } ?: 0
        if (hypot(x - q.x(corner), y - q.y(corner)) <= tolerance) return Target.Corner(corner)
        for (edge in 0 until 4) {
            val mx = (q.x(edge) + q.x((edge + 1) % 4)) / 2f
            val my = (q.y(edge) + q.y((edge + 1) % 4)) / 2f
            if (hypot(x - mx, y - my) <= tolerance) return Target.Edge(edge)
        }
        return Target.Body
    }

    /** New corners after dragging [target] from ([sx], [sy]) to ([x], [y]), starting from [start]. */
    fun drag(
        start: Quad,
        target: Target,
        mode: Mode,
        sx: Float,
        sy: Float,
        x: Float,
        y: Float,
    ): Quad =
        when (target) {
            Target.Body -> translate(start, x - sx, y - sy)
            Target.Rotate -> {
                val a0 = atan2(sy - start.centerY, sx - start.centerX)
                val a1 = atan2(y - start.centerY, x - start.centerX)
                rotate(start, Math.toDegrees((a1 - a0).toDouble()).toFloat())
            }
            is Target.Corner -> dragCorner(start, target.index, mode, x - sx, y - sy)
            is Target.Edge -> dragEdge(start, target.index, mode, x - sx, y - sy)
        }

    /** Procreate's Magnetics and Snapping toggles for a transform drag. */
    data class Assist(
        /** Moves lock to 45° directions, rotation to 15° steps and Freeform corners keep proportions. */
        val magnetics: Boolean = false,
        /** The box's edges and centre snap to the canvas edges and centre lines. */
        val snapping: Boolean = false,
    )

    /** Canvas size and snap distance (canvas pixels) for [Assist.snapping]. */
    data class SnapArea(
        val width: Int,
        val height: Int,
        val tolerance: Float,
    )

    /** [drag] with Magnetics and Snapping applied. */
    fun drag(
        start: Quad,
        target: Target,
        mode: Mode,
        from: Pair<Float, Float>,
        to: Pair<Float, Float>,
        assist: Assist,
        area: SnapArea,
    ): Quad {
        val (sx, sy) = from
        val (x, y) = to
        return when {
            target == Target.Body -> {
                val (dx, dy) = if (assist.magnetics) lockToAxis(x - sx, y - sy) else (x - sx) to (y - sy)
                translate(start, dx, dy).let { if (assist.snapping) snapToCanvas(it, area) else it }
            }
            target == Target.Rotate && assist.magnetics -> {
                val a0 = atan2(sy - start.centerY, sx - start.centerX)
                val a1 = atan2(y - start.centerY, x - start.centerX)
                val degrees = Math.toDegrees((a1 - a0).toDouble()).toFloat()
                rotate(start, (degrees / ROTATION_STEP).roundToInt() * ROTATION_STEP)
            }
            target is Target.Corner && assist.magnetics && mode == Mode.FREEFORM ->
                dragCorner(start, target.index, Mode.UNIFORM, x - sx, y - sy)
            else -> drag(start, target, mode, sx, sy, x, y)
        }
    }

    /** Projects a move onto the nearest horizontal, vertical or diagonal direction. */
    fun lockToAxis(
        dx: Float,
        dy: Float,
    ): Pair<Float, Float> {
        if (dx == 0f && dy == 0f) return 0f to 0f
        val step = Math.PI / 4
        val angle = (atan2(dy.toDouble(), dx.toDouble()) / step).roundToInt() * step
        val ux = cos(angle).toFloat()
        val uy = sin(angle).toFloat()
        val along = dx * ux + dy * uy
        return ux * along to uy * along
    }

    /** Shifts [q] so its nearest edge or centre lands on a canvas edge or centre line within the tolerance. */
    fun snapToCanvas(
        q: Quad,
        area: SnapArea,
    ): Quad {
        val minX = min(min(q.x0, q.x1), min(q.x2, q.x3))
        val maxX = max(max(q.x0, q.x1), max(q.x2, q.x3))
        val minY = min(min(q.y0, q.y1), min(q.y2, q.y3))
        val maxY = max(max(q.y0, q.y1), max(q.y2, q.y3))
        val dx = snapShift(floatArrayOf(minX, (minX + maxX) / 2f, maxX), area.width.toFloat(), area.tolerance)
        val dy = snapShift(floatArrayOf(minY, (minY + maxY) / 2f, maxY), area.height.toFloat(), area.tolerance)
        return if (dx == 0f && dy == 0f) q else translate(q, dx, dy)
    }

    /**
     * Canvas guide lines the box currently sits on: x positions of vertical lines and y positions of
     * horizontal lines among the canvas edges and centre lines.
     */
    fun alignedGuides(
        q: Quad,
        width: Int,
        height: Int,
    ): Pair<List<Float>, List<Float>> {
        val minX = min(min(q.x0, q.x1), min(q.x2, q.x3))
        val maxX = max(max(q.x0, q.x1), max(q.x2, q.x3))
        val minY = min(min(q.y0, q.y1), min(q.y2, q.y3))
        val maxY = max(max(q.y0, q.y1), max(q.y2, q.y3))

        fun touching(
            edges: List<Float>,
            size: Float,
        ) = listOf(0f, size / 2f, size).filter { line -> edges.any { abs(it - line) < GUIDE_EPSILON } }
        return touching(listOf(minX, (minX + maxX) / 2f, maxX), width.toFloat()) to
            touching(listOf(minY, (minY + maxY) / 2f, maxY), height.toFloat())
    }

    private fun snapShift(
        edges: FloatArray,
        size: Float,
        tolerance: Float,
    ): Float {
        var best = 0f
        var bestDistance = tolerance
        for (line in floatArrayOf(0f, size / 2f, size)) {
            for (edge in edges) {
                val distance = abs(line - edge)
                if (distance <= bestDistance) {
                    bestDistance = distance
                    best = line - edge
                }
            }
        }
        return best
    }

    private fun dragCorner(
        q: Quad,
        i: Int,
        mode: Mode,
        dx: Float,
        dy: Float,
    ): Quad {
        val opposite = (i + 2) % 4
        return when (mode) {
            Mode.DISTORT, Mode.WARP -> q.withCorner(i, q.x(i) + dx, q.y(i) + dy)
            Mode.UNIFORM -> {
                val ox = q.x(opposite)
                val oy = q.y(opposite)
                val before = hypot(q.x(i) - ox, q.y(i) - oy)
                if (before < 1f) return q
                // Project the drag onto the diagonal so the box scales without skewing.
                val ux = (q.x(i) - ox) / before
                val uy = (q.y(i) - oy) / before
                val along = before + dx * ux + dy * uy
                scale(q, (along / before).coerceAtLeast(MIN_FACTOR), ox, oy)
            }
            Mode.FREEFORM -> {
                // Move the corner, and slide its two neighbours along the box axes.
                val next = (i + 1) % 4
                val prev = (i + 3) % 4
                val (ax, ay) = unit(q.x(next) - q.x(i), q.y(next) - q.y(i))
                val (bx, by) = unit(q.x(prev) - q.x(i), q.y(prev) - q.y(i))
                val alongA = dx * ax + dy * ay
                val alongB = dx * bx + dy * by
                q
                    .withCorner(i, q.x(i) + dx, q.y(i) + dy)
                    .let { it.withCorner(next, q.x(next) + bx * alongB, q.y(next) + by * alongB) }
                    .let { it.withCorner(prev, q.x(prev) + ax * alongA, q.y(prev) + ay * alongA) }
            }
        }
    }

    private fun dragEdge(
        q: Quad,
        edge: Int,
        mode: Mode,
        dx: Float,
        dy: Float,
    ): Quad {
        val a = edge
        val b = (edge + 1) % 4
        if (mode == Mode.DISTORT) {
            return q.withCorner(a, q.x(a) + dx, q.y(a) + dy).let { it.withCorner(b, q.x(b) + dx, q.y(b) + dy) }
        }
        // Stretch perpendicular to the edge only.
        val (ex, ey) = unit(q.x(b) - q.x(a), q.y(b) - q.y(a))
        val nx = -ey
        val ny = ex
        val push = dx * nx + dy * ny
        val moved = q.withCorner(a, q.x(a) + nx * push, q.y(a) + ny * push).let { it.withCorner(b, q.x(b) + nx * push, q.y(b) + ny * push) }
        if (mode != Mode.UNIFORM) return moved
        // Uniform: keep proportions by also scaling along the edge about its opposite edge's middle.
        val c = (edge + 2) % 4
        val d = (edge + 3) % 4
        val depthBefore = hypot((q.x(a) + q.x(b)) / 2f - (q.x(c) + q.x(d)) / 2f, (q.y(a) + q.y(b)) / 2f - (q.y(c) + q.y(d)) / 2f)
        if (depthBefore < 1f) return moved
        val factor = ((depthBefore + push * signToward(q, edge)) / depthBefore).coerceAtLeast(MIN_FACTOR)
        return scale(q, factor, (q.x(c) + q.x(d)) / 2f, (q.y(c) + q.y(d)) / 2f)
    }

    /** +1 when the edge normal points away from the box centre. */
    private fun signToward(
        q: Quad,
        edge: Int,
    ): Float {
        val a = edge
        val b = (edge + 1) % 4
        val (ex, ey) = unit(q.x(b) - q.x(a), q.y(b) - q.y(a))
        val mx = (q.x(a) + q.x(b)) / 2f - q.centerX
        val my = (q.y(a) + q.y(b)) / 2f - q.centerY
        return if (-ey * mx + ex * my >= 0f) 1f else -1f
    }

    private fun unit(
        x: Float,
        y: Float,
    ): Pair<Float, Float> {
        val len = hypot(x, y)
        return if (len < 1e-4f) 0f to 0f else (x / len) to (y / len)
    }

    /**
     * Inverse projective map for a quad: returns the 3×3 matrix (row-major) taking a destination
     * point to unit-square coordinates (u, v) of the source box, or null for a degenerate quad.
     */
    fun inverseMapping(q: Quad): FloatArray? {
        val dx1 = q.x1 - q.x2
        val dx2 = q.x3 - q.x2
        val dx3 = q.x0 - q.x1 + q.x2 - q.x3
        val dy1 = q.y1 - q.y2
        val dy2 = q.y3 - q.y2
        val dy3 = q.y0 - q.y1 + q.y2 - q.y3
        val g: Float
        val h: Float
        if (abs(dx3) < 1e-4f && abs(dy3) < 1e-4f) {
            g = 0f
            h = 0f
        } else {
            val den = dx1 * dy2 - dx2 * dy1
            if (abs(den) < 1e-6f) return null
            g = (dx3 * dy2 - dx2 * dy3) / den
            h = (dx1 * dy3 - dx3 * dy1) / den
        }
        val a = q.x1 - q.x0 + g * q.x1
        val b = q.x3 - q.x0 + h * q.x3
        val c = q.x0
        val d = q.y1 - q.y0 + g * q.y1
        val e = q.y3 - q.y0 + h * q.y3
        val f = q.y0
        // Adjugate of [[a b c][d e f][g h 1]].
        val m00 = e - f * h
        val m01 = c * h - b
        val m02 = b * f - c * e
        val m10 = f * g - d
        val m11 = a - c * g
        val m12 = c * d - a * f
        val m20 = d * h - e * g
        val m21 = b * g - a * h
        val m22 = a * e - b * d
        val det = a * m00 + b * m10 + c * m20
        if (abs(det) < 1e-9f) return null
        return floatArrayOf(m00, m01, m02, m10, m11, m12, m20, m21, m22)
    }

    /**
     * Renders [source] (only its selected pixels when [selection] is active) so that the box
     * [bounds] lands on [quad]. Unselected pixels stay where they are.
     */
    fun render(
        source: PixelBuffer,
        target: PixelBuffer,
        bounds: IntBounds,
        quad: Quad,
        selection: SelectionMask?,
        interpolation: Interpolation,
    ) {
        require(source.width == target.width && source.height == target.height) { "Buffer sizes differ" }
        val (floating, blend) = LayerTransform.split(source, target, selection)
        val inverse = inverseMapping(quad) ?: return
        val x0 = max(0, floor(min(min(quad.x0, quad.x1), min(quad.x2, quad.x3))).toInt() - 1)
        val x1 = min(target.width - 1, ceil(max(max(quad.x0, quad.x1), max(quad.x2, quad.x3))).toInt() + 1)
        val y0 = max(0, floor(min(min(quad.y0, quad.y1), min(quad.y2, quad.y3))).toInt() - 1)
        val y1 = min(target.height - 1, ceil(max(max(quad.y0, quad.y1), max(quad.y2, quad.y3))).toInt() + 1)
        val sampler = Sampler(floating, inverse, bounds, interpolation)
        for (y in y0..y1) {
            for (x in x0..x1) {
                val sample = sampler.sample(x + 0.5f, y + 0.5f)
                if ((sample ushr 24) == 0) continue
                val i = y * target.width + x
                target.pixels[i] = if (blend) BlendModes.sourceOver(target.pixels[i], sample) else sample
            }
        }
    }

    /** Renders with [mesh] when the content is warped, otherwise with [quad]. */
    fun renderShape(
        source: PixelBuffer,
        target: PixelBuffer,
        bounds: IntBounds,
        quad: Quad,
        mesh: WarpMesh?,
        selection: SelectionMask?,
        interpolation: Interpolation,
    ) {
        if (mesh != null) {
            WarpMesh.render(source, target, bounds, mesh, selection, interpolation)
        } else {
            render(source, target, bounds, quad, selection, interpolation)
        }
    }

    /** Maps destination points back into the source box; outside it, samples are transparent. */
    private class Sampler(
        private val floating: PixelBuffer,
        private val inverse: FloatArray,
        private val bounds: IntBounds,
        private val interpolation: Interpolation,
    ) {
        private val srcW = bounds.width.toFloat()
        private val srcH = bounds.height.toFloat()
        private val inside = -1f / max(srcW, srcH)..1f + 1f / max(srcW, srcH)

        fun sample(
            px: Float,
            py: Float,
        ): Int {
            val w = inverse[6] * px + inverse[7] * py + inverse[8]
            if (abs(w) < 1e-9f) return 0
            val u = (inverse[0] * px + inverse[1] * py + inverse[2]) / w
            val v = (inverse[3] * px + inverse[4] * py + inverse[5]) / w
            if (u !in inside || v !in inside) return 0
            val sx = bounds.left + u * srcW
            val sy = bounds.top + v * srcH
            return interpolation.sample(floating, sx, sy)
        }
    }

    private const val MIN_FACTOR = 0.02f
    private const val ROTATION_STEP = 15f
    private const val GUIDE_EPSILON = 0.5f
    private const val CORNERS = 4
}
