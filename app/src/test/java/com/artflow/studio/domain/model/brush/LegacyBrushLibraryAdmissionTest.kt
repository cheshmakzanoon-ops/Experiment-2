package com.artflow.studio.domain.model.brush

import com.artflow.studio.core.serialization.JsonLimitException
import com.artflow.studio.core.serialization.JsonReadBudget
import com.artflow.studio.data.local.BrushFiles
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Dense schema-v1 documents verified against the captured pre-admission writer and reader. */
class LegacyBrushLibraryAdmissionTest {
    @Test
    fun ordinaryLegacyLibraryBelowTheExistingCharacterCapStillReads() {
        val fixture = legacyLibrary(dual = false, auxiliaryCurves = 25)
        assertEquals(3_033_789, fixture.wire.length)
        assertTrue(fixture.wire.length < BrushLibraryCodec.MAX_CHARACTERS)
        assertEquals(fixture.brushes, BrushLibraryCodec.decode(fixture.wire))
    }

    @Test
    fun dualLegacyLibraryBelowTheExistingCharacterCapStillReads() {
        val fixture = legacyLibrary(dual = true, auxiliaryCurves = 0)
        assertEquals(2_991_421, fixture.wire.length)
        assertTrue(fixture.wire.length < BrushLibraryCodec.MAX_CHARACTERS)
        assertEquals(fixture.brushes, BrushLibraryCodec.decode(fixture.wire))
    }

    @Test
    fun legacyLibrariesStillPayEveryTokenBeforeDeserialization() {
        val fixture = legacyLibrary(dual = true, auxiliaryCurves = 0)
        // The original writer's token inventory includes container, object-key and value tokens.
        val requiredBytes = fixture.wire.length * 2L + DUAL_TOKENS * 128L
        val exact = JsonReadBudget(requiredBytes)
        assertEquals(fixture.brushes, BrushLibraryCodec.decode(fixture.wire, exact))
        assertEquals(0L, exact.remainingBytes)

        val failure =
            assertThrows(JsonLimitException::class.java) {
                BrushLibraryCodec.decode(fixture.wire, JsonReadBudget(requiredBytes - 1, "Token memory exhausted"))
            }
        assertEquals("Token memory exhausted", failure.message)
    }

    @Test
    fun nestedLegacyLibrariesKeepTheBrushFileMemoryReservation() {
        val fixture = legacyLibrary(dual = true, auxiliaryCurves = 0)
        val sharedFile =
            buildJsonObject {
                put("format", "artflow-brush-1")
                put("library", fixture.wire)
            }.toString()
        assertTrue(sharedFile.length < BrushFiles.MAX_BYTES)
        val libraryBytes = fixture.wire.length * 2L + DUAL_TOKENS * 128L
        // The outer object, its two keys and its two string values add five charged tokens.
        val combinedBytes = libraryBytes + sharedFile.length * 2L + 5L * 128
        val restored = BrushFiles.decode(sharedFile, combinedBytes)
        assertEquals(fixture.brushes, restored.first)
        assertTrue(restored.second.isEmpty())

        assertThrows(JsonLimitException::class.java) { BrushFiles.decode(sharedFile, libraryBytes) }
    }

    private fun legacyLibrary(
        dual: Boolean,
        auxiliaryCurves: Int,
    ): LegacyFixture {
        val curve = PressureMapping(List(16) { PressureMapping.Point(it / 100f, 0f) })
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
        val names = alphabet.map { it.toString() } + alphabet.flatMap { first -> alphabet.map { second -> "$first$second" } }
        val scalars = names.take(256).associateWith { SourceBrushScalar(SourceBrushScalar.Type.TEXT, "") }
        val base =
            BrushParams(
                importedPressure = ImportedPressure(curve, curve, curve, curve),
                importedAuxiliaryCurves = (0 until auxiliaryCurves).associate { "dynamicsPressureC$it" to curve },
                importedSourceScalars = scalars,
            )
        val parameters = if (dual) base.copy(dual = DualBrush(base)) else base
        val brushes = List(128) { SavedBrush("legacy-$it", "Legacy $it", parameters) }
        // Match the earlier schema-v1 writer's field order and encodeDefaults policy explicitly.
        val wire = legacyJson.encodeToString(LegacyArchive.serializer(), LegacyArchive(1, brushes))
        return LegacyFixture(brushes, wire)
    }

    private data class LegacyFixture(
        val brushes: List<SavedBrush>,
        val wire: String,
    )

    @Serializable
    private data class LegacyArchive(
        val version: Int,
        val brushes: List<SavedBrush>,
    )

    private companion object {
        val legacyJson = Json { encodeDefaults = true }
        const val DUAL_TOKENS = 524_933L
    }
}
