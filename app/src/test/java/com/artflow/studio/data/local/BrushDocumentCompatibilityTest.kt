package com.artflow.studio.data.local

import com.artflow.studio.domain.model.brush.BrushParams
import com.artflow.studio.domain.model.brush.DualBrush
import com.artflow.studio.domain.model.brush.ImportedPressure
import com.artflow.studio.domain.model.brush.PressureMapping
import com.artflow.studio.domain.model.brush.Stroke
import com.artflow.studio.domain.model.brush.StrokePoint
import com.artflow.studio.domain.model.layer.Layer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

/** Adding a brush control must not make existing checksummed vector artwork unreadable. */
class BrushDocumentCompatibilityTest {
    private val json = Json { encodeDefaults = true }
    private val curve = PressureMapping(listOf(PressureMapping.Point(0f, 0.25f), PressureMapping.Point(1f, 0.75f)))
    private val oldBrush =
        BrushParams(
            importedPressure = ImportedPressure(overall = curve, opacity = curve),
            dual = DualBrush(BrushParams(importedPressure = ImportedPressure(size = curve))),
        )

    @Test
    fun aPreviouslySignedVectorDocumentWithoutNewBrushFieldsStillVerifies() {
        for (sourceVersion in listOf(0, 1)) {
            val secondary = requireNotNull(oldBrush.dual).params.copy(sourceImportVersion = sourceVersion)
            val params =
                oldBrush.copy(
                    sourceImportVersion = sourceVersion,
                    importedAuxiliaryCurves = mapOf("dynamicsPressureBleedCurve" to curve),
                    dual = DualBrush(secondary),
                )
            val original = document(params)
            // Recreate the historical wire shape independently of the new fields' serialization rules.
            // Both importer versions and compound components lack an interpolation marker.
            val historical = historicalFields(json.encodeToJsonElement(CanvasDocument.serializer(), original)).jsonObject
            val unsigned = historical.toString()
            assertFalse("\"wetAttack\"" in unsigned)
            assertFalse("\"flow\":null" in unsigned)
            assertFalse("\"interpolation\"" in unsigned)
            assertEquals(sourceVersion != 0, "\"sourceImportVersion\":1" in unsigned)
            val checksum =
                MessageDigest.getInstance("SHA-256").digest(unsigned.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
            val signed = JsonObject(historical + ("checksum" to JsonPrimitive(checksum))).toString()
            val decoded = CanvasDocumentCodec.decode(signed)
            assertEquals(original, decoded)
            assertEquals(signed, CanvasDocumentCodec.encode(decoded).decodeToString())
            val reopened =
                decoded.layers
                    .single()
                    .strokes
                    .single()
                    .brushParams
            val mappings =
                listOfNotNull(
                    reopened.importedPressure?.overall,
                    reopened.importedPressure?.opacity,
                    reopened.importedAuxiliaryCurves["dynamicsPressureBleedCurve"],
                    reopened.dual
                        ?.params
                        ?.importedPressure
                        ?.size,
                )
            assertEquals(4, mappings.size)
            mappings.forEach { mapping ->
                assertEquals(PressureMapping.Interpolation.LINEAR, mapping.interpolation)
                assertEquals(0.375f, mapping.map(0.25f), 0f)
            }
        }
    }

    @Test
    fun nondefaultAttackAndFlowGraphRemainPersistedAndProtectedByTheChecksum() {
        val params =
            oldBrush.copy(
                wetAttack = 0.25f,
                importedPressure = oldBrush.importedPressure!!.copy(flow = curve),
                sourceImportVersion = 1,
            )
        val original = document(params)
        val encoded = CanvasDocumentCodec.encode(original).decodeToString()
        assertTrue("\"wetAttack\":0.25" in encoded)
        assertTrue("\"flow\":{\"points\":" in encoded)
        assertTrue("\"sourceImportVersion\":1" in encoded)
        assertEquals(original, CanvasDocumentCodec.decode(encoded))
        val altered = encoded.replaceFirst("\"wetAttack\":0.25", "\"wetAttack\":0.5")
        assertThrows(IllegalArgumentException::class.java) { CanvasDocumentCodec.decode(altered) }
    }

    @Test
    fun nativeInterpolationPersistsInPrimaryAuxiliaryAndDualGraphsAndIsChecksummed() {
        val native = curve.copy(interpolation = PressureMapping.Interpolation.NATIVE_SPLINE_1024)
        val params =
            oldBrush.copy(
                sourceImportVersion = 1,
                importedPressure = ImportedPressure(overall = native, opacity = native, flow = native),
                importedAuxiliaryCurves = mapOf("dynamicsPressureBleedCurve" to native),
                dual = DualBrush(BrushParams(sourceImportVersion = 1, importedPressure = ImportedPressure(size = native))),
            )
        val original = document(params)
        val encoded = CanvasDocumentCodec.encode(original).decodeToString()
        val marker = "\"interpolation\":\"NATIVE_SPLINE_1024\""
        assertTrue(marker in encoded)
        assertEquals(original, CanvasDocumentCodec.decode(encoded))

        val altered = encoded.replaceFirst(marker, "\"interpolation\":\"LINEAR\"")
        val failure = assertThrows(IllegalArgumentException::class.java) { CanvasDocumentCodec.decode(altered) }
        assertTrue("An algorithm edit must invalidate the checksum", "checksum" in failure.message.orEmpty())
    }

    @Test
    fun anUnknownPressureInterpolationCannotSilentlyBecomeLinear() {
        val native = curve.copy(interpolation = PressureMapping.Interpolation.NATIVE_SPLINE_1024)
        val original = document(oldBrush.copy(importedPressure = ImportedPressure(overall = native)))
        // No checksum: rejection must come from the unsupported algorithm, not a hash mismatch.
        val encoded = json.encodeToString(CanvasDocument.serializer(), original)
        assertTrue("NATIVE_SPLINE_1024" in encoded)
        val unknown = encoded.replace("NATIVE_SPLINE_1024", "UNSUPPORTED_FUTURE_CURVE")
        assertThrows(IllegalArgumentException::class.java) { CanvasDocumentCodec.decode(unknown) }
    }

    private fun historicalFields(value: JsonElement): JsonElement =
        when (value) {
            is JsonObject ->
                JsonObject(
                    value
                        .filterNot { (key, field) ->
                            (key == "wetAttack" && field == JsonPrimitive(1f)) ||
                                (key == "flow" && field == JsonNull) ||
                                (key == "sourceImportVersion" && field == JsonPrimitive(0)) ||
                                (key == "interpolation" && field == JsonPrimitive("LINEAR"))
                        }.mapValues { (_, field) -> historicalFields(field) },
                )
            is JsonArray -> JsonArray(value.map(::historicalFields))
            else -> value
        }

    private fun document(params: BrushParams): CanvasDocument {
        val stroke =
            Stroke(
                id = 1,
                points = listOf(StrokePoint(2f, 3f, pressure = 0.4f, timestamp = 100)),
                brushParams = params,
                layerId = 1,
                color = 0xFF335577.toInt(),
                timestamp = 100,
            )
        return CanvasDocument(
            width = 8,
            height = 6,
            dpi = 72,
            backgroundColor = 0,
            activeLayerId = 1,
            nextLayerId = 2,
            layers = listOf(Layer(1, "Vector ink", 0, strokes = listOf(stroke))),
            savedAt = 200,
        )
    }
}
