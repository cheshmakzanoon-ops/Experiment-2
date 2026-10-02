package com.artflow.studio.core.render

import com.artflow.studio.domain.model.brush.BrushParams
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * Original procedural brush grains. Sampling is anchored to canvas pixels, not dab order, time,
 * device RNG or thread: overlapping dabs cannot fill the grain and saved strokes replay exactly.
 * Unrecognized legacy texture IDs remain neutral rather than silently changing old documents.
 */
class BrushTexture private constructor(
    private val kind: Kind,
    scale: Float,
    rotation: Float,
) {
    enum class Kind(
        val id: String,
        val label: String,
    ) {
        PAPER("paper", "Paper"),
        CANVAS("canvas", "Canvas"),
        CHARCOAL("charcoal", "Charcoal"),
        FINE("fine", "Fine grain"),
        BLOTCH("blotch", "Watercolour"),
        BRISTLE("bristle", "Bristles"),
        HALFTONE("halftone", "Halftone"),
        HATCH("hatch", "Hatching"),
        SPECKLE("speckle", "Spray"),
    }

    private val radians = Math.toRadians(rotation.toDouble())
    private val cosine = cos(radians).toFloat() / scale
    private val sine = sin(radians).toFloat() / scale

    fun coverage(
        x: Int,
        y: Int,
    ): Float {
        val u = (x + 0.5f) * cosine + (y + 0.5f) * sine
        val v = -(x + 0.5f) * sine + (y + 0.5f) * cosine
        val column = floor(u).toInt()
        val row = floor(v).toInt()
        return when (kind) {
            Kind.PAPER -> 0.35f + 0.65f * noise(column, row)
            Kind.CANVAS -> {
                val horizontal = Math.floorMod(row, 6) < 2
                val vertical = Math.floorMod(column, 6) < 2
                if (horizontal && vertical) {
                    0.3f
                } else if (horizontal || vertical) {
                    0.6f
                } else {
                    1f
                }
            }
            Kind.CHARCOAL -> {
                val grain = noise(Math.floorDiv(column, 2), Math.floorDiv(row, 2))
                ((grain - 0.25f) / 0.75f).coerceIn(0f, 1f)
            }
            Kind.FINE -> 0.55f + 0.45f * noise(column, row)
            Kind.BLOTCH -> 0.3f + 0.7f * smoothNoise(u / BLOTCH_CELL, v / BLOTCH_CELL)
            Kind.BRISTLE -> {
                // Long streaks along u: each row band gets its own density, broken slightly along its length.
                val band = noise(row, 7)
                val breakup = noise(Math.floorDiv(column, 12), row)
                (0.15f + 0.85f * band * (0.7f + 0.3f * breakup)).coerceIn(0f, 1f)
            }
            Kind.HALFTONE -> {
                val cx = Math.floorMod(column, HALFTONE_CELL) - HALFTONE_CELL / 2f + 0.5f
                val cy = Math.floorMod(row, HALFTONE_CELL) - HALFTONE_CELL / 2f + 0.5f
                if (cx * cx + cy * cy <= HALFTONE_RADIUS * HALFTONE_RADIUS) 1f else 0.08f
            }
            Kind.HATCH -> if (Math.floorMod(column + row, 5) < 2 || Math.floorMod(column - row, 9) == 0) 1f else 0.15f
            Kind.SPECKLE -> if (noise(column, row) > 0.62f) 1f else 0.05f
        }
    }

    /** Bilinearly interpolated lattice noise: soft, low-frequency blotches. */
    private fun smoothNoise(
        u: Float,
        v: Float,
    ): Float {
        val x0 = floor(u).toInt()
        val y0 = floor(v).toInt()
        val fx = u - x0
        val fy = v - y0
        val sx = fx * fx * (3f - 2f * fx)
        val sy = fy * fy * (3f - 2f * fy)
        val top = noise(x0, y0) + (noise(x0 + 1, y0) - noise(x0, y0)) * sx
        val bottom = noise(x0, y0 + 1) + (noise(x0 + 1, y0 + 1) - noise(x0, y0 + 1)) * sx
        return top + (bottom - top) * sy
    }

    private fun noise(
        x: Int,
        y: Int,
    ): Float {
        // Overflow is intentional: a stable integer hash, not nondeterministic floating-point noise.
        var hash = x * 374761393 + y * 668265263 + 1442695041
        hash = (hash xor (hash ushr 13)) * 1274126177
        hash = hash xor (hash ushr 16)
        return (hash and 0xFFFF) / 65535f
    }

    companion object {
        private const val BLOTCH_CELL = 14f
        private const val HALFTONE_CELL = 6
        private const val HALFTONE_RADIUS = 2.2f

        fun from(params: BrushParams): BrushTexture? {
            if (!params.blendTexture) return null
            val kind = Kind.entries.firstOrNull { it.id == params.textureId } ?: return null
            require(params.textureScale.isFinite() && params.textureRotation.isFinite()) { "Texture settings must be finite" }
            return BrushTexture(kind, params.textureScale.coerceIn(0.25f, 8f), params.textureRotation % 360f)
        }
    }
}
