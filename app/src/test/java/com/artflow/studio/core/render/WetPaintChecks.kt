package com.artflow.studio.core.render

import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.core.pixels.SelectionMask
import com.artflow.studio.domain.model.brush.BrushParams
import com.artflow.studio.domain.model.brush.Stroke
import com.artflow.studio.domain.model.brush.StrokePoint
import kotlin.random.Random

/** Shared by JUnit and the standalone production-kernel runner. */
object WetPaintChecks {
    private const val RED = 0xFFFF0000.toInt()
    private const val BLUE = 0xFF0000FF.toInt()
    private val brush = BrushParams(size = 12f, pressureToSize = 0f, pressureToOpacity = 0f)

    fun knownPigmentMixtures() {
        val source = PixelBuffer.filled(32, 32, BLUE)
        check(WetPaint.pickup(source, 16f, 16f, 6f, RED, 0f) == RED)
        check(WetPaint.pickup(source, 16f, 16f, 6f, RED, 1f) == BLUE)
        check(WetPaint.pickup(source, 16f, 16f, 6f, RED, 0.5f) == 0xFF800080.toInt())
        check(WetPaint.pickup(source, 16f, 16f, 6f, 0x80FF0000.toInt(), 0.5f) == 0x80800080.toInt())
        check(WetPaint.pickup(source, 16f, 16f, 6f, 0x00FF0000, 1f) == 0x00FF0000)
        check(source.pixels.all { it == BLUE })
    }

    fun transparentColourDoesNotContaminateTheBrush() {
        val hidden = PixelBuffer.filled(32, 32, 0x0000FF00)
        check(WetPaint.pickup(hidden, 16f, 16f, 6f, RED, 1f) == RED)
        val translucent = PixelBuffer.filled(32, 32, 0x800000FF.toInt())
        check(WetPaint.pickup(translucent, 16f, 16f, 6f, RED, 1f) == 0xFF7F0080.toInt())
        check(WetPaint.pickup(translucent, -100f, -100f, 6f, RED, 1f) == RED)
        // Hidden green beside opaque blue must not enter alpha-weighted interpolation.
        val edge = PixelBuffer(2, 1, intArrayOf(BLUE, 0x0000FF00))
        check(WetPaint.pickup(edge, 1f, 0.5f, 0f, RED, 1f) == 0xFF7F0080.toInt())
    }

    fun invalidInputIsRejectedBeforeMutation() {
        val source = PixelBuffer.filled(32, 32, BLUE)
        listOf(Float.NaN, Float.POSITIVE_INFINITY, -0.01f, 1.01f).forEach {
            check(runCatching { WetPaint.pickup(source, 16f, 16f, 6f, RED, it) }.exceptionOrNull() is IllegalArgumentException)
        }
        check(runCatching { WetPaint.pickup(source, Float.NaN, 16f, 6f, RED, 0.5f) }.isFailure)
        check(runCatching { WetPaint.pickup(source, 16f, 16f, -1f, RED, 0.5f) }.isFailure)
        check(runCatching { WetPaint.pickup(source, 16f, 16f, Float.POSITIVE_INFINITY, RED, 0.5f) }.isFailure)
        val renderer = StrokeRasterizer()
        try {
            check(runCatching { renderer.draw(source, stroke(Float.NaN), enableWetMix = true) }.isFailure)
        } finally {
            renderer.release()
        }
        check(source.pixels.all { it == BLUE })
    }

    fun newStrokesMixButLegacyReplayStaysUnchanged() {
        val source = PixelBuffer.filled(32, 32, BLUE)
        val wet = render(source, incoming = listOf(stroke(0.5f)))
        val dry = render(source, incoming = listOf(stroke(0f)))
        check(wet.getSafe(16, 16) == 0xFF800080.toInt())
        check(dry.getSafe(16, 16) == RED)
        val legacyWet = render(source, historical = listOf(stroke(0.5f)))
        val legacyDry = render(source, historical = listOf(stroke(0f)))
        check(legacyWet.pixels.contentEquals(legacyDry.pixels))
        check(legacyWet.pixels.contentEquals(dry.pixels))
        check(wet.pixels.contentEquals(render(source, incoming = listOf(stroke(0.5f))).pixels))
        check(source.pixels.all { it == BLUE })
    }

