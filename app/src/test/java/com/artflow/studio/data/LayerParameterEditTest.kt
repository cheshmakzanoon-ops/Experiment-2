package com.artflow.studio.data

import android.content.Context
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.data.local.ProjectStorage
import com.artflow.studio.data.repository.canvas.CanvasRepositoryImpl
import com.artflow.studio.domain.model.layer.AdjustmentType
import com.artflow.studio.domain.model.layer.FilterType
import com.artflow.studio.domain.model.layer.Layer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock

/** Property edits must have the same transactional history guarantees as pixel edits. */
@OptIn(ExperimentalCoroutinesApi::class)
class LayerParameterEditTest {
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

    private suspend fun open(): Long {
        repository.createCanvas(8, 6, 72)
        val id = repository.getActiveLayerId()
        repository.setLayerPixels(id, PixelBuffer.filled(8, 6, 0xFF52637A.toInt()), "Fixture")
        return id
    }

    private fun layer(id: Long): Layer = repository.getAllLayers().first { it.id == id }

    @Test
    fun adjustmentChangeHasItsOwnUndoAndRedoWithoutRemovingTheLayer() =
        runTest {
            open()
            val id = requireNotNull(repository.addAdjustmentLayer(AdjustmentType.BRIGHTNESS_CONTRAST)).id
            val before = requireNotNull(repository.compositeFrame(0, true)).pixels.copyOf()
            val depth = repository.undoDepth
            assertTrue(repository.setAdjustmentParameter(id, "brightness", 42f))
            assertEquals(depth + 1, repository.undoDepth)
            val changed = requireNotNull(repository.compositeFrame(0, true)).pixels.copyOf()
            assertFalse(before.contentEquals(changed))
            assertTrue(repository.undo())
            assertEquals(0f, layer(id).adjustmentParameters.getValue("brightness"), 0f)
            assertArrayEquals(before, requireNotNull(repository.compositeFrame(0, true)).pixels)
            assertTrue(repository.redo())
            assertEquals(42f, layer(id).adjustmentParameters.getValue("brightness"), 0f)
            assertArrayEquals(changed, requireNotNull(repository.compositeFrame(0, true)).pixels)
        }

    @Test
    fun aBulkAdjustmentCommitsOneOwnedUndoStep() =
        runTest {
            open()
            val type = AdjustmentType.BRIGHTNESS_CONTRAST
            val id = requireNotNull(repository.addAdjustmentLayer(type)).id
            val values = mutableMapOf("brightness" to 35f, "contrast" to 21f)
            val depth = repository.undoDepth
            assertTrue(repository.setAdjustmentParameters(id, values))
            values["brightness"] = -99f
            assertEquals(35f, layer(id).adjustmentParameters.getValue("brightness"), 0f)
            assertEquals(depth + 1, repository.undoDepth)
            assertTrue(repository.undo())
            assertEquals(type.defaultParameters, layer(id).adjustmentParameters)
        }

    @Test
    fun invalidBulkValuesCannotPartiallyMutateTheAdjustment() =
        runTest {
            open()
            val type = AdjustmentType.BRIGHTNESS_CONTRAST
            val id = requireNotNull(repository.addAdjustmentLayer(type)).id
            val depth = repository.undoDepth
            val revision = repository.contentRevision
            for (invalid in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
                assertFalse(repository.setAdjustmentParameters(id, linkedMapOf("brightness" to 42f, "contrast" to invalid)))
                assertEquals(type.defaultParameters, layer(id).adjustmentParameters)
            }
            assertEquals(depth, repository.undoDepth)
            assertEquals(revision, repository.contentRevision)
        }

    @Test
    fun unknownParametersAreRejectedRatherThanPersisted() =
        runTest {
            open()
            val id = requireNotNull(repository.addAdjustmentLayer(AdjustmentType.BRIGHTNESS_CONTRAST)).id
            val original = layer(id).adjustmentParameters
            val depth = repository.undoDepth
            assertFalse(repository.setAdjustmentParameter(id, "brightnes", 50f))
            assertFalse(repository.setAdjustmentParameters(id, linkedMapOf("brightness" to 20f, "unknown" to 7f)))
            assertEquals(original, layer(id).adjustmentParameters)
            assertEquals(depth, repository.undoDepth)
        }

