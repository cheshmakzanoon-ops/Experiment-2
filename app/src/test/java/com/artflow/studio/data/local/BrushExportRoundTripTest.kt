package com.artflow.studio.data.local

import com.artflow.studio.core.export.ProcreateBrushWriter
import com.artflow.studio.core.export.TestPng
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.core.render.CustomGrains
import com.artflow.studio.domain.model.brush.BrushParams
import com.artflow.studio.domain.model.brush.ColorDynamics
import com.artflow.studio.domain.model.brush.ImportedPressure
import com.artflow.studio.domain.model.brush.SourceBrushScalar
import com.artflow.studio.domain.model.brush.SourceBrushStructuredField
import com.artflow.studio.domain.model.brush.PressureMapping
import com.artflow.studio.domain.model.brush.SavedBrush
import com.artflow.studio.domain.model.layer.BlendMode
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.zip.ZipInputStream
import java.util.Base64

/**
 * Procreate brushes written by [ProcreateBrushWriter] and read back through [BrushArchives.read], the import
 * path the app uses: settings within float rounding, names and order, and the shape and grain pixels exactly.
 */
class BrushExportRoundTripTest {
    private val decode: (ByteArray) -> PixelBuffer? = { TestPng.decode(it) }

    private val settings =
        BrushParams(
            size = 40f,
            opacity = 0.8f,
            spacing = 0.15f,
            scatter = 0.2f,
            count = 3,
            rotation = 30f,
            taperStart = 0.25f,
            taperEnd = 0.1f,
            taperOpacity = 0.4f,
            pressureToSize = 0.6f,
            pressureToOpacity = 0.35f,
            hueJitter = 0.1f,
            saturationJitter = 0.2f,
            brightnessJitter = 0.3f,
            sizeJitter = 0.15f,
            opacityJitter = 0.05f,
            smoothing = 0.45f,
            wetMix = 0.4f,
            flow = 0.7f,
            textureScale = 1.5f,
            tiltInfluence = 0.5f,
            tiltToRotation = true,
            velocityToSize = -0.3f,
            velocityToOpacity = 0.25f,
            roundness = 0.6f,
            blendMode = BlendMode.MULTIPLY,
            wetEdges = 0.2f,
            burntEdges = 0.15f,
            grainDepth = 0.8f,
            falloff = 0.35f,
            tipFlipX = true,
            tipFlipY = false,
            tipRandomized = true,
            buildUp = true,
            grainMoving = true,
            secondaryPressure = 0.3f,
            secondaryJitter = 0.12f,
        )

    /**
     * A tile whose values run from 0 to 255 (x * 31 covers every byte along a row), so the import's stretch to
     * the full range leaves them unchanged.
     */
    private fun shapeTile(seed: Int) =
        CustomGrains.Tile(
            CustomGrains.TILE,
            ByteArray(CustomGrains.TILE * CustomGrains.TILE) { index ->
                val x = index % CustomGrains.TILE
                val y = index / CustomGrains.TILE
                ((x * 31 + y * 17 + seed) and 0xFF).toByte()
            },
        )

