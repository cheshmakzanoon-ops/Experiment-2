package com.artflow.studio.core.three

import com.artflow.studio.core.color.ColorProfile
import com.artflow.studio.core.export.PngCodec
import com.artflow.studio.core.export.TestPng
import com.artflow.studio.core.pixels.PixelBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.util.zip.CRC32
import java.util.zip.ZipInputStream

/** The 8x4 fixture was independently authored with Python struct/zlib, not ObjExport or PngCodec. */
class PreparedModelImportTest {
    private val fixture by lazy { requireNotNull(javaClass.getResourceAsStream("/models/own-material-import.zip")).use { it.readBytes() } }
    private val files by lazy {
        buildMap {
            ZipInputStream(fixture.inputStream()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    put(entry.name, zip.readBytes())
                    entry = zip.nextEntry
                }
            }
        }
    }
    private val metallicValues = intArrayOf(0, 1, 64, 128, 153, 254, 255, 17)
    private val roughnessValues = intArrayOf(255, 128, 1, 0, 254, 17, 153, 64)

    @Test
    fun independentFixtureRetainsEveryValueAndOriginalUvText() {
        val prepared = PreparedModelImport.prepare(ModelPackage.read(fixture), TestPng::decode)
        assertEquals(files.getValue("model.obj").decodeToString(), prepared.objText)
        assertEquals(8, prepared.width)
        assertEquals(4, prepared.height)
        assertArrayEquals(expectedColor(8, 4), requireNotNull(prepared.color).pixels)
        assertArrayEquals(expectedGray(metallicValues, 8, 4), requireNotNull(prepared.metallic).pixels)
        assertArrayEquals(expectedGray(roughnessValues, 8, 4), requireNotNull(prepared.roughness).pixels)
    }

    @Test
    fun oneDownsizeKeepsAllThreePlanesRegisteredWithoutRewritingUvs() {
        val prepared = PreparedModelImport.prepare(ModelPackage.read(fixture), TestPng::decode, maxSize = 4)
        assertEquals(files.getValue("model.obj").decodeToString(), prepared.objText)
        assertEquals(4, prepared.width)
        assertEquals(2, prepared.height)
        // Each authored source block is exactly 2x2, so its independent expected mean is its own value.
        assertArrayEquals(expectedColor(4, 2), requireNotNull(prepared.color).pixels)
        assertArrayEquals(expectedGray(metallicValues, 4, 2), requireNotNull(prepared.metallic).pixels)
        assertArrayEquals(expectedGray(roughnessValues, 4, 2), requireNotNull(prepared.roughness).pixels)
    }

    @Test
    fun combinedBudgetRefusesBeforeAnyDecoderAllocationAndSharedDownsizeCanFit() {
        var decoded = 0
        val error =
            assertThrows(IllegalArgumentException::class.java) {
                PreparedModelImport.prepare(
                    ModelPackage.read(fixture),
                    { bytes ->
                        decoded++
                        TestPng.decode(bytes)
                    },
                    memoryBudgetBytes = 1024,
                )
            }
        assertTrue(error.message.orEmpty().contains("memory"))
        assertEquals(0, decoded)
        val fitted =
            PreparedModelImport.prepare(
                ModelPackage.read(fixture),
                { bytes ->
                    decoded++
                    TestPng.decode(bytes)
                },
                maxSize = 4,
                memoryBudgetBytes = 1024,
            )
        assertEquals(3, decoded)
        assertEquals(4, fitted.width)
        assertEquals(2, fitted.height)
    }

    @Test
    fun mismatchedMapDimensionsAreRejectedBeforeDecoding() {
        var decoded = 0
        val mismatched = contents(metallic = PngCodec.encode(PixelBuffer.filled(4, 4, gray(128))))
        val error =
            assertThrows(IllegalArgumentException::class.java) {
                PreparedModelImport.prepare(mismatched, {
                    decoded++
                    null
                })
            }
        assertTrue(error.message.orEmpty().contains("Metallic map must match"))
        assertEquals(0, decoded)
    }

    @Test
    fun corruptOrUnsupportedPngHeadersAreRejectedBeforeDecoding() {
        val malformed = files.getValue("metallic.png").copyOf().also { it[16] = (it[16].toInt() xor 1).toByte() }
        for (bytes in listOf(byteArrayOf(1), malformed)) {
            var decoded = 0
            val error =
                assertThrows(IllegalArgumentException::class.java) {
                    PreparedModelImport.prepare(contents(metallic = bytes), {
                        decoded++
                        null
                    })
                }
            assertTrue(error.message.orEmpty().contains("Metallic"))
            assertEquals(0, decoded)
        }
    }

    @Test
    fun aMapWithColorOrTransparencyCannotBeSilentlyConvertedToScalarData() {
        for (badPixel in listOf(0xFF804020.toInt(), 0x80808080.toInt())) {
            val map = PixelBuffer.filled(8, 4, gray(128)).apply { pixels[7] = badPixel }
            val error =
                assertThrows(IllegalArgumentException::class.java) {
                    PreparedModelImport.prepare(contents(metallic = PngCodec.encode(map)), TestPng::decode)
                }
            assertTrue(error.message.orEmpty().contains("opaque grayscale"))
        }
    }

    @Test
    fun aDeclaredMapWhoseBodyCannotDecodeIsAnExplicitFailure() {
        val source = contents()
        val badMap = requireNotNull(source.scalarMaps?.roughness)
        val error =
            assertThrows(IllegalArgumentException::class.java) {
                PreparedModelImport.prepare(source, { bytes -> if (bytes === badMap) null else TestPng.decode(bytes) })
            }
        assertTrue(error.message.orEmpty().contains("Roughness texture could not be decoded"))
    }

    @Test
    fun scalarColorMetadataIsRefusedBeforeAnyPlatformDecode() {
        val chunks =
            linkedMapOf(
                "gAMA" to integers(100_000),
                "cHRM" to integers(31_270, 32_900, 64_000, 33_000, 30_000, 60_000, 15_000, 6_000),
                "sRGB" to byteArrayOf(0),
                "sBIT" to byteArrayOf(8, 8, 8, 8),
                "cICP" to byteArrayOf(1, 13, 0, 1),
                "mDCV" to
                    ByteBuffer
                        .allocate(24)
                        .apply {
                            shortArrayOf(32_000, 16_500, 15_000, 30_000, 7_500, 3_000, 15_635, 16_450).forEach { putShort(it) }
                            putInt(10_000_000)
                            putInt(1)
                        }.array(),
                "cLLI" to integers(10_000_000, 4_000_000),
            )
        for ((type, body) in chunks) {
            val tagged = withChunk(files.getValue("metallic.png"), type, body)
            // The stored grayscale samples are unchanged. A decoder that honors the metadata may alter them.
            assertArrayEquals(expectedGray(metallicValues, 8, 4), requireNotNull(TestPng.decode(tagged)).pixels)
            var decoded = 0
            val error =
                assertThrows(IllegalArgumentException::class.java) {
                    PreparedModelImport.prepare(contents(metallic = tagged), {
                        decoded++
                        TestPng.decode(it)
                    })
                }
            assertTrue(error.message.orEmpty().contains("color metadata ($type)"))
            assertEquals(0, decoded)
        }
    }

    @Test
    fun scalarIccProfilesAreRefusedWhileColorImagesKeepTheirExistingDecoderPath() {
        val taggedMap = PngCodec.encode(PixelBuffer.filled(8, 4, gray(128)), profile = ColorProfile.DISPLAY_P3)
        var decoded = 0
        val error =
            assertThrows(IllegalArgumentException::class.java) {
                PreparedModelImport.prepare(contents(roughness = taggedMap), {
                    decoded++
                    TestPng.decodeSrgb(it)
                })
            }
        assertTrue(error.message.orEmpty().contains("color metadata (iCCP)"))
        assertEquals(0, decoded)

        val taggedColor = PngCodec.encode(PixelBuffer(8, 4, expectedColor(8, 4)), profile = ColorProfile.DISPLAY_P3)
        val prepared = PreparedModelImport.prepare(contents(color = taggedColor), TestPng::decodeSrgb)
        assertArrayEquals(requireNotNull(TestPng.decodeSrgb(taggedColor)).pixels, requireNotNull(prepared.color).pixels)
        assertArrayEquals(expectedGray(metallicValues, 8, 4), requireNotNull(prepared.metallic).pixels)
        assertArrayEquals(expectedGray(roughnessValues, 8, 4), requireNotNull(prepared.roughness).pixels)
    }

    @Test
    fun harmlessPhysicalPixelMetadataDoesNotChangeScalarValues() {
        val physical = PngCodec.encode(PixelBuffer(8, 4, expectedGray(metallicValues, 8, 4)), dpi = 132)
        val prepared = PreparedModelImport.prepare(contents(metallic = physical), TestPng::decode)
        assertArrayEquals(expectedGray(metallicValues, 8, 4), requireNotNull(prepared.metallic).pixels)
    }

    @Test
    fun truncatedScalarChunksAreRejectedBeforePlatformDecode() {
        val complete = files.getValue("metallic.png")
        var decoded = 0
        val error =
            assertThrows(IllegalArgumentException::class.java) {
                PreparedModelImport.prepare(contents(metallic = complete.copyOf(complete.size - 1)), {
                    decoded++
                    null
                })
            }
        assertTrue(error.message.orEmpty().contains("PNG chunks are incomplete"))
        assertEquals(0, decoded)
    }

    @Test
    fun theDecodedSizeMustAgreeWithThePreflightHeader() {
        val error =
            assertThrows(IllegalArgumentException::class.java) {
                PreparedModelImport.prepare(contents(), { PixelBuffer(1, 1) })
            }
        assertTrue(error.message.orEmpty().contains("size is inconsistent"))
    }

    @Test
    fun absentScalarPlanesStayAbsentInsteadOfCreatingUnrelatedColorLayers() {
        val prepared = PreparedModelImport.prepare(contents(metallic = null, roughness = null), TestPng::decode)
        assertNull(prepared.metallic)
        assertNull(prepared.roughness)
        assertArrayEquals(expectedColor(8, 4), requireNotNull(prepared.color).pixels)
    }

    @Test
    fun scalarPayloadCannotBypassTheSingleMaterialAndColorRequirements() {
        val multiple = files.getValue("model.obj").decodeToString() + "usemtl Other\nf 1/1 2/2 3/3\n"
        assertThrows(IllegalArgumentException::class.java) {
            PreparedModelImport.prepare(contents(objText = multiple), TestPng::decode)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PreparedModelImport.prepare(contents(color = null), TestPng::decode)
        }
    }

    @Test
    fun aLegacyUntexturedModelRetainsTheRequestedDefaultCanvasAndGeometry() {
        val obj = files.getValue("model.obj").decodeToString()
        val prepared = PreparedModelImport.prepare(ModelPackage.Contents(obj), { error("No image should be decoded") }, defaultSize = 16)
        assertEquals(obj, prepared.objText)
        assertEquals(16, prepared.width)
        assertEquals(16, prepared.height)
        assertNull(prepared.color)
        assertNull(prepared.metallic)
        assertNull(prepared.roughness)
    }

    @Test
    fun legacyColorOnlyMaterialAtlasStillUsesItsEstablishedLayout() {
        val two = files.getValue("model.obj").decodeToString() + "usemtl Other\nf 1/1 2/2 3/3\n"
        val first = PngCodec.encode(PixelBuffer.filled(2, 2, 0xFFFF0000.toInt()))
        val second = PngCodec.encode(PixelBuffer.filled(2, 2, 0xFF0000FF.toInt()))
        val source = ModelPackage.Contents(two, first, mapOf("painted" to first, "Other" to second))
        val prepared = PreparedModelImport.prepare(source, TestPng::decode)
        assertEquals(4, prepared.width)
        assertEquals(2, prepared.height)
        assertEquals(0xFFFF0000.toInt(), requireNotNull(prepared.color).getSafe(0, 0))
        assertEquals(0xFF0000FF.toInt(), requireNotNull(prepared.color).getSafe(3, 0))
        assertEquals(2, ObjParser.parse(prepared.objText).triangleCount)
        assertNull(prepared.metallic)
        assertNull(prepared.roughness)
    }

    private fun contents(
        objText: String = files.getValue("model.obj").decodeToString(),
        color: ByteArray? = files.getValue("color.png"),
        metallic: ByteArray? = files.getValue("metallic.png"),
        roughness: ByteArray? = files.getValue("roughness.png"),
    ): ModelPackage.Contents = ModelPackage.Contents(objText, color, scalarMaps = ModelPackage.ScalarMaps(metallic, roughness))

    private fun expectedGray(
        values: IntArray,
        width: Int,
        height: Int,
    ): IntArray = IntArray(width * height) { at -> gray(values[(at / width * 2 / height) * 4 + (at % width * 4 / width)]) }

    private fun expectedColor(
        width: Int,
        height: Int,
    ): IntArray =
        IntArray(width * height) { at ->
            val x = at % width * 4 / width
            val y = at / width * 2 / height
            (255 shl 24) or ((10 + x * 53) shl 16) or ((20 + y * 80) shl 8) or (30 + (x + y) * 17)
        }

    private fun gray(value: Int): Int = (255 shl 24) or (value * 0x010101)

    private fun integers(vararg values: Int): ByteArray =
        ByteBuffer.allocate(values.size * 4).apply { values.forEach { putInt(it) } }.array()

    /** Add independently constructed metadata after IHDR without changing the fixture's IDAT samples. */
    private fun withChunk(
        png: ByteArray,
        type: String,
        body: ByteArray,
    ): ByteArray {
        val name = type.toByteArray(Charsets.US_ASCII)
        val crc =
            CRC32()
                .apply {
                    update(name)
                    update(body)
                }.value
                .toInt()
        val chunk =
            ByteBuffer
                .allocate(body.size + 12)
                .putInt(body.size)
                .put(name)
                .put(body)
                .putInt(crc)
                .array()
        return png.copyOfRange(0, 33) + chunk + png.copyOfRange(33, png.size)
    }
}