    @Test
    fun identicalAdjustmentDoesNotEraseRedoOrChangeRevision() =
        runTest {
            open()
            val id = requireNotNull(repository.addAdjustmentLayer(AdjustmentType.BRIGHTNESS_CONTRAST)).id
            repository.setAdjustmentParameter(id, "brightness", 10f)
            repository.undo()
            val depth = repository.undoDepth
            val redo = repository.redoDepth
            val revision = repository.contentRevision
            assertTrue(repository.setAdjustmentParameter(id, "brightness", 0f))
            assertTrue(repository.setAdjustmentParameters(id, emptyMap()))
            assertTrue(repository.resetAdjustment(id))
            assertEquals(depth, repository.undoDepth)
            assertEquals(redo, repository.redoDepth)
            assertEquals(revision, repository.contentRevision)
        }

    @Test
    fun filterStrengthHasItsOwnUndoAndRedo() =
        runTest {
            open()
            val id = requireNotNull(repository.addFilterLayer(FilterType.VIGNETTE)).id
            val original = layer(id).filterAmount
            val depth = repository.undoDepth
            assertTrue(repository.setFilterAmount(id, 0.9f))
            assertEquals(depth + 1, repository.undoDepth)
            assertTrue(repository.undo())
            assertEquals(original, layer(id).filterAmount, 0f)
            assertTrue(repository.redo())
            assertEquals(0.9f, layer(id).filterAmount, 0f)
        }

    @Test
    fun filterNoOpAndInvalidValuesLeaveHistoryAndRevisionUntouched() =
        runTest {
            open()
            val id = requireNotNull(repository.addFilterLayer(FilterType.VIGNETTE)).id
            val original = layer(id).filterAmount
            val depth = repository.undoDepth
            val revision = repository.contentRevision
            assertTrue(repository.setFilterAmount(id, original))
            for (invalid in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
                assertFalse(repository.setFilterAmount(id, invalid))
            }
            assertEquals(original, layer(id).filterAmount, 0f)
            assertEquals(depth, repository.undoDepth)
            assertEquals(revision, repository.contentRevision)
        }

    @Test
    fun nonFiniteOpacityCannotPoisonTheDocument() =
        runTest {
            val id = open()
            val depth = repository.undoDepth
            val revision = repository.contentRevision
            for (invalid in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
                assertFalse(repository.setLayerOpacity(id, invalid))
                assertEquals(1f, layer(id).opacity, 0f)
            }
            assertEquals(depth, repository.undoDepth)
            assertEquals(revision, repository.contentRevision)
            assertTrue(repository.setLayerOpacity(id, 0.4f))
            assertEquals(0.4f, layer(id).opacity, 0f)
        }

    @Test
    fun nonFiniteLayerCreationIsRejectedBeforeAddingHistory() =
        runTest {
            open()
            val original = repository.getAllLayers()
            val depth = repository.undoDepth
            for (invalid in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
                val failure = runCatching { repository.addLayer(opacity = invalid) }.exceptionOrNull()
                assertTrue(failure is IllegalArgumentException)
            }
            assertEquals(original, repository.getAllLayers())
            assertEquals(depth, repository.undoDepth)
        }

    @Test
    fun maskParametersRejectNonFiniteValuesWithoutMutatingTheMask() =
        runTest {
            val id = open()
            repository.addLayerMask(id)
            val original = layer(id)
            val depth = repository.undoDepth
            val revision = repository.contentRevision
            for (invalid in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
                assertFalse(repository.setLayerMaskDensity(id, invalid))
                assertFalse(repository.setLayerMaskFeather(id, invalid))
            }
            assertEquals(original, layer(id))
            assertEquals(depth, repository.undoDepth)
            assertEquals(revision, repository.contentRevision)
        }

    @Test
    fun identicalMaskParametersDoNotCreateEdits() =
        runTest {
            val id = open()
            repository.addLayerMask(id)
            val depth = repository.undoDepth
            val revision = repository.contentRevision
            assertTrue(repository.setLayerMaskDensity(id, 1f))
            assertTrue(repository.setLayerMaskFeather(id, 0f))
            assertEquals(depth, repository.undoDepth)
            assertEquals(revision, repository.contentRevision)
        }

    @Test
    fun finiteOutOfRangeParametersClampAndRemainUndoable() =
        runTest {
            val id = open()
            repository.addLayerMask(id)
            assertTrue(repository.setLayerMaskFeather(id, 100f))
            assertEquals(64f, layer(id).maskFeather, 0f)
            assertTrue(repository.undo())
            assertEquals(0f, layer(id).maskFeather, 0f)
            val effect = requireNotNull(repository.addAdjustmentLayer(AdjustmentType.BRIGHTNESS_CONTRAST)).id
            assertTrue(repository.setAdjustmentParameter(effect, "brightness", 500f))
            assertEquals(100f, layer(effect).adjustmentParameters.getValue("brightness"), 0f)
            assertTrue(repository.undo())
            assertEquals(0f, layer(effect).adjustmentParameters.getValue("brightness"), 0f)
        }
}
