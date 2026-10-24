package com.artflow.studio.data.local

import com.artflow.studio.core.export.ProcreateBrush
import com.artflow.studio.core.export.ProcreateBrushWriter
import com.artflow.studio.domain.model.brush.BrushLibraryCodec
import com.artflow.studio.domain.model.brush.BrushParams
import com.artflow.studio.domain.model.brush.DualBrush
import com.artflow.studio.domain.model.brush.SavedBrush
import com.artflow.studio.domain.model.brush.SourceBrushScalar
import com.artflow.studio.domain.model.brush.Stroke
import com.artflow.studio.domain.model.brush.StrokePoint
import com.artflow.studio.domain.model.layer.Layer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.zip.ZipInputStream

/** Finite native Count contracts and historical saved-brush compatibility. */
class BrushCountContractTest {
    @Test
    fun freshImportsTruncateNormalizedCountAndRecordVersionTwo() {
        val cases = listOf(0.0625f to 1, 0.1f to 2, 0.5f to 8, 0.9f to 14)
        for ((source, expected) in cases) {
            val imported = ProcreateBrush.parameters(mapOf("shapeCount" to source.toDouble()))
            assertEquals("Native count for $source", expected, imported.count)
            assertEquals(2, imported.sourceImportVersion)
            assertEquals(source, rawCount(imported), 0f)
            assertEquals(expected, exported(imported).count)
        }
    }

    @Test
    fun countThresholdsUseBinary32MultiplicationBeforeTruncation() {
        // Independently derived adjacent binary32 inputs around 7/15 and 8/15.
        // Flooring a Double product would misclassify some exact Float boundaries.
        val cases =
            listOf(
                0x3eeeeeee to 7,
                0x3eeeeeef to 8,
                0x3eeeeef0 to 8,
                0x3f088888 to 8,
                0x3f088889 to 9,
                0x3f08888a to 9,
            )
        for ((bits, expected) in cases) {
            val imported = ProcreateBrush.parameters(mapOf("shapeCount" to Float.fromBits(bits).toDouble()))
            assertEquals("Native count for source bits ${bits.toString(16)}", expected, imported.count)
        }
    }

    @Test
    fun missingCountAndFiniteLimitsRemainWithinTheNativeRange() {
        assertEquals(1, ProcreateBrush.parameters(emptyMap()).count)
        for ((source, expected) in listOf(-1f to 1, 0f to 1, 1f to 16, 2f to 16)) {
            assertEquals(expected, ProcreateBrush.parameters(mapOf("shapeCount" to source.toDouble())).count)
        }
    }

    @Test
    fun everySupportedLocalIntegerCountSurvivesActualArchiveExport() {
        for (count in 1..16) {
            val local = BrushParams(count = count)
            val reopened = exported(local)
            assertEquals("Exported Count $count", count, reopened.count)
            assertEquals(count, ProcreateBrushWriter.exportedCount(local))
            assertEquals(2, reopened.sourceImportVersion)
        }
    }

    @Test
    fun countsAboveSixteenExposeNativeLossAndStayIntactInArtbrush() {
        for (count in listOf(17, 32)) {
            val local = BrushParams(count = count)
            assertEquals(16, ProcreateBrushWriter.exportedCount(local))
            assertEquals(16, exported(local).count)
            assertEquals(count, local.count)
            val saved = listOf(SavedBrush("local-$count", "Local $count", local))
            assertEquals(saved, BrushFiles.decode(BrushFiles.encode(saved)).first)
        }
    }

    @Test
    fun previousImportVersionsKeepSavedCountsAndUneditedRawValues() {
        for (version in listOf(0, 1)) {
            for ((source, count, expectedNative) in listOf(Triple(0.0625f, 2, 1), Triple(0.5f, 9, 8))) {
                val previous = previousImport(version, source, count)
                val saved = listOf(SavedBrush("old-$version", "Earlier import", previous))
                val reopened = BrushLibraryCodec.decode(BrushLibraryCodec.encode(saved)).single().parameters
                assertEquals(previous, reopened)
                assertEquals(count, reopened.count)
                val native = exported(reopened)
                assertEquals(expectedNative, native.count)
                assertEquals(expectedNative, ProcreateBrushWriter.exportedCount(reopened))
                assertEquals(source, rawCount(native), 0f)
                assertSourceAliasesRetained(previous, native)
            }
        }
    }

    @Test
    fun editingEarlierImportsReplacesOnlyTheirNativeCount() {
        for (version in listOf(0, 1)) {
            val previous = previousImport(version, 0.5f, 9)
            for (count in listOf(1, 8, 10)) {
                val edited = previous.copy(count = count)
                val native = exported(edited)
                assertEquals(count, native.count)
                assertEquals(count, ProcreateBrushWriter.exportedCount(edited))
                assertEquals((count - 1) / 15f, rawCount(native), 0f)
                assertSourceAliasesRetained(previous, native)
                assertEquals(0.5f, rawCount(previous), 0f)
            }
        }
    }

    @Test
    fun versionTwoPreservesRawCountUntilTheArtistEditsIt() {
        for ((source, expected) in listOf(0.0625f to 1, 0.5f to 8)) {
            val current = ProcreateBrush.parameters(mapOf("shapeCount" to source.toDouble()))
            val unchanged = exported(current)
            assertEquals(expected, unchanged.count)
            assertEquals(source, rawCount(unchanged), 0f)
            assertEquals(expected, ProcreateBrushWriter.exportedCount(current))
            val edited = current.copy(count = expected + 1)
            assertEquals(expected + 1, exported(edited).count)
            assertEquals(expected / 15f, rawCount(exported(edited)), 0f)
            assertEquals(expected + 1, ProcreateBrushWriter.exportedCount(edited))
        }
    }

