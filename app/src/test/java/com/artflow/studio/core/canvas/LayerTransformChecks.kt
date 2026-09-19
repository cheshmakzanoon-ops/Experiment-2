package com.artflow.studio.core.canvas

import com.artflow.studio.core.pixels.PixelBuffer
import kotlin.math.floor
import kotlin.random.Random

/** Shared by the actual JUnit suite and an offline probe; no Android or replacement renderer. */
object LayerTransformChecks {
    private var comparisons = 0

    private fun expect(value: Boolean) {
        comparisons++
        check(value) { "Transform comparison $comparisons failed" }
    }

    private fun same(
        expected: IntArray,
        actual: IntArray,
    ) {
        expect(expected.contentEquals(actual))
    }

    private fun fixture(
        width: Int,
        height: Int,
    ): PixelBuffer = PixelBuffer(width, height, IntArray(width * height) { 0xFF000000.toInt() or (it + 1) })

    fun identityAndOwnership() {
        val source = PixelBuffer(3, 2, intArrayOf(0x00010203, 0x80FF0000.toInt(), 0, -1, 17, 99))
        val original = source.pixels.copyOf()
        for (angle in listOf(0f, 360f, -720f)) {
            val result = LayerTransform.apply(source, LayerTransform.Parameters(rotationDegrees = angle))
            same(original, result.pixels)
            expect(result !== source && result.pixels !== source.pixels)
            result.clear()
            same(original, source.pixels)
        }
    }

    fun translationsAndClipping() {
        val source = fixture(7, 5)
        for (dx in -9..9) {
            for (dy in -7..7) {
                for (mode in LayerTransform.Interpolation.entries) {
                    val result =
                        LayerTransform.apply(
                            source,
                            LayerTransform.Parameters(translationX = dx.toFloat(), translationY = dy.toFloat(), interpolation = mode),
                        )
                    for (y in 0 until 5) {
                        for (x in 0 until 7) expect(result.getUnchecked(x, y) == source.getSafe(x - dx, y - dy))
                    }
                }
            }
        }
        for (amount in listOf(Float.MAX_VALUE, -Float.MAX_VALUE, 1e12f, -1e12f)) {
            val result = LayerTransform.apply(source, LayerTransform.Parameters(translationX = amount))
            expect(result.isEmpty())
        }
    }

    fun quarterTurnsAndFlips() {
        val source = fixture(5, 5)
        for (mode in LayerTransform.Interpolation.entries) {
            val right = LayerTransform.apply(source, LayerTransform.Parameters(rotationDegrees = 90f, interpolation = mode))
            val left = LayerTransform.apply(source, LayerTransform.Parameters(rotationDegrees = -90f, interpolation = mode))
            for (y in 0 until 5) {
                for (x in 0 until 5) {
                    expect(right.getUnchecked(x, y) == source.getUnchecked(y, 4 - x))
                    expect(left.getUnchecked(x, y) == source.getUnchecked(4 - y, x))
                }
            }
            var rotated = source
            repeat(4) { rotated = LayerTransform.apply(rotated, LayerTransform.Parameters(rotationDegrees = 90f, interpolation = mode)) }
            same(source.pixels, rotated.pixels)
        }
        for (width in 1..9) {
            for (height in 1..8) {
                val rectangle = fixture(width, height)
                val horizontal = LayerTransform.apply(rectangle, LayerTransform.Parameters(flipHorizontal = true))
                val vertical = LayerTransform.apply(rectangle, LayerTransform.Parameters(flipVertical = true))
                val half = LayerTransform.apply(rectangle, LayerTransform.Parameters(rotationDegrees = 180f))
                for (y in 0 until height) {
                    for (x in 0 until width) {
                        expect(horizontal.getUnchecked(x, y) == rectangle.getUnchecked(width - 1 - x, y))
                        expect(vertical.getUnchecked(x, y) == rectangle.getUnchecked(x, height - 1 - y))
                        expect(half.getUnchecked(x, y) == rectangle.getUnchecked(width - 1 - x, height - 1 - y))
                    }
                }
                same(rectangle.pixels, LayerTransform.apply(horizontal, LayerTransform.Parameters(flipHorizontal = true)).pixels)
            }
        }
    }

