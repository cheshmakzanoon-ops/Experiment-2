package com.artflow.studio.data.local

import com.artflow.studio.core.export.AbrReader
import com.artflow.studio.core.pixels.Channels
import com.artflow.studio.core.render.CustomGrains
import kotlin.math.max
import kotlin.math.min

/** Samples the same centered, black-padded square without allocating its empty pixels. */
internal object PaddedBrushTip {
    fun tile(tip: AbrReader.Tip): CustomGrains.Tile {
        val sampler = Sampler(tip)
        val values =
            ByteArray(CustomGrains.TILE * CustomGrains.TILE) { index ->
                val coverage = sampler.sample(index % CustomGrains.TILE, index / CustomGrains.TILE)
                (coverage * 255f + 0.5f).toInt().coerceIn(0, 255).toByte()
            }
        return CustomGrains.Tile(CustomGrains.TILE, values)
    }

    private class Sampler(
        private val tip: AbrReader.Tip,
    ) {
        private val side = max(tip.width, tip.height)
        private val left = (side - tip.width) / 2
        private val top = (side - tip.height) / 2
        private val size = CustomGrains.TILE

        fun sample(
            tx: Int,
            ty: Int,
        ): Float = if (side > size && side % size != 0) fractional(tx, ty) else integer(tx, ty)

        private fun integer(
            tx: Int,
            ty: Int,
        ): Float {
            val x0 = tx * side / size
            val y0 = ty * side / size
            val cell = max(1, side / size)
            var total = 0f
            for (y in max(top, y0) until min(top + tip.height, y0 + cell)) {
                for (x in max(left, x0) until min(left + tip.width, x0 + cell)) total += brightness(x, y)
            }
            return total / (cell * cell)
        }

        private fun fractional(
            tx: Int,
            ty: Int,
        ): Float {
            val x0 = tx.toLong() * side
            val x1 = (tx + 1L) * side
            val y0 = ty.toLong() * side
            val y1 = (ty + 1L) * side
            var total = 0.0
            for (y in max(top, (y0 / size).toInt())..min(top + tip.height - 1, ((y1 - 1) / size).toInt())) {
                val weightY = min(y1, (y + 1L) * size) - max(y0, y.toLong() * size)
                for (x in max(left, (x0 / size).toInt())..min(left + tip.width - 1, ((x1 - 1) / size).toInt())) {
                    val weightX = min(x1, (x + 1L) * size) - max(x0, x.toLong() * size)
                    total += brightness(x, y).toDouble() * weightX * weightY
                }
            }
            return (total / (side.toDouble() * side)).toFloat()
        }

        private fun brightness(
            x: Int,
            y: Int,
        ): Float {
            val value = tip.values[(y - top) * tip.width + x - left].toInt() and 0xFF
            val pixel = 0xFF000000.toInt() or (value shl 16) or (value shl 8) or value
            // Preserve the original grayscale luminance and alpha rounding exactly.
            return Channels.luminance(pixel) * Channels.alpha(pixel) / 255f
        }
    }
}
