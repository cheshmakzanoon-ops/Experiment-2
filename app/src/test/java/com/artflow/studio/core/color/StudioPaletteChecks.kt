package com.artflow.studio.core.color

import kotlin.math.abs
import kotlin.random.Random

/** Dependency-free checks execute the production colour math both locally and through JUnit. */
object StudioPaletteChecks {
    private val seeds = listOf(0xFF2B3A55, 0xFF4C5FD5, 0xFFB4553D, 0xFF2F6F4E, 0xFF7A3E68, 0xFFB07D2B).map { it.toInt() }

    fun knownSrgbValues() {
        check(StudioPalette.contrast(0xFF000000.toInt(), 0xFFFFFFFF.toInt()) == 21.0)
        check(StudioPalette.contrast(0xFF123456.toInt(), 0xFF123456.toInt()) == 1.0)
        check(abs(StudioPalette.contrast(0xFF2B3A55.toInt(), 0xFF2D2D2D.toInt()) - 1.2061166229578602) < 0.0000001)
    }

    fun everyAccentAndSurfaceMeetsItsTextTarget() {
        for (seed in seeds) {
            for (dark in listOf(false, true)) {
                for (highContrast in listOf(false, true)) {
                    checkAccent(seed, dark, highContrast)
                }
            }
        }
    }

    private fun checkAccent(
        seed: Int,
        dark: Boolean,
        highContrast: Boolean,
    ) {
        val surfaces = StudioPalette.surfaces(dark, highContrast)
        val accent = StudioPalette.accent(seed, dark, highContrast)
        val minimum = if (highContrast) 7.0 else 4.5
        val backgrounds =
            listOf(
                surfaces.background,
                surfaces.surface,
                surfaces.variant,
                surfaces.lowest,
                surfaces.low,
                surfaces.normal,
                surfaces.high,
                surfaces.highest,
            )
        for (background in backgrounds) {
            check(StudioPalette.contrast(accent.primary, background) >= minimum)
            check(StudioPalette.contrast(surfaces.text, background) >= minimum)
            check(StudioPalette.contrast(surfaces.secondaryText, background) >= minimum)
        }
        check(StudioPalette.contrast(accent.primary, accent.onPrimary) >= minimum)
        check(StudioPalette.contrast(accent.container, accent.onContainer) >= minimum)
    }

    fun surfacesStayOpaqueNeutralAndOrdered() {
        for (dark in listOf(false, true)) {
            val value = StudioPalette.surfaces(dark, false)
            val levels = listOf(value.lowest, value.low, value.normal, value.high, value.highest)
            for (color in levels) {
                check(color ushr 24 == 255)
                check((color ushr 16 and 255) == (color ushr 8 and 255))
                check((color ushr 8 and 255) == (color and 255))
            }
            val intensities = levels.map { it and 255 }
            check(intensities == if (dark) intensities.sorted() else intensities.sortedDescending())
        }
    }

    fun validSeedsAreRetainedAndMixingIsExact() {
        val black = 0xFF000000.toInt()
        val white = 0xFFFFFFFF.toInt()
        check(StudioPalette.readable(black, white, 7.0) == black)
        check(StudioPalette.readable(white, black, 7.0) == white)
        check(StudioPalette.mix(black, white, 0.0) == black)
        check(StudioPalette.mix(black, white, 1.0) == white)
        check(StudioPalette.mix(black, white, 0.5) == 0xFF808080.toInt())
    }

    fun invalidAndImpossibleRequestsAreRejected() {
        val background = 0xFF808080.toInt()
        check(runCatching { StudioPalette.readable(0, background, 4.5) }.isFailure)
        check(runCatching { StudioPalette.readable(0xFFFFFFFF.toInt(), background, 7.0) }.isFailure)
        check(runCatching { StudioPalette.readable(background, background, Double.NaN) }.isFailure)
        check(runCatching { StudioPalette.readable(background, background, 22.0) }.isFailure)
        check(runCatching { StudioPalette.mix(background, background, Double.NaN) }.isFailure)
        check(runCatching { StudioPalette.mix(background, background, -0.01) }.isFailure)
        check(runCatching { StudioPalette.mix(background, 0, 0.5) }.isFailure)
    }

    fun generatedSeedsConvergeWithoutLosingTheContract() {
        val random = Random(601)
        repeat(1_000) { index ->
            val seed = random.nextInt() or 0xFF000000.toInt()
            val background = if (index % 2 == 0) 0xFF343434.toInt() else 0xFFE3E3E3.toInt()
            val target = if (index % 3 == 0) 7.1 else 4.6
            val result = StudioPalette.readable(seed, background, target)
            check(StudioPalette.contrast(result, background) >= target)
            check(result == StudioPalette.readable(seed, background, target))
            check(result ushr 24 == 255)
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        knownSrgbValues()
        everyAccentAndSurfaceMeetsItsTextTarget()
        surfacesStayOpaqueNeutralAndOrdered()
        validSeedsAreRetainedAndMixingIsExact()
        invalidAndImpossibleRequestsAreRejected()
        generatedSeedsConvergeWithoutLosingTheContract()
        println("PASS studio-palette: 6 groups; 24 theme/accent combinations and 1000 generated seeds")
    }
}
