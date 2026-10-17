package com.artflow.studio.core.pixels

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Convolution and effect filters (Phase 29: Filter Layers).
 *
 * All filters operate on [PixelBuffer] with **premultiplied** intermediate maths. Blurring
 * unpremultiplied colour bleeds invisible pixels into their neighbours, which shows up as dark
 * or coloured halos around soft edges; working premultiplied and dividing the alpha back out at
 * the end avoids that entirely.
 *
 * Pure Kotlin: no `android.graphics`, so every filter is unit-testable on the JVM.
 */
object ImageFilters {
    /**
     * Separable Gaussian blur.
     *
     * @param radius standard deviation in pixels; `0` returns a copy unchanged.
     * @param checkpoint called once per row as the blur runs; a throw from it abandons the blur.
     */
    fun gaussianBlur(
        source: PixelBuffer,
        radius: Float,
        checkpoint: () -> Unit = {},
    ): PixelBuffer {
        if (radius <= 0.01f) return source.copy()
        val kernel = gaussianKernel(radius)
        return separableConvolve(source, kernel, checkpoint)
    }

    /** Fast approximate blur with a repeated box filter (used for large radii and mask feathering). */
    fun boxBlur(
        source: PixelBuffer,
        radius: Int,
    ): PixelBuffer {
        if (radius <= 0) return source.copy()
        val kernel = FloatArray(radius * 2 + 1) { 1f / (radius * 2 + 1) }
        return separableConvolve(source, kernel)
    }

    /**
     * Directional motion blur. [angleDegrees] is measured clockwise from the +x axis. [checkpoint] runs once per row,
     * so a throw from it abandons the blur part way through.
     */
    fun motionBlur(
        source: PixelBuffer,
        distance: Float,
        angleDegrees: Float,
        checkpoint: () -> Unit = {},
    ): PixelBuffer {
        if (distance <= 0.5f) return source.copy()
        val steps = max(2, distance.roundToInt())
        val radians = Math.toRadians(angleDegrees.toDouble())
        val dx = cos(radians).toFloat()
        val dy = sin(radians).toFloat()

        val out = PixelBuffer(source.width, source.height)
        val half = distance / 2f
        for (y in 0 until source.height) {
            checkpoint()
            for (x in 0 until source.width) {
                var a = 0f
                var r = 0f
                var g = 0f
                var b = 0f
                for (i in 0 until steps) {
                    val t = -half + (distance * i) / (steps - 1)
                    val sx = x + dx * t
                    val sy = y + dy * t
                    val sample = source.sampleBilinear(sx + 0.5f, sy + 0.5f)
                    val sa = Channels.alpha(sample) / 255f
                    a += sa
                    r += Channels.red(sample) / 255f * sa
                    g += Channels.green(sample) / 255f * sa
                    b += Channels.blue(sample) / 255f * sa
                }
                out.pixels[y * source.width + x] = unpremultiply(a / steps, r / steps, g / steps, b / steps)
            }
        }
        return out
    }

    /**
     * Unsharp-mask sharpening. Positive [amount] sharpens, negative blurs.
     * @param amount 0..5, where 1 is a subtle sharpen.
     */
    fun sharpen(
        source: PixelBuffer,
        amount: Float,
        radius: Float = 1.2f,
    ): PixelBuffer {
        if (abs(amount) < 0.001f) return source.copy()
        val blurred = gaussianBlur(source, radius)
        val out = source.copy()
        for (i in out.pixels.indices) {
            val s = source.pixels[i]
            val bl = blurred.pixels[i]
            // Only sharpen where there is something to sharpen; transparent pixels stay clear.
            val sa = Channels.alpha(s) / 255f
            if (sa <= 0f) continue
            val r = clampByte(Channels.red(s) + amount * (Channels.red(s) - Channels.red(bl)))
            val g = clampByte(Channels.green(s) + amount * (Channels.green(s) - Channels.green(bl)))
            val b = clampByte(Channels.blue(s) + amount * (Channels.blue(s) - Channels.blue(bl)))
            out.pixels[i] = Channels.argb(Channels.alpha(s).toInt(), r, g, b)
        }
        return out
    }

