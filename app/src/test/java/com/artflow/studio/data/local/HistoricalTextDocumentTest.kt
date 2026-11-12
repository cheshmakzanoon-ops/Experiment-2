package com.artflow.studio.data.local

import com.artflow.studio.core.text.TextLayerContent
import com.artflow.studio.core.text.TextLayout
import com.artflow.studio.domain.model.layer.Layer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoricalTextDocumentTest {
    @Test
    fun art001_historicalTextDocumentLoadsAndPreservesTextProperties() {
        val resourceStream =
            javaClass.classLoader?.getResourceAsStream("documents/v4-editable-text.json")
                ?: error("Missing test fixture documents/v4-editable-text.json")
        val jsonText = resourceStream.bufferedReader().use { it.readText() }

        // Must not contain new v7 fields in historical JSON text
        assertFalse(jsonText.contains("rotationDegrees"))
        assertFalse(jsonText.contains("scaleX"))
        assertFalse(jsonText.contains("baselineShift"))

        val decoded = CanvasDocumentCodec.decode(jsonText)
        CanvasDocumentCodec.requireValidHeader(decoded)
        CanvasDocumentCodec.requireValidStructure(decoded)

        assertEquals(CanvasDocument.CURRENT_VERSION, decoded.version)
        assertEquals(1, decoded.layers.size)

        val layer = decoded.layers.single()
        val text = requireNotNull(layer.textContent)
        assertEquals("Historical Typography", text.text)
        assertEquals(24f, text.style.fontSize)
        assertEquals(10f, text.x)
        assertEquals(20f, text.y)
        assertEquals(0f, text.rotationDegrees)
        assertEquals(1f, text.scaleX)
        assertEquals(1f, text.scaleY)
        assertEquals(0f, text.baselineShift)
        assertEquals(0f, text.kerning)
        assertEquals(0f, text.tracking)
        assertFalse(text.hasTransform())
    }

    @Test
    fun art002_editingVectorTransformOnMigratedTextSavesWithVersion7AndSurvivesRoundTrip() {
        val textLayer =
            Layer(
                id = 1L,
                name = "Text",
                index = 0,
                textContent =
                    TextLayerContent(
                        text = "Modern Typography",
                        style = TextLayout.TextStyle(fontFamily = "sans-serif", fontSize = 32f),
                        color = 0xFF223344.toInt(),
                        x = 15f,
                        y = 25f,
                        rotationDegrees = 45f,
                        scaleX = 1.5f,
                        scaleY = 0.8f,
                        baselineShift = 2f,
                        kerning = 0.5f,
                        tracking = 1.2f,
                    ),
            )
        val document =
            CanvasDocument(
                width = 128,
                height = 128,
                dpi = 72,
                backgroundColor = 0,
                activeLayerId = 1L,
                nextLayerId = 2L,
                layers = listOf(textLayer),
            )

        assertTrue(textLayer.textContent!!.hasTransform())

        val encoded = CanvasDocumentCodec.encode(document)
        val encodedString = encoded.decodeToString()

        assertTrue(encodedString.contains("\"version\":7"))
        assertTrue(encodedString.contains("\"rotationDegrees\":45.0"))
        assertTrue(encodedString.contains("\"scaleX\":1.5"))
        assertTrue(encodedString.contains("\"scaleY\":0.8"))
        assertTrue(encodedString.contains("\"baselineShift\":2.0"))
        assertTrue(encodedString.contains("\"kerning\":0.5"))
        assertTrue(encodedString.contains("\"tracking\":1.2"))

        val decoded = CanvasDocumentCodec.decode(encodedString)
        CanvasDocumentCodec.requireValidHeader(decoded)
        CanvasDocumentCodec.requireValidStructure(decoded)

        assertEquals(document, decoded)
    }

    @Test
    fun art003_tamperRejectionAndOldVersionTransformRejection() {
        val resourceStream =
            javaClass.classLoader?.getResourceAsStream("documents/v4-editable-text.json")
                ?: error("Missing test fixture documents/v4-editable-text.json")
        val jsonText = resourceStream.bufferedReader().use { it.readText() }

        // 1. Tamper with the text string
        val tamperedText = jsonText.replace("Historical Typography", "Tampered Typography")
        val tamperEx =
            assertThrows(IllegalArgumentException::class.java) {
                CanvasDocumentCodec.decode(tamperedText)
            }
        assertTrue(tamperEx.message.orEmpty().contains("checksum"))

        // 2. Reject non-default vector text transform in older document versions (< 7)
        for (version in 1..6) {
            val transformedLayer =
                Layer(
                    id = 1L,
                    name = "Transformed",
                    index = 0,
                    textContent =
                        TextLayerContent(
                            text = "Invalid In Old Version",
                            style = TextLayout.TextStyle(),
                            color = 0xFF000000.toInt(),
                            x = 0f,
                            y = 0f,
                            rotationDegrees = 30f,
                        ),
                )
            val oldDoc =
                CanvasDocument(
                    version = version,
                    width = 64,
                    height = 64,
                    dpi = 72,
                    backgroundColor = 0,
                    activeLayerId = 1L,
                    nextLayerId = 2L,
                    layers = listOf(transformedLayer),
                )

            // CanvasDocumentCodec.encode should reject this
            val encEx =
                assertThrows(IllegalArgumentException::class.java) {
                    CanvasDocumentCodec.encode(oldDoc)
                }
            assertTrue(encEx.message.orEmpty().contains("Vector text transforms require project version 7"))

            // CanvasDocumentCodec.decode of unsigned or forged old doc should also reject it
            val rawJson = Json { encodeDefaults = true }.encodeToString(CanvasDocument.serializer(), oldDoc)
            val decEx =
                assertThrows(IllegalArgumentException::class.java) {
                    CanvasDocumentCodec.decode(rawJson)
                }
            assertTrue(decEx.message.orEmpty().contains("Vector text transforms require project version 7"))
        }
    }
}
