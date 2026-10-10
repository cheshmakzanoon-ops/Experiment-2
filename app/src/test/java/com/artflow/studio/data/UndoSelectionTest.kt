package com.artflow.studio.data

import android.content.Context
import com.artflow.studio.core.pixels.SelectionMask
import com.artflow.studio.data.local.ProjectStorage
import com.artflow.studio.data.repository.canvas.CanvasRepositoryImpl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock

/** Undo restores the selection that was active when the undone edit was made, as well as the pixels. */
@OptIn(ExperimentalCoroutinesApi::class)
class UndoSelectionTest {
    private lateinit var repository: CanvasRepositoryImpl

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        repository = CanvasRepositoryImpl(ProjectStorage(mock(Context::class.java)))
    }

    @After
    fun tearDown() {
        repository.dispose()
        Dispatchers.resetMain()
    }

    @Test
    fun undoPutsBackTheSelectionThatWasActiveWhenTheEditWasMade() =
        runTest {
            repository.createCanvas(8, 6, 72)
            val layer = repository.getActiveLayerId()
            repository.setSelection(SelectionMask(8, 6).apply { selectAll() })
            assertTrue(repository.setLayerOpacity(layer, 0.5f))
            repository.clearSelection()
            assertTrue(repository.undo())
            assertTrue(requireNotNull(repository.selection()).isFull())
        }

    @Test
    fun undoOfAnEditMadeWithoutASelectionLeavesNoSelection() =
        runTest {
            repository.createCanvas(8, 6, 72)
            val layer = repository.getActiveLayerId()
            assertTrue(repository.setLayerOpacity(layer, 0.5f))
            repository.setSelection(SelectionMask(8, 6).apply { selectAll() })
            assertTrue(repository.undo())
            assertNull(repository.selection())
        }
}
