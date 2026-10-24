package com.artflow.studio.data

import android.content.Context
import com.artflow.studio.core.color.ColorProfile
import com.artflow.studio.core.pixels.IntBounds
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.core.pixels.SelectionMask
import com.artflow.studio.core.three.MaterialChannel
import com.artflow.studio.data.local.ProjectStorage
import com.artflow.studio.data.repository.canvas.CanvasRepositoryImpl
import com.artflow.studio.domain.model.brush.BrushParams
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock

@OptIn(ExperimentalCoroutinesApi::class)
class MaterialChannelsRepositoryTest {
    private lateinit var repository: CanvasRepositoryImpl
    private val size = 24
    private val color = 0xFFC05030.toInt()
    private val brush = BrushParams(size = 8f, opacity = 1f, pressureToSize = 0f, pressureToOpacity = 0f, smoothing = 0f)

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        repository = CanvasRepositoryImpl(ProjectStorage(mock(Context::class.java)))
    }

    @After fun tearDown() {
        repository.dispose()
        Dispatchers.resetMain()
    }

    @Test fun provisionalMaterialStrokeCancellationAndHistoryNeverChangeColorOrOtherMaps() =
        runTest {
            open()
            val metal = requireNotNull(repository.selectMaterialChannel(MaterialChannel.METALLIC))
            repository.setMaterialPaintValue(0.75f)
            repository.setStrokeColor(0xFFFF0000.toInt())
            val before = repository.historyMark
            val stroke = repository.beginStroke(12f, 12f, 1f, brush.copy(hueJitter = 1f, colorPressure = true), metal)
            val provisional = requireNotNull(repository.compositeMaterial(MaterialChannel.METALLIC, true))
            assertEquals(0xFFBFBFBF.toInt(), provisional.pixels[12 * size + 12])
            assertTrue(requireNotNull(repository.compositeMaterial(MaterialChannel.METALLIC)).pixels.all { it == 0 })
            assertNull(repository.compositeMaterial(MaterialChannel.ROUGHNESS))
            assertColor()
            repository.cancelStroke(stroke)
            assertEquals(before, repository.historyMark)
            assertTrue(requireNotNull(repository.compositeMaterial(MaterialChannel.METALLIC, true)).pixels.all { it == 0 })
            paint(metal, 0.75f)
            val painted = requireNotNull(repository.compositeMaterial(MaterialChannel.METALLIC)).pixels.copyOf()
            assertTrue(repository.undo())
            assertTrue(requireNotNull(repository.compositeMaterial(MaterialChannel.METALLIC)).pixels.all { it == 0 })
            assertTrue(repository.redo())
            assertArrayEquals(painted, requireNotNull(repository.compositeMaterial(MaterialChannel.METALLIC)).pixels)
            assertColor()
        }

    @Test fun selectionMasksGroupsAndColorProfileChangesKeepIndependentScalarData() =
        runTest {
            open()
            val metal = requireNotNull(repository.selectMaterialChannel(MaterialChannel.METALLIC))
            val selection = SelectionMask(size, size, ByteArray(size * size) { if (it % size < size / 2) -1 else 0 })
            repository.setSelection(selection)
            paint(metal, 1f)
            repository.clearSelection()
            val values = requireNotNull(repository.layerPixels(metal)).pixels.copyOf()
            assertTrue(values.indices.filter { it % size >= size / 2 }.all { values[it] == 0 })
            assertTrue(values.any { it ushr 24 > 0 })
            val group = requireNotNull(repository.groupLayers(listOf(metal)))
            val colorSnapshot = repository.exportSnapshot(false, false, true)
            assertTrue(colorSnapshot.groups.none { it.id == group })
            assertTrue(colorSnapshot.layers.none { it.first.id == metal || it.first.id == group })
            assertTrue(repository.addLayerMask(group, SelectionMask(size, size, ByteArray(size * size) { 128.toByte() })))
            val masked = requireNotNull(repository.compositeMaterial(MaterialChannel.METALLIC)).pixels.copyOf()
            assertTrue(masked.any { it ushr 24 in 1..128 })
            assertTrue(repository.setLayerVisibility(group, false))
            assertTrue(requireNotNull(repository.compositeMaterial(MaterialChannel.METALLIC)).pixels.all { it == 0 })
            assertTrue(repository.setLayerVisibility(group, true))
            assertArrayEquals(masked, requireNotNull(repository.compositeMaterial(MaterialChannel.METALLIC)).pixels)
            assertTrue(repository.setColorProfile(ColorProfile.DISPLAY_P3))
            assertArrayEquals(values, requireNotNull(repository.layerPixels(metal)).pixels)
            assertArrayEquals(masked, requireNotNull(repository.compositeMaterial(MaterialChannel.METALLIC)).pixels)
        }

    @Test fun materialRasterEditsStayProvisionalUntilCommitAndUndoCleanly() =
        runTest {
            open()
            val metal = requireNotNull(repository.selectMaterialChannel(MaterialChannel.METALLIC))
            val session = requireNotNull(repository.beginRasterEdit(metal))
            session.buffer.pixels.fill(0xFF808080.toInt())
            assertTrue(requireNotNull(repository.compositeMaterial(MaterialChannel.METALLIC)).pixels.all { it == 0 })
            assertTrue(requireNotNull(repository.compositeMaterial(MaterialChannel.METALLIC, true)).pixels.all { it == 0xFF808080.toInt() })
            assertColor()
            assertTrue(repository.commitRasterEdit(session, "Paint material pixels"))
            assertTrue(requireNotNull(repository.compositeMaterial(MaterialChannel.METALLIC)).pixels.all { it == 0xFF808080.toInt() })
            assertTrue(repository.undo())
            assertTrue(requireNotNull(repository.compositeMaterial(MaterialChannel.METALLIC)).pixels.all { it == 0 })
            assertColor()
        }

    @Test fun snapshotsKeepMapsWhileColorExportsAndClonesRetainTheirOwnChannel() =
        runTest {
            val colorId = open()
            val metal = requireNotNull(repository.selectMaterialChannel(MaterialChannel.METALLIC))
            paint(metal, 1f)
            val rough = requireNotNull(repository.selectMaterialChannel(MaterialChannel.ROUGHNESS))
            paint(rough, 0.25f)
            val snapshot = repository.exportSnapshot(false, false, true, includeMaterials = true)
            assertEquals(listOf(colorId), snapshot.layers.map { it.first.id })
            assertArrayEquals(IntArray(size * size) { color }, snapshot.frames.single().pixels)
            assertEquals(setOf(MaterialChannel.METALLIC, MaterialChannel.ROUGHNESS), snapshot.materialMaps.keys)
            val copied = requireNotNull(repository.duplicateLayer(rough))
            assertEquals(MaterialChannel.ROUGHNESS, repository.getActiveLayer()!!.materialChannel)
            assertEquals(MaterialChannel.ROUGHNESS, repository.addLayer("More roughness").materialChannel)
            assertTrue(repository.copyFrame(0))
            assertTrue(repository.pasteFrame(1))
            assertEquals(2, repository.frames().size)
            assertEquals(3, repository.frames()[1].layers.count { it.materialChannel == MaterialChannel.ROUGHNESS })
            assertNotNull(repository.getAllLayers().firstOrNull { it.materialChannel == MaterialChannel.METALLIC })
            assertTrue(copied != rough)
            assertArrayEquals(IntArray(size * size) { color }, snapshot.frames.single().pixels)
        }

    @Test fun crossChannelMergeAndFlattenRefuseWithoutLosingAnyMapOrHistory() =
        runTest {
            val base = open()
            val metal = requireNotNull(repository.selectMaterialChannel(MaterialChannel.METALLIC))
            paint(metal, 1f)
            val layers = repository.getAllLayers()
            val history = repository.historyMark
            assertFalse(repository.mergeLayers(metal, base))
            assertNull(repository.flattenAllLayers())
            assertNull(repository.mergeVisibleLayers(false))
            assertEquals(layers, repository.getAllLayers())
            assertEquals(history, repository.historyMark)
            val duplicate = requireNotNull(repository.duplicateLayer(metal))
            assertTrue(repository.mergeLayers(duplicate, metal))
            assertEquals(MaterialChannel.METALLIC, repository.getActiveLayer()!!.materialChannel)
            assertColor()
        }

    @Test fun transparentTrimPreservesEveryMaterialChannelAcrossFramesEvenWithoutColorPaint() =
        runTest {
            for (hasColor in listOf(true, false)) {
                repository.createCanvas(size, size, 72)
                val colorLayer = repository.getActiveLayerId()
                val colorPixels = PixelBuffer(size, size).also { if (hasColor) it.pixels[2 * size + 2] = color }
                assertTrue(repository.setLayerPixels(colorLayer, colorPixels, "Sparse color"))
                val metal = requireNotNull(repository.selectMaterialChannel(MaterialChannel.METALLIC))
                val metalPixels = PixelBuffer(size, size).also { it.pixels[20 * size + 20] = -1 }
                assertTrue(repository.setLayerPixels(metal, metalPixels, "Metal corner"))
                val rough = requireNotNull(repository.selectMaterialChannel(MaterialChannel.ROUGHNESS))
                val roughPixels = PixelBuffer(size, size).also { it.pixels[10 * size + 22] = 0xFF000000.toInt() }
                assertTrue(repository.setLayerPixels(rough, roughPixels, "Opaque zero roughness"))
                // The blank active frame must not hide material coverage on the frame being cropped.
                repository.addFrame(false)
                val before = repository.historyMark
                assertTrue(repository.trimTransparent())
                assertEquals(before + 1, repository.historyMark)
                val bounds = if (hasColor) IntBounds(2, 2, 22, 20) else IntBounds(20, 10, 22, 20)
                assertEquals(bounds.width, repository.getCanvasSize().width)
                assertEquals(bounds.height, repository.getCanvasSize().height)
                repository.selectFrame(0)
                for ((layer, pixels) in listOf(colorLayer to colorPixels, metal to metalPixels, rough to roughPixels)) {
                    assertArrayEquals(pixels.crop(bounds).pixels, requireNotNull(repository.layerPixels(layer)).pixels)
                }
                assertTrue(repository.undo())
                assertEquals(size, repository.getCanvasSize().width)
                assertEquals(size, repository.getCanvasSize().height)
                repository.selectFrame(0)
                assertArrayEquals(metalPixels.pixels, requireNotNull(repository.layerPixels(metal)).pixels)
                assertArrayEquals(roughPixels.pixels, requireNotNull(repository.layerPixels(rough)).pixels)
            }
        }

    private suspend fun open(): Long {
        repository.createCanvas(size, size, 72)
        val layer = repository.getActiveLayerId()
        assertTrue(repository.setLayerPixels(layer, PixelBuffer.filled(size, size, color), "Color fixture"))
        return layer
    }

    private fun paint(
        layer: Long,
        value: Float,
    ) {
        repository.setMaterialPaintValue(value)
        val stroke = repository.beginStroke(12f, 12f, 1f, brush, layer)
        assertTrue(stroke > 0)
        repository.endStroke(stroke)
    }

    private suspend fun assertColor() {
        assertArrayEquals(IntArray(size * size) { color }, requireNotNull(repository.compositeBuffer()).pixels)
        assertArrayEquals(IntArray(size * size) { color }, requireNotNull(repository.compositePreview()).pixels)
        assertArrayEquals(IntArray(size * size) { color }, requireNotNull(repository.compositeOnionFrame(0)).pixels)
    }
}
