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
    private val repository =
        mock(CanvasRepository::class.java) { call ->
            when (call.method.name) {
                "selection" -> current
                "getCanvasSize" -> CanvasSize(4, 4, 72)
                "setSelection" -> {
                    current = call.arguments[0] as SelectionMask?
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
}
