package com.artflow.studio.data.local

import com.artflow.studio.core.render.CustomGrains
import com.artflow.studio.domain.model.brush.BrushLibraryCodec
import com.artflow.studio.domain.model.brush.BrushParams
import com.artflow.studio.domain.model.brush.DualBrush
import com.artflow.studio.domain.model.brush.ImportedPressure
import com.artflow.studio.domain.model.brush.PressureMapping
import com.artflow.studio.domain.model.brush.SavedBrush
import com.artflow.studio.domain.model.brush.SourceBrushScalar
import com.artflow.studio.domain.model.brush.SourceBrushStructuredField
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ImportedBrushPersistenceTest {
    @Test fun signedChannelsIrregularCurvesAndSecondaryAssetsRoundTrip() {
        val shape = CustomGrains.Tile(CustomGrains.TILE, ByteArray(CustomGrains.TILE * CustomGrains.TILE) { (it % 233).toByte() })
        val grain = CustomGrains.Tile(CustomGrains.TILE, ByteArray(CustomGrains.TILE * CustomGrains.TILE) { (it % 197).toByte() })
        val shapeId = CustomGrains.idFor(shape)
        val grainId = CustomGrains.idFor(grain)
        CustomGrains.register(shapeId, shape)
        CustomGrains.register(grainId, grain)
        val response =
            PressureMapping(listOf(PressureMapping.Point(0f, 0.8f), PressureMapping.Point(0.2f, 0.3f), PressureMapping.Point(1f, 0.1f)))
        val secondary =
            BrushParams(shapeId = shapeId, textureId = grainId, blendTexture = true, opacity = 0.3f, count = 16, textureScale = 0.1f)
        val params =
            BrushParams(
                pressureToSize = -0.7f,
                pressureToOpacity = -0.3f,
                velocityToSize = -0.4f,
                velocityToOpacity = -0.6f,
                importedPressure = ImportedPressure(response, response, response),
                tipFlipXJitter = true,
                tipFlipYJitter = true,
                dual = DualBrush(secondary, sourceBlendMode = 28),
            )
        val saved = listOf(SavedBrush("synthetic-dual", "Synthetic pressure brush", params))
        assertEquals(saved, BrushLibraryCodec.decode(BrushLibraryCodec.encode(saved)))
        val (decoded, images) = BrushFiles.decode(BrushFiles.encode(saved))
        assertEquals(saved, decoded)
        assertEquals(setOf(shapeId, grainId), images.keys)
        assertArrayEquals(shape.values, images.getValue(shapeId).values)
        assertArrayEquals(grain.values, images.getValue(grainId).values)
    }

    @Test fun additionalSourceCurvesAreBoundedAndNamesAreValidated() {
        val response =
            PressureMapping(listOf(PressureMapping.Point(0f, 1f), PressureMapping.Point(1f, 0f)))
        val good = BrushParams(importedAuxiliaryCurves = mapOf("dynamicsPressureBleedCurve" to response))
        val library = listOf(SavedBrush("valid-curves", "Curves", good))
        assertEquals(library, BrushLibraryCodec.decode(BrushLibraryCodec.encode(library)))
        val malformed = good.copy(importedAuxiliaryCurves = mapOf("../escapedKey" to response))
        assertThrows(IllegalArgumentException::class.java) {
            BrushLibraryCodec.encode(listOf(SavedBrush("bad-key", "Bad key", malformed)))
        }
        val tooMany = (0..30).associate { index -> "dynamicsPressureSynthetic$index" to response }
        assertThrows(IllegalArgumentException::class.java) {
            BrushLibraryCodec.encode(listOf(SavedBrush("too-many", "Too many", good.copy(importedAuxiliaryCurves = tooMany))))
        }
    }

    @Test fun sourceScalarMetadataIsValidatedAndVersionOneLibrariesRemainCompatible() {
        val source =
            BrushParams(
                importedSourceScalars =
                    mapOf(
                        "plotJitterLongitudinal" to SourceBrushScalar(SourceBrushScalar.Type.REAL, "0.125"),
                        "shapeCountRoll" to SourceBrushScalar(SourceBrushScalar.Type.BOOLEAN, "true"),
                        "burntEdgesBlendModeExtended" to SourceBrushScalar(SourceBrushScalar.Type.INTEGER, "34"),
                        "authorName" to SourceBrushScalar(SourceBrushScalar.Type.TEXT, "Synthetic artist"),
                    ),
            )
        val brush = SavedBrush("raw-settings", "Source settings", source)
        assertEquals(listOf(brush), BrushLibraryCodec.decode(BrushLibraryCodec.encode(listOf(brush))))
        for (bad in listOf(
            mapOf("../invalid" to SourceBrushScalar(SourceBrushScalar.Type.TEXT, "x")),
            mapOf("shapeCountRoll" to SourceBrushScalar(SourceBrushScalar.Type.BOOLEAN, "not-a-boolean")),
            mapOf("plotJitterLongitudinal" to SourceBrushScalar(SourceBrushScalar.Type.REAL, "NaN")),
            mapOf("authorName" to SourceBrushScalar(SourceBrushScalar.Type.TEXT, "x".repeat(257))),
        )) {
            assertThrows(IllegalArgumentException::class.java) {
                BrushLibraryCodec.encode(listOf(brush.copy(parameters = source.copy(importedSourceScalars = bad))))
            }
        }
    }

    @Test fun sourceStructureKindsAreValidatedBeforePersistence() {
        val rollShape =
            SourceBrushStructuredField(
                "ValkyrieBrushRollParameters",
                SourceBrushStructuredField.Kind.FIELDS,
                fields = mapOf("activationOrigin" to SourceBrushScalar(SourceBrushScalar.Type.REAL, "1.0")),
            )
        val invalid = BrushParams(importedSourceStructures = mapOf("savedPaintSizes" to rollShape))
        assertThrows(IllegalArgumentException::class.java) {
            BrushLibraryCodec.encode(listOf(SavedBrush("invalid-kind", "Malformed", invalid)))
        }
        val valid =
            BrushParams(
                importedSourceStructures =
                    mapOf(
                        "savedPaintSizes" to
                            SourceBrushStructuredField("NSArray", SourceBrushStructuredField.Kind.ARRAY, items = emptyList()),
                    ),
            )
        val original = listOf(SavedBrush("original", "Source array", valid))
        assertEquals(original, BrushLibraryCodec.decode(BrushLibraryCodec.encode(original)))
    }

    @Test fun oldLibrariesWithoutNewFieldsStillDecodeWithTheirOriginalDefaults() {
        val legacy = """{"version":1,"brushes":[{"id":"old","name":"Old brush","parameters":{"pressureToSize":0.8}}]}"""
        assertEquals(BrushParams(pressureToSize = 0.8f), BrushLibraryCodec.decode(legacy).single().parameters)
    }

    @Test fun outOfRangeSignedDynamicsStillFailClosed() {
        for (value in listOf(-1.01f, 1.01f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertThrows(IllegalArgumentException::class.java) {
                BrushLibraryCodec.encode(listOf(SavedBrush("invalid", "Invalid", BrushParams(velocityToOpacity = value))))
            }
        }
    }
}
