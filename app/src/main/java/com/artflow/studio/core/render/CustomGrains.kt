package com.artflow.studio.core.render

import com.artflow.studio.core.pixels.Channels
import com.artflow.studio.core.pixels.PixelBuffer
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Grain tiles imported from photos, like Procreate's Grain Source "Import photo". White paints and
 * black holds paint back. A brush whose imported tile is missing on this device paints without
 * grain rather than failing.
 */
object CustomGrains {
    const val TILE = 256
    const val PREFIX = "custom-"
    private val ID = Regex("custom-[0-9a-f]{1,32}")
    private val tiles = ConcurrentHashMap<String, Tile>()

    class Tile(
        val size: Int,
        val values: ByteArray,
    ) {
        init {
            require(size > 0 && values.size == size * size) { "A grain tile is square" }
        }

        fun at(
            x: Int,
            y: Int,
        ): Float = (values[Math.floorMod(y, size) * size + Math.floorMod(x, size)].toInt() and 0xFF) / 255f

        /** Bilinear coverage at (u, v) in 0..1 across the tile, transparent beyond its edges (for tips). */
        fun sample(
            u: Float,
            v: Float,
        ): Float {
            val fx = u * size - 0.5f
            val fy = v * size - 0.5f
            val x0 = floor(fx).toInt()
            val y0 = floor(fy).toInt()
            val tx = fx - x0
            val ty = fy - y0
            val top = edged(x0, y0) + (edged(x0 + 1, y0) - edged(x0, y0)) * tx
            val bottom = edged(x0, y0 + 1) + (edged(x0 + 1, y0 + 1) - edged(x0, y0 + 1)) * tx
            return top + (bottom - top) * ty
        }

        private fun edged(
            x: Int,
            y: Int,
        ): Float = if (x in 0 until size && y in 0 until size) (values[y * size + x].toInt() and 0xFF) / 255f else 0f
    }

    fun isCustom(id: String?): Boolean = id != null && ID.matches(id)

    fun register(
        id: String,
        tile: Tile,
    ) {
        require(isCustom(id)) { "Invalid grain identity" }
        tiles[id] = tile
    }

    fun get(id: String?): Tile? = id?.let(tiles::get)

    fun ids(): List<String> = tiles.keys.sorted()

    /** A stable identity for the tile's contents, so importing the same photo twice reuses it. */
    fun idFor(tile: Tile): String {
        var hash = -0x340d631b7bdddcdbL
        tile.values.forEach { value ->
            hash = (hash xor (value.toLong() and 0xFF)) * 0x100000001b3L
        }
        return PREFIX + java.lang.Long.toHexString(hash)
    }

    /**
     * Makes a grain tile from any image: the centred square is box-filtered down to [size], read as
     * brightness (transparent reads as black). Photos stretch to the full range by default;
     * authored brush masks disable [normalizeContrast] to preserve their coverage values.
     */
    fun tileFrom(
        image: PixelBuffer,
        size: Int = TILE,
        normalizeContrast: Boolean = true,
    ): Tile {
        require(size > 0) { "A grain tile must have positive size" }
        val side = min(image.width, image.height)
        val left = (image.width - side) / 2
        val top = (image.height - side) / 2
        val areaSampler = if (side > size && side % size != 0) AreaSampler(image, left, top, side, size) else null
        val levels = FloatArray(size * size)
        for (ty in 0 until size) {
            for (tx in 0 until size) {
                levels[ty * size + tx] =
                    areaSampler?.sample(tx, ty)
                        ?: averageBrightness(image, left + tx * side / size, top + ty * side / size, max(1, side / size))
            }
        }
        val low = if (normalizeContrast) levels.minOrNull() ?: 0f else 0f
        val range = if (normalizeContrast) ((levels.maxOrNull() ?: 1f) - low).takeIf { it > 1e-3f } ?: 1f else 1f
        val values = ByteArray(levels.size) { (((levels[it] - low) / range) * 255f + 0.5f).toInt().coerceIn(0, 255).toByte() }
        return Tile(size, values)
    }

    /** Fractional box boundaries use integer overlap weights, so no source edge is dropped. */
    private class AreaSampler(
        private val image: PixelBuffer,
        private val left: Int,
        private val top: Int,
        private val side: Int,
        private val size: Int,
    ) {
        fun sample(
            tx: Int,
            ty: Int,
        ): Float {
            // Coordinates are in units of 1 / size of a source pixel. Every destination box has
            // area side² in these units, including boxes crossing a source-pixel boundary.
            val x0 = tx.toLong() * side
            val x1 = (tx + 1L) * side
            val y0 = ty.toLong() * side
            val y1 = (ty + 1L) * side
            var total = 0.0
            for (y in (y0 / size).toInt()..((y1 - 1) / size).toInt()) {
                val weightY = min(y1, (y + 1L) * size) - max(y0, y.toLong() * size)
                for (x in (x0 / size).toInt()..((x1 - 1) / size).toInt()) {
                    val weightX = min(x1, (x + 1L) * size) - max(x0, x.toLong() * size)
                    val pixel = image.pixels[(top + y) * image.width + left + x]
                    val brightness = Channels.luminance(pixel) * Channels.alpha(pixel) / 255f
                    total += brightness.toDouble() * weightX * weightY
                }
            }
            return (total / (side.toDouble() * side)).toFloat()
        }
    }

    private fun averageBrightness(
        image: PixelBuffer,
        x0: Int,
        y0: Int,
        cell: Int,
    ): Float {
        var total = 0f
        var count = 0
        for (y in y0 until min(image.height, y0 + cell)) {
            for (x in x0 until min(image.width, x0 + cell)) {
                val pixel = image.pixels[y * image.width + x]
                total += Channels.luminance(pixel) * Channels.alpha(pixel) / 255f
                count++
            }
        }
        return if (count == 0) 0f else total / count
    }
}
