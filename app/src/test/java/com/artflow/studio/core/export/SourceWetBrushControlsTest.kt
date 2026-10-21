package com.artflow.studio.core.export

import com.artflow.studio.domain.model.brush.BrushParams
import com.artflow.studio.domain.model.brush.PressureMapping
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.zip.ZipInputStream

/** Verified archive names connect to ArtFlow controls; source scalar scales survive interchange. */
class SourceWetBrushControlsTest {
    private fun roundTrip(params: BrushParams): BrushParams {
        val bytes = ProcreateBrushWriter.brush(ProcreateBrushWriter.Brush("Synthetic wet brush", params))
        val archive =
            ZipInputStream(bytes.inputStream()).use { zip ->
                check(zip.nextEntry.name == "Brush.archive")
                zip.readBytes()
            }
        return ProcreateBrush.read(archive).parameters
    }

    @Test
    fun nativeOpacityArchiveAliasControlsFlowAndOpacityTransferControlsOpacity() {
        val params =
            ProcreateBrush.parameters(
                mapOf(
                    "dynamicsPressureOpacity" to -0.75,
                    "dynamicsPressureOpacityTransfer" to 0.25,
                ),
            )
        assertEquals(-0.75f, params.pressureToFlow, 0f)
        assertEquals(0.25f, params.pressureToOpacity, 0f)
        val exported = roundTrip(params)
        assertEquals(params.pressureToFlow, exported.pressureToFlow, 0f)
        assertEquals(params.pressureToOpacity, exported.pressureToOpacity, 0f)
        assertEquals(1f, params.flowAt(0f), 0f)
        assertEquals(0.25f, params.flowAt(1f), 0f)
    }

    @Test
    fun sourceFlowAndOpacityCurvesFeedTheNativeVerifiedChannels() {
        val keyed = KeyedArchiveBuilder()

        fun curve(value: Float): Any =
            keyed.instance(
                "ValkyrieMagnitudinalCurve",
                mapOf("points" to keyed.array(listOf(keyed.add("{0.0, $value}"), keyed.add("{1.0, $value}")))),
            )
        val params =
            ProcreateBrush
                .read(
                    keyed.encode(
                        mapOf(
                            "dynamicsPressureOpacity" to 1.0,
                            "dynamicsPressureOpacityTransfer" to 1.0,
                            "dynamicsPressureOpacityCurve" to curve(0.25f),
                            "dynamicsPressureTransferModulationCurve" to curve(0.75f),
                        ),
                    ),
                ).parameters
        assertEquals(0.25f, params.flowAt(0.5f), 0f)
        assertEquals(0.75f, params.calculateEffectiveOpacity(0.5f), 0f)
        assertEquals(params.importedPressure, roundTrip(params).importedPressure)
        assertEquals(emptyMap<String, PressureMapping>(), params.importedAuxiliaryCurves)
    }

    @Test
    fun nativeWetAliasesAreMappedToDilutionAttackAndCanvasPull() {
        val params =
            ProcreateBrush.parameters(
                mapOf(
                    "dynamicsMix" to 0.8,
                    "dynamicsPressureMix" to 0.3,
                    "dynamicsWetAccumulation" to 0.6,
                ),
            )
        assertEquals(0.8f, params.wetDilution, 0f)
        assertEquals(0.3f, params.wetAttack, 0f)
        assertEquals(0.6f, params.wetPull, 0f)
        assertEquals(0f, params.wetMix, 0f)
        val exported = roundTrip(params)
        assertEquals(params.wetDilution, exported.wetDilution, 0f)
        assertEquals(params.wetAttack, exported.wetAttack, 0f)
        assertEquals(params.wetPull, exported.wetPull, 0f)
    }

    @Test
    fun legacySmudgeAccumulationIsOnlyTheFallbackForMissingWetAccumulation() {
        val legacy = ProcreateBrush.parameters(mapOf("dynamicsSmudgeAccumulation" to 0.65))
        val modern = ProcreateBrush.parameters(mapOf("dynamicsSmudgeAccumulation" to 0.65, "dynamicsWetAccumulation" to 0.0))
        assertEquals(0.65f, legacy.wetPull, 0f)
        assertEquals(0f, modern.wetPull, 0f)
        assertEquals(legacy.wetPull, roundTrip(legacy).wetPull, 0f)
        assertEquals(modern.wetPull, roundTrip(modern).wetPull, 0f)
    }

    @Test
    fun observedSourceWetAndCountFieldsBecomeEditableParameters() {
        val params =
            ProcreateBrush.parameters(
                mapOf(
                    "shapeCountJitter" to 0.75,
                    "dynamicsPressureBleed" to 0.5,
                    "dynamicsBlur" to 0.625,
                    "dynamicsBlurJitter" to 0.125,
                ),
            )
        assertEquals(0.75f, params.countJitter, 0f)
        assertEquals(0.5f, params.bleed, 0f)
        assertEquals(0.625f, params.wetBlur, 0f)
        assertEquals(0.125f, params.wetBlurJitter, 0f)
        val exported = roundTrip(params)
        assertEquals(params.countJitter, exported.countJitter, 0f)
        assertEquals(params.bleed, exported.bleed, 0f)
        assertEquals(params.wetBlur, exported.wetBlur, 0f)
        assertEquals(params.wetBlurJitter, exported.wetBlurJitter, 0f)
    }

    @Test
    fun anUneditedOutOfRangeSourceValueIsRetainedDespiteRendererClamping() {
        val fields =
            mapOf(
                "shapeCountJitter" to 2.25,
                "dynamicsPressureBleed" to -0.25,
                "dynamicsBlur" to 1.75,
                "dynamicsBlurJitter" to 3.5,
            )
        val imported = ProcreateBrush.parameters(fields)
        val exported = roundTrip(imported)
        for (name in fields.keys) {
            assertEquals(name, imported.importedSourceScalars[name], exported.importedSourceScalars[name])
        }
    }

    @Test
    fun editedWetControlsReplaceOnlyTheirOwnSourceScalars() {
        val imported =
            ProcreateBrush.parameters(
                mapOf(
                    "shapeCountJitter" to 0.75,
                    "dynamicsPressureBleed" to 0.5,
                    "dynamicsBlur" to 0.625,
                    "dynamicsBlurJitter" to 0.125,
                ),
            )
        val exported = roundTrip(imported.copy(wetBlur = 0.25f))
        assertEquals(0.25f, exported.wetBlur, 0f)
        for (name in listOf("shapeCountJitter", "dynamicsPressureBleed", "dynamicsBlurJitter")) {
            assertEquals(name, imported.importedSourceScalars[name], exported.importedSourceScalars[name])
        }
    }
}
