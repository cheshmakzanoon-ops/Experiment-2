package com.artflow.studio.core.color

import com.artflow.studio.core.color.ProcreateSwatchesBudgetTest.Companion.PALETTE
import com.artflow.studio.core.color.ProcreateSwatchesBudgetTest.Companion.RED
import com.artflow.studio.core.color.ProcreateSwatchesBudgetTest.Companion.SWATCH
import com.artflow.studio.core.color.ProcreateSwatchesBudgetTest.Companion.archive
import com.artflow.studio.core.serialization.JsonLimitException
import com.artflow.studio.core.serialization.JsonReadBudget
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcreateSwatchesJsonAdmissionTest {
    @Test
    fun aSixtyFifthPaletteIsRefusedBeforeDomParsing() {
        refusedBeforeParsing(List(65) { PALETTE.removeSurrounding("[", "]") }.joinToString(",", "[", "]"))
    }

    @Test
    fun theFullSixtyFourPaletteBoundaryRemainsReadable() {
        val json = List(64) { PALETTE.removeSurrounding("[", "]") }.joinToString(",", "[", "]")
        val palettes = ProcreateSwatches.read(archive("Swatches.json" to json))
        assertEquals(64, palettes.size)
        assertTrue(palettes.all { it.name == "Tiny" && it.colors == listOf(RED) })
    }

    @Test
    fun anExcessSwatchSlotIsRefusedBeforeDomParsingEvenWhenEverySlotIsNull() {
        refusedBeforeParsing("{\"swatches\":[" + "null,".repeat(65_536) + "null]}")
    }

    @Test
    fun theFullSwatchSlotBoundaryKeepsEmptySlotTolerance() {
        val json = "{\"name\":\"Full slots\",\"swatches\":[" + "null,".repeat(65_535) + "$SWATCH]}"
        val palette = ProcreateSwatches.read(archive("Swatches.json" to json)).single()
        assertEquals("Full slots", palette.name)
        assertEquals(listOf(RED), palette.colors)
    }

    @Test
    fun ignoredMetadataCannotExceedTheNestingBudgetBeforeDomParsing() {
        val nested = "[".repeat(16) + "0" + "]".repeat(16)
        refusedBeforeParsing("{\"metadata\":$nested,\"swatches\":[$SWATCH]}")
    }

    @Test
    fun escapedDuplicateKeysAreRefusedBeforeDomParsing() {
        refusedBeforeParsing("{\"swatches\":[],\"\\u0073watches\":[$SWATCH]}")
    }

    @Test
    fun longUnknownTextIsRefusedBeforeDomParsing() {
        refusedBeforeParsing("{\"note\":\"${"x".repeat(4097)}\",\"swatches\":[$SWATCH]}")
    }

    @Test
    fun identityAwareProbeSurfacesARealPaletteResourceError() {
        val bytes = archive("Swatches.json" to ("[".repeat(17) + "0" + "]".repeat(17)))
        val failure =
            assertThrows(JsonLimitException::class.java) {
                ProcreateSwatches.probeForImport(bytes) {}
            }
        assertTrue(requireNotNull(failure.message).contains("smaller palette"))
    }

    @Test
    fun malformedSinglePaletteAndUnknownSmallFieldsRetainTheirCompatibility() {
        val text = "{\"name\":\"Tolerance\",\"metadata\":[1,true,{}],\"swatches\":[null,1,\"x\",$SWATCH]}"
        val palette = ProcreateSwatches.read(archive("Swatches.json" to text)).single()
        assertEquals("Tolerance", palette.name)
        assertEquals(listOf(RED), palette.colors)
    }

    @Test
    fun writerTokenAdmissionAcceptsTheExactDocumentCapAndReservesItsFullMemoryCost() {
        val budget = JsonReadBudget(64L * 1024 * 1024)
        budget.reserveTokens(524_288)
        assertThrows(JsonLimitException::class.java) { budget.reserve(1) }
    }

    @Test
    fun writerTokenAdmissionChecksCountBeforeMultiplyingOrReserving() {
        val budget = JsonReadBudget(64L * 1024 * 1024)
        for (count in listOf(-1L, 524_289L, Long.MAX_VALUE)) {
            assertThrows(JsonLimitException::class.java) { budget.reserveTokens(count) }
        }
        budget.reserveTokens(524_288)
    }

    private fun refusedBeforeParsing(text: String) {
        var parses = 0
        assertThrows(JsonLimitException::class.java) {
            ProcreateSwatches.read(
                archive("Swatches.json" to text),
                ProcreateSwatches.ReadOptions(),
                parse = {
                    parses++
                    Json.parseToJsonElement(it)
                },
            )
        }
        assertEquals("Admission must precede materializing a JSON tree", 0, parses)
    }
}