    /**
     * Additive noise. [seed] keeps the result deterministic, which matters for tests and for the
     * live preview matching the committed pixels.
     */
    fun addNoise(
        source: PixelBuffer,
        amount: Float,
        monochrome: Boolean = true,
        seed: Long = 1234L,
    ): PixelBuffer {
        if (amount <= 0f) return source.copy()
        val out = source.copy()
        val random = java.util.Random(seed)
        val scale = amount * 255f
        for (i in out.pixels.indices) {
            val pixel = source.pixels[i]
            if ((pixel ushr 24) == 0) continue
            val noise = (random.nextFloat() - 0.5f) * scale
            val nr = if (monochrome) noise else (random.nextFloat() - 0.5f) * scale
            val ng = if (monochrome) noise else (random.nextFloat() - 0.5f) * scale
            val nb = if (monochrome) noise else (random.nextFloat() - 0.5f) * scale
            out.pixels[i] =
                Channels.argb(
                    Channels.alpha(pixel).toInt(),
                    clampByte(Channels.red(pixel) + nr),
                    clampByte(Channels.green(pixel) + ng),
                    clampByte(Channels.blue(pixel) + nb),
                )
        }
        return out
    }

    /** Lens-style chromatic aberration: the red and blue channels are scaled apart radially. */
    fun chromaticAberration(
        source: PixelBuffer,
        amount: Float,
        centerX: Float,
        centerY: Float,
    ): PixelBuffer {
        if (abs(amount) < 0.001f) return source.copy()
        val out = PixelBuffer(source.width, source.height)
        val shift = amount.coerceIn(-0.05f, 0.05f)
        for (y in 0 until source.height) {
            for (x in 0 until source.width) {
                val dx = x - centerX
                val dy = y - centerY
                val rSample =
                    source.sampleBilinear(
                        centerX + dx * (1f + shift) + 0.5f,
                        centerY + dy * (1f + shift) + 0.5f,
                    )
                val gSample = source.sampleBilinear(x + 0.5f, y + 0.5f)
                val bSample =
                    source.sampleBilinear(
                        centerX + dx * (1f - shift) + 0.5f,
                        centerY + dy * (1f - shift) + 0.5f,
                    )
                val alpha = Channels.alpha(gSample).toInt()
                out.pixels[y * source.width + x] =
                    Channels.argb(
                        alpha,
                        Channels.red(rSample).toInt(),
                        Channels.green(gSample).toInt(),
                        Channels.blue(bSample).toInt(),
                    )
            }
        }
        return out
    }

    /**
     * Darkens towards the edges. [amount] 0..1 controls strength, [radius] 0..1 how far from the
     * centre the falloff starts.
     */
    fun vignette(
        source: PixelBuffer,
        amount: Float,
        radius: Float = 0.7f,
        feather: Float = 0.5f,
    ): PixelBuffer {
        if (amount <= 0f) return source.copy()
        val out = source.copy()
        val centerX = source.width / 2f
        val centerY = source.height / 2f
        val maxDistance = sqrt(centerX * centerX + centerY * centerY)
        val inner = radius.coerceIn(0f, 1f) * maxDistance
        val outer = (inner + feather.coerceAtLeast(0.01f) * maxDistance).coerceAtMost(maxDistance)

        for (y in 0 until source.height) {
            for (x in 0 until source.width) {
                val dx = x - centerX
                val dy = y - centerY
                val distance = sqrt(dx * dx + dy * dy)
                val falloff =
                    when {
                        distance <= inner -> 0f
                        distance >= outer -> 1f
                        else -> (distance - inner) / (outer - inner)
                    }
                if (falloff <= 0f) continue
                val factor = 1f - amount.coerceIn(0f, 1f) * falloff
                val index = y * source.width + x
                val pixel = source.pixels[index]
                out.pixels[index] =
                    Channels.argb(
                        Channels.alpha(pixel).toInt(),
                        (Channels.red(pixel) * factor).roundToInt(),
                        (Channels.green(pixel) * factor).roundToInt(),
                        (Channels.blue(pixel) * factor).roundToInt(),
                    )
            }
        }
        return out
    }

    /** Gaussian blur whose strength varies linearly from top to bottom (fake depth of field). */
    fun tiltShift(
        source: PixelBuffer,
        maxRadius: Float,
        focusCenter: Float,
        focusHeight: Float,
    ): PixelBuffer {
        val fullyBlurred = gaussianBlur(source, maxRadius)
        val out = PixelBuffer(source.width, source.height)
        for (y in 0 until source.height) {
            val distance = abs(y - focusCenter)
            val t =
                if (distance <= focusHeight / 2f) {
                    0f
                } else {
                    ((distance - focusHeight / 2f) / (source.height / 2f)).coerceIn(0f, 1f)
                }
            if (t <= 0f) {
                System.arraycopy(
                    source.pixels,
                    y * source.width,
                    out.pixels,
                    y * source.width,
                    source.width,
                )
                continue
            }
            for (x in 0 until source.width) {
                val index = y * source.width + x
                out.pixels[index] = lerpArgb(source.pixels[index], fullyBlurred.pixels[index], t)
            }
        }
        return out
    }

