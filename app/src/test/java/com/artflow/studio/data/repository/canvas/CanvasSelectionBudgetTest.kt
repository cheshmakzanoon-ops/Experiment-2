package com.artflow.studio.data.repository.canvas

import com.artflow.studio.core.pixels.SelectionMask
import com.artflow.studio.core.pixels.SelectionMaskCodec
import com.artflow.studio.data.export.ArtflowPackage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class CanvasSelectionBudgetTest {
    @Test
    fun savedSlotsReserveBothRepositoryAndUiCopiesAtTheExactBoundary() {
        val budget = CanvasRasterBudget(64L * 64 * 2 * 3 * 8)
        assertTrue(budget.allowsSavedSelections(64, 64, 3))
        assertFalse(budget.allowsSavedSelections(64, 64, 4))
    }

    @Test
    fun largeCanvasCannotRetainEightIndependentSavedSlotsOnA512MiBHeap() {
        val budget = CanvasRasterBudget(512L * 1024 * 1024)
        assertTrue(budget.allowsSavedSelections(4096, 3072, 2))
        assertFalse(budget.allowsSavedSelections(4096, 3072, 3))
        assertFalse(budget.allowsSavedSelections(4096, 3072, 8))
    }

    @Test
    fun malformedDimensionsAndCountsFailWithoutOverflow() {
        val budget = CanvasRasterBudget(512L * 1024 * 1024)
        assertFalse(budget.allowsSavedSelections(Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE))
        assertFalse(budget.allowsSavedSelections(64, 0, 1))
        assertFalse(budget.allowsSavedSelections(64, 64, -1))
        assertTrue(budget.allowsSavedSelections(64, 64, 0))
    }

    @Test
    fun historyLimitUsesTheInjectedHeapAndRetainsTheAbsoluteCeiling() {
        assertEquals(128L * 1024 * 1024, CanvasRasterBudget(512L * 1024 * 1024).historyBytes)
        assertEquals(192L * 1024 * 1024, CanvasRasterBudget(1024L * 1024 * 1024).historyBytes)
        assertEquals(64L, CanvasRasterBudget(256L).historyBytes)
    }

    @Test
    fun rejectedSavedSlotsProvideAnActionableFailure() {
        val failure =
            runCatching {
                CanvasRasterBudget(64L * 64 * 2 * 8).requireSavedSelectionsFit(64, 64, 2)
            }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertEquals(CanvasRasterBudget.SAVED_SELECTION_LIMIT_MESSAGE, failure?.message)
    }

    @Test
    fun metadataAllowanceRejectsEightNoisy2048SlotsEvenWhenTheirMemoryFits() {
        val budget = CanvasRasterBudget(512L * 1024 * 1024)
        assertTrue(budget.allowsSavedSelections(2048, 2048, 8))
        assertFalse(budget.allowsSavedSelectionEncoding(2048, 2048, 8))
        assertTrue(budget.allowsSavedSelectionEncoding(2048, 2048, 4))
        assertTrue(budget.allowsSavedSelectionEncoding(4096, 4096, 1))
    }

    @Test
    fun encodedAllowanceIncludesEnvelopeBase64AndJsonAtItsBoundary() {
        // Four 8x8 masks: each has at most 77 zlib bytes, a 12-byte envelope,
        // 120 Base64 characters, two JSON quotes and a separator. Add the array brackets.
        val exact = CanvasRasterBudget(savedSelectionMetadataBytes = 494)
        assertTrue(exact.allowsSavedSelectionEncoding(8, 8, 4))
        assertFalse(CanvasRasterBudget(savedSelectionMetadataBytes = 493).allowsSavedSelectionEncoding(8, 8, 4))
        assertFalse(exact.allowsSavedSelectionEncoding(8, 8, 5))
    }

    @Test
    fun admittedRealCodecOutputStaysInsideTheReservedMetadataBytes() {
        val limit = 100_000L
        val budget = CanvasRasterBudget(savedSelectionMetadataBytes = limit)
        val random = Random(23)
        // Include deflate block boundaries and all three Base64 padding cases.
        for ((width, height) in listOf(1 to 1, 2 to 1, 3 to 1, 129 to 127, 128 to 128, 145 to 113, 255 to 257)) {
            val pixels = width * height
            val masks =
                listOf(
                    SelectionMask(width, height, ByteArray(pixels).also(random::nextBytes)),
                    SelectionMask(width, height, ByteArray(pixels) { 255.toByte() }),
                )
            masks.forEach { mask ->
                assertTrue(budget.allowsSavedSelectionEncoding(mask.width, mask.height, 1))
                val json = "[\"${SelectionMaskCodec.encode(mask)}\"]"
                assertTrue("$pixels pixels exceed the admitted metadata limit", json.length <= limit)
            }
        }
    }

    @Test
    fun encodedAllowanceRejectsInvalidInputsAndCannotOverflow() {
        val budget = CanvasRasterBudget(Long.MAX_VALUE)
        assertFalse(budget.allowsSavedSelectionEncoding(Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE))
        assertFalse(budget.allowsSavedSelectionEncoding(0, 64, 1))
        assertFalse(budget.allowsSavedSelectionEncoding(64, 64, -1))
        assertTrue(budget.allowsSavedSelectionEncoding(64, 64, 0))
        assertFalse(CanvasRasterBudget(savedSelectionMetadataBytes = 1).allowsSavedSelectionEncoding(1, 1, 1))
    }

    @Test
    fun metadataLimitCanOnlyNarrowAndDoesNotRestrictExistingSlotMemoryAdmission() {
        val maximum = ArtflowPackage.MAX_DOCUMENT_BYTES - 8L * 1024 * 1024
        assertTrue(runCatching { CanvasRasterBudget(savedSelectionMetadataBytes = 0) }.exceptionOrNull() is IllegalArgumentException)
        val widened = runCatching { CanvasRasterBudget(savedSelectionMetadataBytes = maximum + 1) }.exceptionOrNull()
        assertTrue(widened is IllegalArgumentException)
        val budget = CanvasRasterBudget(512L * 1024 * 1024, savedSelectionMetadataBytes = 1)
        assertTrue(budget.allowsSavedSelections(2048, 2048, 8))
        assertFalse(budget.allowsSavedSelectionEncoding(2048, 2048, 8))
    }

    @Test
    fun serializedSlotRejectionExplainsHowToMakeTheArtworkSavable() {
        val failure =
            runCatching {
                CanvasRasterBudget(512L * 1024 * 1024).requireSavedSelectionEncodingFits(2048, 2048, 8)
            }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertEquals(CanvasRasterBudget.SAVED_SELECTION_METADATA_LIMIT_MESSAGE, failure?.message)
    }
}
