package com.artflow.studio.core.pixels

import java.util.BitSet
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Procreate's Warp: the content box becomes a Bézier patch with a square grid of control points
 * (row-major, x and y interleaved): 4 × 4 (bicubic) for Warp, or [ADVANCED_SIDE] × [ADVANCED_SIDE]
 * for Advanced Mesh, whose extra points bend smaller areas. Dragging a point bends the patch;
 * dragging the surface pulls the spot under the finger along with it. Pixels are always resampled
 * from the session's original pixels, so repeated edits never accumulate blur.
 */
class WarpMesh(
    private val points: FloatArray,
) {
    /** Control points along each side of the grid. */
    val side: Int = SIDES.firstOrNull { it * it * 2 == points.size } ?: 0

    /** Control points in the whole grid. */
    val pointCount: Int get() = side * side

    init {
        require(side > 0) { "A warp mesh has ${POINTS * 2} or ${ADVANCED_SIDE * ADVANCED_SIDE * 2} coordinates" }
    }

    /** What a touch grabbed: one control point, the surface at (u, v), or the whole mesh. */
    sealed interface Target {
        data class Point(
            val index: Int,
        ) : Target

        data class Surface(
            val u: Float,
            val v: Float,
        ) : Target

        data object Body : Target
    }

    fun x(index: Int): Float = points[index * 2]

    fun y(index: Int): Float = points[index * 2 + 1]

    /** Point on the patch at (u, v) in 0..1, as an x/y pair. */
    fun evaluate(
        u: Float,
        v: Float,
    ): Pair<Float, Float> {
        var px = 0f
        var py = 0f
        val degree = side - 1
        for (row in 0 until side) {
            val bv = bernstein(row, degree, v)
            for (col in 0 until side) {
                val w = bv * bernstein(col, degree, u)
                px += w * x(row * side + col)
                py += w * y(row * side + col)
            }
        }
        return px to py
    }

    fun map(transform: (Float, Float) -> Pair<Float, Float>): WarpMesh {
        val out = FloatArray(points.size)
        for (i in 0 until pointCount) {
            val (nx, ny) = transform(x(i), y(i))
            out[i * 2] = nx
            out[i * 2 + 1] = ny
        }
        return WarpMesh(out)
    }

    fun translate(
        dx: Float,
        dy: Float,
    ): WarpMesh = map { px, py -> (px + dx) to (py + dy) }

    /** The four outer corners, in [Quad] order. */
    fun corners(): Quad {
        val last = pointCount - 1
        return Quad(x(0), y(0), x(side - 1), y(side - 1), x(last), y(last), x(pointCount - side), y(pointCount - side))
    }

    /** Index of the control point within [tolerance] of (x, y), or -1. */
    fun pointAt(
        x: Float,
        y: Float,
        tolerance: Float,
    ): Int {
        val nearest = (0 until pointCount).minByOrNull { hypot(x - x(it), y - y(it)) } ?: return -1
        return if (hypot(x - x(nearest), y - y(nearest)) <= tolerance) nearest else -1
    }

    /** Patch coordinates of the surface under (x, y), or null when the touch is off the patch. */
    fun locate(
        x: Float,
        y: Float,
    ): Pair<Float, Float>? {
        var best: Pair<Float, Float>? = null
        var bestDistance = Float.MAX_VALUE
        for (j in 0..LOCATE_STEPS) {
            for (i in 0..LOCATE_STEPS) {
                val u = i / LOCATE_STEPS.toFloat()
                val v = j / LOCATE_STEPS.toFloat()
                val (px, py) = evaluate(u, v)
                val distance = hypot(x - px, y - py)
                if (distance < bestDistance) {
                    bestDistance = distance
                    best = u to v
                }
            }
        }
        val (bu, bv) = best ?: return null
        val step = 1f / LOCATE_STEPS
        val (cx, cy) = evaluate(bu, bv)
        val (nx, ny) = evaluate((bu + step).coerceAtMost(1f), (bv + step).coerceAtMost(1f))
        val (mx, my) = evaluate((bu - step).coerceAtLeast(0f), (bv - step).coerceAtLeast(0f))
        val spacing = max(hypot(nx - cx, ny - cy), hypot(mx - cx, my - cy))
        return best.takeIf { bestDistance <= spacing.coerceAtLeast(1f) }
    }

    fun hit(
        x: Float,
        y: Float,
        tolerance: Float,
    ): Target {
        val point = pointAt(x, y, tolerance)
        if (point >= 0) return Target.Point(point)
        val surface = locate(x, y) ?: return Target.Body
        return Target.Surface(surface.first, surface.second)
    }

    /** New control points after dragging [target] by ([dx], [dy]) from this mesh. */
    fun drag(
        target: Target,
        dx: Float,
        dy: Float,
    ): WarpMesh =
        when (target) {
            Target.Body -> translate(dx, dy)
            is Target.Point -> {
                val out = points.copyOf()
                out[target.index * 2] += dx
                out[target.index * 2 + 1] += dy
                WarpMesh(out)
            }
            is Target.Surface -> pullSurface(target.u, target.v, dx, dy)
        }

    /**
     * Moves the control points so the surface point at (u, v) moves by exactly (dx, dy), spreading
     * the change by each point's influence there (least-squares direct manipulation).
     */
    private fun pullSurface(
        u: Float,
        v: Float,
        dx: Float,
        dy: Float,
    ): WarpMesh {
        val degree = side - 1
        val weights = FloatArray(pointCount) { bernstein(it / side, degree, v) * bernstein(it % side, degree, u) }
        val norm = weights.sumOf { (it * it).toDouble() }.toFloat()
        if (norm < 1e-9f) return this
        val out = points.copyOf()
        for (i in 0 until pointCount) {
            val share = weights[i] / norm
            out[i * 2] += dx * share
            out[i * 2 + 1] += dy * share
        }
        return WarpMesh(out)
    }

    val centerX: Float get() = (0 until pointCount).sumOf { x(it).toDouble() }.toFloat() / pointCount

    val centerY: Float get() = (0 until pointCount).sumOf { y(it).toDouble() }.toFloat() / pointCount

    fun rotate(degrees: Float): WarpMesh {
        val cx = centerX
        val cy = centerY
        val r = Math.toRadians(degrees.toDouble())
        val c = cos(r).toFloat()
        val s = sin(r).toFloat()
        return map { px, py -> (cx + (px - cx) * c - (py - cy) * s) to (cy + (px - cx) * s + (py - cy) * c) }
    }

    fun scale(
        factor: Float,
        cx: Float = centerX,
        cy: Float = centerY,
    ): WarpMesh = map { px, py -> (cx + (px - cx) * factor) to (cy + (py - cy) * factor) }

    /** Mirrors the content inside the same shape by reversing the grid's columns or rows. */
    fun flip(horizontal: Boolean): WarpMesh {
        val out = FloatArray(points.size)
        for (i in 0 until pointCount) {
            val row = i / side
            val col = i % side
            val from = if (horizontal) row * side + (side - 1 - col) else (side - 1 - row) * side + col
            out[i * 2] = x(from)
            out[i * 2 + 1] = y(from)
        }
        return WarpMesh(out)
    }

    /** Largest centred placement of the control points' box inside the canvas, keeping proportions. */
    fun fitTo(
        width: Int,
        height: Int,
    ): WarpMesh {
        val xs = (0 until pointCount).map { x(it) }
        val ys = (0 until pointCount).map { y(it) }
        val w = xs.max() - xs.min()
        val h = ys.max() - ys.min()
        if (w < 1f || h < 1f) return this
        val moved = translate(width / 2f - (xs.min() + xs.max()) / 2f, height / 2f - (ys.min() + ys.max()) / 2f)
        return moved.scale(min(width / w, height / h), width / 2f, height / 2f)
    }

    /**
     * The same shape with [newSide] control points along each side, so switching to or from
     * Advanced Mesh keeps the current bend: exactly when adding points (Bézier degree elevation),
     * and as closely as the coarser grid allows when removing them.
     */
    fun resampled(newSide: Int): WarpMesh {
        require(newSide in SIDES) { "Unsupported warp mesh size" }
        if (newSide == side) return this
        if (newSide < side) return fromQuad(Quad(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f), newSide).map { u, v -> evaluate(u, v) }
        var grid = List(side) { row -> List(side) { col -> x(row * side + col) to y(row * side + col) } }
        repeat(newSide - side) {
            grid = grid.map(::elevate)
            grid = transpose(transpose(grid).map(::elevate))
        }
        return WarpMesh(grid.flatten().flatMap { listOf(it.first, it.second) }.toFloatArray())
    }

    fun toArray(): FloatArray = points.copyOf()

    override fun equals(other: Any?): Boolean = other is WarpMesh && points.contentEquals(other.points)

    override fun hashCode(): Int = points.contentHashCode()

    companion object {
        const val SIDE = 4
        const val POINTS = SIDE * SIDE

        /** Advanced Mesh: more control points, each bending a smaller part of the content. */
        const val ADVANCED_SIDE = 6
        private val SIDES = listOf(SIDE, ADVANCED_SIDE)
        private const val LOCATE_STEPS = 24
        private const val SUBDIVISIONS = 32
        private const val AREA_EPSILON = 1e-6f

        /** An unbent mesh covering [bounds]: evenly spaced control points map the box onto itself. */
        fun fromBounds(
            bounds: IntBounds,
            side: Int = SIDE,
        ): WarpMesh = fromQuad(Quad.fromBounds(bounds), side)

        /** A mesh with the bilinear shape of [quad] and [side] control points along each side. */
        fun fromQuad(
            quad: Quad,
            side: Int = SIDE,
        ): WarpMesh {
            val out = FloatArray(side * side * 2)
            for (row in 0 until side) {
                val v = row / (side - 1f)
                for (col in 0 until side) {
                    val u = col / (side - 1f)
                    val top = lerp(quad.x0, quad.x1, u) to lerp(quad.y0, quad.y1, u)
                    val bottom = lerp(quad.x3, quad.x2, u) to lerp(quad.y3, quad.y2, u)
                    out[(row * side + col) * 2] = lerp(top.first, bottom.first, v)
                    out[(row * side + col) * 2 + 1] = lerp(top.second, bottom.second, v)
                }
            }
            return WarpMesh(out)
        }

        /**
         * Renders [source] (only its selected pixels when [selection] is active) so that the box
         * [bounds] follows [mesh]. The patch is cut into small triangles, each filled once.
         * Filter support outside the box linearly continues its boundary cells; the original
         * interior is rendered first so an exterior fold cannot replace its pixel ownership.
         */
        fun render(
            source: PixelBuffer,
            target: PixelBuffer,
            bounds: IntBounds,
            mesh: WarpMesh,
            selection: SelectionMask?,
            interpolation: TransformQuad.Interpolation,
        ) {
            require(source.width == target.width && source.height == target.height) { "Buffer sizes differ" }
            if (mesh.points.contentEquals(fromBounds(bounds, mesh.side).points)) {
                source.pixels.copyInto(target.pixels)
                return
            }
            val vertices = MeshVertices(mesh, bounds, interpolation)
            // Reject an invalid/collapsed placement before split clears any selected source.
            if (!vertices.isUsable()) {
                source.pixels.copyInto(target.pixels)
                return
            }
            val (floating, blend) = LayerTransform.split(source, target, selection)
            val raster = TriangleRaster(floating, target, bounds, blend, interpolation, vertices)
            vertices.visitTriangles(raster::fill)
        }

        /** One Bézier degree elevation of a row of control points: the same curve with one more point. */
        private fun elevate(row: List<Pair<Float, Float>>): List<Pair<Float, Float>> {
            val n = row.size
            return List(n + 1) { i ->
                val a = i / n.toFloat()
                val before = row.getOrNull(i - 1) ?: row[0]
                val here = row.getOrNull(i) ?: row[n - 1]
                (a * before.first + (1 - a) * here.first) to (a * before.second + (1 - a) * here.second)
            }
        }

        private fun <T> transpose(grid: List<List<T>>): List<List<T>> = List(grid[0].size) { col -> grid.map { it[col] } }

        /** The Bernstein basis polynomial [i] of [degree] at [t]. */
        private fun bernstein(
            i: Int,
            degree: Int,
            t: Float,
        ): Float {
            var value = binomial(degree, i).toFloat()
            repeat(i) { value *= t }
            repeat(degree - i) { value *= 1f - t }
            return value
        }

        private fun binomial(
            n: Int,
            k: Int,
        ): Long {
            var result = 1L
            for (j in 1..k) result = result * (n - k + j) / j
            return result
        }

        private fun lerp(
            a: Float,
            b: Float,
            t: Float,
        ): Float = a + (b - a) * t
    }

    /** Keeps the original tessellation intact and adds at most one exterior filter-support ring. */
    private class MeshVertices(
        mesh: WarpMesh,
        bounds: IntBounds,
        interpolation: TransformQuad.Interpolation,
    ) {
        private val support =
            when (interpolation) {
                TransformQuad.Interpolation.NEAREST -> 0f
                TransformQuad.Interpolation.BILINEAR -> 0.5f
                TransformQuad.Interpolation.BICUBIC -> 1.5f
            }
        private val padding = if (support == 0f) 0 else 1
        private val side = SUBDIVISIONS + 1 + 2 * padding
        private val count = side * side
        val x = FloatArray(count)
        val y = FloatArray(count)
        val u = FloatArray(count) { parameter(it % side, support / bounds.width) }
        val v = FloatArray(count) { parameter(it / side, support / bounds.height) }

        init {
            for (j in 0..SUBDIVISIONS) {
                for (i in 0..SUBDIVISIONS) {
                    val (px, py) = mesh.evaluate(i / SUBDIVISIONS.toFloat(), j / SUBDIVISIONS.toFloat())
                    val index = (j + padding) * side + i + padding
                    x[index] = px
                    y[index] = py
                }
            }
            if (padding != 0) {
                // Extrapolate the boundary tessellation, rather than a degree-five polynomial
                // far outside [0,1] for a one-pixel-wide source. Corners continue both axes.
                for (j in 1 until side - 1) {
                    val first = j * side
                    extend(first, first + 1, first + 2, support * SUBDIVISIONS / bounds.width)
                    extend(first + side - 1, first + side - 2, first + side - 3, support * SUBDIVISIONS / bounds.width)
                }
                for (i in 0 until side) {
                    extend(i, i + side, i + 2 * side, support * SUBDIVISIONS / bounds.height)
                    val last = (side - 1) * side + i
                    extend(last, last - side, last - 2 * side, support * SUBDIVISIONS / bounds.height)
                }
            }
        }

        private fun parameter(
            index: Int,
            margin: Float,
        ): Float =
            when {
                padding != 0 && index == 0 -> -margin
                padding != 0 && index == side - 1 -> 1f + margin
                else -> (index - padding) / SUBDIVISIONS.toFloat()
            }

        private fun extend(
            destination: Int,
            edge: Int,
            inner: Int,
            factor: Float,
        ) {
            x[destination] = x[edge] + (x[edge] - x[inner]) * factor
            y[destination] = y[edge] + (y[edge] - y[inner]) * factor
        }

        fun isUsable(): Boolean {
            if (x.any { !it.isFinite() } || y.any { !it.isFinite() }) return false
            var finite = true
            var hasArea = false
            visitTriangles { a, b, c ->
                val area = area(a, b, c)
                finite = finite && area.isFinite()
                hasArea = hasArea || abs(area) >= AREA_EPSILON
            }
            return finite && hasArea
        }

        fun area(
            a: Int,
            b: Int,
            c: Int,
        ): Float = (x[b] - x[a]) * (y[c] - y[a]) - (x[c] - x[a]) * (y[b] - y[a])

        inline fun visitTriangles(fill: (Int, Int, Int) -> Unit) {
            // Retain the same order and geometry for every original interior triangle.
            for (j in padding until padding + SUBDIVISIONS) {
                for (i in padding until padding + SUBDIVISIONS) fillCell(i, j, fill)
            }
            if (padding == 0) return
            for (i in 0 until side - 1) {
                fillCell(i, 0, fill)
                fillCell(i, side - 2, fill)
            }
            for (j in 1 until side - 2) {
                fillCell(0, j, fill)
                fillCell(side - 2, j, fill)
            }
        }

        private inline fun fillCell(
            column: Int,
            row: Int,
            fill: (Int, Int, Int) -> Unit,
        ) {
            val a = row * side + column
            val c = a + side + 1
            fill(a, a + 1, c)
            fill(a, c, c - 1)
        }
    }

    /** Fills destination triangles, sampling the source box by interpolated patch coordinates. */
    private class TriangleRaster(
        private val floating: PixelBuffer,
        private val target: PixelBuffer,
        private val bounds: IntBounds,
        private val blend: Boolean,
        private val interpolation: TransformQuad.Interpolation,
        private val vertices: MeshVertices,
    ) {
        private val vx = vertices.x
        private val vy = vertices.y
        private val vu = vertices.u
        private val vv = vertices.v
        private val written = BitSet(target.width * target.height)

        fun fill(
            a: Int,
            b: Int,
            c: Int,
        ) {
            val area = vertices.area(a, b, c)
            if (abs(area) < AREA_EPSILON) return
            val minX = min(vx[a], min(vx[b], vx[c]))
            val maxX = max(vx[a], max(vx[b], vx[c]))
            val minY = min(vy[a], min(vy[b], vy[c]))
            val maxY = max(vy[a], max(vy[b], vy[c]))
            if (maxX < 0f || minX >= target.width) return
            if (maxY < 0f || minY >= target.height) return
            // The validated finite geometry can still lie far away from the canvas. Bound
            // each scan before integer conversion; no wrapped or unbounded loop is possible.
            val x0 = floor(minX.coerceIn(0f, target.width - 1f)).toInt()
            val x1 = ceil(maxX.coerceIn(0f, target.width - 1f)).toInt()
            val y0 = floor(minY.coerceIn(0f, target.height - 1f)).toInt()
            val y1 = ceil(maxY.coerceIn(0f, target.height - 1f)).toInt()
            val interior = isInterior(a) && isInterior(b) && isInterior(c)
            for (y in y0..y1) {
                for (x in x0..x1) {
                    val px = x + 0.5f
                    val py = y + 0.5f
                    val wa = ((vx[b] - px) * (vy[c] - py) - (vx[c] - px) * (vy[b] - py)) / area
                    val wb = ((vx[c] - px) * (vy[a] - py) - (vx[a] - px) * (vy[c] - py)) / area
                    val wc = 1f - wa - wb
                    val index = y * target.width + x
                    if (!covers(wa, wb, wc) || written[index]) continue
                    write(index, wa * vu[a] + wb * vu[b] + wc * vu[c], wa * vv[a] + wb * vv[b] + wc * vv[c], interior)
                }
            }
        }

        private fun covers(
            wa: Float,
            wb: Float,
            wc: Float,
        ): Boolean = wa.isFinite() && wb.isFinite() && min(wa, min(wb, wc)) >= -EDGE_EPSILON

        private fun isInterior(vertex: Int): Boolean = vu[vertex] in 0f..1f && vv[vertex] in 0f..1f

        private fun write(
            index: Int,
            u: Float,
            v: Float,
            interior: Boolean,
        ) {
            written.set(index)
            // Preserve the original interior's clamping/nearest rules exactly. Only the explicit
            // exterior ring is allowed to sample past the content box into the filter support.
            val sx = bounds.left + (if (interior) u.coerceIn(0f, 1f) else u) * bounds.width
            val sy = bounds.top + (if (interior) v.coerceIn(0f, 1f) else v) * bounds.height
            val sample = interpolation.sample(floating, sx, sy)
            if ((sample ushr 24) == 0) return
            target.pixels[index] = if (blend) BlendModes.sourceOver(target.pixels[index], sample) else sample
        }

        private companion object {
            const val EDGE_EPSILON = 1e-4f
        }
    }
}
