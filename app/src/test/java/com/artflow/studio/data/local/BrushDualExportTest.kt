package com.artflow.studio.data.local

import com.artflow.studio.core.export.ProcreateBrush
import com.artflow.studio.core.export.ProcreateBrushWriter
import com.artflow.studio.core.export.TestPng
import com.artflow.studio.core.render.CustomGrains
import com.artflow.studio.domain.model.brush.BrushParams
import com.artflow.studio.domain.model.brush.DualBrush
import com.artflow.studio.domain.model.brush.ImportedPressure
import com.artflow.studio.domain.model.brush.PressureMapping
import com.artflow.studio.domain.model.brush.SavedBrush
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.zip.ZipInputStream

/** Actual app export/import entry points preserve one logical brush with two independent components. */
class BrushDualExportTest {
    private val primaryShape = tile(0)
    private val primaryGrain = tile(17)
    private val secondShape = tile(57)
    private val secondGrain = tile(91)
    private val curve = PressureMapping(listOf(PressureMapping.Point(0f, 0.2f), PressureMapping.Point(1f, 0.8f)))

    @Test
    fun savedBrushExportKeepsBothArchivesFourImagesAndTheOriginalCombineIdentifier() {
        val second =
            ProcreateBrush
                .parameters(mapOf("name" to "Inner tip", "dynamicsPressureOpacity" to -0.5, "dynamicsMix" to 0.25))
                .copy(
                    shapeId = store(secondShape),
                    textureId = store(secondGrain),
                    tipFlipX = true,
                    tipFlipYJitter = true,
                    importedPressure = ImportedPressure(flow = curve),
                )
        val primary =
            ProcreateBrush
                .parameters(mapOf("dualBlendMode" to 28, "dualBlendModeExtended" to 34))
                .copy(
                    shapeId = store(primaryShape),
                    textureId = store(primaryGrain),
                    dual = DualBrush(second, DualBrush.Mode.MULTIPLY, sourceBlendMode = 34),
                )
        val bytes = BrushArchives.procreateBrush(SavedBrush("dual", "Compound", primary))
        assertEquals(
            listOf("Brush.archive", "Shape.png", "Grain.png", "Sub01/Brush.archive", "Sub01/Shape.png", "Sub01/Grain.png"),
            members(bytes).keys.toList(),
        )
        val imported = BrushArchives.read(bytes, TestPng::decode).single()
        assertEquals("Compound", imported.name)
        val component = requireNotNull(imported.secondary)
        assertEquals("Inner tip", component.name)
        assertArrayEquals(primaryShape.values, requireNotNull(imported.shape).values)
        assertArrayEquals(primaryGrain.values, requireNotNull(imported.grain).values)
        val flipped =
            secondShape.values
                .toList()
                .chunked(secondShape.size)
                .flatMap { it.asReversed() }
                .toByteArray()
        assertArrayEquals(flipped, requireNotNull(component.shape).values)
        assertArrayEquals(secondGrain.values, requireNotNull(component.grain).values)
        val restored = imported.parameters(::store)
        assertEquals(4, restored.imageIds.toSet().size)
        val dual = requireNotNull(restored.dual)
        assertEquals(34, dual.sourceBlendMode)
        assertEquals(DualBrush.Mode.MULTIPLY, dual.mode)
        assertEquals("28", restored.importedSourceScalars.getValue("dualBlendMode").value)
        assertEquals("34", restored.importedSourceScalars.getValue("dualBlendModeExtended").value)
        assertEquals(-0.5f, dual.params.pressureToFlow, 0f)
        assertEquals(0.25f, dual.params.wetDilution, 0f)
        assertEquals(curve, dual.params.importedPressure?.flow)
        assertTrue(dual.params.tipFlipYJitter)
        assertFalse(dual.params.tipFlipX)
        assertNull(dual.params.dual)
        assertArrayEquals("Export does not mutate the shared secondary tip", tile(57).values, secondShape.values)
    }

    @Test
    fun brushSetListsOnlyOrderedPrimariesAndKeepsExplicitSecondaryAssets() {
        val dual = BrushParams(dual = DualBrush(BrushParams(size = 12f)))
        val bytes =
            ProcreateBrushWriter.brushset(
                "Mixed set",
                listOf(
                    ProcreateBrushWriter.Brush("First", dual, primaryShape, primaryGrain, secondShape, secondGrain),
                    ProcreateBrushWriter.Brush("Middle", BrushParams()),
                    ProcreateBrushWriter.Brush("Last", dual, secondShape, secondGrain, primaryShape, primaryGrain),
                ),
            )
        val imported = BrushArchives.read(bytes, TestPng::decode)
        assertEquals(listOf("First", "Middle", "Last"), imported.map { it.name })
        assertNull(imported[1].secondary)
        assertArrayEquals(secondShape.values, requireNotNull(imported[0].secondary?.shape).values)
        assertArrayEquals(secondGrain.values, requireNotNull(imported[0].secondary?.grain).values)
        assertArrayEquals(primaryShape.values, requireNotNull(imported[2].secondary?.shape).values)
        assertArrayEquals(primaryGrain.values, requireNotNull(imported[2].secondary?.grain).values)
        assertEquals(2, members(bytes).keys.count { it.endsWith("/Sub01/Brush.archive") })
    }

