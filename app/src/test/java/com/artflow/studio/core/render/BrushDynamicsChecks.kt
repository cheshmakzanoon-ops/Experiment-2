package com.artflow.studio.core.render

import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.domain.model.brush.BrushParams
import com.artflow.studio.domain.model.brush.PressureResponse
import com.artflow.studio.domain.model.brush.Stroke
import com.artflow.studio.domain.model.brush.StrokePoint
import com.artflow.studio.presentation.ui.components.brush.BrushAttribute
import kotlin.math.abs

/** Shared assertions for JUnit and the standalone probe; no alternate rendering implementation. */
object BrushDynamicsChecks {
    fun attributeRoutesAreCompleteAndDisjoint() {
        val attributes = BrushAttribute.entries.filter { it != BrushAttribute.ALL }
        check(attributes.size == 10)
        val labels = BrushAttribute.entries.map { it.label }
        check(labels.distinct().size == labels.size)
        check(BrushAttribute.visible(BrushAttribute.ALL) == attributes)
        attributes.forEach { check(BrushAttribute.visible(it) == listOf(it)) }
    }

    fun speedControlsAffectSizeAndOpacityWithinTheirContracts() {
        val settings = base().copy(velocityToSize = 1f, velocityToOpacity = 1f)
        near(20f, settings.calculateEffectiveSize(1f, 0f))
        near(15f, settings.calculateEffectiveSize(1f, 5f))
        near(10f, settings.calculateEffectiveSize(1f, 10f))
        near(10f, settings.calculateEffectiveSize(1f, 100f))
        near(1f, settings.calculateEffectiveOpacity(1f, 0f))
        near(0.85f, settings.calculateEffectiveOpacity(1f, 5f))
        near(0.7f, settings.calculateEffectiveOpacity(1f, 10f))
        near(0.7f, settings.calculateEffectiveOpacity(1f, 100f))
        near(0f, settings.copy(opacity = 0f).calculateEffectiveOpacity(1f, 10f))
        near(20f, base().calculateEffectiveSize(1f, 10f))
        near(1f, base().calculateEffectiveOpacity(1f, 10f))
    }

    fun speedHueAndPressureBrightnessRetainSourceAlpha() {
        val red = 0x80FF0000.toInt()
        val speed = base().copy(velocityToHue = 1f)
        check(speed.applyColorJitter(red, velocity = 0f) == red)
        check(speed.applyColorJitter(red, velocity = 10f) == 0x80FFFF00.toInt())
        val pressure = base().copy(colorPressure = true)
        val soft = pressure.applyColorJitter(red, pressure = 0f)
        check(soft ushr 24 == 128)
        check((soft ushr 16 and 255) in 127..128)
        check(soft and 0xFFFF == 0)
        check(pressure.applyColorJitter(red, pressure = 1f) == red)
        val response =
            pressure.copy(
                pressureCurve = BrushParams.PressureCurve.CUSTOM,
                customPressure = PressureResponse(0.1f, 0.2f, 0.4f),
            )
        val custom = response.applyColorJitter(red, pressure = 0.5f)
        check((custom ushr 16 and 255) in 152..154)
        check(custom ushr 24 == 128)
    }

    fun speedSizeChangesRenderedInkRatherThanJustTheSlider() {
        val settings = base().copy(velocityToSize = 1f)
        val slow = render(settings, duration = 1_280L)
        val fast = render(settings, duration = 8L)
        check(coverage(fast) < coverage(slow))
        check(coverage(fast) > 0)
        check(render(base(), 1_280L).pixels.contentEquals(render(base(), 8L).pixels))
    }

    fun speedOpacityChangesRenderedAlphaWithoutChangingFootprint() {
        val settings = base().copy(velocityToOpacity = 1f)
        val slow = render(settings, 1_280L)
        val fast = render(settings, 8L)
        check(coverage(fast) == coverage(slow))
        check(alphaSum(fast) < alphaSum(slow))
        check(alphaSum(fast) > 0L)
    }

    fun colourDynamicsChangeActualRenderedPixels() {
        val speed = base().copy(velocityToHue = 1f)
        val fast = render(speed, 8L)
        val slow = render(speed, 1_280L)
        check(!fast.pixels.contentEquals(slow.pixels))
        check(fast.getSafe(80, 48) and 0x00FFFFFF == 0x00FFFF00)
        val pressure = base().copy(colorPressure = true)
        val soft = render(pressure, 100L, pressure = 0.1f)
        val hard = render(pressure, 100L, pressure = 1f)
        check(coverage(soft) == coverage(hard))
        check(alphaSum(soft) == alphaSum(hard))
        check((soft.getSafe(80, 48) ushr 16 and 255) < (hard.getSafe(80, 48) ushr 16 and 255))
    }

    fun rendererRetainsDeterminismAndUnrelatedSettings() {
        val settings =
            base().copy(
                velocityToSize = 0.8f,
                velocityToOpacity = 0.6f,
                velocityToHue = 0.9f,
                colorPressure = true,
                textureId = "paper",
                blendTexture = true,
            )
        val before = settings.copy()
        val first = render(settings, 80L)
        val expected = first.pixels.copyOf()
        first.pixels.fill(0)
        check(render(settings, 80L).pixels.contentEquals(expected))
        check(settings == before)
        check(settings.textureId == "paper" && settings.blendTexture)
    }

    private fun base() = BrushParams(size = 20f, pressureToSize = 0f, pressureToOpacity = 0f, smoothing = 0f)

    private fun coverage(buffer: PixelBuffer) = buffer.pixels.count { it ushr 24 != 0 }

    private fun alphaSum(buffer: PixelBuffer) = buffer.pixels.sumOf { (it ushr 24).toLong() }

    private fun render(
        settings: BrushParams,
        duration: Long,
        pressure: Float = 1f,
    ): PixelBuffer {
        val stroke =
            Stroke(
                id = 902L,
                points =
                    listOf(
                        StrokePoint(16f, 48f, pressure = pressure, timestamp = 0L),
                        StrokePoint(144f, 48f, pressure = pressure, timestamp = duration),
                    ),
                brushParams = settings,
                layerId = 0L,
                color = 0xFFFF0000.toInt(),
                timestamp = 0L,
            )
        val buffer = PixelBuffer(160, 96)
        val renderer = StrokeRasterizer()
        try {
            renderer.draw(buffer, stroke)
        } finally {
            renderer.release()
        }
        return buffer
    }

    private fun near(
        expected: Float,
        actual: Float,
    ) {
        check(abs(expected - actual) < 0.0001f) { "Expected $expected, got $actual" }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        attributeRoutesAreCompleteAndDisjoint()
        speedControlsAffectSizeAndOpacityWithinTheirContracts()
        speedHueAndPressureBrightnessRetainSourceAlpha()
        speedSizeChangesRenderedInkRatherThanJustTheSlider()
        speedOpacityChangesRenderedAlphaWithoutChangingFootprint()
        colourDynamicsChangeActualRenderedPixels()
        rendererRetainsDeterminismAndUnrelatedSettings()
        println("PASS brush-attribute/dynamics: seven groups using production routing, parameters and rasterizer")
    }
}
