package com.artflow.studio.data.local

import com.artflow.studio.domain.model.brush.BrushLibraryCodec
import com.artflow.studio.domain.model.brush.BrushParams
import com.artflow.studio.domain.model.brush.DualBrush
import com.artflow.studio.domain.model.brush.SavedBrush
import com.artflow.studio.domain.model.brush.SourceBrushScalar
import com.artflow.studio.domain.model.brush.SourceBrushStructuredField
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrushTextPreservationTest {
    @Test
    fun everyAcceptedNameCodeUnitSurvivesLibraryAndSharedFileUtf8() {
        val names = listOf("X\uD800Y", "X\uDC00Y", "X\uD83D\uDE00Y", ("a".repeat(79) + "\uD83D\uDE00").take(80))
        names.forEach { name ->
            assertTrue(BrushLibraryCodec.validName(name))
            val original = listOf(SavedBrush("preserved", name, BrushParams()))
            assertEquals(original, BrushLibraryCodec.decode(wire(BrushLibraryCodec.encode(original))))
            assertEquals(original, BrushFiles.decode(wire(BrushFiles.encode(original))).first)
        }
    }

    @Test
    fun sourceAndDualMetadataPreserveIsolatedUnitsPairsAndEscapedControls() {
        val texts = listOf("before\uD800after", "before\uDC00after", "pair\uD83D\uDE00", "quotes\"\\\n\t\r\b\u000c")
        val scalars = texts.mapIndexed { index, text -> "field$index" to SourceBrushScalar(SourceBrushScalar.Type.TEXT, text) }.toMap()
        assertTrue(scalars.values.all { it.isValid() })
        val parameters =
            BrushParams(
                importedSourceScalars = scalars,
                importedSourceStructures =
                    mapOf(
                        "attackRollParameters" to
                            SourceBrushStructuredField("ValkyrieBrushRollParameters", SourceBrushStructuredField.Kind.FIELDS, scalars),
                    ),
            )
        val original = listOf(SavedBrush("metadata", "Source metadata", parameters.copy(dual = DualBrush(parameters))))
        assertEquals(original, BrushLibraryCodec.decode(wire(BrushLibraryCodec.encode(original))))
        assertEquals(original, BrushFiles.decode(wire(BrushFiles.encode(original))).first)
    }

    private fun wire(encoded: String): String =
        encoded.encodeToByteArray(throwOnInvalidSequence = true).decodeToString(throwOnInvalidSequence = true)
}
