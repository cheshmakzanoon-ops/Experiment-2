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
        val original = document(oldBrush)
        // Recreate the historical wire shape independently of the new fields' serialization rules.
        // Both primary and compound components omit wetAttack and the nullable flow graph.
        val historical = historicalFields(json.encodeToJsonElement(CanvasDocument.serializer(), original)).jsonObject
        val unsigned = historical.toString()
        assertFalse("\"wetAttack\"" in unsigned)
        assertFalse("\"flow\":null" in unsigned)
        assertFalse("\"sourceImportVersion\"" in unsigned)
        val checksum =
            MessageDigest.getInstance("SHA-256").digest(unsigned.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
        val signed = JsonObject(historical + ("checksum" to JsonPrimitive(checksum))).toString()
        assertEquals(original, CanvasDocumentCodec.decode(signed))
        assertEquals(signed, CanvasDocumentCodec.encode(original).decodeToString())
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

    private fun historicalFields(value: JsonElement): JsonElement =
        when (value) {
            is JsonObject ->
                JsonObject(
                    value
                        .filterNot { (key, field) ->
                            (key == "wetAttack" && field == JsonPrimitive(1f)) ||
                                (key == "flow" && field == JsonNull) ||
                                (key == "sourceImportVersion" && field == JsonPrimitive(0))
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
