package com.artflow.studio.domain.usecase.brush

import com.artflow.studio.domain.model.brush.BrushParams
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for UpdateColorDynamics use case
 * Tests Phase 11: Color Dynamics implementation
 */
class UpdateColorDynamicsTest {

    private lateinit var updateColorDynamics: UpdateColorDynamics

    @Before
    fun setup() {
        updateColorDynamics = UpdateColorDynamics()
    }

    @Test
    fun `test update hue jitter with valid value`() {
        val initialParams = BrushParams(hueJitter = 0f)
        val result = updateColorDynamics.updateHueJitter(initialParams, 0.5f)

        assertTrue(result.success)
        assertNotNull(result.updatedParams)
        assertEquals(0.5f, result.updatedParams?.hueJitter)
    }

    @Test
    fun `test update hue jitter coerces out of range value`() {
        val initialParams = BrushParams(hueJitter = 0f)
        val result = updateColorDynamics.updateHueJitter(initialParams, 1.5f)

        assertTrue(result.success)
        assertEquals(1.0f, result.updatedParams?.hueJitter)
    }

    @Test
    fun `test update saturation jitter`() {
        val initialParams = BrushParams(saturationJitter = 0f)
        val result = updateColorDynamics.updateSaturationJitter(initialParams, 0.3f)

        assertTrue(result.success)
        assertEquals(0.3f, result.updatedParams?.saturationJitter)
    }

    @Test
    fun `test update brightness jitter`() {
        val initialParams = BrushParams(brightnessJitter = 0f)
        val result = updateColorDynamics.updateBrightnessJitter(initialParams, 0.7f)

        assertTrue(result.success)
        assertEquals(0.7f, result.updatedParams?.brightnessJitter)
    }

    @Test
    fun `test toggle color pressure on`() {
        val initialParams = BrushParams(colorPressure = false)
        val result = updateColorDynamics.toggleColorPressure(initialParams, true)

        assertTrue(result.success)
        assertEquals(true, result.updatedParams?.colorPressure)
    }

    @Test
    fun `test toggle color pressure off`() {
        val initialParams = BrushParams(colorPressure = true)
        val result = updateColorDynamics.toggleColorPressure(initialParams, false)

        assertTrue(result.success)
        assertEquals(false, result.updatedParams?.colorPressure)
    }

    @Test
    fun `test update velocity to hue`() {
        val initialParams = BrushParams(velocityToHue = 0f)
        val result = updateColorDynamics.updateVelocityToHue(initialParams, 0.6f)

        assertTrue(result.success)
        assertEquals(0.6f, result.updatedParams?.velocityToHue)
    }

    @Test
    fun `test apply complete color dynamics config`() {
        val initialParams = BrushParams()
        val config = UpdateColorDynamics.ColorDynamicsConfig(
            hueJitter = 0.2f,
            saturationJitter = 0.25f,
            brightnessJitter = 0.3f,
            colorPressure = true,
            velocityToHue = 0.4f,
            pressureCurve = BrushParams.PressureCurve.EASE_IN_OUT
        )

        val result = updateColorDynamics.applyColorDynamicsConfig(initialParams, config)

        assertTrue(result.success)
        assertEquals(0.2f, result.updatedParams?.hueJitter)
        assertEquals(0.25f, result.updatedParams?.saturationJitter)
        assertEquals(0.3f, result.updatedParams?.brightnessJitter)
        assertEquals(true, result.updatedParams?.colorPressure)
        assertEquals(0.4f, result.updatedParams?.velocityToHue)
        assertEquals(BrushParams.PressureCurve.EASE_IN_OUT, result.updatedParams?.pressureCurve)
    }

    @Test
    fun `test apply watercolor_blend preset`() {
        val initialParams = BrushParams()
        val result = updateColorDynamics.applyPreset(initialParams, "watercolor_blend")

        assertTrue(result.success)
        assertEquals(0.1f, result.updatedParams?.hueJitter)
        assertEquals(0.15f, result.updatedParams?.saturationJitter)
        assertEquals(0.2f, result.updatedParams?.brightnessJitter)
        assertEquals(true, result.updatedParams?.colorPressure)
        assertEquals(BrushParams.PressureCurve.EASE_IN_OUT, result.updatedParams?.pressureCurve)
    }

    @Test
    fun `test apply oil_paint_rich preset`() {
        val initialParams = BrushParams()
        val result = updateColorDynamics.applyPreset(initialParams, "oil_paint_rich")

        assertTrue(result.success)
        assertEquals(0.05f, result.updatedParams?.hueJitter)
        assertEquals(0.1f, result.updatedParams?.saturationJitter)
        assertEquals(0.1f, result.updatedParams?.brightnessJitter)
        assertEquals(true, result.updatedParams?.colorPressure)
        assertEquals(BrushParams.PressureCurve.EASE_IN, result.updatedParams?.pressureCurve)
    }

