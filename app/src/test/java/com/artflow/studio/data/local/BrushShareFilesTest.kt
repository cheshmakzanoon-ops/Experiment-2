package com.artflow.studio.data.local

import com.artflow.studio.core.export.TestPng
import com.artflow.studio.core.render.CustomGrains
import com.artflow.studio.domain.model.brush.BrushParams
import com.artflow.studio.domain.model.brush.DualBrush
import com.artflow.studio.domain.model.brush.SavedBrush
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BrushShareFilesTest {
    private val shape = CustomGrains.Tile(CustomGrains.TILE, ByteArray(CustomGrains.TILE * CustomGrains.TILE) { (it % 256).toByte() })
    private val grain = CustomGrains.Tile(CustomGrains.TILE, ByteArray(CustomGrains.TILE * CustomGrains.TILE) { (255 - it % 256).toByte() })

    private fun brushes(): List<SavedBrush> {
        val shapeId = CustomGrains.idFor(shape)
        val grainId = CustomGrains.idFor(grain)
        CustomGrains.register(shapeId, shape)
        CustomGrains.register(grainId, grain)
        return listOf(
            SavedBrush("first", "Shaped ink", BrushParams(shapeId = shapeId, pressureToFlow = -0.3f)),
            SavedBrush("second", "Paper wash", BrushParams(textureId = grainId, blendTexture = true, wetDilution = 0.4f)),
        )
    }

    @Test
    fun artflowRemainsTheDefaultAndKeepsAllBrushNamesAndImages() {
        val source = brushes()
        val file = BrushShareFiles.encode("My/inks", source)
        assertEquals("Myinks.artbrush", file.fileName)
        val (restored, images) = BrushFiles.decode(file.bytes.decodeToString())
        assertEquals(source, restored)
        assertArrayEquals(shape.values, images.getValue(source[0].parameters.shapeId!!).values)
        assertArrayEquals(grain.values, images.getValue(source[1].parameters.textureId!!).values)
    }

    @Test
    fun singleProcreateChoiceWritesABrushWithItsOwnNamePressureAndShape() {
        val source = brushes().first()
        val file = BrushShareFiles.encode("Shared ink", listOf(source), BrushShareFormat.PROCREATE_BRUSH)
        assertEquals("Shared ink.brush", file.fileName)
        val restored = BrushArchives.read(file.bytes, TestPng::decode).single()
        assertEquals(source.name, restored.name)
        assertEquals(-0.3f, restored.settings!!.parameters.pressureToFlow, 1e-6f)
        assertArrayEquals(shape.values, restored.shape!!.values)
    }

    @Test
    fun procreateSetChoiceKeepsOrderNamesAndComponentImages() {
        val source = brushes()
        val file = BrushShareFiles.encode("My inks", source, BrushShareFormat.PROCREATE_SET)
        assertEquals("My inks.brushset", file.fileName)
        val restored = BrushArchives.read(file.bytes, TestPng::decode)
        assertEquals(source.map { it.name }, restored.map { it.name })
        assertArrayEquals(shape.values, restored[0].shape!!.values)
        assertArrayEquals(grain.values, restored[1].grain!!.values)
        assertEquals(0.4f, restored[1].settings!!.parameters.wetDilution, 1e-6f)
    }

    @Test
    fun bothProcreateShareChoicesKeepTheDualComponentAndResolveItsOwnImages() {
        val source = brushes().first()
        val secondary =
            BrushParams(
                shapeId = CustomGrains.idFor(grain),
                textureId = CustomGrains.idFor(shape),
                pressureToFlow = -0.4f,
            )
        val compound = source.copy(parameters = source.parameters.copy(dual = DualBrush(secondary, DualBrush.Mode.ADD)))
        for (format in listOf(BrushShareFormat.PROCREATE_BRUSH, BrushShareFormat.PROCREATE_SET)) {
            val encoded = BrushShareFiles.encode("Compound ink", listOf(compound), format)
            val restored = BrushArchives.read(encoded.bytes, TestPng::decode).single()
            assertEquals(compound.name, restored.name)
            assertArrayEquals(shape.values, requireNotNull(restored.shape).values)
            val second = requireNotNull(restored.secondary)
            assertArrayEquals(grain.values, requireNotNull(second.shape).values)
            assertArrayEquals(shape.values, requireNotNull(second.grain).values)
            val dual = requireNotNull(restored.parameters(CustomGrains::idFor).dual)
            assertEquals(DualBrush.Mode.ADD, dual.mode)
            assertEquals(-0.4f, dual.params.pressureToFlow, 1e-6f)
        }
    }

    @Test
    fun wrongCardinalityIsRejectedBeforeWritingAnUnimportableFile() {
        assertThrows(IllegalArgumentException::class.java) {
            BrushShareFiles.encode("Not single", brushes(), BrushShareFormat.PROCREATE_BRUSH)
        }
        assertThrows(IllegalArgumentException::class.java) {
            BrushShareFiles.encode("Empty", emptyList(), BrushShareFormat.PROCREATE_SET)
        }
        assertThrows(IllegalArgumentException::class.java) {
            BrushShareFiles.encode(
                "Too many",
                (0..100).map { SavedBrush("brush-$it", "Brush $it", BrushParams()) },
                BrushShareFormat.PROCREATE_SET,
            )
        }
    }
}
