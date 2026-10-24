package com.artflow.studio.data.local

import com.artflow.studio.core.export.ProcreateBrush
import com.artflow.studio.core.export.ProcreateBrushWriter
import com.artflow.studio.domain.model.brush.BrushLibraryCodec
import com.artflow.studio.domain.model.brush.BrushParams
import com.artflow.studio.domain.model.brush.ImportedPressure
import com.artflow.studio.domain.model.brush.NATIVE_BRUSH_IMPORT_VERSION
import com.artflow.studio.domain.model.brush.PressureMapping
import com.artflow.studio.domain.model.brush.SavedBrush
import com.artflow.studio.domain.model.brush.SourceBrushScalar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.zip.ZipInputStream

/** Version-one libraries written before the native channel audit retain their source and their local appearance. */
class BrushSourceContractCompatibilityTest {
    private val flowCurve = curve(0.25f, 0.75f)
    private val opacityCurve = curve(0.875f, 0.125f)
    private val source =
        mapOf(
            "dynamicsPressureOpacity" to -0.625,
            "dynamicsPressureOpacityTransfer" to 0.375,
            "dynamicsMix" to 0.625,
            "dynamicsPressureMix" to 0.25,
            "dynamicsWetAccumulation" to 0.75,
            "dynamicsPressureBleed" to 0.5,
            "dynamicsBlur" to 0.375,
            "dynamicsBlurJitter" to 0.125,
            "shapeCountJitter" to 0.5,
            "textureScale" to 0.1,
            "unknownSourceKnob" to 0.875,
        )

    // Explicit historical importer output, independent of the current parser and its baseline helper.
    private val legacy =
        BrushParams(
            size = 24f,
            smoothing = 0f,
            pressureToSize = 0f,
            pressureToOpacity = -0.625f,
            wetMix = 0.625f,
            textureScale = 0.25f,
            tiltOpacityInfluence = 0f,
            importedPressure = ImportedPressure(opacity = flowCurve),
            importedAuxiliaryCurves = mapOf("dynamicsPressureTransferModulationCurve" to opacityCurve),
            importedSourceScalars = source.mapValues { (_, value) -> requireNotNull(SourceBrushScalar.capture(value)) },
        )

    @Test
    fun anOldSavedLibraryKeepsItsParametersAndUntouchedSourceAliases() {
        val saved = SavedBrush("legacy", "Legacy import", legacy)
        val encoded = BrushLibraryCodec.encode(listOf(saved))
        assertFalse("sourceImportVersion" in encoded)
        val reopened = BrushLibraryCodec.decode(encoded).single().parameters
        assertEquals(legacy, reopened)
        assertEquals(0.625f, reopened.wetMix, 0f)
        assertEquals(0f, reopened.wetDilution, 0f)
        assertEquals(legacy.calculateEffectiveOpacity(0.25f), reopened.calculateEffectiveOpacity(0.25f), 0f)

        val reimported = exported(reopened)
        for (name in source.keys) {
            assertEquals(name, legacy.importedSourceScalars[name], reimported.importedSourceScalars[name])
        }
        // Explicit re-import uses the native-verified interpretation without silently migrating old strokes.
        assertEquals(NATIVE_BRUSH_IMPORT_VERSION, reimported.sourceImportVersion)
        assertEquals(-0.625f, reimported.pressureToFlow, 0f)
        assertEquals(0.375f, reimported.pressureToOpacity, 0f)
        assertEquals(0.625f, reimported.wetDilution, 0f)
        assertEquals(0f, reimported.wetMix, 0f)
        assertEquals(flowCurve.copy(interpolation = PressureMapping.Interpolation.NATIVE_SPLINE_1024), reimported.importedPressure?.flow)
        assertEquals(
            opacityCurve.copy(interpolation = PressureMapping.Interpolation.NATIVE_SPLINE_1024),
            reimported.importedPressure?.opacity,
        )
    }

