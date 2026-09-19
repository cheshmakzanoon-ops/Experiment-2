package com.artflow.studio.core.canvas

import com.artflow.studio.core.pixels.PixelBuffer
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/** Canvas-sized, inverse-mapped affine resampling. No Android types or document mutations. */
object LayerTransform {
    enum class Interpolation(
        val label: String,
    ) {
        BILINEAR("Smooth"),
        NEAREST("Pixel art"),
    }

    data class Parameters(
        val translationX: Float = 0f,
        val translationY: Float = 0f,
        val scaleX: Float = 1f,
        val scaleY: Float = 1f,
        val rotationDegrees: Float = 0f,
        val skewXDegrees: Float = 0f,
        val flipHorizontal: Boolean = false,
        val flipVertical: Boolean = false,
        val interpolation: Interpolation = Interpolation.BILINEAR,
    ) {
        init {
            require(translationX.isFinite() && translationY.isFinite()) { "Translation must be finite" }
            require(scaleX.isFinite() && scaleX in 0.01f..16f) { "Width must be between 1% and 1600%" }
            require(scaleY.isFinite() && scaleY in 0.01f..16f) { "Height must be between 1% and 1600%" }
            require(rotationDegrees.isFinite()) { "Rotation must be finite" }
            require(skewXDegrees.isFinite() && skewXDegrees in -80f..80f) { "Skew must be between -80 and 80 degrees" }
        }

        val isIdentity: Boolean
            get() =
                translationX == 0f &&
                    translationY == 0f &&
                    scaleX == 1f &&
                    scaleY == 1f &&
                    rotationDegrees % 360f == 0f &&
                    skewXDegrees == 0f &&
                    !flipHorizontal &&
                    !flipVertical
    }

    /**
     * Scale/flip, horizontal skew and clockwise rotation about the SAME pixel-edge pivot, then move.
     * The output remains canvas-sized; outside samples are transparent and off-canvas pixels clip.
     * One resampling pass avoids progressive degradation while adjusting several parameters.
     */
    fun apply(
        source: PixelBuffer,
        parameters: Parameters,
        pivotX: Float = source.width / 2f,
        pivotY: Float = source.height / 2f,
        checkActive: () -> Unit = {},
    ): PixelBuffer {
        require(pivotX.isFinite() && pivotY.isFinite()) { "Pivot must be finite" }
        checkActive()
        if (parameters.isIdentity) return source.copy()
        val radians = Math.toRadians((parameters.rotationDegrees % 360f).toDouble())
        val cosine = snapTrig(cos(radians))
        val sine = snapTrig(sin(radians))
        val shear = tan(Math.toRadians(parameters.skewXDegrees.toDouble()))
        val sx = parameters.scaleX.toDouble() * if (parameters.flipHorizontal) -1 else 1
        val sy = parameters.scaleY.toDouble() * if (parameters.flipVertical) -1 else 1
        val a = cosine * sx
        val b = sine * sx
        val c = (cosine * shear - sine) * sy
        val d = (sine * shear + cosine) * sy
        val determinant = a * d - b * c
        val out = PixelBuffer(source.width, source.height)
        for (y in 0 until out.height) {
            checkActive()
            val relativeY = y + 0.5 - pivotY - parameters.translationY
            for (x in 0 until out.width) {
                val relativeX = x + 0.5 - pivotX - parameters.translationX
                val sourceX = ((d * relativeX - c * relativeY) / determinant + pivotX).toFloat()
                val sourceY = ((a * relativeY - b * relativeX) / determinant + pivotY).toFloat()
                // Huge, but finite, translations may overflow when converted back to Float.
                if (!sourceX.isFinite() || !sourceY.isFinite()) continue
                out.setUnchecked(
                    x,
                    y,
                    when (parameters.interpolation) {
                        Interpolation.BILINEAR -> source.sampleBilinear(sourceX, sourceY)
                        Interpolation.NEAREST -> source.sampleNearest(sourceX, sourceY)
                    },
                )
            }
        }
        return out
    }

    /** Exact quarter-turns must not acquire sampling blur from floating point trigonometry. */
    private fun snapTrig(value: Double): Double =
        when {
            abs(value) < 1e-12 -> 0.0
            abs(value - 1.0) < 1e-12 -> 1.0
            abs(value + 1.0) < 1e-12 -> -1.0
            else -> value
        }
}
