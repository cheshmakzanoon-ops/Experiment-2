package com.artflow.studio.core.export

import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.core.render.CustomGrains
import com.artflow.studio.core.render.StrokeRasterizer
import com.artflow.studio.data.local.BrushArchives
import com.artflow.studio.domain.model.brush.BrushParams
import com.artflow.studio.domain.model.brush.SavedBrush
import com.artflow.studio.domain.model.brush.Stroke
import com.artflow.studio.domain.model.brush.StrokePoint
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.zip.ZipInputStream

class BrushShapeFlipExportTest {
    private val values = byteArrayOf(0, 16, 32, 48, 64, 80, 96, 112, -1)
    private val tile = CustomGrains.Tile(3, values)

    private val flips = listOf(false to false, true to false, false to true, true to true)
    private val expectedShapes =
        listOf(
            values,
            byteArrayOf(32, 16, 0, 80, 64, 48, -1, 112, 96),
            byteArrayOf(96, 112, -1, 48, 64, 80, 0, 16, 32),
            byteArrayOf(-1, 112, 96, 80, 64, 48, 32, 16, 0),
        )

    @Test
    fun everyFixedFlipIsBakedIntoShapePixelsWithoutChangingGrainOrSharedTiles() {
        val original = values.copyOf()
        for ((index, flags) in flips.withIndex()) {
            val params = BrushParams(tipFlipX = flags.first, tipFlipY = flags.second, tipFlipYJitter = true)
            val files = members(ProcreateBrushWriter.brush(ProcreateBrushWriter.Brush("Asymmetric", params, tile, tile)))
            assertArrayEquals(expectedShapes[index], pixels(files.getValue("Shape.png")))
            assertArrayEquals(original, pixels(files.getValue("Grain.png")))
            val restored = ProcreateBrush.read(files.getValue("Brush.archive")).parameters
            assertFalse(restored.tipFlipX)
            assertFalse(restored.tipFlipY)
            assertFalse(restored.tipFlipXJitter)
            assertTrue(restored.tipFlipYJitter)
            assertArrayEquals("Export must not mutate a registered/shared tile", original, tile.values)
        }
    }

    @Test
    fun brushSetsNormalizeEachShapesOwnFixedFlips() {
        val brushes =
            flips.mapIndexed { index, flags ->
                ProcreateBrushWriter.Brush(
                    "Shape $index",
                    BrushParams(tipFlipX = flags.first, tipFlipY = flags.second),
                    shape = tile,
                )
            }
        val files = members(ProcreateBrushWriter.brushset("Flips", brushes))
        val images = files.filterKeys { it.endsWith("/Shape.png") }.values.toList()
        assertEquals(4, images.size)
        for (index in images.indices) assertArrayEquals(expectedShapes[index], pixels(images[index]))
    }

    @Test
    fun embeddedShapeReplacesItsBundleReferenceAndRetainsIndependentSourceSettings() {
        val params =
            ProcreateBrush
                .parameters(
                    mapOf(
                        "bundledShapePath" to "Synthetic/Original.png",
                        "bundledGrainPath" to "Synthetic/Grain.png",
                        "shapeInverted" to true,
                        "textureInverted" to true,
                        "shapeFlipXJitter" to true,
                        "shapeFlipYJitter" to false,
                        "dynamicsMixSoftening" to -0.37,
                    ),
                ).copy(tipFlipX = true, tipFlipY = true)
        val original = params.importedSourceScalars.toMap()
        val files = members(ProcreateBrushWriter.brush(ProcreateBrushWriter.Brush("Edited source", params, shape = tile)))
        val restored = ProcreateBrush.read(files.getValue("Brush.archive"))
        assertFalse(restored.bundledShape)
        assertFalse(restored.shapeInverted)
        assertTrue(restored.bundledGrain)
        assertTrue(restored.grainInverted)
        assertArrayEquals(expectedShapes[3], pixels(files.getValue("Shape.png")))
        for (name in listOf("shapeFlipXJitter", "shapeFlipYJitter", "dynamicsMixSoftening", "bundledGrainPath")) {
            assertEquals(name, original[name], restored.parameters.importedSourceScalars[name])
        }
        assertEquals("Export must not rewrite the saved source settings", original, params.importedSourceScalars)

        val missingFiles = members(ProcreateBrushWriter.brush(ProcreateBrushWriter.Brush("Missing image", params)))
        val missing = ProcreateBrush.read(missingFiles.getValue("Brush.archive"))
        assertTrue(missing.bundledShape)
        assertTrue(missing.shapeInverted)
        assertTrue(missing.grainInverted)
        assertFalse("An unavailable shape must not be invented", "Shape.png" in missingFiles)
    }