    private fun assertSettings(
        expected: BrushParams,
        actual: BrushParams,
    ) {
        fun close(
            field: String,
            want: Float,
            got: Float,
        ) = assertEquals(field, want.toDouble(), got.toDouble(), TOLERANCE)
        close("size", expected.size, actual.size)
        close("opacity", expected.opacity, actual.opacity)
        close("spacing", expected.spacing, actual.spacing)
        close("scatter", expected.scatter, actual.scatter)
        assertEquals("count", expected.count, actual.count)
        close("rotation", expected.rotation, actual.rotation)
        close("taperStart", expected.taperStart, actual.taperStart)
        close("taperEnd", expected.taperEnd, actual.taperEnd)
        close("taperOpacity", expected.taperOpacity, actual.taperOpacity)
        close("pressureToSize", expected.pressureToSize, actual.pressureToSize)
        close("pressureToOpacity", expected.pressureToOpacity, actual.pressureToOpacity)
        close("hueJitter", expected.hueJitter, actual.hueJitter)
        close("saturationJitter", expected.saturationJitter, actual.saturationJitter)
        close("brightnessJitter", expected.brightnessJitter, actual.brightnessJitter)
        close("sizeJitter", expected.sizeJitter, actual.sizeJitter)
        close("opacityJitter", expected.opacityJitter, actual.opacityJitter)
        close("smoothing", expected.smoothing, actual.smoothing)
        close("wetMix", expected.wetMix, actual.wetMix)
        close("flow", expected.flow, actual.flow)
        close("textureScale", expected.textureScale, actual.textureScale)
        close("tiltInfluence", expected.tiltInfluence, actual.tiltInfluence)
        close("velocityToSize", expected.velocityToSize, actual.velocityToSize)
        close("velocityToOpacity", expected.velocityToOpacity, actual.velocityToOpacity)
        close("roundness", expected.roundness, actual.roundness)
        close("wetEdges", expected.wetEdges, actual.wetEdges)
        close("burntEdges", expected.burntEdges, actual.burntEdges)
        close("grainDepth", expected.grainDepth, actual.grainDepth)
        close("falloff", expected.falloff, actual.falloff)
        close("secondaryPressure", expected.secondaryPressure, actual.secondaryPressure)
        close("secondaryJitter", expected.secondaryJitter, actual.secondaryJitter)
        assertEquals("blendMode", expected.blendMode, actual.blendMode)
        assertEquals("tiltToRotation", expected.tiltToRotation, actual.tiltToRotation)
        assertEquals("tipFlipX", expected.tipFlipX, actual.tipFlipX)
        assertEquals("tipFlipY", expected.tipFlipY, actual.tipFlipY)
        assertEquals("tipRandomized", expected.tipRandomized, actual.tipRandomized)
        assertEquals("buildUp", expected.buildUp, actual.buildUp)
        assertEquals("grainMoving", expected.grainMoving, actual.grainMoving)
    }

    @Test
    fun aBrushFileReadsBackItsSettingsNameAndImages() {
        val shape = shapeTile(seed = 0)
        val grain = shapeTile(seed = 91)
        CustomGrains.register("custom-7e1a", shape)
        CustomGrains.register("custom-7e1b", grain)
        val brush = SavedBrush("one", "Round Ink", settings.copy(shapeId = "custom-7e1a", textureId = "custom-7e1b"))

        val imported = BrushArchives.read(BrushArchives.procreateBrush(brush), decode).single()

        assertEquals("Round Ink", imported.name)
        assertSettings(settings, requireNotNull(imported.settings).parameters)
        assertEquals("Round Ink", imported.settings?.name)
        assertArrayEquals(shape.values, requireNotNull(imported.shape).values)
        assertArrayEquals(grain.values, requireNotNull(imported.grain).values)
        assertEquals(false, imported.settings?.shapeInverted)
    }

    @Test
    fun aBrushFileHasItsArchiveAndImagesAtTheTopLevel() {
        CustomGrains.register("custom-7e1c", shapeTile(seed = 3))
        val bytes = BrushArchives.procreateBrush(SavedBrush("two", "Top level", settings.copy(shapeId = "custom-7e1c")))
        val names = ZipInputStream(bytes.inputStream()).use { zip -> generateSequence { zip.nextEntry }.map { it.name }.toList() }
        assertEquals(listOf("Brush.archive", "Shape.png"), names)
    }

    @Test
    fun imagesThatAreNotLoadedAreLeftOutAndTheBrushStillImports() {
        val bytes = BrushArchives.procreateBrush(SavedBrush("three", "Bare", BrushParams(shapeId = "custom-7e1d")))
        val imported = BrushArchives.read(bytes, decode).single()
        assertNull(imported.shape)
        assertNull(imported.grain)
        assertNotNull(imported.settings)
        assertEquals("Bare", imported.name)
    }

