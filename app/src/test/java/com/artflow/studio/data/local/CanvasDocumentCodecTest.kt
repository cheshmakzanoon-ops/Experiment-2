package com.artflow.studio.data.local

import com.artflow.studio.core.pixels.SelectionMaskCodec
import com.artflow.studio.core.text.TextLayout
import com.artflow.studio.domain.model.animation.AnimationFrame
import com.artflow.studio.domain.model.layer.AdjustmentType
import com.artflow.studio.domain.model.layer.BlendMode
import com.artflow.studio.domain.model.layer.FilterType
import com.artflow.studio.domain.model.layer.Layer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every field of a `.artflow` document must come back unchanged after encode and decode, because
 * the editor reopens the manifest rather than the in-memory state it was saved from.
 */
class CanvasDocumentCodecTest {
    private fun roundTrip(document: CanvasDocument): Pair<ByteArray, CanvasDocument> {
        val bytes = CanvasDocumentCodec.encode(document)
        return bytes to CanvasDocumentCodec.decode(bytes.decodeToString())
    }

    @Test
    fun everyLayerKindAndMetadataFieldComesBackEqual() {
        val document = CanvasDocumentFixtures.richDocument()
        val (bytes, decoded) = roundTrip(document)

        assertEquals(document, decoded)
        // Re-encoding the decoded document must reproduce the same bytes, so saves are stable.
        assertArrayEquals(bytes, CanvasDocumentCodec.encode(decoded))
    }

    @Test
    fun allBlendModesSurviveIncludingPassThrough() {
        val decoded = roundTrip(CanvasDocumentFixtures.richDocument()).second
        val modes = decoded.layers.filter { it.id >= 100L }.map { it.blendMode }

        assertEquals(BlendMode.entries.toList(), modes)
        assertEquals(27, BlendMode.entries.size)
        assertEquals(BlendMode.PASS_THROUGH, decoded.layers.single { it.id == 9L }.blendMode)
    }

    @Test
    fun layerFlagsAndKindFieldsSurvive() {
        val layers = roundTrip(CanvasDocumentFixtures.richDocument()).second.layers.associateBy { it.id }

        val mask = layers.getValue(3L)
        assertEquals(CanvasDocumentFixtures.MASK_PATH, mask.maskFile)
        assertEquals(2L, mask.maskLayerId)
        assertFalse(mask.maskEnabled)
        assertTrue(mask.maskInverted)
        assertEquals(0.5f, mask.maskDensity)
        assertEquals(3.25f, mask.maskFeather)
        assertTrue(mask.isLocked)

        val child = layers.getValue(8L)
        assertEquals(7L, child.parentGroupId)
        assertEquals(42L, child.linkGroupId)
        assertFalse(child.isVisible)
        assertTrue(child.isPrivate)
        assertTrue(child.isInternal)
        assertTrue(child.drawingAssist)

        val reference = layers.getValue(9L)
        assertTrue(reference.isReference)
        assertTrue(reference.isFillReference)

        assertEquals(AdjustmentType.HUE_SATURATION, layers.getValue(5L).adjustmentType)
        val expectedParameters = AdjustmentType.HUE_SATURATION.defaultParameters.mapValues { it.value + 0.25f }
        assertEquals(expectedParameters, layers.getValue(5L).adjustmentParameters)
        assertEquals(FilterType.TILT_SHIFT, layers.getValue(6L).filterType)
        assertEquals(0.35f, layers.getValue(6L).filterAmount)

        assertTrue(layers.getValue(7L).isGroup)
        assertTrue(layers.getValue(10L).isGroup)
        assertEquals(10L, layers.getValue(11L).parentGroupId)
    }

