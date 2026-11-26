package com.artflow.studio.data.repository.canvas

import android.content.Context
import com.artflow.studio.core.pixels.LayerTransform
import com.artflow.studio.data.local.ProjectStorage
import com.artflow.studio.domain.model.brush.BrushParams
import com.artflow.studio.domain.model.brush.PhysicalPaintMode
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

@OptIn(ExperimentalCoroutinesApi::class)
class PhysicalRasterTransformTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var repository: CanvasRepositoryImpl
    private val dispatcher = UnconfinedTestDispatcher()

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        val context = mock(Context::class.java)
        `when`(context.filesDir).thenReturn(temporary.newFolder("files"))
        repository = CanvasRepositoryImpl(ProjectStorage(context, dispatcher))
    }

    @After fun tearDown() {
        repository.dispose()
        Dispatchers.resetMain()
    }

    private suspend fun paint(): Long {
        repository.loadOrCreate(99L, 64, 64, 72)
        val layer = repository.getActiveLayerId()
        val brush = BrushParams(size = 12f, physicalMode = PhysicalPaintMode.WATERCOLOR, waterAmount = 0.9f)
        repository.setStrokeColor(0xFF2060CC.toInt())
        val stroke = repository.beginStroke(12f, 20f, 1f, brush, layer, timestamp = 0L)
        repository.continueStroke(stroke, 28f, 20f, 1f, timestamp = 16L)
        repository.endStroke(stroke)
        return layer
    }

    @Test
    fun movedPhysicalPaintSurvivesUndoRedoAndSaveReopen() =
        runTest {
            val layer = paint()
            val original = repository.physicalPaintSource(layer)
            val before = original.plane!!.wetnessAt(20, 20)
            val edit = requireNotNull(repository.beginRasterEdit(layer))
            val params = LayerTransform.Params(0f, 0f, 30f, 0f)
            LayerTransform.render(edit.buffer.copy(), edit.buffer, params)
            edit.transformPhysical { input, output -> LayerTransform.render(input, output, params) }
            assertTrue(repository.commitRasterEdit(edit, "Move"))

            val moved = repository.physicalPaintSource(layer).plane!!
            assertEquals(0f, moved.wetnessAt(20, 20), 0f)
            assertEquals(before, moved.wetnessAt(50, 20), 0.01f)
            assertEquals(before, original.plane.wetnessAt(20, 20), 0f)
            assertTrue(repository.undo())
            assertEquals(before, repository.physicalPaintSource(layer).plane!!.wetnessAt(20, 20), 0f)
            assertTrue(repository.redo())
            assertNotNull(repository.saveCanvas(99L))
            assertNotNull(repository.loadCanvas(99L))
            assertEquals(before, repository.physicalPaintSource(layer).plane!!.wetnessAt(50, 20), 0.01f)
        }

    @Test
    fun resetRestoresOriginalPhysicalSourceAfterRepeatedTransforms() =
        runTest {
            val layer = paint()
            val pixels = requireNotNull(repository.layerPixels(layer))
            val physical = repository.physicalPaintSource(layer)
            val before = physical.plane!!.wetnessAt(20, 20)
            for (translation in listOf(10f, 20f, 0f)) {
                val params = LayerTransform.Params(0f, 0f, translation, 0f)
                assertTrue(
                    repository.applyRasterEdits(listOf(layer), "Transform", preparePhysical = { edit ->
                        edit.transformPhysical(physical) { input, output -> LayerTransform.render(input, output, params) }
                    }) { _, output -> LayerTransform.render(pixels, output, params) },
                )
            }
            assertArrayEquals(pixels.pixels, repository.layerPixels(layer)!!.pixels)
            assertEquals(before, repository.physicalPaintSource(layer).plane!!.wetnessAt(20, 20), 0f)
        }

    @Test
    fun staleTransformCannotReplaceNewPhysicalEdits() =
        runTest {
            val layer = paint()
            val originalPixels = repository.layerPixels(layer)!!.pixels
            val edit = requireNotNull(repository.beginRasterEdit(layer))
            val params = LayerTransform.Params(0f, 0f, 30f, 0f)
            LayerTransform.render(edit.buffer.copy(), edit.buffer, params)
            edit.transformPhysical { input, output -> LayerTransform.render(input, output, params) }
            assertTrue(repository.dryPhysicalLayer(layer))
            val depth = repository.undoDepth
            assertFalse(repository.commitRasterEdit(edit, "Stale transform"))
            assertEquals(depth, repository.undoDepth)
            assertArrayEquals(originalPixels, repository.layerPixels(layer)!!.pixels)
            assertFalse(repository.hasActiveWetPaint())
        }
}
