package com.artflow.studio.data.local

import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.core.render.Compositor
import com.artflow.studio.domain.model.animation.AnimationFrame
import com.artflow.studio.domain.model.brush.BrushLibraryCodec
import com.artflow.studio.domain.model.brush.BrushParams
import com.artflow.studio.domain.model.brush.DualBrush
import com.artflow.studio.domain.model.brush.GrainBlend
import com.artflow.studio.domain.model.brush.SavedBrush
import com.artflow.studio.domain.model.brush.SourceBrushScalar
import com.artflow.studio.domain.model.brush.SourceBrushStructuredField
import com.artflow.studio.domain.model.brush.Stroke
import com.artflow.studio.domain.model.brush.StrokePoint
import com.artflow.studio.domain.model.layer.BlendMode
import com.artflow.studio.domain.model.layer.Layer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.security.MessageDigest

/** Stored vector strokes replay, so correcting a brush setting needs an explicit document version. */
class SecondaryGrainDocumentMigrationTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun signedVersionThreeFixturesKeepTheirOldPixelsAfterMigrationAndResave() {
        for ((name, expectedFileSha) in LEGACY_FIXTURES) {
            val bytes = fixture(name)
            assertEquals(name, expectedFileSha, sha(bytes))
            val original = json.decodeFromString(CanvasDocument.serializer(), bytes.decodeToString())
            assertEquals(3, original.version)
            assertTrue(original.checksum != null)
            assertNotEquals(
                GrainBlend.MULTIPLY,
                original.layers
                    .single()
                    .strokes
                    .single()
                    .brushParams.dual
                    ?.params
                    ?.grainBlend,
            )

            val migrated = CanvasDocumentCodec.decode(bytes.decodeToString())
            CanvasDocumentCodec.requireValidHeader(migrated)
            CanvasDocumentCodec.requireValidStructure(migrated)
            assertEquals(4, migrated.version)
            assertNull(migrated.checksum)
            assertEquals(
                GrainBlend.MULTIPLY,
                migrated.layers
                    .single()
                    .strokes
                    .single()
                    .brushParams.dual
                    ?.params
                    ?.grainBlend,
            )
            val pixels = render(migrated)
            assertEquals(31, pixels.pixels[30 * pixels.width + 30] ushr 24)
            assertEquals("8ca68f2cbdc5acb22f56e91923660166597c0f531a1a2e0e4964df2d6d1887b8", pixelSha(pixels))

            val saved = CanvasDocumentCodec.encode(migrated)
            val reopened = CanvasDocumentCodec.decode(saved.decodeToString())
            assertEquals(migrated, reopened)
            assertArrayEquals(pixels.pixels, render(reopened).pixels)
            assertArrayEquals(saved, CanvasDocumentCodec.encode(reopened))
            assertEquals("Loading must not rewrite the supplied bytes", expectedFileSha, sha(bytes))
        }
    }

    @Test
    fun originalVersionControlsBothMigrationsAcrossLayersAndEveryFrame() {
        val nested = BrushParams(grainBlend = GrainBlend.HARD_MIX, sourceImportVersion = 2)
        val second =
            sourceAware(GrainBlend.SUBTRACT).copy(
                dual = DualBrush(nested, DualBrush.Mode.ADD, sourceBlendMode = 27),
            )
        val primary = sourceAware(GrainBlend.HARD_MIX).copy(dual = DualBrush(second, DualBrush.Mode.SUBTRACT, sourceBlendMode = 31))
        val first = Layer(1, "First", 0, strokes = listOf(stroke(primary)))
        val group = Layer(10, "Normal", 1, isGroup = true)
        val secondFrame = Layer(2, "Second", 0, strokes = listOf(stroke(primary).copy(layerId = 2, isEraser = true)))
        val expectedPrimary = primary.copy(dual = primary.dual?.copy(params = second.copy(grainBlend = GrainBlend.MULTIPLY)))
        for (version in 1..3) {
            val input =
                document(listOf(first, group), version).copy(
                    frames =
                        listOf(
                            AnimationFrame(1, "First", listOf(first, group)),
                            AnimationFrame(2, "Second", listOf(secondFrame)),
                        ),
                )
            val migrated = roundTrip(input)
            assertEquals(4, migrated.version)
            assertEquals(
                expectedPrimary,
                migrated.layers
                    .first()
                    .strokes
                    .single()
                    .brushParams,
            )
            assertEquals(
                expectedPrimary,
                migrated.frames[0]
                    .layers
                    .first()
                    .strokes
                    .single()
                    .brushParams,
            )
            assertEquals(
                expectedPrimary,
                migrated.frames[1]
                    .layers
                    .single()
                    .strokes
                    .single()
                    .brushParams,
            )
            assertTrue(
                migrated.frames[1]
                    .layers
                    .single()
                    .strokes
                    .single()
                    .isEraser,
            )
            val expectedGroup = if (version < 3) BlendMode.PASS_THROUGH else BlendMode.NORMAL
            assertEquals(expectedGroup, migrated.layers[1].blendMode)
            assertEquals(expectedGroup, migrated.frames[0].layers[1].blendMode)
            val restoredSecond =
                requireNotNull(
                    migrated.layers
                        .first()
                        .strokes
                        .single()
                        .brushParams.dual,
                ).params
            assertEquals(second.importedSourceScalars, restoredSecond.importedSourceScalars)
            assertEquals(second.importedSourceStructures, restoredSecond.importedSourceStructures)
            assertEquals(second.importedSourceColorData, restoredSecond.importedSourceColorData)
            assertEquals(2, restoredSecond.sourceImportVersion)
            assertEquals(second.dual, restoredSecond.dual)
            assertEquals(
                31,
                migrated.layers
                    .first()
                    .strokes
                    .single()
                    .brushParams.dual
                    ?.sourceBlendMode,
            )
        }
    }

    @Test
    fun corruptLegacyChecksumIsRejectedBeforeNormalizingEitherReplaySetting() {
        val signed = fixture("v3-secondary-grain-subtract.json").decodeToString()
        val editedGrain = signed.replace("\"grainBlend\":\"SUBTRACT\"", "\"grainBlend\":\"HARD_MIX\"")
        val editedVersion = signed.replace("\"version\":3", "\"version\":2")
        assertNotEquals(signed, editedGrain)
        assertNotEquals(signed, editedVersion)
        for (damaged in listOf(editedGrain, editedVersion)) {
            val error = assertThrows(IllegalArgumentException::class.java) { CanvasDocumentCodec.decode(damaged) }
            assertTrue(requireNotNull(error.message).contains("checksum"))
        }
    }

    @Test
    fun versionFourKeepsCorrectedSubtractAndHardMixAcrossSignedRoundTrips() {
        val expected =
            mapOf(
                "v3-secondary-grain-subtract.json" to (13 to "f8c13977c11982089eb97fb5668df21570d4ce264c1f3bd8313b02c42ef9c57e"),
                "v3-secondary-grain-hard-mix.json" to (128 to "a7cad3d3cbd7c2ff16d47da60b18a8f145ba45dd8c0ab047ff707554a80b799e"),
            )
        for ((name, pixelExpectation) in expected) {
            val original = json.decodeFromString(CanvasDocument.serializer(), fixture(name).decodeToString())
            val current = original.copy(version = 4, checksum = null)
            val bytes = CanvasDocumentCodec.encode(current)
            val reopened = CanvasDocumentCodec.decode(bytes.decodeToString())
            assertEquals(current, reopened)
            val pixels = render(reopened)
            assertEquals(pixelExpectation.first, pixels.pixels[30 * pixels.width + 30] ushr 24)
            assertEquals(pixelExpectation.second, pixelSha(pixels))
            assertArrayEquals(bytes, CanvasDocumentCodec.encode(reopened))
        }
    }

    @Test
    fun versionThreeNormalGroupsRetainTheirIsolatedRendering() {
        val paper = Layer(1, "Paper", 0)
        val ink = Layer(2, "Ink", 1, blendMode = BlendMode.MULTIPLY, parentGroupId = 10)
        val group = Layer(10, "Normal", 2, isGroup = true)

        fun composite(layers: List<Layer>): Int {
            val inputs =
                layers.map { layer ->
                    val raster =
                        when (layer.id) {
                            1L -> PixelBuffer.filled(1, 1, 0xFF808080.toInt())
                            2L -> PixelBuffer.filled(1, 1, 0xFF800000.toInt())
                            else -> null
                        }
                    Compositor.LayerInput(layer, raster)
                }
            return Compositor().composite(inputs, 1, 1).pixels.single()
        }
        val original = document(listOf(paper, ink, group), 3)
        val migrated = roundTrip(original)
        assertEquals(BlendMode.NORMAL, migrated.layers.last().blendMode)
        assertEquals(0xFF800000.toInt(), composite(migrated.layers))
        assertEquals(composite(original.layers), composite(migrated.layers))
        val older = roundTrip(original.copy(version = 2))
        assertEquals(BlendMode.PASS_THROUGH, older.layers.last().blendMode)
        assertEquals(0xFF400000.toInt(), composite(older.layers))
    }

    @Test
    fun rasterReferencesDefaultSecondaryAndPrimaryGrainRemainUnchangedAndFutureVersionsAreRejected() {
        val primary = sourceAware(GrainBlend.HARD_MIX).copy(dual = DualBrush(sourceAware(GrainBlend.MULTIPLY)))
        val raster = Layer(1, "Raster", 0, rasterFile = "layers/1/original.png", maskFile = "layers/1/mask.png", maskDensity = 0.7f)
        val vectors = Layer(2, "Vector", 1, strokes = listOf(stroke(primary).copy(layerId = 2)))
        val input = document(listOf(raster, vectors), 3).copy(trackedMs = 123L)
        assertEquals(input.copy(version = 4), roundTrip(input))
        val future = roundTrip(input.copy(version = 5))
        assertEquals(5, future.version)
        assertThrows(IllegalArgumentException::class.java) { CanvasDocumentCodec.requireValidHeader(future) }
    }

    @Test
    fun savedBrushLibraryDefinitionsAndRawSourceValuesAreOutsideTheDocumentMigration() {
        for (grainBlend in listOf(GrainBlend.SUBTRACT, GrainBlend.HARD_MIX)) {
            val second = sourceAware(grainBlend)
            val primary = sourceAware(GrainBlend.HARD_MIX).copy(dual = DualBrush(second, sourceBlendMode = 31))
            val saved = listOf(SavedBrush("source-grain", "Source grain", primary))
            val bytes = BrushLibraryCodec.encode(saved)
            val migrated = roundTrip(document(listOf(Layer(1, "Vector", 0, strokes = listOf(stroke(primary)))), 3))
            assertEquals(
                GrainBlend.MULTIPLY,
                migrated.layers
                    .single()
                    .strokes
                    .single()
                    .brushParams.dual
                    ?.params
                    ?.grainBlend,
            )
            assertEquals(saved, BrushLibraryCodec.decode(bytes))
            assertEquals(bytes, BrushLibraryCodec.encode(saved))
            assertEquals(
                grainBlend,
                BrushLibraryCodec
                    .decode(bytes)
                    .single()
                    .parameters.dual
                    ?.params
                    ?.grainBlend,
            )
            assertTrue(bytes.startsWith("{\"version\":1,"))
        }
    }

    private fun sourceAware(grainBlend: GrainBlend): BrushParams =
        BrushParams(
            grainBlend = grainBlend,
            sourceImportVersion = 2,
            importedSourceScalars = mapOf("grainBlendMode" to SourceBrushScalar(SourceBrushScalar.Type.INTEGER, "4")),
            importedSourceStructures =
                mapOf(
                    "savedPaintSizes" to
                        SourceBrushStructuredField(
                            "NSArray",
                            SourceBrushStructuredField.Kind.ARRAY,
                            items = listOf(SourceBrushScalar(SourceBrushScalar.Type.REAL, "0.25")),
                        ),
                ),
            importedSourceColorData = "AAAAAAAAAAAAAAAAAAAAAA==",
        )

    private fun stroke(params: BrushParams): Stroke =
        Stroke(961L, listOf(StrokePoint(30.5f, 30.5f, timestamp = 0L)), params, 1L, 0xFF114477.toInt(), timestamp = 0L)

    private fun document(
        layers: List<Layer>,
        version: Int,
    ): CanvasDocument =
        CanvasDocument(
            version = version,
            width = 96,
            height = 64,
            dpi = 72,
            backgroundColor = 0,
            activeLayerId = layers.first().id,
            nextLayerId = 20L,
            layers = layers,
            savedAt = 0L,
        )

    private fun roundTrip(document: CanvasDocument): CanvasDocument =
        CanvasDocumentCodec.decode(CanvasDocumentCodec.encode(document).decodeToString())

    private fun fixture(name: String): ByteArray = requireNotNull(javaClass.getResourceAsStream("/documents/$name")).use { it.readBytes() }

    private fun render(document: CanvasDocument): PixelBuffer =
        Compositor().composite(
            document.layers.map { Compositor.LayerInput(it, strokes = it.strokes) },
            document.width,
            document.height,
            document.backgroundColor,
        )

    private fun pixelSha(buffer: PixelBuffer): String {
        val bytes = ByteBuffer.allocate(buffer.pixels.size * Int.SIZE_BYTES)
        buffer.pixels.forEach(bytes::putInt)
        return sha(bytes.array())
    }

    private fun sha(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
            "%02x".format(
                it.toInt() and 255,
            )
        }

    companion object {
        private val LEGACY_FIXTURES =
            mapOf(
                "v3-secondary-grain-subtract.json" to "2173832724cf2b794d89383d2acc58d7c821413f774602a14c75ffb2ed03b320",
                "v3-secondary-grain-hard-mix.json" to "2cea9b303ea4f5399a08152b26db890c6f8fef69e72586ee0351cf6b7da489bc",
            )
    }
}