    fun scaleSkewAndCustomPivot() {
        val source = fixture(7, 7)
        val random = Random(8131)
        // An independent inverse oracle decomposes operations instead of inverting a matrix.
        repeat(300) {
            val sx = random.nextInt(1, 5) / 2f
            val sy = random.nextInt(1, 5) / 2f
            val dx = random.nextInt(-4, 5).toFloat()
            val dy = random.nextInt(-4, 5).toFloat()
            val pivotX = random.nextInt(0, 8).toFloat()
            val pivotY = random.nextInt(0, 8).toFloat()
            val flip = random.nextBoolean()
            val parameters =
                LayerTransform.Parameters(
                    translationX = dx,
                    translationY = dy,
                    scaleX = sx,
                    scaleY = sy,
                    flipHorizontal = flip,
                    interpolation = LayerTransform.Interpolation.NEAREST,
                )
            val result = LayerTransform.apply(source, parameters, pivotX, pivotY)
            for (y in 0 until 7) {
                for (x in 0 until 7) {
                    val fromX = floor((x + 0.5f - dx - pivotX) / (if (flip) -sx else sx) + pivotX).toInt()
                    val fromY = floor((y + 0.5f - dy - pivotY) / sy + pivotY).toInt()
                    expect(result.getUnchecked(x, y) == source.getSafe(fromX, fromY))
                }
            }
        }
        val skewed = LayerTransform.apply(source, LayerTransform.Parameters(skewXDegrees = 45f))
        // Centre row stays anchored; a row below moves right by one pixel, not by the pivot again.
        expect(skewed.getUnchecked(3, 3) == source.getUnchecked(3, 3))
        expect(skewed.getUnchecked(4, 4) == source.getUnchecked(3, 4))
    }

    fun alphaCorrectInterpolation() {
        val red = 0xFFFF0000.toInt()
        val source = PixelBuffer(3, 1, intArrayOf(red, 0x000000FF, 0))
        val smooth = LayerTransform.apply(source, LayerTransform.Parameters(translationX = 0.5f))
        expect(smooth.pixels[0] == 0x80FF0000.toInt())
        expect(smooth.pixels[1] == 0x80FF0000.toInt())
        expect(smooth.pixels[2] ushr 24 == 0)
        val nearest =
            LayerTransform.apply(
                source,
                LayerTransform.Parameters(translationX = 0.5f, interpolation = LayerTransform.Interpolation.NEAREST),
            )
        expect(nearest.pixels[0] == red)
        expect(nearest.pixels[1] == 0x000000FF)
        // A mask and its raw pixels get identical geometric coordinates in separate passes.
        val mask = fixture(7, 7)
        val params = LayerTransform.Parameters(scaleX = 0.75f, rotationDegrees = 13f, translationY = 2f)
        same(LayerTransform.apply(mask, params).pixels, LayerTransform.apply(mask.copy(), params).pixels)
    }

    fun validationAndCancellation() {
        val invalid =
            listOf<() -> LayerTransform.Parameters>(
                { LayerTransform.Parameters(scaleX = 0f) },
                { LayerTransform.Parameters(scaleY = -1f) },
                { LayerTransform.Parameters(scaleX = 17f) },
                { LayerTransform.Parameters(rotationDegrees = Float.NaN) },
                { LayerTransform.Parameters(translationX = Float.POSITIVE_INFINITY) },
                { LayerTransform.Parameters(skewXDegrees = 81f) },
            )
        invalid.forEach { construct ->
            var rejected = false
            try {
                construct()
            } catch (_: IllegalArgumentException) {
                rejected = true
            }
            expect(rejected)
        }
        val source = fixture(32, 32)
        val original = source.pixels.copyOf()
        var rows = 0
        var cancelled = false
        try {
            LayerTransform.apply(source, LayerTransform.Parameters(rotationDegrees = 13f), checkActive = {
                if (++rows == 5) throw java.util.concurrent.CancellationException("Probe")
            })
        } catch (_: java.util.concurrent.CancellationException) {
            cancelled = true
        }
        expect(cancelled)
        same(original, source.pixels)
        val tiny = LayerTransform.apply(source, LayerTransform.Parameters(scaleX = 0.01f, scaleY = 0.01f, skewXDegrees = 80f))
        expect(tiny.width == source.width && tiny.height == source.height)
    }

    @JvmStatic
    fun main(args: Array<String>) {
        identityAndOwnership()
        translationsAndClipping()
        quarterTurnsAndFlips()
        scaleSkewAndCustomPivot()
        alphaCorrectInterpolation()
        validationAndCancellation()
        println("PASS transform-kernel-groups=6 checks=$comparisons")
    }
}
