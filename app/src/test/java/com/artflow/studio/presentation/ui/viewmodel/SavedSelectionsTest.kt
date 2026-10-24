package com.artflow.studio.presentation.ui.viewmodel

import com.artflow.studio.core.pixels.SelectionMask
import com.artflow.studio.domain.repository.canvas.CanvasRepository
import com.artflow.studio.domain.repository.canvas.CanvasSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock

class SavedSelectionsTest {
    private var current: SelectionMask? = null
    private var stored: List<SelectionMask> = emptyList()
    private val repository =
        mock(CanvasRepository::class.java) { call ->
            when (call.method.name) {
                "selection" -> current
                "getCanvasSize" -> CanvasSize(4, 4, 72)
                "setSelection" -> {
                    current = call.arguments[0] as SelectionMask?
                    null
                }

                "savedSelections" -> stored
                "setSavedSelections" -> {
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
}
