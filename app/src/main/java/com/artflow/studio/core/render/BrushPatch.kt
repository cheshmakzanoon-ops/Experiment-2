package com.artflow.studio.core.render

import com.artflow.studio.core.pixels.Stamping
import com.artflow.studio.domain.model.brush.BrushParams
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** The selected brush's tip and grain for patch tools such as Smudge, which carry its texture in Procreate. */
object BrushPatch {
    fun texture(params: BrushParams): Stamping.PatchTexture {
        val tile = CustomGrains.get(params.shapeId)
        val roundness = params.roundness.coerceIn(MIN_ROUNDNESS, 1f)
        val radians = Math.toRadians(params.rotation.toDouble())
        val cosA = cos(radians).toFloat()
        val sinA = sin(radians).toFloat()
        val shape: ((Float, Float) -> Float)? =
            if (tile == null && roundness >= ROUND) {
                null
            } else {
                { u, v ->
                    // Into the tip's own frame: rotated, and squashed across its short axis.
                    val x = u * cosA + v * sinA
                    val y = (-u * sinA + v * cosA) / roundness
                    when {
                        tile != null -> if (x in -1f..1f && y in -1f..1f) tile.sample((x + 1f) / 2f, (y + 1f) / 2f) else 0f
                        else -> ((1f - sqrt(x * x + y * y)) / SOFT_EDGE).coerceIn(0f, 1f)
                    }
                }
            }
        val grain = BrushTexture.from(params)?.let { texture -> { x: Int, y: Int -> texture.coverage(x, y) } }
        return Stamping.PatchTexture(shape, grain)
    }

    private const val MIN_ROUNDNESS = 0.05f
    private const val ROUND = 0.999f
    private const val SOFT_EDGE = 0.4f
}