    @Test
    fun bakedShapeRendersLikeFixedFlagsWithRotationAndSeededMirrorJitter() {
        val shape =
            CustomGrains.Tile(
                CustomGrains.TILE,
                ByteArray(CustomGrains.TILE * CustomGrains.TILE) { index ->
                    val x = index % CustomGrains.TILE
                    val y = index / CustomGrains.TILE
                    when {
                        x in 24..72 && y in 16..200 -> 100.toByte()
                        x in 130..230 && y in 120..180 -> 255.toByte()
                        else -> 0
                    }
                },
            )
        val shapeId = store(shape)
        for (flags in flips) {
            for (jitter in flips) {
                for (rotation in listOf(0f, 23f, 91f)) {
                    val params =
                        BrushParams(
                            shapeId = shapeId,
                            size = 32f,
                            spacing = 0.8f,
                            pressureToSize = 0f,
                            pressureToOpacity = 0f,
                            rotation = rotation,
                            roundness = 0.65f,
                            tipFlipX = flags.first,
                            tipFlipY = flags.second,
                            tipFlipXJitter = jitter.first,
                            tipFlipYJitter = jitter.second,
                        )
                    val exported = BrushArchives.procreateBrush(SavedBrush("flip", "Flip", params))
                    val imported = BrushArchives.read(exported, TestPng::decode).single().parameters(::store)
                    assertFalse(imported.tipFlipX || imported.tipFlipY)
                    assertEquals(params.tipFlipXJitter, imported.tipFlipXJitter)
                    assertEquals(params.tipFlipYJitter, imported.tipFlipYJitter)
                    assertEquivalentPixels("fixed=$flags jitter=$jitter rotation=$rotation", render(params), render(imported))
                }
            }
        }
    }

    @Test
    fun exportingANormalizedShapeAgainDoesNotFlipItTwice() {
        val shape = CustomGrains.Tile(CustomGrains.TILE, ByteArray(CustomGrains.TILE * CustomGrains.TILE) { (it % 256).toByte() })
        val params = BrushParams(shapeId = store(shape), tipFlipX = true, tipFlipY = true, tipFlipXJitter = true)
        val first = BrushArchives.procreateBrush(SavedBrush("first", "First", params))
        val normalized = BrushArchives.read(first, TestPng::decode).single().parameters(::store)
        val second = BrushArchives.procreateBrush(SavedBrush("second", "Second", normalized))
        assertArrayEquals(pixels(members(first).getValue("Shape.png")), pixels(members(second).getValue("Shape.png")))
        assertTrue(normalized.tipFlipXJitter)
        assertFalse(normalized.tipFlipX || normalized.tipFlipY)
    }

    private fun store(shape: CustomGrains.Tile): String = CustomGrains.idFor(shape).also { CustomGrains.register(it, shape) }

    private fun assertEquivalentPixels(
        label: String,
        expected: PixelBuffer,
        actual: PixelBuffer,
    ) {
        assertTrue(expected.pixels.any { it ushr 24 > 0 })
        for (index in expected.pixels.indices) {
            // Reflecting a bilinear sample changes floating-point evaluation order.
            // The image coverage must agree within one final 8-bit rounding step.
            val expectedAlpha = expected.pixels[index] ushr 24
            val actualAlpha = actual.pixels[index] ushr 24
            assertEquals("$label pixel=$index", expectedAlpha.toDouble(), actualAlpha.toDouble(), 1.0)
            if (expectedAlpha > 0 && actualAlpha > 0) {
                assertEquals(expected.pixels[index] and 0xFFFFFF, actual.pixels[index] and 0xFFFFFF)
            }
        }
    }

    private fun render(params: BrushParams): PixelBuffer {
        val stroke =
            Stroke(
                id = 2297L,
                points =
                    listOf(
                        StrokePoint(25f, 40f, timestamp = 0),
                        StrokePoint(79f, 61f, timestamp = 50),
                        StrokePoint(132f, 46f, timestamp = 120),
                    ),
                brushParams = params,
                layerId = 1L,
                color = 0xFF1D3E57.toInt(),
            )
        return PixelBuffer(160, 112).also { StrokeRasterizer().draw(it, stroke) }
    }

    private fun members(bytes: ByteArray): Map<String, ByteArray> =
        ZipInputStream(bytes.inputStream()).use { zip ->
            val result = linkedMapOf<String, ByteArray>()
            var entry = zip.nextEntry
            while (entry != null) {
                result[entry.name] = zip.readBytes()
                entry = zip.nextEntry
            }
            result
        }

    private fun pixels(png: ByteArray): ByteArray = requireNotNull(TestPng.decode(png)).pixels.map { (it and 255).toByte() }.toByteArray()
}