    @Test
    fun localCombineModesSurviveTheDocumentIdentifierApproximation() {
        // Native dual equations are unverified; this pins the intentional ArtFlow interchange approximation.
        val ids = mapOf(DualBrush.Mode.MULTIPLY to 1, DualBrush.Mode.ADD to 3, DualBrush.Mode.SUBTRACT to 7)
        for ((mode, id) in ids) {
            val params = BrushParams(dual = DualBrush(BrushParams(size = 6f), mode))
            val bytes = ProcreateBrushWriter.brush(ProcreateBrushWriter.Brush(mode.name, params))
            val restored = BrushArchives.read(bytes, TestPng::decode).single().parameters(::store)
            assertEquals(mode, restored.dual?.mode)
            assertEquals(id, restored.dual?.sourceBlendMode)
            assertEquals(6f, requireNotNull(restored.dual).params.size, 0f)
        }
    }

    @Test
    fun changingAnImportedCombineModeDoesNotLeaveAnExtendedIdentifierThatCouldOverrideIt() {
        val source = ProcreateBrush.parameters(mapOf("dualBlendMode" to 28, "dualBlendModeExtended" to 34))
        for (sourceMode in listOf(null, 28, 34)) {
            val changed = source.copy(dual = DualBrush(BrushParams(), DualBrush.Mode.ADD, sourceBlendMode = sourceMode))
            val bytes = ProcreateBrushWriter.brush(ProcreateBrushWriter.Brush("Changed", changed))
            val restored = BrushArchives.read(bytes, TestPng::decode).single().parameters(::store)
            assertEquals(DualBrush.Mode.ADD, restored.dual?.mode)
            assertEquals(3, restored.dual?.sourceBlendMode)
            assertFalse("dualBlendModeExtended" in restored.importedSourceScalars)
        }
    }

    @Test
    fun aMissingSecondaryImageKeepsItsSourceReferenceAndTheComponentSettings() {
        val second =
            ProcreateBrush.parameters(
                mapOf("bundledShapePath" to "Synthetic/Unavailable.png", "shapeInverted" to true, "dynamicsMix" to 0.5),
            )
        val primary = BrushParams(dual = DualBrush(second))
        val bytes = ProcreateBrushWriter.brush(ProcreateBrushWriter.Brush("Missing tile", primary))
        assertEquals(setOf("Brush.archive", "Sub01/Brush.archive"), members(bytes).keys)
        val imported = BrushArchives.read(bytes, TestPng::decode).single()
        assertNotNull(imported.secondary)
        assertNull(imported.secondary?.shape)
        val settings = requireNotNull(imported.secondary?.settings)
        assertTrue(settings.bundledShape)
        assertTrue(settings.shapeInverted)
        assertEquals(0.5f, settings.parameters.wetDilution, 0f)
    }

    @Test
    fun nestedDualBrushesAndOrphanSecondaryImagesAreRejected() {
        val nested = BrushParams(dual = DualBrush(BrushParams(dual = DualBrush(BrushParams()))))
        assertThrows(IllegalArgumentException::class.java) { ProcreateBrushWriter.brush(ProcreateBrushWriter.Brush("Nested", nested)) }
        assertThrows(IllegalArgumentException::class.java) {
            ProcreateBrushWriter.brush(ProcreateBrushWriter.Brush("Orphan", BrushParams(), secondaryShape = secondShape))
        }
    }

    private fun tile(seed: Int): CustomGrains.Tile =
        CustomGrains.Tile(
            CustomGrains.TILE,
            ByteArray(
                CustomGrains.TILE * CustomGrains.TILE,
            ) { index -> ((index * 31 + index / CustomGrains.TILE * 17 + seed) and 255).toByte() },
        )

    private fun store(tile: CustomGrains.Tile): String = CustomGrains.idFor(tile).also { CustomGrains.register(it, tile) }

    private fun members(bytes: ByteArray): Map<String, ByteArray> =
        ZipInputStream(bytes.inputStream()).use { zip ->
            buildMap {
                var entry = zip.nextEntry
                while (entry != null) {
                    put(entry.name, zip.readBytes())
                    entry = zip.nextEntry
                }
            }
        }
}
