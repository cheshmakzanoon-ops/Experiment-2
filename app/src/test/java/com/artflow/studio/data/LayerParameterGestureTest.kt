package com.artflow.studio.data

import android.content.Context
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.core.pixels.SelectionMask
import com.artflow.studio.data.local.ProjectStorage
import com.artflow.studio.data.repository.canvas.CanvasRasterBudget
import com.artflow.studio.data.repository.canvas.CanvasRepositoryImpl
import com.artflow.studio.domain.repository.canvas.CanvasRepository.LayerParameter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock

@OptIn(ExperimentalCoroutinesApi::class)
class LayerParameterGestureTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var repository: CanvasRepositoryImpl

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        repository = CanvasRepositoryImpl(ProjectStorage(mock(Context::class.java)))
    }

    @After
    fun tearDown() {
        repository.dispose()
        Dispatchers.resetMain()
    }

    private suspend fun open(): Long {
        repository.createCanvas(8, 8, 72)
        val id = repository.getActiveLayerId()
        assertTrue(repository.addLayerMask(id))
        return id
    }

    private fun value(parameter: LayerParameter): Float {
        val layer = requireNotNull(repository.getActiveLayer())
        return when (parameter) {
            LayerParameter.OPACITY -> layer.opacity
            LayerParameter.MASK_DENSITY -> layer.maskDensity
            LayerParameter.MASK_FEATHER -> layer.maskFeather
        }
    }

    @Test
    fun eachParameterKeepsOneOriginalStatePerDragAndSeparatesImmediateDrags() =
        runTest {
            for (parameter in LayerParameter.entries) {
                val id = open()
                val original = value(parameter)
                val depth = repository.undoDepth
                val first = requireNotNull(repository.beginLayerParameterGesture(id, parameter))
                listOf(0.9f, 0.7f, 0.5f).forEach { assertTrue(repository.updateLayerParameterGesture(first, it)) }
                repository.endLayerParameterGesture(first)
                assertEquals(depth + 1, repository.undoDepth)

                val second = requireNotNull(repository.beginLayerParameterGesture(id, parameter))
                listOf(0.4f, 0.3f, 0.2f).forEach { assertTrue(repository.updateLayerParameterGesture(second, it)) }
                repository.endLayerParameterGesture(second)
                assertEquals(depth + 2, repository.undoDepth)
                assertTrue(repository.undo())
                assertEquals(0.5f, value(parameter), 0f)
                assertTrue(repository.undo())
                assertEquals(original, value(parameter), 0f)
                assertTrue(repository.redo())
                assertEquals(0.5f, value(parameter), 0f)
                assertTrue(repository.redo())
                assertEquals(0.2f, value(parameter), 0f)
            }
        }

    @Test
    fun emptyAndInvalidGesturesPreserveRevisionHistoryAndRedo() =
        runTest {
            val id = open()
            repository.setLayerOpacity(id, 0.5f)
            assertTrue(repository.undo())
            val depth = repository.undoDepth
            val revision = repository.contentRevision
            val mark = repository.historyMark
            val gesture = requireNotNull(repository.beginLayerParameterGesture(id, LayerParameter.OPACITY))
            assertTrue(repository.updateLayerParameterGesture(gesture, 1f))
            assertTrue(repository.updateLayerParameterGesture(gesture, 2f))
            assertFalse(repository.updateLayerParameterGesture(gesture, Float.NaN))
            assertFalse(repository.updateLayerParameterGesture(gesture, Float.POSITIVE_INFINITY))
            repository.endLayerParameterGesture(gesture)
            assertEquals(depth, repository.undoDepth)
            assertEquals(1, repository.redoDepth)
            assertEquals(revision, repository.contentRevision)
            assertEquals(mark, repository.historyMark)
        }

    @Test
    fun cancelledTokensAndOldFinishCallbacksCannotJoinOrCloseANewDrag() =
        runTest {
            val id = open()
            val first = requireNotNull(repository.beginLayerParameterGesture(id, LayerParameter.OPACITY))
            assertTrue(repository.updateLayerParameterGesture(first, 0.6f))
            repository.endLayerParameterGesture(first)
            assertFalse(repository.updateLayerParameterGesture(first, 0.1f))
            val second = requireNotNull(repository.beginLayerParameterGesture(id, LayerParameter.OPACITY))
            repository.endLayerParameterGesture(first)
            assertTrue(repository.updateLayerParameterGesture(second, 0.4f))
            assertTrue(repository.undo())
            assertEquals(0.6f, value(LayerParameter.OPACITY), 0f)
            assertFalse(repository.updateLayerParameterGesture(second, 0.2f))
            assertTrue(repository.redo())
            assertFalse(repository.updateLayerParameterGesture(second, 0.2f))
            assertEquals(0.4f, value(LayerParameter.OPACITY), 0f)
        }

    @Test
    fun anInterveningEditSealsTheGestureEvenWhenItTargetsAnotherProperty() =
        runTest {
            val id = open()
            val gesture = requireNotNull(repository.beginLayerParameterGesture(id, LayerParameter.MASK_DENSITY))
            assertTrue(repository.updateLayerParameterGesture(gesture, 0.6f))
            assertTrue(repository.setLayerName(id, "Renamed"))
            assertFalse(repository.updateLayerParameterGesture(gesture, 0.2f))
            assertTrue(repository.undo())
            assertEquals(0.6f, value(LayerParameter.MASK_DENSITY), 0f)
            assertTrue(repository.undo())
            assertEquals(1f, value(LayerParameter.MASK_DENSITY), 0f)
        }

    @Test
    fun frameAndLayerRoundTripsInvalidateOldCallbacks() =
        runTest {
            val id = open()
            val other = repository.addLayer("Other").id
            repository.addFrame(false)
            repository.selectFrame(0)
            repository.setActiveLayer(id)
            val frameGesture = requireNotNull(repository.beginLayerParameterGesture(id, LayerParameter.OPACITY))
            assertTrue(repository.updateLayerParameterGesture(frameGesture, 0.6f))
            repository.selectFrame(1)
            repository.selectFrame(0)
            assertFalse(repository.updateLayerParameterGesture(frameGesture, 0.2f))

            val layerGesture = requireNotNull(repository.beginLayerParameterGesture(id, LayerParameter.MASK_FEATHER))
            assertTrue(repository.updateLayerParameterGesture(layerGesture, 12f))
            repository.setActiveLayer(other)
            repository.setActiveLayer(id)
            assertFalse(repository.updateLayerParameterGesture(layerGesture, 24f))
            assertEquals(12f, value(LayerParameter.MASK_FEATHER), 0f)
        }

    @Test
    fun replacementDocumentsCannotReuseAGestureEvenWhenTheirLayerIdsMatch() =
        runTest {
            val id = open()
            val gesture = requireNotNull(repository.beginLayerParameterGesture(id, LayerParameter.OPACITY))
            assertTrue(repository.updateLayerParameterGesture(gesture, 0.4f))
            repository.createCanvas(8, 8, 72)
            assertEquals(id, repository.getActiveLayerId())
            val revision = repository.contentRevision
            assertFalse(repository.updateLayerParameterGesture(gesture, 0.2f))
            assertEquals(1f, value(LayerParameter.OPACITY), 0f)
            assertEquals(0, repository.undoDepth)
            assertEquals(revision, repository.contentRevision)
        }

    @Test
    fun selectionAndPixelEditOwnershipAreNotBypassed() =
        runTest {
            val id = open()
            val gesture = requireNotNull(repository.beginLayerParameterGesture(id, LayerParameter.OPACITY))
            assertTrue(repository.updateLayerParameterGesture(gesture, 0.6f))
            repository.setSelection(SelectionMask(8, 8).apply { selectAll() })
            assertFalse(repository.updateLayerParameterGesture(gesture, 0.4f))
            val raster = requireNotNull(repository.beginRasterEdit(id))
            val depth = repository.undoDepth
            assertNull(repository.beginLayerParameterGesture(id, LayerParameter.MASK_DENSITY))
            assertEquals(depth, repository.undoDepth)
            repository.cancelRasterEdit(raster)
            assertNotNull(repository.beginLayerParameterGesture(id, LayerParameter.MASK_DENSITY))
        }

    @Test
    fun gestureHistoryCannotRetainASnapshotOutsideTheMemoryBudget() =
        runTest {
            repository.dispose()
            repository =
                CanvasRepositoryImpl(
                    ProjectStorage(mock(Context::class.java)),
                    dispatcher,
                    rasterBudget = CanvasRasterBudget(10L * 1_024),
                )
            // Three 1 KiB planes fit document admission but exceed the 2.5 KiB history allowance.
            repository.createCanvas(16, 16, 72)
            assertTrue(repository.addLayerMask(repository.getActiveLayerId()))
            val id = repository.addLayer("Third plane").id
            assertTrue(repository.setLayerPixels(id, PixelBuffer(16, 16), "Fixture"))
            val position = repository.historyPosition
            val gesture = requireNotNull(repository.beginLayerParameterGesture(id, LayerParameter.OPACITY))
            repeat(100) { assertTrue(repository.updateLayerParameterGesture(gesture, it / 100f)) }
            assertEquals(position + 1, repository.historyPosition)
            assertEquals(0, repository.undoDepth)
            assertFalse(repository.undo())
            assertFalse(repository.updateLayerParameterGesture(gesture, 0.4f))
            assertEquals(0.99f, value(LayerParameter.OPACITY), 0f)
        }

    @Test
    fun ordinaryOpacityRepeatsStillCoalesceButCannotJoinAPointerDrag() =
        runTest {
            val id = open()
            val depth = repository.undoDepth
            repository.setLayerOpacity(id, 0.9f)
            repository.setLayerOpacity(id, 0.8f)
            assertEquals(depth + 1, repository.undoDepth)
            val gesture = requireNotNull(repository.beginLayerParameterGesture(id, LayerParameter.OPACITY))
            assertTrue(repository.updateLayerParameterGesture(gesture, 0.6f))
            repository.endLayerParameterGesture(gesture)
            repository.setLayerOpacity(id, 0.4f)
            repository.setLayerOpacity(id, 0.2f)
            assertEquals(depth + 3, repository.undoDepth)
            assertTrue(repository.undo())
            assertEquals(0.6f, value(LayerParameter.OPACITY), 0f)
            assertTrue(repository.undo())
            assertEquals(0.8f, value(LayerParameter.OPACITY), 0f)
        }
}
