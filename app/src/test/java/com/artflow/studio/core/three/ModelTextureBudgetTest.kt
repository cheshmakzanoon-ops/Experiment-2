package com.artflow.studio.core.three

import com.artflow.studio.core.export.PngCodec
import com.artflow.studio.core.export.TestPng
import com.artflow.studio.core.pixels.PixelBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelTextureBudgetTest {
    @Test
    fun colorOnlySourceIsAdmittedBeforeTheDecoderIsCalled() {
        val source = ModelPackage.Contents(OBJ, png(256, 256))
        var decodes = 0
        val failure =
            assertThrows(IllegalArgumentException::class.java) {
                PreparedModelImport.prepare(source, {
                    decodes++
                    null
                }, TestPng::dimensions, memoryBudgetBytes = MIB)
            }
        assertEquals(0, decodes)
        assertTrue(failure.message.orEmpty().contains("memory"))
    }

    @Test
    fun aSmallFinalAtlasDoesNotHideSeveralLiveOriginalTextures() {
        val sources = (1..8).associate { "material$it" to png(128, 128) }
        val contents = ModelPackage.Contents(multiObj(8), sources.values.first(), sources)
        var inspected = 0
        var decodes = 0
        assertThrows(IllegalArgumentException::class.java) {
            PreparedModelImport.prepare(
                contents,
                {
                    decodes++
                    null
                },
                {
                    inspected++
                    TestPng.dimensions(it)
                },
                maxSize = 4,
                memoryBudgetBytes = MIB + 500_000,
            )
        }
        assertEquals(8, inspected)
        assertEquals(0, decodes)
    }

    @Test
    fun theLargestBitmapConversionAndPixelCopyAreIncludedBeforeDecode() {
        val contents = ModelPackage.Contents(OBJ, png(256, 256))
        var decodes = 0
        assertThrows(IllegalArgumentException::class.java) {
            PreparedModelImport.prepare(
                contents,
                {
                    decodes++
                    null
                },
                TestPng::dimensions,
                maxSize = 4,
                memoryBudgetBytes = MIB + 600_000,
            )
        }
        assertEquals(0, decodes)
    }

    @Test
    fun everyAtlasHeaderIsCheckedBeforeAnyEarlierPlaneDecodes() {
        val first = png(2, 2)
        val broken = byteArrayOf(1, 2, 3)
        var decodes = 0
        val error =
            assertThrows(IllegalArgumentException::class.java) {
                PreparedModelImport.prepare(
                    ModelPackage.Contents(multiObj(2), first, mapOf("material1" to first, "material2" to broken)),
                    {
                        decodes++
                        null
                    },
                    TestPng::dimensions,
                )
            }
        assertEquals(0, decodes)
        assertTrue(error.message.orEmpty().contains("material2 texture could not be read"))
    }

    @Test
    fun damagedColorBodiesFailInsteadOfCreatingAnUntexturedArtwork() {
        val bytes = png(2, 2)
        val error =
            assertThrows(IllegalArgumentException::class.java) {
                PreparedModelImport.prepare(ModelPackage.Contents(OBJ, bytes), { null }, TestPng::dimensions)
            }
        assertTrue(error.message.orEmpty().contains("Color texture could not be decoded"))
    }

    @Test
    fun aDamagedAtlasPlaneFailsInsteadOfSilentlyBecomingWhite() {
        val first = png(2, 2)
        val second = png(2, 2)
        val error =
            assertThrows(IllegalArgumentException::class.java) {
                PreparedModelImport.prepare(
                    ModelPackage.Contents(multiObj(2), first, mapOf("material1" to first, "material2" to second)),
                    { if (it === second) null else TestPng.decode(it) },
                    TestPng::dimensions,
                )
            }
        assertTrue(error.message.orEmpty().contains("material2 texture could not be decoded"))
    }

    @Test
    fun decodedColorDimensionsMustMatchTheAdmittedMetadata() {
        val error =
            assertThrows(IllegalArgumentException::class.java) {
                PreparedModelImport.prepare(ModelPackage.Contents(OBJ, png(2, 2)), { PixelBuffer(4, 4) }, TestPng::dimensions)
            }
        assertTrue(error.message.orEmpty().contains("size is inconsistent"))
    }

    @Test
    fun unusedCanvasDefaultsStillNeedAnAffordableInstallation() {
        var inspected = false
        assertThrows(IllegalArgumentException::class.java) {
            PreparedModelImport.prepare(
                ModelPackage.Contents(OBJ),
                { error("An untextured model must not decode an image") },
                {
                    inspected = true
                    null
                },
                defaultSize = 256,
                memoryBudgetBytes = 2 * MIB,
            )
        }
        assertTrue(!inspected)
    }

    @Test
    fun metadataInspectionSupportsAnyPlatformFormatWithoutTreatingItAsPng() {
        // Production supplies BitmapFactory bounds. This protocol fixture has deliberately no PNG header.
        val encoded = byteArrayOf(8, 9, 10)
        val pixels = PixelBuffer(3, 2, intArrayOf(1, 2, 3, 4, 5, 6))
        val prepared =
            PreparedModelImport.prepare(
                ModelPackage.Contents(OBJ, encoded),
                {
                    assertTrue(it === encoded)
                    pixels
                },
                {
                    assertTrue(it === encoded)
                    3 to 2
                },
            )
        assertEquals(3, prepared.width)
        assertEquals(2, prepared.height)
        assertArrayEquals(pixels.pixels, requireNotNull(prepared.color).pixels)
    }

    @Test
    fun anAtlasThatCannotPreserveAllMaterialsIsAnExplicitFailure() {
        val bytes = png(2, 2)
        var decodes = 0
        assertThrows(IllegalArgumentException::class.java) {
            PreparedModelImport.prepare(
                ModelPackage.Contents(multiObj(17), bytes, mapOf("material1" to bytes)),
                {
                    decodes++
                    null
                },
                TestPng::dimensions,
            )
        }
        assertEquals(0, decodes)
    }

    @Test
    fun validAtlasRetainsItsPriorGeometryAndIndependentColorCells() {
        val red = PngCodec.encode(PixelBuffer.filled(3, 2, 0xFFFF0000.toInt()))
        val blue = PngCodec.encode(PixelBuffer.filled(3, 2, 0xFF0000FF.toInt()))
        val prepared =
            PreparedModelImport.prepare(
                ModelPackage.Contents(multiObj(2), red, mapOf("material1" to red, "material2" to blue)),
                TestPng::decode,
                TestPng::dimensions,
            )
        assertEquals(6, prepared.width)
        assertEquals(2, prepared.height)
        assertEquals(2, ObjParser.parse(prepared.objText).triangleCount)
        assertEquals(0xFFFF0000.toInt(), requireNotNull(prepared.color).getSafe(0, 0))
        assertEquals(0xFF0000FF.toInt(), requireNotNull(prepared.color).getSafe(5, 0))
    }

    private fun png(
        width: Int,
        height: Int,
    ): ByteArray = PngCodec.encode(PixelBuffer.filled(width, height, -1))

    private fun multiObj(materials: Int): String = HEADER + (1..materials).joinToString("") { "usemtl material$it\nf 1/1 2/2 3/3\n" }

    companion object {
        private const val MIB = 1024 * 1024L
        private const val HEADER = "v 0 0 0\nv 1 0 0\nv 0 1 0\nvt 0 0\nvt 1 0\nvt 0 1\n"
        private const val OBJ = HEADER + "usemtl material1\nf 1/1 2/2 3/3\n"
    }
}