    @Test
    fun aBrushSetKeepsItsOrderNamesAndSettings() {
        val second = BrushParams(size = 12f, spacing = 0.04f, count = 1, blendMode = BlendMode.SCREEN, roundness = 0.3f)
        val third = BrushParams(size = 150f, opacity = 0.5f, rotation = 170f, tipRandomized = true)
        val bytes =
            ProcreateBrushWriter.brushset(
                "Fixture set",
                listOf(
                    ProcreateBrushWriter.Brush("First", settings),
                    ProcreateBrushWriter.Brush("Second", second, shape = shapeTile(seed = 5)),
                    ProcreateBrushWriter.Brush("Third", third),
                ),
            )
        val brushes = BrushArchives.read(bytes, decode)
        assertEquals(listOf("First", "Second", "Third"), brushes.map { it.name })
        assertSettings(settings, requireNotNull(brushes[0].settings).parameters)
        assertSettings(second, requireNotNull(brushes[1].settings).parameters)
        assertSettings(third, requireNotNull(brushes[2].settings).parameters)
        assertArrayEquals(shapeTile(seed = 5).values, requireNotNull(brushes[1].shape).values)
        assertNull(brushes[0].shape)
        assertTrue(brushes.all { it.grain == null })
    }

    @Test
    fun signedPressureAndIndependentMirrorJitterSurviveExportAndImport() {
        val original =
            BrushParams(
                pressureToSize = -0.6f,
                pressureToOpacity = -0.35f,
                tipFlipX = false,
                tipFlipY = false,
                tipFlipXJitter = true,
                tipFlipYJitter = false,
            )
        val bytes = ProcreateBrushWriter.brush(ProcreateBrushWriter.Brush("Signed pressure", original))
        val imported = requireNotNull(BrushArchives.read(bytes, decode).single().settings).parameters
        assertEquals(-0.6f, imported.pressureToSize, 1e-6f)
        assertEquals(-0.35f, imported.pressureToOpacity, 1e-6f)
        assertTrue(imported.tipFlipXJitter)
        assertEquals(false, imported.tipFlipYJitter)
    }

    @Test
    fun signedColourDynamicsRoundTripThroughProcreateBrushFile() {
        val dynamics =
            ColorDynamics(
                pressureHue = -0.125f,
                pressureSaturation = -0.2f,
                pressureBrightness = 0.35f,
                tiltHue = -0.01f,
                tiltSaturation = 0.01f,
                tiltBrightness = -0.02f,
            )
        val source = BrushParams(colorDynamics = dynamics)
        val archive = ProcreateBrushWriter.brush(ProcreateBrushWriter.Brush("Colour pressure", source))
        val imported = requireNotNull(BrushArchives.read(archive, decode).single().settings).parameters
        assertEquals(dynamics, imported.colorDynamics)
        assertTrue(imported.colorDynamics.isActive)
    }

    @Test
    fun sourceAuxiliaryCurvesSurviveBrushExportAndLibraryRoundTrip() {
        val descending =
            PressureMapping(
                listOf(
                    PressureMapping.Point(0f, 1f),
                    PressureMapping.Point(0.35f, 0.6f),
                    PressureMapping.Point(1f, 0f),
                ),
            )
        val rising =
            PressureMapping(
                listOf(
                    PressureMapping.Point(0f, 0.1f),
                    PressureMapping.Point(1f, 0.9f),
                ),
            )
        val curves =
            mapOf(
                "dynamicsPressureBleedCurve" to descending,
                "dynamicsPressureGrainDepthCurve" to rising,
                "dynamicsPressureWetPullCurve" to descending,
            )
        val source = BrushParams(importedAuxiliaryCurves = curves)
        val brushFile = ProcreateBrushWriter.brush(ProcreateBrushWriter.Brush("Auxiliary curves", source))
        val imported = requireNotNull(BrushArchives.read(brushFile, decode).single().settings).parameters
        assertEquals(curves, imported.importedAuxiliaryCurves)
        val saved = com.artflow.studio.domain.model.brush.BrushLibraryCodec.encode(
            listOf(SavedBrush("curves", "Curves", imported)),
        )
        val restored = com.artflow.studio.domain.model.brush.BrushLibraryCodec.decode(saved).single().parameters
        assertEquals(curves, restored.importedAuxiliaryCurves)
    }

