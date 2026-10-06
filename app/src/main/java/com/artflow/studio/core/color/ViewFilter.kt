package com.artflow.studio.core.color

/**
 * On-screen checks that never touch the artwork: Greyscale shows values alone (is the picture
 * readable without colour?), and the three colour-vision filters show it as people with
 * protanopia, deuteranopia or tritanopia see it (Machado, Oliveira and Fernandes 2009, full
 * severity). Each is a 3×3 matrix on linear sRGB, row-major; the display shader applies the same.
 */
enum class ViewFilter(
    val displayName: String,
    val matrix: FloatArray?,
) {
    OFF("Off", null),
    GREYSCALE("Greyscale", GREY),
    PROTANOPIA("Protanopia", PROTAN),
    DEUTERANOPIA("Deuteranopia", DEUTAN),
    TRITANOPIA("Tritanopia", TRITAN),
    ;

    /** An encoded sRGB colour (0..1 per channel) as this filter shows it, encoded sRGB. */
    fun apply(
        r: Float,
        g: Float,
        b: Float,
    ): FloatArray {
        val m = matrix ?: return floatArrayOf(r, g, b)
        val linear = floatArrayOf(ColorProfiles.toLinear(r), ColorProfiles.toLinear(g), ColorProfiles.toLinear(b))
        return FloatArray(3) { row ->
            val value = m[row * 3] * linear[0] + m[row * 3 + 1] * linear[1] + m[row * 3 + 2] * linear[2]
            ColorProfiles.fromLinear(value.coerceIn(0f, 1f))
        }
    }

    /** [matrix] in the column-major order OpenGL expects, or the identity when off. */
    fun columnMajor(): FloatArray {
        val m = matrix ?: return floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        return FloatArray(9) { i -> m[(i % 3) * 3 + i / 3] }
    }
}

private val GREY = floatArrayOf(0.2126f, 0.7152f, 0.0722f, 0.2126f, 0.7152f, 0.0722f, 0.2126f, 0.7152f, 0.0722f)
private val PROTAN = floatArrayOf(0.152286f, 1.052583f, -0.204868f, 0.114503f, 0.786281f, 0.099216f, -0.003882f, -0.048116f, 1.051998f)
private val DEUTAN = floatArrayOf(0.367322f, 0.860646f, -0.227968f, 0.280085f, 0.672501f, 0.047413f, -0.01182f, 0.04294f, 0.968881f)
private val TRITAN = floatArrayOf(1.255528f, -0.076749f, -0.178779f, -0.078411f, 0.930809f, 0.147602f, 0.004733f, 0.691367f, 0.3039f)
