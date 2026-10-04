package com.artflow.studio.core.three

import kotlin.math.sqrt

/** A small 3D vector for the camera and picking maths. */
data class Vec3(
    val x: Float,
    val y: Float,
    val z: Float,
) {
    operator fun plus(other: Vec3) = Vec3(x + other.x, y + other.y, z + other.z)

    operator fun minus(other: Vec3) = Vec3(x - other.x, y - other.y, z - other.z)

    operator fun times(scale: Float) = Vec3(x * scale, y * scale, z * scale)

    fun dot(other: Vec3): Float = x * other.x + y * other.y + z * other.z

    fun length(): Float = sqrt(dot(this))

    fun normalised(): Vec3 = length().let { if (it > 0f) this * (1f / it) else this }

    companion object {
        fun cross(
            a: Vec3,
            b: Vec3,
        ) = Vec3(a.y * b.z - a.z * b.y, a.z * b.x - a.x * b.z, a.x * b.y - a.y * b.x)

        fun at(
            values: FloatArray,
            offset: Int,
        ) = Vec3(values[offset], values[offset + 1], values[offset + 2])
    }
}