    @Test
    fun unknownSourceScalarsSurviveImportSaveAndExportWithoutReplacingEditableSettings() {
        val source =
            com.artflow.studio.core.export.ProcreateBrush.parameters(
                mapOf(
                    "plotSpacing" to 0.2,
                    "shapeCountRoll" to true,
                    "burntEdgesBlendModeExtended" to 34L,
                    "plotJitterLongitudinal" to 0.125,
                    "authorName" to "Synthetic artist",
                ),
            )
        assertEquals(SourceBrushScalar(SourceBrushScalar.Type.BOOLEAN, "true"), source.importedSourceScalars["shapeCountRoll"])
        assertEquals(SourceBrushScalar(SourceBrushScalar.Type.INTEGER, "34"), source.importedSourceScalars["burntEdgesBlendModeExtended"])
        val brush = SavedBrush("raw", "Source", source)
        val restored = com.artflow.studio.domain.model.brush.BrushLibraryCodec.decode(
            com.artflow.studio.domain.model.brush.BrushLibraryCodec.encode(listOf(brush)),
        ).single().parameters
        assertEquals(source.importedSourceScalars, restored.importedSourceScalars)
        val modified = restored.copy(spacing = 0.35f)
        val exported = ProcreateBrushWriter.brush(ProcreateBrushWriter.Brush("Edited source", modified))
        val imported = requireNotNull(BrushArchives.read(exported, decode).single().settings)
        assertEquals("Edited source", imported.name)
        assertEquals(0.35f, imported.parameters.spacing, 1e-6f)
        assertEquals(source.importedSourceScalars["authorName"], imported.parameters.importedSourceScalars["authorName"])
        assertEquals(source.importedSourceScalars["shapeCountRoll"], imported.parameters.importedSourceScalars["shapeCountRoll"])
        assertEquals(
            source.importedSourceScalars["burntEdgesBlendModeExtended"],
            imported.parameters.importedSourceScalars["burntEdgesBlendModeExtended"],
        )
        assertEquals(
            source.importedSourceScalars["plotJitterLongitudinal"],
            imported.parameters.importedSourceScalars["plotJitterLongitudinal"],
        )
    }

    @Test
    fun sourceScalarWriterRejectsInvalidNamesAndValues() {
        for (sourceScalars in listOf(
            mapOf("../escapes" to SourceBrushScalar(SourceBrushScalar.Type.TEXT, "x")),
            mapOf("shapeCountRoll" to SourceBrushScalar(SourceBrushScalar.Type.BOOLEAN, "no")),
            mapOf("unknownValue" to SourceBrushScalar(SourceBrushScalar.Type.REAL, "Infinity")),
        )) {
            assertThrows(IllegalArgumentException::class.java) {
                ProcreateBrushWriter.brush(
                    ProcreateBrushWriter.Brush(
                        "Malformed",
                        BrushParams(importedSourceScalars = sourceScalars),
                    ),
                )
            }
        }
    }

    @Test
    fun keyedRollParametersDatesAndSavedSizeArraysPreserveTheirTypes() {
        val archive = com.artflow.studio.core.export.KeyedArchiveBuilder()
        val roll =
            archive.instance(
                "ValkyrieBrushRollParameters",
                mapOf(
                    "activationFalloff" to 1.57079637,
                    "activationOrigin" to 3.14159274,
                    "mode" to 1L,
                    "activationRange" to 3.14159274,
                ),
            )
        val date = archive.instance("NSDate", mapOf("NS.time" to 595235271.168956))
        val sizes = archive.instance("NSArray", mapOf("NS.objects" to listOf(archive.add(0.24))))
        val original =
            com.artflow.studio.core.export.ProcreateBrush.read(
                archive.encode(
                    mapOf(
                        "name" to archive.add("Structured"),
                        "attackRollParameters" to roll,
                        "creationDate" to date,
                        "savedPaintSizes" to sizes,
                    ),
                ),
            ).parameters
        val stored = original.importedSourceStructures
        assertEquals(3, stored.size)
        assertEquals("ValkyrieBrushRollParameters", stored.getValue("attackRollParameters").className)
        assertEquals(SourceBrushScalar(SourceBrushScalar.Type.REAL, "595235271.168956"), stored.getValue("creationDate").fields["NS.time"])
        assertEquals(SourceBrushStructuredField.Kind.ARRAY, stored.getValue("savedPaintSizes").kind)
        val brush = SavedBrush("structured", "Structured", original)
        val reopened = com.artflow.studio.domain.model.brush.BrushLibraryCodec.decode(
            com.artflow.studio.domain.model.brush.BrushLibraryCodec.encode(listOf(brush)),
        ).single().parameters
        val exported = ProcreateBrushWriter.brush(ProcreateBrushWriter.Brush("Structured", reopened))
        val imported = requireNotNull(BrushArchives.read(exported, decode).single().settings).parameters
        assertEquals(stored, imported.importedSourceStructures)
    }

