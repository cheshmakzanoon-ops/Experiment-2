package com.artflow.studio.core.three

import kotlin.math.max
import kotlin.math.sqrt

/**
 * A triangle mesh ready to draw and paint: three corners per triangle, each with a position, a
 * normal and a texture coordinate. Positions are centred and scaled to fit a unit sphere.
 */
class Mesh(
    /** x, y, z per corner. */
    val positions: FloatArray,
    /** x, y, z per corner. */
    val normals: FloatArray,
    /** u, v per corner; v = 0 is the bottom of the texture, as in OBJ files. */
    val uvs: FloatArray,
) {
    val triangleCount: Int get() = positions.size / 9
}

/** Reads Wavefront OBJ text: positions, texture coordinates and normals; faces are fanned into triangles. */
object ObjParser {
    const val MAX_TRIANGLES = 300_000

    fun parse(text: String): Mesh {
        val positions = ArrayList<Float>()
        val uvs = ArrayList<Float>()
        val normals = ArrayList<Float>()
        val corners = ArrayList<IntArray>()
        text.lineSequence().forEach { raw ->
            val parts = raw.trim().split(WHITESPACE)
            when (parts.firstOrNull()) {
                "v" -> {
                    parts.drop(1).take(3).forEach { positions += number(it) }
                }

                "vt" -> {
                    parts.drop(1).take(2).forEach { uvs += number(it) }
                }

                "vn" -> {
                    parts.drop(1).take(3).forEach { normals += number(it) }
                }

                "f" -> {
                    val face = parts.drop(1).map { corner(it, positions.size / 3, uvs.size / 2, normals.size / 3) }
                    require(face.size >= 3) { "A face needs at least three corners" }
                    for (i in 1 until face.size - 1) {
                        corners += face[0]
                        corners += face[i]
                        corners += face[i + 1]
                    }
                    require(corners.size / 3 <= MAX_TRIANGLES) { "This model has too many triangles" }
                }
            }
        }
        require(corners.isNotEmpty()) { "This file has no faces" }
        require(corners.all { it[1] >= 0 }) { "This model has no texture coordinates (UVs) to paint on" }
        return build(positions, uvs, normals, corners)
    }

    private fun build(
        positions: List<Float>,
        uvs: List<Float>,
        normals: List<Float>,
        corners: List<IntArray>,
    ): Mesh {
        val count = corners.size
        val outPositions = FloatArray(count * 3)
        val outUvs = FloatArray(count * 2)
        val outNormals = FloatArray(count * 3)
        corners.forEachIndexed { index, (p, t, n) ->
            for (axis in 0 until 3) outPositions[index * 3 + axis] = positions[p * 3 + axis]
            outUvs[index * 2] = uvs[t * 2]
            outUvs[index * 2 + 1] = uvs[t * 2 + 1]
            if (n >= 0) for (axis in 0 until 3) outNormals[index * 3 + axis] = normals[n * 3 + axis]
        }
        if (corners.any { it[2] < 0 }) faceNormals(outPositions, outNormals)
        normalise(outPositions)
        return Mesh(outPositions, outNormals, outUvs)
    }

    /** One corner `v`, `v/vt`, `v//vn` or `v/vt/vn`; indices are 1-based, or negative from the end. */
    private fun corner(
        token: String,
        positions: Int,
        uvs: Int,
        normals: Int,
    ): IntArray {
        val fields = token.split('/')

        fun index(
            field: String?,
            available: Int,
        ): Int {
            if (field.isNullOrEmpty()) return -1
            val value = field.toInt()
            val resolved = if (value < 0) available + value else value - 1
            require(resolved in 0 until available) { "A face refers to a missing vertex" }
            return resolved
        }
        return intArrayOf(index(fields[0], positions), index(fields.getOrNull(1), uvs), index(fields.getOrNull(2), normals))
    }

    private fun number(token: String): Float = token.toFloat().also { require(it.isFinite()) { "Invalid number in the model" } }

    private fun faceNormals(
        positions: FloatArray,
        normals: FloatArray,
    ) {
        for (t in 0 until positions.size / 9) {
            val base = t * 9
            val n =
                Vec3
                    .cross(
                        Vec3.at(positions, base + 3) - Vec3.at(positions, base),
                        Vec3.at(positions, base + 6) - Vec3.at(positions, base),
                    ).normalised()
            for (corner in 0 until 3) {
                normals[base + corner * 3] = n.x
                normals[base + corner * 3 + 1] = n.y
                normals[base + corner * 3 + 2] = n.z
            }
        }
    }

    /** Centres the model and scales it to fit a unit sphere, so every model opens framed alike. */
    private fun normalise(positions: FloatArray) {
        val min = FloatArray(3) { Float.MAX_VALUE }
        val max = FloatArray(3) { -Float.MAX_VALUE }
        for (i in positions.indices) {
            min[i % 3] = minOf(min[i % 3], positions[i])
            max[i % 3] = maxOf(max[i % 3], positions[i])
        }
        val centre = FloatArray(3) { (min[it] + max[it]) / 2f }
        var radius = 0f
        for (i in positions.indices step 3) {
            val dx = positions[i] - centre[0]
            val dy = positions[i + 1] - centre[1]
            val dz = positions[i + 2] - centre[2]
            radius = max(radius, sqrt(dx * dx + dy * dy + dz * dz))
        }
        val scale = if (radius > 0f) 1f / radius else 1f
        for (i in positions.indices) positions[i] = (positions[i] - centre[i % 3]) * scale
    }

    private val WHITESPACE = Regex("\\s+")
}
