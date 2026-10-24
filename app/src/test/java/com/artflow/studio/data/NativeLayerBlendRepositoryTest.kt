package com.artflow.studio.data

import android.content.Context
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.data.local.ProjectStorage
import com.artflow.studio.data.repository.canvas.CanvasRepositoryImpl
import com.artflow.studio.domain.model.layer.BlendMode
import com.artflow.studio.domain.model.layer.LayerBlendContract
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

@OptIn(ExperimentalCoroutinesApi::class)
class NativeLayerBlendRepositoryTest {
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
    fun explicitlyChoosingTheSameStandardModeClearsNativeContractWithUndoAndRedo() =
        runTest {
            val id = open()
            assertArrayEquals(bands(NATIVE), pixels())
            val depth = repository.undoDepth
            assertTrue(repository.setLayerBlendMode(id, BlendMode.DARKEN))
            assertEquals(depth + 1, repository.undoDepth)
            assertEquals(LayerBlendContract.STANDARD, contract(id))
            assertArrayEquals(bands(STANDARD), pixels())
            assertTrue(repository.undo())
            assertEquals(LayerBlendContract.PROCREATE_DARKEN_19, contract(id))
            assertArrayEquals(bands(NATIVE), pixels())
            assertTrue(repository.redo())
            assertEquals(LayerBlendContract.STANDARD, contract(id))
            assertArrayEquals(bands(STANDARD), pixels())
        }

    @Test
    fun copiedLayersAndBothFrameCopyRoutesRetainTheirNativeRendering() =
        runTest {
            val original = open()
            val duplicate = requireNotNull(repository.duplicateLayer(original))
            assertEquals(LayerBlendContract.PROCREATE_DARKEN_19, contract(duplicate))
            assertTrue(repository.setLayerVisibility(original, false))
            assertArrayEquals(bands(NATIVE), pixels())
            assertTrue(repository.copyFrame(0))
            assertTrue(repository.pasteFrame(1))
            repository.addFrame(duplicateCurrent = true)
            assertEquals(3, repository.frames().size)
            for ((index, frame) in repository.frames().withIndex()) {
                assertTrue(
                    frame.layers.filter { it.blendMode == BlendMode.DARKEN }.all {
                        it.blendContract ==
                            LayerBlendContract.PROCREATE_DARKEN_19
                    },
                )
                assertArrayEquals(bands(NATIVE), requireNotNull(repository.compositeFrame(index, true)).pixels)
            }
        }

    @Test
    fun flattenedPixelsUseANeutralContractAndUndoRestoresEditableNativeLayers() =
        runTest {
            val original = open()
            val before = pixels()
            requireNotNull(repository.flattenAllLayers())
            val flattened = repository.getAllLayers().single()
            assertEquals(BlendMode.NORMAL, flattened.blendMode)
            assertEquals(LayerBlendContract.STANDARD, flattened.blendContract)
            assertArrayEquals(before, pixels())
            assertTrue(repository.undo())
            assertEquals(LayerBlendContract.PROCREATE_DARKEN_19, contract(original))
            assertArrayEquals(before, pixels())
            assertTrue(repository.redo())
            assertEquals(LayerBlendContract.STANDARD, repository.getAllLayers().single().blendContract)
            assertArrayEquals(before, pixels())
        }

    @Test
    fun anInvalidContractCannotPartiallyChangeTheLayerOrHistory() =
        runTest {
            val id = open()
            val before = repository.getAllLayers()
            val depth = repository.undoDepth
            val revision = repository.contentRevision
            assertFalse(repository.setLayerBlendMode(id, BlendMode.MULTIPLY, LayerBlendContract.PROCREATE_DARKEN_19))
            assertEquals(before, repository.getAllLayers())
            assertEquals(depth, repository.undoDepth)
            assertEquals(revision, repository.contentRevision)
        }

    @Test
    fun reapplyingTheSameExplicitNativeContractPreservesRedoAndRevision() =
        runTest {
            val id = open()
            assertTrue(repository.setLayerOpacity(id, 0.5f))
            assertTrue(repository.undo())
            val depth = repository.undoDepth
            val redo = repository.redoDepth
            val revision = repository.contentRevision
            assertTrue(repository.setLayerBlendMode(id, BlendMode.DARKEN, LayerBlendContract.PROCREATE_DARKEN_19))
            assertEquals(depth, repository.undoDepth)
            assertEquals(redo, repository.redoDepth)
            assertEquals(revision, repository.contentRevision)
        }

    private suspend fun open(): Long {
        repository.createCanvas(32, 16, 72)
        repository.setCanvasBackgroundColor(0)
        val base = repository.getActiveLayerId()
        assertTrue(repository.setLayerPixels(base, PixelBuffer(32, 16, bands(BASE)), "Base fixture"))
        val id = repository.addLayer(name = "Native Darken").id
        assertTrue(repository.setLayerPixels(id, PixelBuffer.filled(32, 16, 0x553CB45A), "Source fixture"))
        assertTrue(repository.setLayerBlendMode(id, BlendMode.DARKEN, LayerBlendContract.PROCREATE_DARKEN_19))
        return id
    }

    private fun contract(id: Long): LayerBlendContract = repository.getAllLayers().first { it.id == id }.blendContract

    private suspend fun pixels(): IntArray = requireNotNull(repository.compositeFrame(repository.activeFrameIndex(), true)).pixels

    private fun bands(values: IntArray): IntArray = IntArray(32 * 16) { values[(it % 32) / 8] }

    private companion object {
        val BASE = intArrayOf(0, 0xAA963CB4.toInt(), 0xFFD25A96.toInt(), 0x551ED25A)
        val NATIVE = intArrayOf(0, 0xAA823CA5.toInt(), 0xFFA05A82.toInt(), 0x551ED25A)
        val STANDARD = intArrayOf(0x553CB45A, 0xC66F4D8D.toInt(), 0xFFA05A82.toInt(), 0x8E2AC05A.toInt())
    }
}