    fun opacitySelectionAlphaLockAndEraserRetainCoverage() {
        val source = PixelBuffer.filled(32, 32, BLUE)
        val halfOpacity = stroke(0.5f).copy(brushParams = brush.copy(wetMix = 0.5f, opacity = 0.5f))
        val faded = render(source, incoming = listOf(halfOpacity))
        check(faded.getSafe(16, 16) == 0xFF4000BF.toInt())
        val empty = SelectionMask(32, 32)
        check(source.pixels.contentEquals(render(source, incoming = listOf(stroke(1f)), selection = empty).pixels))
        val selection = SelectionMask(32, 32).apply { coverage.fill(128.toByte()) }
        check(faded.pixels.contentEquals(render(source, incoming = listOf(stroke(0.5f)), selection = selection).pixels))
        val translucent = PixelBuffer.filled(32, 32, 0x400000FF)
        check(render(translucent, incoming = listOf(stroke(0.5f)), alphaLock = true).pixels.all { it ushr 24 == 64 })
        val dryEraser = stroke(0f).copy(isEraser = true)
        val wetEraser = stroke(1f).copy(isEraser = true)
        check(render(source, incoming = listOf(dryEraser)).pixels.contentEquals(render(source, incoming = listOf(wetEraser)).pixels))
    }

    fun pickupIsDeterministicBoundedAndKeepsInkAlpha() {
        val random = Random(411)
        val source = PixelBuffer(32, 32, IntArray(1024) { random.nextInt() })
        val original = source.pixels.copyOf()
        repeat(10_000) {
            val ink = random.nextInt()
            val x = random.nextFloat() * 100f - 50f
            val y = random.nextFloat() * 100f - 50f
            val radius = random.nextFloat() * 100_000f
            val amount = random.nextFloat()
            val value = WetPaint.pickup(source, x, y, radius, ink, amount)
            check(value ushr 24 == ink ushr 24)
            check(value == WetPaint.pickup(source, x, y, radius, ink, amount))
            check(WetPaint.pickup(source, x, y, radius, ink, 0f) == ink)
        }
        check(source.pixels.contentEquals(original))
    }

    fun practiceRetainsIndependentColoursAndRerendersWetMarks() {
        val first = stroke(0f).copy(points = listOf(StrokePoint(160f, 90f)), color = BLUE)
        val second = first.copy(id = 2L, color = RED)
        val paths = listOf(first, second)
        val dry = BrushPractice.render(brush, paths)
        val wet = BrushPractice.render(brush.copy(wetMix = 0.5f), paths)
        check(dry.getSafe(160, 90) == RED)
        check(wet.getSafe(160, 90) != RED)
        check(first.color == BLUE && second.color == RED)
        check(wet.pixels.contentEquals(BrushPractice.render(brush.copy(wetMix = 0.5f), paths).pixels))
        check(BrushPractice.render(brush, emptyList()).pixels.all { it == BrushPractice.PAPER })
    }

    private fun stroke(amount: Float) =
        Stroke(1L, listOf(StrokePoint(16f, 16f, timestamp = 0L)), brush.copy(wetMix = amount), 1L, RED, 0L)

    private fun render(
        source: PixelBuffer,
        historical: List<Stroke> = emptyList(),
        incoming: List<Stroke> = emptyList(),
        selection: SelectionMask? = null,
        alphaLock: Boolean = false,
    ): PixelBuffer = LayerStrokeRenderer.render(source, historical, incoming, 32, 32, alphaLock, selection)

    @JvmStatic
    fun main(args: Array<String>) {
        knownPigmentMixtures()
        transparentColourDoesNotContaminateTheBrush()
        invalidInputIsRejectedBeforeMutation()
        newStrokesMixButLegacyReplayStaysUnchanged()
        opacitySelectionAlphaLockAndEraserRetainCoverage()
        pickupIsDeterministicBoundedAndKeepsInkAlpha()
        practiceRetainsIndependentColoursAndRerendersWetMarks()
        println("PASS wet-paint: 7 groups, including 10000 generated sampling cases")
    }
}