    @Test
    fun sourceSixteenByteColourDataSurvivesKeyedArchiveAndSavedLibrary() {
        val payload = ByteArray(16) { index -> (index * 13).toByte() }
        val keyed = com.artflow.studio.core.export.KeyedArchiveBuilder()
        val original =
            com.artflow.studio.core.export.ProcreateBrush.read(
                keyed.encode(mapOf("name" to keyed.add("Binary colour"), "color" to keyed.add(payload))),
            ).parameters
        val encoded = Base64.getEncoder().encodeToString(payload)
        assertEquals(encoded, original.importedSourceColorData)
        val saved = SavedBrush("binary-colour", "Binary colour", original)
        val restored = com.artflow.studio.domain.model.brush.BrushLibraryCodec.decode(
            com.artflow.studio.domain.model.brush.BrushLibraryCodec.encode(listOf(saved)),
        ).single().parameters
        assertEquals(encoded, restored.importedSourceColorData)
        val exported = ProcreateBrushWriter.brush(ProcreateBrushWriter.Brush("Binary colour", restored))
        val roundTripped = com.artflow.studio.core.export.ProcreateBrush.read(
            ZipInputStream(exported.inputStream()).use { zip ->
                check(zip.nextEntry.name == "Brush.archive")
                zip.readBytes()
            },
        )
        assertEquals(encoded, roundTripped.parameters.importedSourceColorData)
        assertThrows(IllegalArgumentException::class.java) {
            com.artflow.studio.domain.model.brush.BrushLibraryCodec.encode(
                listOf(saved.copy(parameters = original.copy(importedSourceColorData = "not-base64"))),
            )
        }
    }

    @Test
    fun allThreePrimaryPressureCurvesExportAsTheirObservedKeyedArchiveClass() {
        val overall =
            PressureMapping(
                listOf(
                    PressureMapping.Point(0f, 0.2f),
                    PressureMapping.Point(0.35f, 0.8f),
                    PressureMapping.Point(1f, 0.95f),
                ),
            )
        val falling = PressureMapping(listOf(PressureMapping.Point(0f, 1f), PressureMapping.Point(1f, 0f)))
        val channels = ImportedPressure(overall = overall, size = falling, opacity = overall)
        val source = BrushParams(importedPressure = channels)
        val exported = ProcreateBrushWriter.brush(ProcreateBrushWriter.Brush("Three pressure curves", source))
        val archiveData =
            ZipInputStream(exported.inputStream()).use { zip ->
                check(zip.nextEntry.name == "Brush.archive")
                zip.readBytes()
            }
        val keyed = com.artflow.studio.core.export.KeyedArchive(
            com.artflow.studio.core.export.BinaryPlist.read(archiveData),
        )
        val root = requireNotNull(keyed.root)
        for (field in listOf("dynamicsPressureOverall", "dynamicsPressureSizeCurve", "dynamicsPressureOpacityCurve")) {
            assertEquals("ValkyrieMagnitudinalCurve", keyed.className(root[field]))
        }
        val imported = requireNotNull(BrushArchives.read(exported, decode).single().settings).parameters
        assertEquals(channels, imported.importedPressure)
    }