    /** Edge-detection preview (used by the "Find Edges" filter layer). */
    fun findEdges(source: PixelBuffer): PixelBuffer {
        val out = PixelBuffer(source.width, source.height)
        for (y in 0 until source.height) {
            for (x in 0 until source.width) {
                val center = source.getSafe(x, y)
                val right = source.getSafe(x + 1, y)
                val down = source.getSafe(x, y + 1)
                val gx = Channels.luminance(center) - Channels.luminance(right)
                val gy = Channels.luminance(center) - Channels.luminance(down)
                val magnitude = (sqrt(gx * gx + gy * gy) * 255f).coerceIn(0f, 255f)
                val alpha = Channels.alpha(center).toInt()
                out.pixels[y * source.width + x] =
                    Channels.argb(
                        alpha,
                        magnitude.roundToInt(),
                        magnitude.roundToInt(),
                        magnitude.roundToInt(),
                    )
            }
        }
        return out
    }

    /** 3x3 emboss. */
    fun emboss(
        source: PixelBuffer,
        strength: Float = 1f,
    ): PixelBuffer {
        val kernel =
            // The weights sum to zero, so a flat area lands on the 128 bias (mid-grey) and only edges relieve.
            floatArrayOf(
                -2f,
                -1f,
                0f,
                -1f,
                0f,
                1f,
                0f,
                1f,
                2f,
            ).map { it * strength }.toFloatArray()
        return convolve3x3(source, kernel)
    }

    /** General 3x3 convolution with a normalised bias of 128 (matches Photoshop's style filters). */
    fun convolve3x3(
        source: PixelBuffer,
        kernel: FloatArray,
    ): PixelBuffer {
        require(kernel.size == 9) { "3x3 convolution needs 9 weights" }
        val out = PixelBuffer(source.width, source.height)
        for (y in 0 until source.height) {
            for (x in 0 until source.width) {
                var a = 0f
                var r = 0f
                var g = 0f
                var b = 0f
                var k = 0
                for (ky in -1..1) {
                    for (kx in -1..1) {
                        val sample = source.getSafe(x + kx, y + ky)
                        val weight = kernel[k]
                        val sa = Channels.alpha(sample) / 255f
                        a += Channels.alpha(sample) * weight
                        r += Channels.red(sample) * weight * sa
                        g += Channels.green(sample) * weight * sa
                        b += Channels.blue(sample) * weight * sa
                        k++
                    }
                }
                val center = source.getSafe(x, y)
                val alpha = Channels.alpha(center).toInt()
                if (alpha == 0) {
                    out.pixels[y * source.width + x] = 0
                    continue
                }
                val sumA = (a / 255f).coerceAtLeast(0.0001f)
                out.pixels[y * source.width + x] =
                    Channels.argb(
                        alpha,
                        clampByte(r / sumA + 128f),
                        clampByte(g / sumA + 128f),
                        clampByte(b / sumA + 128f),
                    )
            }
        }
        return out
    }

    /** Feather helper shared by mask feathering and soft brush edges. */
    fun featherAlpha(
        alpha: FloatArray,
        width: Int,
        height: Int,
        radius: Int,
    ): FloatArray {
        if (radius <= 0) return alpha.copyOf()
        val kernel = FloatArray(radius * 2 + 1) { 1f / (radius * 2 + 1) }
        val temp = FloatArray(alpha.size)
        horizontalPass(alpha, temp, width, height, kernel)
        val out = FloatArray(alpha.size)
        verticalPassReal(temp, out, width, height, kernel)
        return out
    }

    // -----------------------------------------------------------------------------------------
    // Internals
    // -----------------------------------------------------------------------------------------