    @Test
    fun strokesTextAndOpacityKeepEveryField() {
        val decoded = roundTrip(CanvasDocumentFixtures.richDocument()).second
        val layers = decoded.layers.associateBy { it.id }

        val strokes = layers.getValue(2L).strokes
        assertEquals(2, strokes.size)
        assertTrue(strokes[1].isEraser)
        assertEquals(0xFF445566.toInt(), strokes[0].secondaryColor)
        assertEquals(0.5f, strokes[0].points[0].pressure)
        assertEquals(-0.25f, strokes[0].points[0].tiltX)
        assertEquals(0.75f, strokes[0].points[0].tiltY)
        assertEquals(1.125f, strokes[0].points[0].azimuth)
        assertEquals(0x80FF8800.toInt(), strokes[0].points[0].color)
        assertEquals(12.5f, strokes[0].brushParams.size)
        assertEquals(45f, strokes[0].brushParams.rotation)

        assertEquals(0.123456789f, layers.getValue(2L).opacity)
        assertEquals("Éclair 絵", layers.getValue(2L).name)
        val expectedStyle = TextLayout.TextStyle(fontFamily = "serif", fontSize = 48f, bold = true, letterSpacing = 1.5f)
        assertEquals(expectedStyle, layers.getValue(4L).textContent?.style)
        assertEquals("Hello, \"world\"\né", layers.getValue(4L).textContent?.text)
        assertEquals(null, layers.getValue(4L).rasterFile)
    }

    @Test
    fun framesDurationsAndPlaybackSettingsSurvive() {
        val decoded = roundTrip(CanvasDocumentFixtures.richDocument()).second

        assertEquals(listOf(100, 250, AnimationFrame.MAX_DURATION_MS), decoded.resolvedFrames().map { it.durationMs })
        assertEquals(listOf(1L, 2L, 3L), decoded.frames.map { it.id })
        assertEquals(true, decoded.frames[1].isKeyframe)
        assertEquals(1_234L, decoded.frames[1].createdAt)
        assertEquals(1, decoded.resolvedActiveFrameIndex())
        assertEquals(24, decoded.animation.fps)
        assertFalse(decoded.animation.loop)
        assertTrue(decoded.animation.onionSkinEnabled)
        assertEquals(3, decoded.animation.onionSkinFrames)
        assertEquals(0.45f, decoded.animation.onionSkinOpacity)
        assertEquals(0xFF123456.toInt(), decoded.animation.onionSkinPreviousColor)
        assertEquals(0xFF654321.toInt(), decoded.animation.onionSkinNextColor)
        assertEquals(1, decoded.animation.playbackRangeStart)
        assertEquals(2, decoded.animation.playbackRangeEnd)
        assertTrue(decoded.animation.pingPong)
        assertTrue(decoded.animation.backgroundFrame)
        assertTrue(decoded.animation.foregroundFrame)
    }

    @Test
    fun canvasMetadataTimelapseAndProfileSurvive() {
        val decoded = roundTrip(CanvasDocumentFixtures.richDocument()).second

        assertEquals(CanvasDocument.CURRENT_VERSION, decoded.version)
        assertEquals(640, decoded.width)
        assertEquals(480, decoded.height)
        assertEquals(300, decoded.dpi)
        assertEquals("DISPLAY_P3", decoded.colorProfile)
        assertEquals(98_765L, decoded.trackedMs)
        assertEquals(0xFFF8F1E3.toInt(), decoded.backgroundColor)
        assertEquals(9L, decoded.activeLayerId)
        assertEquals(301L, decoded.nextLayerId)
        assertEquals(1_700_000_123_456L, decoded.savedAt)
        assertEquals(1_700_000_000_000L, decoded.timelapse.startedAt)
        assertEquals(2_500L, decoded.timelapse.durationMs)
        assertEquals(3.5f, decoded.timelapse.speedMultiplier)
        assertEquals(0.25f, decoded.timelapse.resolutionScale)
        assertTrue(decoded.timelapse.includeWatermark)
        assertEquals("Made with ArtFlow ✓", decoded.timelapse.watermarkText)
        assertEquals("Fill", decoded.timelapse.entries[1].label)
        assertEquals(9_000, decoded.timelapse.entries[1].affectedPixels)
    }

    @Test
    fun inMemoryMaskPresenceIsNotPersisted() {
        val layer = Layer(id = 1L, name = "L", index = 0, hasInMemoryMask = true)
        val document =
            CanvasDocument(
                width = 8,
                height = 8,
                dpi = 72,
                backgroundColor = -1,
                activeLayerId = 1L,
                nextLayerId = 2L,
                layers = listOf(layer),
            )
        val (bytes, decoded) = roundTrip(document)

        assertFalse(bytes.decodeToString().contains("hasInMemoryMask"))
        assertFalse(decoded.layers.single().hasInMemoryMask)
    }

