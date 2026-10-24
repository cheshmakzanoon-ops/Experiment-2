package com.artflow.studio.core.three

import com.artflow.studio.core.pixels.PixelBuffer
import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

/** Independently editable texture planes. Scalar values are data, never colour-profile converted. */
@Serializable
enum class MaterialChannel(
    val displayName: String,
    val defaultValue: Float,
) {
    COLOR("Color", 0f),
    METALLIC("Metallic", 0f),
    ROUGHNESS("Roughness", 0.6f),
    ;

    val isScalar: Boolean get() = this != COLOR

    fun defaultColor(): Int = gray(defaultValue)

    companion object {
        fun gray(value: Float): Int {
            val channel = ((if (value.isFinite()) value else 0f).coerceIn(0f, 1f) * 255f).roundToInt()
            return (255 shl 24) or (channel shl 16) or (channel shl 8) or channel
        }

        /** Converts a composed map to neutral data without changing coverage or mutating its source. */
        fun grayscale(source: PixelBuffer): PixelBuffer =
            PixelBuffer(
                source.width,
                source.height,
                IntArray(source.pixels.size) { index ->
                    val pixel = source.pixels[index]
                    val value = ((pixel ushr 16 and 255) * 54 + (pixel ushr 8 and 255) * 183 + (pixel and 255) * 19 + 128) / 256
                    (pixel and (255 shl 24)) or (value shl 16) or (value shl 8) or value
                },
            )

        /** Opaque map for interchange: uncovered pixels receive the documented channel default. */
        fun resolved(
            source: PixelBuffer,
            channel: MaterialChannel,
        ): PixelBuffer {
            require(channel.isScalar) { "Only material data maps have a scalar default" }
            val neutral = grayscale(source)
            val fallback = channel.defaultColor() and 255
            neutral.pixels.indices.forEach { index ->
                val pixel = neutral.pixels[index]
                val alpha = pixel ushr 24
                val value = ((pixel and 255) * alpha + fallback * (255 - alpha) + 127) / 255
                neutral.pixels[index] = (255 shl 24) or (value shl 16) or (value shl 8) or value
            }
            return neutral
        }
    }
}
