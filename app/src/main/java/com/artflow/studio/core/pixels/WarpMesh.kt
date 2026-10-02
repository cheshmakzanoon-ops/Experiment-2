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
 * Procreate's Warp: the content box becomes a bicubic Bézier patch with a 4 × 4 grid of control
 * points (row-major, x and y interleaved). Dragging a point bends the patch; dragging the surface
 * pulls the spot under the finger along with it. Pixels are always resampled from the session's
 * original pixels, so repeated edits never accumulate blur.
 */
class WarpMesh(
    private val points: FloatArray,
) {
    init {
        require(points.size == POINTS * 2) { "A warp mesh has ${POINTS * 2} coordinates" }
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
        for (row in 0 until SIDE) {
            val bv = bernstein(row, v)
            for (col in 0 until SIDE) {
                val w = bv * bernstein(col, u)
                px += w * x(row * SIDE + col)
                py += w * y(row * SIDE + col)
            }
        }
        return px to py
    }

    fun map(transform: (Float, Float) -> Pair<Float, Float>): WarpMesh {
        val out = FloatArray(points.size)
        for (i in 0 until POINTS) {
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
    fun corners(): Quad = Quad(x(0), y(0), x(SIDE - 1), y(SIDE - 1), x(POINTS - 1), y(POINTS - 1), x(POINTS - SIDE), y(POINTS - SIDE))

    /** Index of the control point within [tolerance] of (x, y), or -1. */
    fun pointAt(
        x: Float,
        y: Float,
        tolerance: Float,
    ): Int {
        val nearest = (0 until POINTS).minByOrNull { hypot(x - x(it), y - y(it)) } ?: return -1
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
        val weights = FloatArray(POINTS) { bernstein(it / SIDE, v) * bernstein(it % SIDE, u) }
        val norm = weights.sumOf { (it * it).toDouble() }.toFloat()
        if (norm < 1e-9f) return this
        val out = points.copyOf()
        for (i in 0 until POINTS) {
            val share = weights[i] / norm
            out[i * 2] += dx * share
            out[i * 2 + 1] += dy * share
        }
        return WarpMesh(out)
    }

    val centerX: Float get() = (0 until POINTS).sumOf { x(it).toDouble() }.toFloat() / POINTS

    val centerY: Float get() = (0 until POINTS).sumOf { y(it).toDouble() }.toFloat() / POINTS

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
        for (i in 0 until POINTS) {
            val row = i / SIDE
            val col = i % SIDE
            val from = if (horizontal) row * SIDE + (SIDE - 1 - col) else (SIDE - 1 - row) * SIDE + col
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
        val xs = (0 until POINTS).map { x(it) }
        val ys = (0 until POINTS).map { y(it) }
        val w = xs.max() - xs.min()
        val h = ys.max() - ys.min()
        if (w < 1f || h < 1f) return this
        val moved = translate(width / 2f - (xs.min() + xs.max()) / 2f, height / 2f - (ys.min() + ys.max()) / 2f)
        return moved.scale(min(width / w, height / h), width / 2f, height / 2f)
    }

    fun toArray(): FloatArray = points.copyOf()

    override fun equals(other: Any?): Boolean = other is WarpMesh && points.contentEquals(other.points)

    override fun hashCode(): Int = points.contentHashCode()

    companion object {
        const val SIDE = 4
        const val POINTS = SIDE * SIDE
        private const val LOCATE_STEPS = 24
        private const val SUBDIVISIONS = 32

        /** An unbent mesh covering [bounds]: evenly spaced control points map the box onto itself. */
        fun fromBounds(bounds: IntBounds): WarpMesh = fromQuad(Quad.fromBounds(bounds))

        /** A mesh with the bilinear shape of [quad]. */
        fun fromQuad(quad: Quad): WarpMesh {
            val out = FloatArray(POINTS * 2)
            for (row in 0 until SIDE) {
                val v = row / (SIDE - 1f)
                for (col in 0 until SIDE) {
                    val u = col / (SIDE - 1f)
                    val top = lerp(quad.x0, quad.x1, u) to lerp(quad.y0, quad.y1, u)
                    val bottom = lerp(quad.x3, quad.x2, u) to lerp(quad.y3, quad.y2, u)
                    out[(row * SIDE + col) * 2] = lerp(top.first, bottom.first, v)
                    out[(row * SIDE + col) * 2 + 1] = lerp(top.second, bottom.second, v)
                }
            }
            return WarpMesh(out)
        }

        /**
         * Renders [source] (only its selected pixels when [selection] is active) so that the box
         * [bounds] follows [mesh]. The patch is cut into small triangles, each filled once.
         */
        fun render(
            source: PixelBuffer,
            target: PixelBuffer,
            bounds: IntBounds,
            mesh: WarpMesh,
            selection: SelectionMask?,
            highQuality: Boolean,
        ) {
            require(source.width == target.width && source.height == target.height) { "Buffer sizes differ" }
            val (floating, blend) = LayerTransform.split(source, target, selection)
            val raster = TriangleRaster(floating, target, bounds, blend, highQuality)
            val n = SUBDIVISIONS
            for (j in 0..n) {
                for (i in 0..n) {
                    val (px, py) = mesh.evaluate(i / n.toFloat(), j / n.toFloat())
                    raster.setVertex(j * (n + 1) + i, px, py, i / n.toFloat(), j / n.toFloat())
                }
            }
            for (j in 0 until n) {
                for (i in 0 until n) {
                    val a = j * (n + 1) + i
                    val c = a + n + 2
                    raster.fill(a, a + 1, c)
                    raster.fill(a, c, c - 1)
                }
            }
        }

        private fun bernstein(
            i: Int,
            t: Float,
        ): Float {
            val s = 1f - t
            return when (i) {
                0 -> s * s * s
                1 -> 3f * t * s * s
                2 -> 3f * t * t * s
                else -> t * t * t
            }
        }

        private fun lerp(
            a: Float,
            b: Float,
            t: Float,
        ): Float = a + (b - a) * t
    }

    /** Fills destination triangles, sampling the source box by interpolated patch coordinates. */
    private class TriangleRaster(
        private val floating: PixelBuffer,
        private val target: PixelBuffer,
        private val bounds: IntBounds,
        private val blend: Boolean,
        private val highQuality: Boolean,
    ) {
        private val count = (SUBDIVISIONS + 1) * (SUBDIVISIONS + 1)
        private val vx = FloatArray(count)
        private val vy = FloatArray(count)
        private val vu = FloatArray(count)
        private val vv = FloatArray(count)
        private val written = BitSet(target.width * target.height)

        fun setVertex(
            index: Int,
            x: Float,
            y: Float,
            u: Float,
            v: Float,
        ) {
            vx[index] = x
            vy[index] = y
            vu[index] = u
            vv[index] = v
        }

        fun fill(
            a: Int,
            b: Int,
            c: Int,
        ) {
            val area = (vx[b] - vx[a]) * (vy[c] - vy[a]) - (vx[c] - vx[a]) * (vy[b] - vy[a])
            if (abs(area) < 1e-6f) return
            val x0 = max(0, floor(min(vx[a], min(vx[b], vx[c]))).toInt())
            val x1 = min(target.width - 1, ceil(max(vx[a], max(vx[b], vx[c]))).toInt())
            val y0 = max(0, floor(min(vy[a], min(vy[b], vy[c]))).toInt())
            val y1 = min(target.height - 1, ceil(max(vy[a], max(vy[b], vy[c]))).toInt())
            for (y in y0..y1) {
                for (x in x0..x1) {
                    val px = x + 0.5f
                    val py = y + 0.5f
                    val wa = ((vx[b] - px) * (vy[c] - py) - (vx[c] - px) * (vy[b] - py)) / area
                    val wb = ((vx[c] - px) * (vy[a] - py) - (vx[a] - px) * (vy[c] - py)) / area
                    val wc = 1f - wa - wb
                    val index = y * target.width + x
                    if (min(wa, min(wb, wc)) < -EDGE_EPSILON || written[index]) continue
                    write(index, wa * vu[a] + wb * vu[b] + wc * vu[c], wa * vv[a] + wb * vv[b] + wc * vv[c])
                }
            }
        }

        private fun write(
            index: Int,
            u: Float,
            v: Float,
        ) {
            written.set(index)
            val sx = bounds.left + u.coerceIn(0f, 1f) * bounds.width
            val sy = bounds.top + v.coerceIn(0f, 1f) * bounds.height
            val sample = if (highQuality) floating.sampleBilinear(sx, sy) else floating.sampleNearest(sx, sy)
            if ((sample ushr 24) == 0) return
            target.pixels[index] = if (blend) BlendModes.sourceOver(target.pixels[index], sample) else sample
        }

        private companion object {
            const val EDGE_EPSILON = 1e-4f
        }
    }
}