    @Test
    fun fieldsFromANewerWriterAreIgnoredAtEveryLevel() {
        val document = CanvasDocumentFixtures.richDocument()
        val text = CanvasDocumentCodec.encode(document).decodeToString()
        val withFutureFields =
            text
                .replaceFirst("{", "{\"futureTop\":[1,\"two\",{\"three\":3.5}],")
                .replace("\"name\":\"Background\"", "\"futureLayer\":{\"a\":[null,true]},\"name\":\"Background\"")
                .replace("\"animation\":{", "\"animation\":{\"futureAnimation\":1,")
                .replace("\"name\":\"Frame 2\"", "\"futureFrame\":\"x\",\"name\":\"Frame 2\"")
        // The edits above must have applied, or the test would prove nothing.
        assertTrue(withFutureFields.contains("futureLayer") && withFutureFields.contains("futureAnimation"))
        assertTrue(withFutureFields.contains("futureFrame") && withFutureFields.contains("futureTop"))

        val decoded = CanvasDocumentCodec.decode(withFutureFields)

        assertEquals(document, decoded)
    }

    @Test
    fun version1DocumentWithoutFramesLoadsAsFrameOne() {
        val legacy =
            """
            {"version":1,"width":8,"height":6,"dpi":72,"backgroundColor":-1,"activeLayerId":2,"nextLayerId":3,
             "layers":[
               {"id":1,"name":"Background","index":0},
               {"id":2,"name":"Sketch","index":1,"isVisible":false,"opacity":0.5,"blendMode":"MULTIPLY",
                "strokes":[{"id":5,"points":[{"x":1.0,"y":2.0}],"brushParams":{},"layerId":2,"color":-16777216,"timestamp":10}]}
             ]}
            """.trimIndent()

        val document = CanvasDocumentCodec.decode(legacy)
        CanvasDocumentCodec.requireValidHeader(document)
        CanvasDocumentCodec.requireValidStructure(document)

        assertEquals(1, document.version)
        assertTrue(document.frames.isEmpty())
        val frames = document.resolvedFrames()
        assertEquals(1, frames.size)
        assertEquals(1L, frames[0].id)
        assertEquals(listOf(1L, 2L), frames[0].layers.map { it.id })
        assertEquals(document.animation.frameDurationMs, frames[0].durationMs)
        assertEquals(0, document.resolvedActiveFrameIndex())
        val sketch = frames[0].layers[1]
        assertFalse(sketch.isVisible)
        assertEquals(0.5f, sketch.opacity)
        assertEquals(BlendMode.MULTIPLY, sketch.blendMode)
        assertEquals(null, sketch.rasterFile)
        assertEquals("SRGB", document.colorProfile)
        val stroke = sketch.strokes.single()
        val point = stroke.points.single()
        assertEquals(1f, point.pressure)
        assertEquals(0, document.timelapse.entries.size)
    }

    @Test
    fun savedSelectionSlotsSurviveWithTheirCoverage() {
        val decoded = roundTrip(CanvasDocumentFixtures.richDocument()).second

        assertEquals(2, decoded.savedSelections.size)
        val full = requireNotNull(SelectionMaskCodec.decode(decoded.savedSelections[0], 640, 480))
        assertTrue(full.isFull())
        val half = requireNotNull(SelectionMaskCodec.decode(decoded.savedSelections[1], 640, 480))
        assertEquals(255, half.coverageAt(319, 479))
        assertEquals(0, half.coverageAt(320, 0))
    }

    /** Documents saved before selections lived with the artwork must keep loading. */
    @Test
    fun aDocumentWithoutSavedSelectionsStillLoads() {
        val text = CanvasDocumentCodec.encode(CanvasDocumentFixtures.richDocument()).decodeToString()
        val root = Json.parseToJsonElement(text).jsonObject
        val legacy = JsonObject(root - "savedSelections" + ("checksum" to JsonNull))

        val document = CanvasDocumentCodec.decode(legacy.toString())

        assertEquals(0, document.savedSelections.size)
        assertEquals(CanvasDocumentFixtures.richDocument().layers.size, document.layers.size)
    }
}
