package com.artflow.studio.presentation.ui.viewmodel

import com.artflow.studio.core.pixels.SelectionMask
import com.artflow.studio.domain.repository.canvas.CanvasRepository
import com.artflow.studio.domain.repository.canvas.CanvasSize
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock

class SavedSelectionsTest {
    private var current: SelectionMask? = null
    private var stored: List<SelectionMask> = emptyList()
    private var rejection: IllegalArgumentException? = null
    private var beforeCommit: (() -> Unit)? = null
    private val repository =
        mock(CanvasRepository::class.java) { call ->
            when (call.method.name) {
                "selection" -> current?.copy()
                "getCanvasSize" -> CanvasSize(4, 4, 72)
                "setSelection" -> {
                    current = (call.arguments[0] as SelectionMask?)?.copy()
                    null
                }

                "savedSelections" -> stored
                "setSavedSelections" -> {
                    beforeCommit?.invoke()
                    rejection?.let { throw it }
                    @Suppress("UNCHECKED_CAST")
                    stored = (call.arguments[0] as List<SelectionMask>).map { it.copy() }
                    null
                }

                else -> null
            }
        }

    @Test fun savedSelectionsCanBeRestoredAndDeleted() {
        var restored = 0
        val saved = SavedSelections(repository) { restored++ }
        assertFalse("Nothing to save without a selection", saved.save())
        current = SelectionMask(4, 4).apply { selectAll() }
        assertTrue(saved.save())
        current = null
        saved.load(0)
        assertTrue(requireNotNull(current).isFull())
        assertEquals(1, restored)
        saved.delete(0)
        assertTrue(saved.saved.value.isEmpty())
    }

    @Test fun savedSelectionsStayWithTheArtworkWhenItIsReopened() {
        val saved = SavedSelections(repository) {}
        current = SelectionMask(4, 4).apply { selectAll() }
        assertTrue(saved.save())
        assertEquals(1, stored.size)

        // Reopening the document builds a new controller over the same repository.
        val reopened = SavedSelections(repository) {}
        assertEquals(1, reopened.saved.value.size)
        reopened.load(0)
        assertTrue(requireNotNull(current).isFull())

        reopened.delete(0)
        assertTrue("Deleting must reach the document, not only the visible list", stored.isEmpty())
    }

    @Test fun loadingAnOlderMaskForAnotherCanvasIsIgnored() {
        stored = listOf(SelectionMask(8, 8).apply { selectAll() })
        val saved = SavedSelections(repository) {}
        saved.load(0)
        assertEquals(null, current)
    }

    @Test fun rejectedSaveRetainsPublishedSlotsAndReportsTheCapacityReason() {
        val failures = mutableListOf<String>()
        val saved = SavedSelections(repository, onFailure = failures::add) {}
        current = SelectionMask(4, 4).apply { selectAll() }
        assertTrue(saved.save())
        val published = saved.saved.value
        val persisted = stored
        current = SelectionMask(4, 4).apply { coverage[3] = 255.toByte() }
        rejection = IllegalArgumentException("Saved selections exceed the available memory; remove a slot")

        assertFalse(saved.save())

        assertSame(published, saved.saved.value)
        assertSame(persisted, stored)
        assertEquals(listOf(rejection!!.message), failures)
        assertEquals(1, requireNotNull(current).selectedPixelCount())
    }

    @Test fun rejectedDeleteAndClearKeepBothCopiesOfTheSavedSlots() {
        val failures = mutableListOf<String>()
        val saved = SavedSelections(repository, onFailure = failures::add) {}
        current = SelectionMask(4, 4).apply { selectAll() }
        assertTrue(saved.save())
        val published = saved.saved.value
        val persisted = stored
        rejection = IllegalArgumentException("Saved selections could not be changed")

        saved.delete(0)
        assertSame(published, saved.saved.value)
        assertSame(persisted, stored)
        saved.clear()
        assertSame(published, saved.saved.value)
        assertSame(persisted, stored)
        assertEquals(List(2) { "Saved selections could not be changed" }, failures)
    }

    @Test fun theVisibleSlotsChangeOnlyAfterTheDocumentAcceptsThem() {
        val saved = SavedSelections(repository) {}
        current = SelectionMask(4, 4).apply { selectAll() }
        beforeCommit = { assertTrue(saved.saved.value.isEmpty()) }

        assertTrue(saved.save())

        assertEquals(1, saved.saved.value.size)
        assertEquals(1, stored.size)
        val savedMask = saved.saved.value.single()
        requireNotNull(current).clear()
        assertTrue(savedMask.isFull())
        saved.load(0)
        requireNotNull(current).clear()
        assertTrue(savedMask.isFull())
        assertArrayEquals(savedMask.coverage, stored.single().coverage)
    }

    @Test fun missingSelectionAndUnexplainedRejectionHaveUsefulDistinctMessages() {
        val failures = mutableListOf<String>()
        val saved = SavedSelections(repository, onFailure = failures::add) {}

        assertFalse(saved.save())
        current = SelectionMask(4, 4).apply { selectAll() }
        rejection = IllegalArgumentException("")
        assertFalse(saved.save())

        assertEquals(listOf("Make a selection first", "Saved selections could not be updated"), failures)
        assertTrue(saved.saved.value.isEmpty())
        assertTrue(stored.isEmpty())
    }
}