    @Test
    fun `test apply rainbow_stroke preset with velocity to hue`() {
        val initialParams = BrushParams()
        val result = updateColorDynamics.applyPreset(initialParams, "rainbow_stroke")

        assertTrue(result.success)
        assertEquals(0.3f, result.updatedParams?.hueJitter)
        assertEquals(0.2f, result.updatedParams?.saturationJitter)
        assertEquals(0.15f, result.updatedParams?.brightnessJitter)
        assertEquals(0.5f, result.updatedParams?.velocityToHue)
        assertEquals(false, result.updatedParams?.colorPressure)
    }

    @Test
    fun `test apply pressure_shade preset`() {
        val initialParams = BrushParams()
        val result = updateColorDynamics.applyPreset(initialParams, "pressure_shade")

        assertTrue(result.success)
        assertEquals(0f, result.updatedParams?.hueJitter)
        assertEquals(0f, result.updatedParams?.saturationJitter)
        assertEquals(0.3f, result.updatedParams?.brightnessJitter)
        assertEquals(true, result.updatedParams?.colorPressure)
        assertEquals(BrushParams.PressureCurve.EASE_IN, result.updatedParams?.pressureCurve)
    }

    @Test
    fun `test apply speed_gradient preset`() {
        val initialParams = BrushParams()
        val result = updateColorDynamics.applyPreset(initialParams, "speed_gradient")

        assertTrue(result.success)
        assertEquals(0f, result.updatedParams?.hueJitter)
        assertEquals(0.1f, result.updatedParams?.saturationJitter)
        assertEquals(0f, result.updatedParams?.brightnessJitter)
        assertEquals(0.8f, result.updatedParams?.velocityToHue)
    }

    @Test
    fun `test apply natural_media preset with all dynamics`() {
        val initialParams = BrushParams()
        val result = updateColorDynamics.applyPreset(initialParams, "natural_media")

        assertTrue(result.success)
        assertEquals(0.08f, result.updatedParams?.hueJitter)
        assertEquals(0.12f, result.updatedParams?.saturationJitter)
        assertEquals(0.25f, result.updatedParams?.brightnessJitter)
        assertEquals(true, result.updatedParams?.colorPressure)
        assertEquals(0.2f, result.updatedParams?.velocityToHue)
    }

    @Test
    fun `test apply subtle_variation preset`() {
        val initialParams = BrushParams()
        val result = updateColorDynamics.applyPreset(initialParams, "subtle_variation")

        assertTrue(result.success)
        assertEquals(0.05f, result.updatedParams?.hueJitter)
        assertEquals(0.05f, result.updatedParams?.saturationJitter)
        assertEquals(0.08f, result.updatedParams?.brightnessJitter)
        assertEquals(false, result.updatedParams?.colorPressure)
    }

    @Test
    fun `test apply none preset resets values`() {
        val initialParams = BrushParams(
            hueJitter = 0.5f,
            saturationJitter = 0.5f,
            brightnessJitter = 0.5f,
            colorPressure = true,
            velocityToHue = 0.5f
        )
        val result = updateColorDynamics.applyPreset(initialParams, "none")

        assertTrue(result.success)
        assertEquals(0f, result.updatedParams?.hueJitter)
        assertEquals(0f, result.updatedParams?.saturationJitter)
        assertEquals(0f, result.updatedParams?.brightnessJitter)
        assertEquals(false, result.updatedParams?.colorPressure)
        assertEquals(0f, result.updatedParams?.velocityToHue)
    }

    @Test
    fun `test apply non-existent preset returns error`() {
        val initialParams = BrushParams()
        val result = updateColorDynamics.applyPreset(initialParams, "nonexistent_preset")

        assertFalse(result.success)
        assertNull(result.updatedParams)
        assertTrue(result.errorMessage?.contains("not found") == true)
    }

    @Test
    fun `test reset to defaults`() {
        val initialParams = BrushParams(
            hueJitter = 0.8f,
            saturationJitter = 0.7f,
            brightnessJitter = 0.6f,
            colorPressure = true,
            velocityToHue = 0.9f
        )

        val result = updateColorDynamics.resetToDefaults(initialParams)

        assertTrue(result.success)
        assertEquals(0f, result.updatedParams?.hueJitter)
        assertEquals(0f, result.updatedParams?.saturationJitter)
        assertEquals(0f, result.updatedParams?.brightnessJitter)
        assertEquals(false, result.updatedParams?.colorPressure)
        assertEquals(0f, result.updatedParams?.velocityToHue)
    }

