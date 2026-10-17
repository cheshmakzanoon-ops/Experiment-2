package com.artflow.studio.data.local

import com.artflow.studio.core.text.TextLayerContent
import com.artflow.studio.core.text.TextLayout
import com.artflow.studio.domain.model.animation.AnimationFrame
import com.artflow.studio.domain.model.animation.AnimationSettings
import com.artflow.studio.domain.model.animation.TimelapseEntry
import com.artflow.studio.domain.model.animation.TimelapseRecording
import com.artflow.studio.domain.model.brush.BrushParams
import com.artflow.studio.domain.model.brush.Stroke
import com.artflow.studio.domain.model.brush.StrokePoint
import com.artflow.studio.domain.model.layer.AdjustmentType
import com.artflow.studio.domain.model.layer.BlendMode
import com.artflow.studio.domain.model.layer.FilterType
import com.artflow.studio.domain.model.layer.Layer
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
    private val pixelPath = "layers/1/v1-0123456789abcdef.png"
    private val maskPath = "layers/3/mask1-fedcba9876543210.png"

    private fun stroke(
        id: Long,
        layerId: Long,
        eraser: Boolean,
    ) = Stroke(
        id = id,
        points =
            listOf(
                StrokePoint(
                    x = 1.5f,
                    y = 2.25f,
                    pressure = 0.5f,
                    tiltX = -0.25f,
                    tiltY = 0.75f,
                    azimuth = 1.125f,
                    timestamp = 1_000L,
                    color = 0x80FF8800.toInt(),
                ),
                StrokePoint(x = 9.0f, y = 4.75f, pressure = 1f, timestamp = 1_020L, color = 0xFF000000.toInt()),
            ),
        brushParams = BrushParams(size = 12.5f, opacity = 0.75f, spacing = 0.2f, scatter = 0.1f, count = 3, rotation = 45f),
        layerId = layerId,
        color = 0xFF112233.toInt(),
        timestamp = 1_000L,
        isEraser = eraser,
        secondaryColor = 0xFF445566.toInt(),
    )

    /** One layer per kind, plus flags that the storage layer must keep. */
    private fun firstFrameLayers(): List<Layer> {
        val blendLayers =
            BlendMode.entries.mapIndexed { index, mode ->
                Layer(id = 100L + index, name = "Blend ${mode.name}", index = 10 + index, blendMode = mode, opacity = (index + 1) / 32f)
            }
        val pixel =
            Layer(
                id = 2L,
                name = "Éclair 絵",
                index = 1,
                strokes = listOf(stroke(id = 7L, layerId = 2L, eraser = false), stroke(id = 8L, layerId = 2L, eraser = true)),
                rasterFile = pixelPath,
                opacity = 0.123456789f,
                isAlphaLocked = true,
                isClippingMask = true,
            )
        val mask =
            Layer(
                id = 3L,
                name = "Masked",
                index = 2,
                rasterFile = pixelPath,
                maskFile = maskPath,
                maskLayerId = 2L,
                maskEnabled = false,
                maskInverted = true,
                maskDensity = 0.5f,
                maskFeather = 3.25f,
                isLocked = true,
            )
        val text =
            Layer(
                id = 4L,
                name = "Title",
                index = 3,
                textContent =
                    TextLayerContent(
                        text = "Hello, \"world\"\né",
                        style = TextLayout.TextStyle(fontFamily = "serif", fontSize = 48f, bold = true, letterSpacing = 1.5f),
                        color = 0xFF00AA55.toInt(),
                        x = 10.5f,
                        y = 20.25f,
                    ),
            )
        val adjustment =
            Layer(
                id = 5L,
                name = "Hue",
                index = 4,
                adjustmentType = AdjustmentType.HUE_SATURATION,
                adjustmentParameters = AdjustmentType.HUE_SATURATION.defaultParameters.mapValues { it.value + 0.25f },
            )
        val filter = Layer(id = 6L, name = "Tilt", index = 5, filterType = FilterType.TILT_SHIFT, filterAmount = 0.35f)
        val group = Layer(id = 7L, name = "Group", index = 6, isGroup = true, isVisible = false)
        val child =
            Layer(
                id = 8L,
                name = "Child",
                index = 7,
                parentGroupId = 7L,
                linkGroupId = 42L,
                isVisible = false,
                isPrivate = true,
                isInternal = true,
                drawingAssist = true,
            )
        val nestedGroup = Layer(id = 10L, name = "Inner", index = 8, parentGroupId = 7L, isGroup = true)
        val nestedChild = Layer(id = 11L, name = "Deep", index = 9, parentGroupId = 10L, opacity = 0.5f)
        val reference =
            Layer(
                id = 9L,
                name = "Reference",
                index = 11,
                isReference = true,
                isFillReference = true,
                blendMode = BlendMode.PASS_THROUGH,
            )
        val background = Layer(id = 1L, name = "Background", index = 0, rasterFile = pixelPath)
        return listOf(background, pixel, mask, text, adjustment, filter, group, child, nestedGroup, nestedChild, reference) + blendLayers
    }

    private fun richDocument(): CanvasDocument {
        val first = firstFrameLayers()
        val second =
            listOf(
                Layer(id = 200L, name = "Second frame", index = 0, rasterFile = "layers/200/v1-aaaaaaaaaaaaaaaa.png"),
                Layer(id = 201L, name = "Second line", index = 1, strokes = listOf(stroke(id = 9L, layerId = 201L, eraser = false))),
            )
        val third = listOf(Layer(id = 300L, name = "Third", index = 0, opacity = 0f))
        return CanvasDocument(
            version = CanvasDocument.CURRENT_VERSION,
            width = 640,
            height = 480,
            dpi = 300,
            colorProfile = "DISPLAY_P3",
            trackedMs = 98_765L,
            backgroundColor = 0xFFF8F1E3.toInt(),
            activeLayerId = 9L,
            nextLayerId = 301L,
            layers = first,
            frames =
                listOf(
                    AnimationFrame(id = 1L, name = "Frame 1", layers = first, durationMs = 100),
                    AnimationFrame(
                        id = 2L,
                        name = "Frame 2",
                        layers = second,
                        durationMs = 250,
                        isKeyframe = true,
                        createdAt = 1_234L,
                    ),
                    AnimationFrame(id = 3L, name = "Frame 3", layers = third, durationMs = AnimationFrame.MAX_DURATION_MS),
                ),
            activeFrameIndex = 1,
            animation =
                AnimationSettings(
                    fps = 24,
                    loop = false,
                    onionSkinEnabled = true,
                    onionSkinFrames = 3,
                    onionSkinOpacity = 0.45f,
                    onionSkinPreviousColor = 0xFF123456.toInt(),
                    onionSkinNextColor = 0xFF654321.toInt(),
                    playbackRangeStart = 1,
                    playbackRangeEnd = 2,
                    pingPong = true,
                    backgroundFrame = true,
                    foregroundFrame = true,
                ),
            timelapse =
                TimelapseRecording(
                    isRecording = false,
                    startedAt = 1_700_000_000_000L,
                    entries =
                        listOf(
                            TimelapseEntry(timestampMs = 10L, frameIndex = 0, label = "Brush stroke", affectedPixels = 42),
                            TimelapseEntry(timestampMs = 2_500L, frameIndex = 1, label = "Fill", affectedPixels = 9_000),
                        ),
                    speedMultiplier = 3.5f,
                    resolutionScale = 0.25f,
                    includeWatermark = true,
                    watermarkText = "Made with ArtFlow ✓",
                ),
            savedAt = 1_700_000_123_456L,
        )
    }

    private fun roundTrip(document: CanvasDocument): Pair<ByteArray, CanvasDocument> {
        val bytes = CanvasDocumentCodec.encode(document)
        return bytes to CanvasDocumentCodec.decode(bytes.decodeToString())
    }

    @Test
    fun everyLayerKindAndMetadataFieldComesBackEqual() {
        val document = richDocument()
        val (bytes, decoded) = roundTrip(document)

        assertEquals(document, decoded)
        // Re-encoding the decoded document must reproduce the same bytes, so saves are stable.
        assertArrayEquals(bytes, CanvasDocumentCodec.encode(decoded))
    }

    @Test
    fun allBlendModesSurviveIncludingPassThrough() {
        val decoded = roundTrip(richDocument()).second
        val modes = decoded.layers.filter { it.id >= 100L }.map { it.blendMode }

        assertEquals(BlendMode.entries.toList(), modes)
        assertEquals(27, BlendMode.entries.size)
        assertEquals(BlendMode.PASS_THROUGH, decoded.layers.single { it.id == 9L }.blendMode)
    }

    @Test
    fun layerFlagsAndKindFieldsSurvive() {
        val layers = roundTrip(richDocument()).second.layers.associateBy { it.id }

        val mask = layers.getValue(3L)
        assertEquals(maskPath, mask.maskFile)
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
        val decoded = roundTrip(richDocument()).second
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
        val decoded = roundTrip(richDocument()).second

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
        val decoded = roundTrip(richDocument()).second

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
        val document = richDocument()
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
}
