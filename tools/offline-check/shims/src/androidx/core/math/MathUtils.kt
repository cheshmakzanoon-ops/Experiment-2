// Harness shim of androidx.core.math.MathUtils.
@file:Suppress("unused")

package androidx.core.math

object MathUtils {
    @JvmStatic
    fun clamp(
        value: Float,
        min: Float,
        max: Float,
    ): Float = if (value < min) min else if (value > max) max else value

    @JvmStatic
    fun clamp(
        value: Double,
        min: Double,
        max: Double,
    ): Double = if (value < min) min else if (value > max) max else value

    @JvmStatic
    fun clamp(
        value: Int,
        min: Int,
        max: Int,
    ): Int = if (value < min) min else if (value > max) max else value

    @JvmStatic
    fun clamp(
        value: Long,
        min: Long,
        max: Long,
    ): Long = if (value < min) min else if (value > max) max else value

    @JvmStatic
    fun clamp(
        value: Long,
        min: Int,
        max: Int,
    ): Int = if (value < min) min else if (value > max) max else value.toInt()
}
