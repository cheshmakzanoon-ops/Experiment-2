package com.artflow.studio.core.three

import com.artflow.studio.core.pixels.PixelBuffer
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Gives a model with several materials one artwork: each material's texture gets a cell of a
 * grid, and the texture coordinates of that material's faces are moved into its cell, so every
 * material can be painted on its own part of the same canvas.
 */
object TextureAtlas {
    const val MAX_MATERIALS = 16
    private const val BLANK = 0xFFFFFFFF.toInt()

    class Packed(
        val objText: String,
        val image: PixelBuffer,
    )

    /** The materials [objText] assigns to faces (`usemtl`), in order of first use; "" for faces before any. */
    fun materials(objText: String): List<String> {
        val used = LinkedHashSet<String>()
        var current = ""
        objText.lineSequence().map { it.trim() }.forEach { line ->
            if (line.startsWith("usemtl ")) current = line.removePrefix("usemtl ").trim()
            if (line.startsWith("f ")) used += current
        }
        return used.toList()
    }

    /**
     * Packs [textures] (by material name; materials without one get a white cell) into a grid no
     * larger than [maxSize] on either side and rewrites [objText]'s faces to use it. Returns null
     * when there are fewer than two materials or too many to pack.
     */
    fun pack(
        objText: String,
        textures: Map<String, PixelBuffer>,
        maxSize: Int,
    ): Packed? {
        val materials = materials(objText)
        if (materials.size < 2 || materials.size > MAX_MATERIALS) return null
        val columns = ceil(sqrt(materials.size.toDouble())).toInt()
        val rows = (materials.size + columns - 1) / columns
        val widest = textures.values.maxOfOrNull { it.width } ?: maxSize
        val tallest = textures.values.maxOfOrNull { it.height } ?: maxSize
        val scale = min(1f, maxSize.toFloat() / max(widest * columns, tallest * rows))
        val cellWidth = max(1, (widest * scale).toInt())
        val cellHeight = max(1, (tallest * scale).toInt())
        val image = PixelBuffer(cellWidth * columns, cellHeight * rows)
        materials.forEachIndexed { index, name ->
            val left = (index % columns) * cellWidth
            val top = (index / columns) * cellHeight
            val texture =
                textures[name]?.let {
                    if (it.width == cellWidth &&
                        it.height == cellHeight
                    ) {
                        it
                    } else {
                        it.scaled(cellWidth, cellHeight)
                    }
                }
            for (y in 0 until cellHeight) {
                val row = (top + y) * image.width + left
                if (texture == null) {
                    image.pixels.fill(BLANK, row, row + cellWidth)
                } else {
                    System.arraycopy(texture.pixels, y * cellWidth, image.pixels, row, cellWidth)
                }
            }
        }
        return Packed(remap(objText, materials, columns, rows, Inset(cellWidth, cellHeight)), image)
    }

    /** Half a pixel inside each cell, so sampling never picks up the neighbouring texture. */
    private class Inset(
        width: Int,
        height: Int,
    ) {
        val u = 0.5f / width
        val v = 0.5f / height
    }

    /**
     * Rewrites each face corner's texture coordinate into its material's cell. The result keeps
     * every other record, then lists the new `vt` records, then the faces that use them.
     */
    private fun remap(
        objText: String,
        materials: List<String>,
        columns: Int,
        rows: Int,
        inset: Inset,
    ): String {
        val grid = Grid(columns, rows, inset)
        val head = StringBuilder()
        val faces = StringBuilder()
        var cell = materials.indexOf("")
        for (raw in objText.lineSequence()) {
            val line = raw.trim()
            when {
                line.startsWith("vt ") -> grid.coordinate(line)
                line.startsWith("f ") -> faces.append(grid.face(line, cell)).append('\n')
                else -> {
                    if (line.startsWith("usemtl ")) cell = materials.indexOf(line.removePrefix("usemtl ").trim())
                    head.append(raw).append('\n')
                }
            }
        }
        return head.append(grid.coordinates).append(faces).toString()
    }

    /** The original texture coordinates, and the new ones written for each face corner. */
    private class Grid(
        val columns: Int,
        val rows: Int,
        val inset: Inset,
    ) {
        private val uvs = ArrayList<Float>()
        val coordinates = StringBuilder()
        private var next = 1

        fun coordinate(line: String) {
            val parts = line.split(WHITESPACE)
            uvs += parts.getOrNull(1)?.toFloatOrNull() ?: 0f
            uvs += parts.getOrNull(2)?.toFloatOrNull() ?: 0f
        }

        /** [line] with every corner's texture coordinate moved into [cell]. */
        fun face(
            line: String,
            cell: Int,
        ): String =
            line.split(WHITESPACE).drop(1).joinToString(" ", prefix = "f ") { corner ->
                val fields = corner.split('/').toMutableList()
                val uv = fields.getOrNull(1)?.toIntOrNull()?.let { if (it < 0) uvs.size / 2 + it else it - 1 }
                if (uv != null && uv in 0 until uvs.size / 2 && cell >= 0) fields[1] = place(uv, cell).toString()
                fields.joinToString("/")
            }

        private fun place(
            uv: Int,
            cell: Int,
        ): Int {
            val u = (cell % columns + uvs[uv * 2].coerceIn(inset.u, 1f - inset.u)) / columns
            // OBJ's v runs up from the bottom; the first grid row is at the top of the image.
            val v = (rows - cell / columns - 1 + uvs[uv * 2 + 1].coerceIn(inset.v, 1f - inset.v)) / rows
            coordinates
                .append("vt ")
                .append(round(u))
                .append(' ')
                .append(round(v))
                .append('\n')
            return next++
        }
    }

    private fun round(value: Float): Float = (value * PRECISION).roundToInt() / PRECISION

    private const val PRECISION = 1_000_000f
    private val WHITESPACE = Regex("\\s+")
}