    @Test
    fun `test validate color dynamics with valid config`() {
        val config = UpdateColorDynamics.ColorDynamicsConfig(
            hueJitter = 0.5f,
            saturationJitter = 0.3f,
            brightnessJitter = 0.4f,
            velocityToHue = 0.2f
        )

        assertTrue(updateColorDynamics.validateColorDynamics(config))
    }

    @Test
    fun `test validate color dynamics with invalid hue jitter`() {
        val config = UpdateColorDynamics.ColorDynamicsConfig(
            hueJitter = 1.5f, // Out of range
            saturationJitter = 0.3f,
            brightnessJitter = 0.4f,
            velocityToHue = 0.2f
        )

        assertFalse(updateColorDynamics.validateColorDynamics(config))
    }

    @Test
    fun `test validate color dynamics with negative saturation`() {
        val config = UpdateColorDynamics.ColorDynamicsConfig(
            hueJitter = 0.5f,
            saturationJitter = -0.1f, // Negative
            brightnessJitter = 0.4f,
            velocityToHue = 0.2f
        )

        assertFalse(updateColorDynamics.validateColorDynamics(config))
    }

    @Test
    fun `test calculate effective color with no dynamics`() {
        val baseColor = android.graphics.Color.RED
        val params = BrushParams(
            hueJitter = 0f,
            saturationJitter = 0f,
            brightnessJitter = 0f,
            colorPressure = false,
            velocityToHue = 0f
        )

        val result = updateColorDynamics.calculateEffectiveColor(baseColor, params)

        assertEquals(baseColor, result)
    }

    @Test
    fun `test calculate effective color with hue jitter`() {
        val baseColor = android.graphics.Color.BLUE
        val params = BrushParams(
            hueJitter = 0.5f,
            saturationJitter = 0f,
            brightnessJitter = 0f,
            colorPressure = false,
            velocityToHue = 0f
        )

        val result = updateColorDynamics.calculateEffectiveColor(baseColor, params)

        // Result should be different from base due to hue jitter
        assertNotEquals(baseColor, result)
    }

    @Test
    fun `test generate color preview returns correct count`() {
        val baseColor = android.graphics.Color.GREEN
        val params = BrushParams(
            hueJitter = 0.3f,
            saturationJitter = 0.2f,
            brightnessJitter = 0.2f
        )

        val colors = updateColorDynamics.generateColorPreview(baseColor, params, sampleCount = 5)

        assertEquals(5, colors.size)
    }

    @Test
    fun `test generate color preview with default sample count`() {
        val baseColor = android.graphics.Color.YELLOW
        val params = BrushParams(hueJitter = 0.2f)

        val colors = updateColorDynamics.generateColorPreview(baseColor, params)

        assertEquals(5, colors.size)
    }

    @Test
    fun `test update multiple color dynamics in sequence`() {
        var currentParams = BrushParams()

        val result1 = updateColorDynamics.updateHueJitter(currentParams, 0.3f)
        assertTrue(result1.success)
        currentParams = result1.updatedParams!!

        val result2 = updateColorDynamics.updateSaturationJitter(currentParams, 0.4f)
        assertTrue(result2.success)
        currentParams = result2.updatedParams!!

        val result3 = updateColorDynamics.updateBrightnessJitter(currentParams, 0.5f)
        assertTrue(result3.success)
        currentParams = result3.updatedParams!!

        val result4 = updateColorDynamics.toggleColorPressure(currentParams, true)
        assertTrue(result4.success)
        currentParams = result4.updatedParams!!

        assertEquals(0.3f, currentParams.hueJitter)
        assertEquals(0.4f, currentParams.saturationJitter)
        assertEquals(0.5f, currentParams.brightnessJitter)
        assertEquals(true, currentParams.colorPressure)
    }

    @Test
    fun `test apply preset preserves non-color parameters`() {
        val initialParams = BrushParams(
            size = 50f,
            opacity = 0.8f,
            spacing = 0.1f,
            hueJitter = 0f,
            saturationJitter = 0f,
            brightnessJitter = 0f
        )

        val result = updateColorDynamics.applyPreset(initialParams, "watercolor_blend")

        assertTrue(result.success)
        assertEquals(50f, result.updatedParams?.size)
        assertEquals(0.8f, result.updatedParams?.opacity)
        assertEquals(0.1f, result.updatedParams?.spacing)
        // Color dynamics should be updated
        assertEquals(0.1f, result.updatedParams?.hueJitter)
        assertEquals(0.15f, result.updatedParams?.saturationJitter)
        assertEquals(0.2f, result.updatedParams?.brightnessJitter)
    }
}
