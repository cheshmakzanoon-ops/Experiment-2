package com.artflow.studio.core.render

import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.domain.model.brush.BrushParams
import com.artflow.studio.domain.model.brush.BrushValue
import com.artflow.studio.domain.model.brush.Stroke
import com.artflow.studio.domain.model.brush.StrokePoint
import com.artflow.studio.domain.model.brush.StudioBrushes

/** The same checks run in JUnit and in the dependency-free local renderer probe. */
object BrushStudioChecks {
    fun originalPresetsAreDistinctAndSearchable() {
        val presets = StudioBrushes.presets
        check(presets.size == 8)
        check(presets.map { it.id }.distinct().size == presets.size)
        check(presets.map { it.parameters }.distinct().size == presets.size)
        check(StudioBrushes.search("  FINE   INK  ").single().id == "fine-liner")
        check(StudioBrushes.search("", "Ink").size == 2)
        check(StudioBrushes.search("paper", "Ink").isEmpty())
        check(StudioBrushes.search("does-not-exist").isEmpty())
        check(StudioBrushes.search("") == presets)
        check(StudioBrushes.categories.toSet() == setOf("All", "Sketch", "Ink", "Texture", "Paint"))
    }

    fun presetsRenderRealDistinctDeterministicMarks() {
        val signatures =
            StudioBrushes.presets.map { preset ->
                val first = BrushPreview.render(preset.parameters)
                val second = BrushPreview.render(preset.parameters)
                check(first.pixels.any { it ushr 24 != 0 })
                check(first.pixels.contentEquals(second.pixels))
                first.pixels.contentHashCode()
            }
        check(signatures.distinct().size == StudioBrushes.presets.size)
    }

    fun exactValuesSupportPercentAndDecimalSeparators() {
        check(BrushValue.parse("37.5", 0f..1f, displayScale = 100f) == 0.375f)
        check(BrushValue.parse(" 37,5 ", 0f..1f, displayScale = 100f) == 0.375f)
        check(BrushValue.parse("0", 0f..1f, displayScale = 100f) == 0f)
        check(BrushValue.parse("100", 0f..1f, displayScale = 100f) == 1f)
        check(BrushValue.parse(".25", 0f..8f) == 0.25f)
        check(BrushValue.parse("-2.5", -4f..40f) == -2.5f)
        check(BrushValue.parse("3", 1f..5f, integer = true) == 3f)
    }

    fun exactValuesRejectInvalidInputRatherThanClamping() {
        listOf("", "-", ".", "NaN", "Infinity", "1e2", "1,2.3", "0xff", "10000000000000000").forEach {
            check(BrushValue.parse(it, 0f..1f) == null)
        }
        check(BrushValue.parse("101", 0f..1f, displayScale = 100f) == null)
        check(BrushValue.parse("-1", 0f..1f) == null)
        check(BrushValue.parse("2.5", 1f..5f, integer = true) == null)
        check(BrushValue.parse("6", 1f..5f, integer = true) == null)
        check(runCatching { BrushValue.parse("1", 0f..1f, displayScale = 0f) }.isFailure)
    }

    fun practiceRerendersTheSamePathWithoutMutatingIt() {
        val settings = BrushParams(size = 8f, pressureToSize = 0f, pressureToOpacity = 0f)
        val original = fixture(settings)
        val before = original.copy(points = original.points.toList())
        val thin = BrushPractice.render(settings, listOf(original))
        val thick = BrushPractice.render(settings.copy(size = 40f), listOf(original))
        check(thin.pixels.any { it != BrushPractice.PAPER })
        check(thick.pixels.count { it != BrushPractice.PAPER } > thin.pixels.count { it != BrushPractice.PAPER })
        check(original == before)
        check(BrushPractice.render(settings, listOf(original)).pixels.contentEquals(thin.pixels))
        thick.pixels.fill(0)
        check(BrushPractice.render(settings, listOf(original)).pixels.contentEquals(thin.pixels))
    }

    fun practiceMatchesTheProductionRenderer() {
        val settings = StudioBrushes.presets.first { it.id == "dry-charcoal" }.parameters
        val stroke = fixture(settings)
        val expected = PixelBuffer.filled(BrushPractice.WIDTH, BrushPractice.HEIGHT, BrushPractice.PAPER)
        val renderer = StrokeRasterizer()
        try {
            renderer.draw(expected, stroke)
        } finally {
            renderer.release()
        }
        check(BrushPractice.render(settings, listOf(stroke)).pixels.contentEquals(expected.pixels))
        val capped = BrushPractice.render(settings.copy(size = 512f), listOf(stroke))
        val atLimit = BrushPractice.render(settings.copy(size = 48f), listOf(stroke))
        check(capped.pixels.contentEquals(atLimit.pixels))
    }

    fun practiceIsBoundedAndCanBeCleared() {
        val settings = BrushParams()
        val blank = BrushPractice.render(settings, emptyList())
        check(blank.width == BrushPractice.WIDTH && blank.height == BrushPractice.HEIGHT)
        check(blank.pixels.all { it == BrushPractice.PAPER })
        val stroke = fixture(settings)
        check(runCatching { BrushPractice.render(settings, List(BrushPractice.MAX_STROKES + 1) { stroke }) }.isFailure)
        val tooManyPoints = stroke.copy(points = List(BrushPractice.MAX_POINTS + 1) { stroke.points.first() })
        check(runCatching { BrushPractice.render(settings, listOf(tooManyPoints)) }.isFailure)
        val result = BrushPractice.render(settings, List(BrushPractice.MAX_STROKES) { stroke })
        check(result.pixels.any { it != BrushPractice.PAPER })
        check(BrushPractice.render(settings, emptyList()).pixels.contentEquals(blank.pixels))
    }

    private fun fixture(settings: BrushParams) =
        Stroke(
            id = 41L,
            points = listOf(StrokePoint(40f, 90f, timestamp = 0L), StrokePoint(280f, 90f, timestamp = 120L)),
            brushParams = settings,
            layerId = 0L,
            color = BrushPractice.INK,
            timestamp = 0L,
        )

    @JvmStatic
    fun main(args: Array<String>) {
        originalPresetsAreDistinctAndSearchable()
        presetsRenderRealDistinctDeterministicMarks()
        exactValuesSupportPercentAndDecimalSeparators()
        exactValuesRejectInvalidInputRatherThanClamping()
        practiceRerendersTheSamePathWithoutMutatingIt()
        practiceMatchesTheProductionRenderer()
        practiceIsBoundedAndCanBeCleared()
        println("PASS brush-studio: 7 groups; real renderer, original presets, numeric validation and isolated practice")
    }
}
