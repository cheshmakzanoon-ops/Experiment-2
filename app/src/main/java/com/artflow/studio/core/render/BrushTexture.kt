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
        }
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
        fun from(params: BrushParams): BrushTexture? {
            if (!params.blendTexture) return null
            val kind = Kind.entries.firstOrNull { it.id == params.textureId } ?: return null
            require(params.textureScale.isFinite() && params.textureRotation.isFinite()) { "Texture settings must be finite" }
            return BrushTexture(kind, params.textureScale.coerceIn(0.25f, 8f), params.textureRotation % 360f)
        }
    }
}
