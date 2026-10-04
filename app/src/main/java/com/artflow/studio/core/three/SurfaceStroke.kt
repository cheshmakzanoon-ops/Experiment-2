package com.artflow.studio.core.three

import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.roundToInt

/** A point on the model: texture coordinates and the UV island (connected texture region) it lies in. */
data class SurfaceHit(
    val u: Float,
    val v: Float,
    val island: Int,
)

/** Receives strokes in texture space: u and v run 0..1 with v = 0 at the bottom. */
interface SurfaceSink {
    fun begin(
        u: Float,
        v: Float,
        pressure: Float,
    )

    fun move(
        u: Float,
        v: Float,
        pressure: Float,
    )

    fun end()
}

/**
 * Groups a mesh's triangles into UV islands: triangles that share an edge with the same corners in
 * space and in the texture belong together. A seam is where an edge meets in space but not in the
 * texture, so crossing it means jumping to another island.
 */
object UvIslands {
    private const val GRID = 1e4f
    private const val MULTIPLIER = -0x61c8864680b583ebL
    private const val SHIFT_A = 31
    private const val SHIFT_B = 29

    fun of(mesh: Mesh): IntArray {
        val count = mesh.triangleCount
        val parent = IntArray(count) { it }

        fun root(of: Int): Int {
            var node = of
            while (parent[node] != node) {
                parent[node] = parent[parent[node]]
                node = parent[node]
            }
            return node
        }
        val owners = HashMap<Long, Int>()
        val keys = LongArray(3)
        for (t in 0 until count) {
            for (corner in 0 until 3) keys[corner] = cornerKey(mesh, t * 3 + corner)
            for (edge in 0 until 3) {
                val a = keys[edge]
                val b = keys[(edge + 1) % 3]
                val other = owners.putIfAbsent(mix(minOf(a, b), maxOf(a, b)), t)
                if (other != null) parent[root(t)] = root(other)
            }
        }
        val ids = HashMap<Int, Int>()
        return IntArray(count) { ids.getOrPut(root(it)) { ids.size } }
    }

    /** A 64-bit fingerprint of a corner's rounded position and texture coordinate. */
    private fun cornerKey(
        mesh: Mesh,
        corner: Int,
    ): Long {
        val p = mesh.positions
        val uv = mesh.uvs
        var key = 0L
        for (value in floatArrayOf(p[corner * 3], p[corner * 3 + 1], p[corner * 3 + 2], uv[corner * 2], uv[corner * 2 + 1])) {
            key = mix(key, (value * GRID).roundToInt().toLong())
        }
        return key
    }

    private fun mix(
        a: Long,
        b: Long,
    ): Long {
        var h = a * MULTIPLIER + b
        h = h xor (h ushr SHIFT_A)
        h *= MULTIPLIER
        return h xor (h ushr SHIFT_B)
    }
}

/**
 * Turns finger drags into texture strokes that follow the model's surface. Each drag is sampled
 * along the screen so strokes bend with the surface; where a sample lands on another UV island the
 * seam is found by bisection, the stroke is brought up to the seam on one side and carries on from
 * the seam on the other, so lines run across seams without gaps or streaks through the texture.
 */
class SurfaceStroker(
    private val pick: (Float, Float) -> SurfaceHit?,
    private val sink: SurfaceSink,
    private val stepPx: Float = STEP_PX,
) {
    private var stroking = false
    private var last: SurfaceHit? = null
    private var lastX = 0f
    private var lastY = 0f
    private var lastPressure = 1f
    private var down = false

    fun down(
        x: Float,
        y: Float,
        pressure: Float,
    ) {
        down = true
        lastX = x
        lastY = y
        lastPressure = pressure
        visit(x, y, pressure)
    }

    fun move(
        x: Float,
        y: Float,
        pressure: Float,
    ) {
        if (!down) return down(x, y, pressure)
        val steps = ceil(hypot(x - lastX, y - lastY) / stepPx).toInt().coerceIn(1, MAX_STEPS)
        val fromX = lastX
        val fromY = lastY
        val fromPressure = lastPressure
        for (step in 1..steps) {
            val t = step.toFloat() / steps
            visit(fromX + (x - fromX) * t, fromY + (y - fromY) * t, fromPressure + (pressure - fromPressure) * t)
        }
    }

    fun up() {
        if (stroking) sink.end()
        stroking = false
        down = false
        last = null
    }

    private fun visit(
        x: Float,
        y: Float,
        pressure: Float,
    ) {
        val hit = pick(x, y)
        val previous = last
        when {
            hit == null -> {
                if (stroking) sink.end()
                stroking = false
            }

            !stroking || previous == null -> {
                sink.begin(hit.u, hit.v, pressure)
                stroking = true
            }

            hit.island == previous.island -> {
                sink.move(hit.u, hit.v, pressure)
            }

            else -> {
                crossSeam(previous.island, x, y, pressure)
                sink.move(hit.u, hit.v, pressure)
            }
        }
        last = hit
        lastX = x
        lastY = y
        lastPressure = pressure
    }

    /** Bisects between the last sample and ([x], [y]) to end the stroke at the seam and restart past it. */
    private fun crossSeam(
        island: Int,
        x: Float,
        y: Float,
        pressure: Float,
    ) {
        var insideX = lastX
        var insideY = lastY
        var outsideX = x
        var outsideY = y
        var inside: SurfaceHit? = null
        var outside: SurfaceHit? = null
        repeat(SEAM_ITERATIONS) {
            val midX = (insideX + outsideX) / 2f
            val midY = (insideY + outsideY) / 2f
            val mid = pick(midX, midY)
            if (mid != null && mid.island == island) {
                insideX = midX
                insideY = midY
                inside = mid
            } else {
                outsideX = midX
                outsideY = midY
                if (mid != null) outside = mid
            }
        }
        inside?.let { sink.move(it.u, it.v, pressure) }
        sink.end()
        val start = outside ?: pick(x, y) ?: return
        sink.begin(start.u, start.v, pressure)
    }

    private companion object {
        const val STEP_PX = 6f
        const val SEAM_ITERATIONS = 8

        // Each sample casts a ray through every triangle, so fast drags on dense models stay bounded.
        const val MAX_STEPS = 12
    }
}
