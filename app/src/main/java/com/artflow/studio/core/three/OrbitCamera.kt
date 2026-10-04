package com.artflow.studio.core.three

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/** A camera circling the model's centre; drags turn it and pinches move it nearer or farther. */
data class OrbitCamera(
    val yaw: Float = 0.6f,
    val pitch: Float = 0.3f,
    val distance: Float = 3.2f,
    val fieldOfViewDegrees: Float = 45f,
) {
    val eye: Vec3
        get() = Vec3(cos(pitch) * sin(yaw), sin(pitch), cos(pitch) * cos(yaw)) * distance

    fun turned(
        dYaw: Float,
        dPitch: Float,
    ) = copy(yaw = yaw + dYaw, pitch = (pitch + dPitch).coerceIn(-MAX_PITCH, MAX_PITCH))

    fun zoomed(factor: Float) = copy(distance = (distance / factor).coerceIn(MIN_DISTANCE, MAX_DISTANCE))

    /** The ray from the eye through a point on a [width] × [height] view, as (origin, direction). */
    fun ray(
        x: Float,
        y: Float,
        width: Float,
        height: Float,
    ): Pair<Vec3, Vec3> {
        val forward = (Vec3(0f, 0f, 0f) - eye).normalised()
        val right = Vec3.cross(forward, UP).normalised()
        val up = Vec3.cross(right, forward)
        val half = tan(fieldOfViewDegrees * PI.toFloat() / 360f)
        val ndcX = 2f * x / width - 1f
        val ndcY = 1f - 2f * y / height
        val direction = (forward + right * (ndcX * half * width / height) + up * (ndcY * half)).normalised()
        return eye to direction
    }

    companion object {
        private val UP = Vec3(0f, 1f, 0f)
        private const val MAX_PITCH = 1.45f
        private const val MIN_DISTANCE = 1.2f
        private const val MAX_DISTANCE = 12f
    }
}

/** Finds where a ray meets the mesh and the texture coordinate there (Möller–Trumbore). */
object MeshPicker {
    private const val EPSILON = 1e-7f

    /** (u, v) at the nearest hit, or null when the ray misses. */
    fun pick(
        mesh: Mesh,
        origin: Vec3,
        direction: Vec3,
    ): Pair<Float, Float>? = hit(mesh, origin, direction)?.let { it.u to it.v }

    /** The nearest point the ray hits, with its UV island, or null when it misses. */
    fun hit(
        mesh: Mesh,
        origin: Vec3,
        direction: Vec3,
    ): SurfaceHit? {
        var nearest = Float.MAX_VALUE
        var hitU = 0f
        var hitV = 0f
        var hitTriangle = -1
        val p = mesh.positions
        for (t in 0 until mesh.triangleCount) {
            val a = Vec3.at(p, t * 9)
            val edge1 = Vec3.at(p, t * 9 + 3) - a
            val edge2 = Vec3.at(p, t * 9 + 6) - a
            val h = Vec3.cross(direction, edge2)
            val det = edge1.dot(h)
            if (det > -EPSILON && det < EPSILON) continue
            val inverse = 1f / det
            val s = origin - a
            val b1 = inverse * s.dot(h)
            val q = Vec3.cross(s, edge1)
            val b2 = inverse * direction.dot(q)
            val distance = inverse * edge2.dot(q)
            val inside = b1 >= 0f && b2 >= 0f && b1 + b2 <= 1f
            if (inside && distance > EPSILON && distance < nearest) {
                nearest = distance
                val uv = mesh.uvs
                val w0 = 1f - b1 - b2
                hitU = w0 * uv[t * 6] + b1 * uv[t * 6 + 2] + b2 * uv[t * 6 + 4]
                hitV = w0 * uv[t * 6 + 1] + b1 * uv[t * 6 + 3] + b2 * uv[t * 6 + 5]
                hitTriangle = t
            }
        }
        return if (hitTriangle >= 0) SurfaceHit(hitU, hitV, mesh.islands[hitTriangle]) else null
    }
}