    @Test
    fun unchangedOutOfRangeReferenceScalarsSurviveWithoutClampingOnExport() {
        val imported =
            com.artflow.studio.core.export.ProcreateBrush.parameters(
                mapOf(
                    "maxSize" to 16.0,
                    "paintSize" to 4.58,
                    "plotSpacing" to 0.0,
                    "textureScale" to 0.1,
                    "shapeCount" to 0.45,
                    "shapeRoundness" to 0.0,
                    "textureMovement" to 0.23,
                    "maxOpacity" to 0.8,
                    "paintOpacity" to 2.0,
                ),
            )
        // These are deliberately ArtFlow's *clamped* effective controls.
        assertEquals(192f, imported.size, 1e-6f)
        assertEquals(0.01f, imported.spacing, 1e-6f)
        assertEquals(0.25f, imported.textureScale, 1e-6f)
        val exported = ProcreateBrushWriter.brush(ProcreateBrushWriter.Brush("Raw values", imported))
        val archiveData =
            ZipInputStream(exported.inputStream()).use { zip ->
                check(zip.nextEntry.name == "Brush.archive")
                zip.readBytes()
            }
        val root = requireNotNull(
            com.artflow.studio.core.export.KeyedArchive(
                com.artflow.studio.core.export.BinaryPlist.read(archiveData),
            ).root,
        )
        fun exact(key: String, value: Double) = assertEquals(key, value, (root[key] as Number).toDouble(), 0.000001)
        exact("maxSize", 16.0)
        exact("paintSize", 4.58)
        exact("plotSpacing", 0.0)
        exact("textureScale", 0.1)
        exact("shapeRoundness", 0.0)
        exact("textureMovement", 0.23)
        exact("maxOpacity", 0.8)
        exact("paintOpacity", 2.0)
        val restored = com.artflow.studio.core.export.ProcreateBrush.read(archiveData).parameters
        assertEquals(imported.size, restored.size, 1e-6f)
        assertEquals(imported.spacing, restored.spacing, 1e-6f)
        assertEquals(imported.textureScale, restored.textureScale, 1e-6f)
    }

    @Test
    fun editingClampedBrushValuesOverridesOnlyThoseOriginalSourceKeys() {
        val original =
            com.artflow.studio.core.export.ProcreateBrush.parameters(
                mapOf("maxSize" to 16.0, "paintSize" to 4.58, "plotSpacing" to 0.0, "textureScale" to 0.1),
            )
        val edited = original.copy(size = 72f, spacing = 0.4f, textureScale = 1.5f)
        val exported = ProcreateBrushWriter.brush(ProcreateBrushWriter.Brush("Edited", edited))
        val archiveData =
            ZipInputStream(exported.inputStream()).use { zip ->
                check(zip.nextEntry.name == "Brush.archive")
                zip.readBytes()
            }
        val root = requireNotNull(
            com.artflow.studio.core.export.KeyedArchive(
                com.artflow.studio.core.export.BinaryPlist.read(archiveData),
            ).root,
        )
        assertEquals(3.0, (root["maxSize"] as Number).toDouble(), 1e-6)
        assertEquals(1.0, (root["paintSize"] as Number).toDouble(), 1e-6)
        assertEquals(0.4, (root["plotSpacing"] as Number).toDouble(), 1e-6)
        assertEquals(1.5, (root["textureScale"] as Number).toDouble(), 1e-6)
    }

    @Test
    fun unmappedNativeBlendIdentifiersArePreservedUntilTheBlendModeIsEdited() {
        val unknown = com.artflow.studio.core.export.ProcreateBrush.parameters(mapOf("extendedBlend" to 28L))
        assertEquals(BlendMode.NORMAL, unknown.blendMode)
        fun root(params: BrushParams): Map<String, Any?> {
            val bytes = ProcreateBrushWriter.brush(ProcreateBrushWriter.Brush("Unknown blend", params))
            val archiveData =
                ZipInputStream(bytes.inputStream()).use { zip ->
                    check(zip.nextEntry.name == "Brush.archive")
                    zip.readBytes()
                }
            return requireNotNull(
                com.artflow.studio.core.export.KeyedArchive(
                    com.artflow.studio.core.export.BinaryPlist.read(archiveData),
                ).root,
            )
        }
        assertEquals(28L, root(unknown)["extendedBlend"])
        assertEquals(1L, root(unknown.copy(blendMode = BlendMode.MULTIPLY))["extendedBlend"])
        // In the legacy schema, creating an extra extendedBlend would shadow the
        // unsupported source blendMode code when Procreate imports the file.
        val legacy = com.artflow.studio.core.export.ProcreateBrush.parameters(mapOf("blendMode" to 28L))
        val kept = root(legacy)
        assertEquals(28L, kept["blendMode"])
        assertTrue("Preserve the original legacy mode without fabricating an override", "extendedBlend" !in kept)
    }

    private companion object {
        const val TOLERANCE = 1e-4
    }
}
