package com.artflow.studio.domain.model.brush

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ImportedColorPressureTest {
    private val ink = 0x7F804020

    private fun constant(value: Float) = PressureMapping(listOf(PressureMapping.Point(0f, value), PressureMapping.Point(1f, value)))

    @Test
    fun eachColourChannelReadsItsOwnImportedPressureGraph() {
        val dynamics = ColorDynamics(pressureHue = 0.8f, pressureSaturation = 0.7f, pressureBrightness = 0.6f)
        val fields = listOf("dynamicsPressureHueCurve", "dynamicsPressureSaturationCurve", "dynamicsPressureBrightnessCurve")
        val disabled =
            listOf(
                dynamics.copy(pressureHue = 0f),
                dynamics.copy(pressureSaturation = 0f),
                dynamics.copy(pressureBrightness = 0f),
            )
        for (i in fields.indices) {
            val params = BrushParams(colorDynamics = dynamics, importedAuxiliaryCurves = mapOf(fields[i] to constant(1f)))
            val expected = BrushParams(colorDynamics = disabled[i]).applyColorJitter(ink, pressure = 0.3f)
            assertEquals(fields[i], expected, params.applyColorJitter(ink, pressure = 0.3f))
        }
    }

    @Test
    fun sourceChannelGraphsFollowTheOverallGraphExactlyOnce() {
        val overall = PressureMapping(listOf(PressureMapping.Point(0f, 0.2f), PressureMapping.Point(1f, 0.8f)))
        val channel = PressureMapping(listOf(PressureMapping.Point(0f, 1f), PressureMapping.Point(1f, 0f)))
        val params =
            BrushParams(
                importedPressure = ImportedPressure(overall = overall),
                importedAuxiliaryCurves = mapOf("dynamicsPressureHueCurve" to channel),
            )
        assertEquals(0.65f, params.channelPressure(0.25f, "dynamicsPressureHueCurve"), 0.00001f)
        assertEquals(0.35f, params.channelPressure(0.25f, "dynamicsPressureBrightnessCurve"), 0.00001f)
    }

    @Test
    fun aNonzeroSourceEndpointCanChangeColourEvenAtFullPressure() {
        val plain = BrushParams(colorDynamics = ColorDynamics(pressureBrightness = 1f))
        val mapped = plain.copy(importedAuxiliaryCurves = mapOf("dynamicsPressureBrightnessCurve" to constant(0.5f)))
        assertEquals(ink, plain.applyColorJitter(ink, pressure = 1f))
        assertNotEquals(ink, mapped.applyColorJitter(ink, pressure = 1f))
        assertEquals(ink ushr 24, mapped.applyColorJitter(ink, pressure = 1f) ushr 24)
    }

    @Test
    fun secondaryColourHasAnIndependentPressureCurveAndPreservesInkAlpha() {
        val params =
            BrushParams(
                secondaryPressure = 1f,
                importedAuxiliaryCurves = mapOf("dynamicsPressureSecondaryColorCurve" to constant(0f)),
            )
        val blue = 0xFF0000FF.toInt()
        assertEquals(ink, params.applyColorJitter(ink, pressure = 1f, secondary = blue))
        val reversed = params.copy(importedAuxiliaryCurves = mapOf("dynamicsPressureSecondaryColorCurve" to constant(1f)))
        assertEquals(0x7F0000FF, reversed.applyColorJitter(ink, pressure = 0f, secondary = blue))
    }

    @Test
    fun curvesForUnsupportedChannelsStayInert() {
        val params = BrushParams(colorDynamics = ColorDynamics(pressureBrightness = 0.3f))
        val retained = params.copy(importedAuxiliaryCurves = mapOf("dynamicsPressureGradeCurve" to constant(0f)))
        assertEquals(params.applyColorJitter(ink, pressure = 0.4f), retained.applyColorJitter(ink, pressure = 0.4f))
    }

    @Test
    fun savingRejectsNonfiniteAndOutOfRangeWetControls() {
        val bad =
            listOf(
                BrushParams(wetAttack = Float.NaN),
                BrushParams(wetCharge = Float.POSITIVE_INFINITY),
                BrushParams(wetDilution = -0.1f),
                BrushParams(wetPull = 1.01f),
                BrushParams(wetBlurJitter = Float.NaN),
                BrushParams(bleed = 2f),
            )
        for (params in bad) {
            assertThrows(IllegalArgumentException::class.java) {
                BrushLibraryCodec.encode(listOf(SavedBrush("invalid", "Invalid", params)))
            }
        }
    }

    @Test
    fun wetControlsAndAppliedGraphsSurviveSavedLibraryPersistence() {
        val params =
            BrushParams(
                wetAttack = 0.2f,
                wetCharge = 0.4f,
                wetDilution = 0.3f,
                wetPull = 0.6f,
                wetBlurJitter = 0.5f,
                bleed = 0.7f,
                pressureToFlow = 0.8f,
                importedAuxiliaryCurves = mapOf("dynamicsPressureHueCurve" to constant(0.4f)),
            )
        val saved = SavedBrush("wet", "Wet", params)
        assertEquals(saved, BrushLibraryCodec.decode(BrushLibraryCodec.encode(listOf(saved))).single())
    }
}