    @Test
    fun compoundComponentsResolveTheirOwnHistoricalCountAndEdits() {
        val secondary = previousImport(1, 0.0625f, 2)
        val primary = previousImport(0, 0.5f, 9).copy(dual = DualBrush(secondary))
        val native = exportedComponents(primary)
        assertEquals(8, native.getValue("Brush.archive").count)
        assertEquals(1, native.getValue("Sub01/Brush.archive").count)
        assertEquals(0.5f, rawCount(native.getValue("Brush.archive")), 0f)
        assertEquals(0.0625f, rawCount(native.getValue("Sub01/Brush.archive")), 0f)
        assertEquals(8, ProcreateBrushWriter.exportedCount(primary))
        assertEquals(1, ProcreateBrushWriter.exportedCount(secondary))

        val edited = primary.copy(dual = DualBrush(secondary.copy(count = 3)))
        val editedNative = exportedComponents(edited)
        assertEquals(8, editedNative.getValue("Brush.archive").count)
        assertEquals(0.5f, rawCount(editedNative.getValue("Brush.archive")), 0f)
        assertEquals(3, editedNative.getValue("Sub01/Brush.archive").count)
        assertEquals(2f / 15f, rawCount(editedNative.getValue("Sub01/Brush.archive")), 0f)
    }

    @Test
    fun eachImportVersionPersistsInLibraryArtbrushAndChecksummedDocuments() {
        for (version in 0..2) {
            val secondary =
                if (version < 2) previousImport(version, 0.0625f, 2) else ProcreateBrush.parameters(mapOf("shapeCount" to 0.0625))
            val primary =
                if (version < 2) previousImport(version, 0.5f, 9) else ProcreateBrush.parameters(mapOf("shapeCount" to 0.5))
            val params = primary.copy(dual = DualBrush(secondary))
            val saved = listOf(SavedBrush("version-$version", "Source version $version", params))
            val library = BrushLibraryCodec.encode(saved)
            assertEquals(saved, BrushLibraryCodec.decode(library))
            assertEquals(saved, BrushFiles.decode(BrushFiles.encode(saved)).first)
            if (version == 0) {
                assertFalse("sourceImportVersion" in library)
            } else {
                assertTrue("\"sourceImportVersion\":$version" in library)
            }

            val document = document(params)
            val encoded = CanvasDocumentCodec.encode(document)
            val reopened = CanvasDocumentCodec.decode(encoded.decodeToString())
            assertEquals(document, reopened)
            val actual =
                reopened.layers
                    .single()
                    .strokes
                    .single()
                    .brushParams
            assertEquals(version, actual.sourceImportVersion)
            assertEquals(version, requireNotNull(actual.dual).params.sourceImportVersion)
            assertEquals(if (version < 2) 9 else 8, actual.count)
            assertEquals(if (version < 2) 2 else 1, requireNotNull(actual.dual).params.count)
        }
    }

    /** Historical fields are explicit; new importer behavior does not manufacture the expected baseline. */
    private fun previousImport(
        version: Int,
        sourceCount: Float,
        count: Int,
    ): BrushParams {
        val source =
            mapOf(
                "shapeCount" to sourceCount,
                "dynamicsPressureOpacity" to -0.625,
                "dynamicsPressureOpacityTransfer" to 0.375,
                "dynamicsMix" to 0.625,
            )
        return BrushParams(
            size = 24f,
            smoothing = 0f,
            pressureToSize = 0f,
            count = count,
            sourceImportVersion = version,
            pressureToOpacity = if (version == 0) -0.625f else 0.375f,
            pressureToFlow = if (version == 0) 0f else -0.625f,
            wetMix = if (version == 0) 0.625f else 0f,
            wetDilution = if (version == 0) 0f else 0.625f,
            tiltOpacityInfluence = 0f,
            importedSourceScalars = source.mapValues { (_, value) -> requireNotNull(SourceBrushScalar.capture(value)) },
        )
    }

    private fun assertSourceAliasesRetained(
        previous: BrushParams,
        native: BrushParams,
    ) {
        for (name in listOf("dynamicsPressureOpacity", "dynamicsPressureOpacityTransfer", "dynamicsMix")) {
            assertEquals(name, previous.importedSourceScalars[name], native.importedSourceScalars[name])
        }
        assertEquals(-0.625f, native.pressureToFlow, 0f)
        assertEquals(0.375f, native.pressureToOpacity, 0f)
        assertEquals(0.625f, native.wetDilution, 0f)
    }

    private fun rawCount(params: BrushParams): Float =
        params.importedSourceScalars
            .getValue("shapeCount")
            .value
            .toFloat()

    private fun exported(params: BrushParams): BrushParams = exportedComponents(params).getValue("Brush.archive")

    private fun exportedComponents(params: BrushParams): Map<String, BrushParams> {
        val bytes = ProcreateBrushWriter.brush(ProcreateBrushWriter.Brush("Count contract", params))
        return ZipInputStream(bytes.inputStream()).use { zip ->
            buildMap {
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.name.endsWith("Brush.archive")) put(entry.name, ProcreateBrush.read(zip.readBytes()).parameters)
                }
            }
        }
    }

    private fun document(params: BrushParams): CanvasDocument =
        CanvasDocument(
            width = 8,
            height = 6,
            dpi = 72,
            backgroundColor = 0,
            activeLayerId = 1,
            nextLayerId = 2,
            layers =
                listOf(
                    Layer(
                        1,
                        "Vector ink",
                        0,
                        strokes = listOf(Stroke(1, listOf(StrokePoint(2f, 3f, timestamp = 100)), params, 1, 0xFF335577.toInt(), 100)),
                    ),
                ),
            savedAt = 200,
        )
}