    @Test
    fun aSavedVersionOneImportWithoutAnAlgorithmMarkerKeepsLinearGraphs() {
        val previous =
            legacy.copy(
                sourceImportVersion = 1,
                pressureToFlow = -0.625f,
                pressureToOpacity = 0.375f,
                importedPressure = ImportedPressure(flow = flowCurve, opacity = opacityCurve),
                importedAuxiliaryCurves = mapOf("dynamicsPressureBleedCurve" to flowCurve),
            )
        val saved = listOf(SavedBrush("previous-contract", "Previous import", previous))
        val encoded = BrushLibraryCodec.encode(saved)
        assertTrue("\"sourceImportVersion\":1" in encoded)
        assertFalse("interpolation" in encoded)
        val reopened = BrushLibraryCodec.decode(encoded)
        assertEquals(saved, reopened)
        val mappings = requireNotNull(reopened.single().parameters.importedPressure)
        assertEquals(PressureMapping.Interpolation.LINEAR, requireNotNull(mappings.flow).interpolation)
        assertEquals(0.375f, requireNotNull(mappings.flow).map(0.25f), 0f)
        assertEquals(0.6875f, requireNotNull(mappings.opacity).map(0.25f), 0f)
    }

    @Test
    fun editingNewControlsInAnOldLibraryReplacesOnlyTheirOwnNativeFields() {
        val changed =
            legacy.copy(
                pressureToFlow = -0.25f,
                pressureToOpacity = 0.875f,
                wetDilution = 0.125f,
                wetAttack = 0.5f,
                wetBlur = 0.25f,
                countJitter = 0.25f,
                textureScale = 0.5f,
            )
        val reimported = exported(changed)
        assertEquals(changed.pressureToFlow, reimported.pressureToFlow, 0f)
        assertEquals(changed.pressureToOpacity, reimported.pressureToOpacity, 0f)
        assertEquals(changed.wetDilution, reimported.wetDilution, 0f)
        assertEquals(changed.wetAttack, reimported.wetAttack, 0f)
        assertEquals(changed.wetBlur, reimported.wetBlur, 0f)
        assertEquals(changed.countJitter, reimported.countJitter, 0f)
        assertEquals(changed.textureScale, reimported.textureScale, 0f)
        for (name in listOf("dynamicsWetAccumulation", "dynamicsPressureBleed", "dynamicsBlurJitter", "unknownSourceKnob")) {
            assertEquals(name, legacy.importedSourceScalars[name], reimported.importedSourceScalars[name])
        }
    }

    @Test
    fun freshImportsRecordTheirContractAndDoNotUseLegacyCurveOrScalarMappings() {
        val fresh = exported(legacy)
        val saved = SavedBrush("fresh", "Fresh import", fresh)
        val encoded = BrushLibraryCodec.encode(listOf(saved))
        assertTrue("\"sourceImportVersion\":$NATIVE_BRUSH_IMPORT_VERSION" in encoded)
        val reopened = BrushLibraryCodec.decode(encoded).single().parameters
        assertEquals(fresh, reopened)
        val secondExport = exported(reopened)
        assertEquals(fresh.importedPressure, secondExport.importedPressure)
        assertEquals(fresh.pressureToFlow, secondExport.pressureToFlow, 0f)
        assertEquals(fresh.pressureToOpacity, secondExport.pressureToOpacity, 0f)
        assertEquals(fresh.wetDilution, secondExport.wetDilution, 0f)
        assertEquals(fresh.importedSourceScalars, secondExport.importedSourceScalars)
    }

    private fun exported(params: BrushParams): BrushParams {
        val bytes = ProcreateBrushWriter.brush(ProcreateBrushWriter.Brush("Legacy import", params))
        return ZipInputStream(bytes.inputStream()).use { zip ->
            check(zip.nextEntry.name == "Brush.archive")
            ProcreateBrush.read(zip.readBytes()).parameters
        }
    }

    private fun curve(
        start: Float,
        end: Float,
    ): PressureMapping = PressureMapping(listOf(PressureMapping.Point(0f, start), PressureMapping.Point(1f, end)))
}