    /** Separable convolution on premultiplied channels. */
    private fun separableConvolve(
        source: PixelBuffer,
        kernel: FloatArray,
        checkpoint: () -> Unit = {},
    ): PixelBuffer {
        val width = source.width
        val height = source.height
        val count = width * height
        val pixels = source.pixels

        // One shared pass plane serves every channel. Each channel's result plane also holds its input before
        // the horizontal pass, so a blur holds five float planes at a time instead of six. The arithmetic per
        // channel is unchanged.
        val passed = FloatArray(count)

        // shift < 0 blurs alpha; otherwise the colour channel at [shift] is premultiplied by alpha first.
        fun blur(shift: Int): FloatArray {
            val result = FloatArray(count)
            for (i in 0 until count) {
                val pixel = pixels[i]
                val alpha = ((pixel ushr 24) and 0xFF) / 255f
                result[i] = if (shift < 0) alpha else ((pixel ushr shift) and 0xFF) / 255f * alpha
            }
            horizontalPass(result, passed, width, height, kernel, checkpoint)
            verticalPassReal(passed, result, width, height, kernel, checkpoint)
            return result
        }

        val a = blur(-1)
        val r = blur(16)
        val g = blur(8)
        val b = blur(0)

        val out = PixelBuffer(width, height)
        for (i in 0 until count) {
            out.pixels[i] = unpremultiply(a[i], r[i], g[i], b[i])
        }
        return out
    }

    /** Interpolates premultiplied colour, then returns straight ARGB without dark alpha fringes. */
    fun lerpArgb(
        from: Int,
        to: Int,
        t: Float,
    ): Int {
        val clamped = t.coerceIn(0f, 1f)
        if (clamped <= 0f) return from
        if (clamped >= 1f) return to
        val fromWeight = Channels.alpha(from) * (1f - clamped)
        val toWeight = Channels.alpha(to) * clamped
        val alpha = fromWeight + toWeight
        if (alpha <= 0f) return 0
        return Channels.fromFloats(
            a = alpha,
            r = (Channels.red(from) * fromWeight + Channels.red(to) * toWeight) / alpha,
            g = (Channels.green(from) * fromWeight + Channels.green(to) * toWeight) / alpha,
            b = (Channels.blue(from) * fromWeight + Channels.blue(to) * toWeight) / alpha,
        )
    }

    private fun horizontalPass(
        src: FloatArray,
        dst: FloatArray,
        width: Int,
        height: Int,
        kernel: FloatArray,
        checkpoint: () -> Unit = {},
    ) {
        val radius = kernel.size / 2
        for (y in 0 until height) {
            checkpoint()
            val row = y * width
            for (x in 0 until width) {
                var sum = 0f
                for (k in -radius..radius) {
                    val sx = (x + k).coerceIn(0, width - 1)
                    sum += src[row + sx] * kernel[k + radius]
                }
                dst[row + x] = sum
            }
        }
    }

    private fun verticalPassReal(
        src: FloatArray,
        dst: FloatArray,
        width: Int,
        height: Int,
        kernel: FloatArray,
        checkpoint: () -> Unit = {},
    ) {
        val radius = kernel.size / 2
        for (y in 0 until height) {
            checkpoint()
            for (x in 0 until width) {
                var sum = 0f
                for (k in -radius..radius) {
                    val sy = (y + k).coerceIn(0, height - 1)
                    sum += src[sy * width + x] * kernel[k + radius]
                }
                dst[y * width + x] = sum
            }
        }
    }

    private fun gaussianKernel(radius: Float): FloatArray {
        val sigma = radius.coerceAtLeast(0.1f)
        // Three sigma covers 99.7% of the distribution; keeps the kernel small and fast.
        val half = max(1, (sigma * 3f).roundToInt())
        val kernel = FloatArray(half * 2 + 1)
        var sum = 0f
        val twoSigmaSquared = 2f * sigma * sigma
        for (i in -half..half) {
            val value = exp(-(i * i).toFloat() / twoSigmaSquared)
            kernel[i + half] = value
            sum += value
        }
        for (i in kernel.indices) kernel[i] /= sum
        return kernel
    }

    private fun unpremultiply(
        a: Float,
        r: Float,
        g: Float,
        b: Float,
    ): Int {
        val alpha = a.coerceIn(0f, 1f)
        if (alpha <= 0.0001f) return 0
        val invAlpha = 1f / alpha
        return Channels.fromFloats(
            a = alpha * 255f,
            r = (r * invAlpha * 255f).coerceIn(0f, 255f),
            g = (g * invAlpha * 255f).coerceIn(0f, 255f),
            b = (b * invAlpha * 255f).coerceIn(0f, 255f),
        )
    }

    private fun clampByte(value: Float): Int = value.roundToInt().coerceIn(0, 255)
}
