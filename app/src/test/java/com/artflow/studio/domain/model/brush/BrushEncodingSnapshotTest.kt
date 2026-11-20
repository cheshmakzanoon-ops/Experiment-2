package com.artflow.studio.domain.model.brush

import com.artflow.studio.core.serialization.JsonLimitException
import com.artflow.studio.core.serialization.JsonReadBudget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BrushEncodingSnapshotTest {
    @Test
    fun callerEditsCannotChangeAnyCollectionBetweenSerializationPasses() {
        val points = mutableListOf(PressureMapping.Point(0f, 0f), PressureMapping.Point(1f, 1f))
        val curve = PressureMapping(points)
        val curves = mutableMapOf("dynamicsPressureSizeCurve" to curve)
        val text = SourceBrushScalar(SourceBrushScalar.Type.TEXT, "source\uD800")
        val scalars = mutableMapOf("text" to text)
        val fields = mutableMapOf("text" to text)
        val items = mutableListOf(SourceBrushScalar(SourceBrushScalar.Type.REAL, "0.25"))
        val structures =
            mutableMapOf(
                "attackRollParameters" to
                    SourceBrushStructuredField("ValkyrieBrushRollParameters", SourceBrushStructuredField.Kind.FIELDS, fields),
                "savedPaintSizes" to SourceBrushStructuredField("NSArray", SourceBrushStructuredField.Kind.ARRAY, items = items),
            )
        val parameters =
            BrushParams(
                importedPressure = ImportedPressure(curve, curve, curve, curve),
                importedAuxiliaryCurves = curves,
                importedSourceScalars = scalars,
                importedSourceStructures = structures,
            )
        val originals = mutableListOf(SavedBrush("source", "Source", parameters.copy(dual = DualBrush(parameters))))
        val expected = BrushLibraryCodec.encode(originals)
        val owned = BrushEncodingSnapshot(JsonReadBudget()).copy(originals)
        originals.clear()
        points.clear()
        curves.clear()
        scalars.clear()
        fields.clear()
        items.clear()
        structures.clear()
        assertEquals(expected, BrushLibraryCodec.encode(owned))
    }

    @Test
    fun oversizeLibrariesAreRefusedBeforeReadingTheirFirstBrush() {
        val originals = CountingBrushes(129)
        assertThrows(IllegalArgumentException::class.java) { BrushLibraryCodec.encode(originals) }
        assertEquals(0, originals.reads)
    }

    @Test
    fun snapshotStorageIsAdmittedBeforeCopyingSourceEntries() {
        val originals = CountingBrushes(1)
        assertThrows(JsonLimitException::class.java) { BrushEncodingSnapshot(JsonReadBudget(64)).copy(originals) }
        assertEquals(0, originals.reads)
    }

    private class CountingBrushes(
        override val size: Int,
    ) : AbstractList<SavedBrush>() {
        var reads = 0

        override fun get(index: Int): SavedBrush {
            reads++
            return SavedBrush("brush-$index", "Ink", BrushParams())
        }
    }
}
