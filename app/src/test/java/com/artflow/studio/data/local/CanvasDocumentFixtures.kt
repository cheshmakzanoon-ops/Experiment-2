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

/** Representative documents for the codec tests: every layer kind, frame and metadata field is populated. */
internal object CanvasDocumentFixtures {
    internal const val PIXEL_PATH = "layers/1/v1-0123456789abcdef.png"
    internal const val MASK_PATH = "layers/3/mask1-fedcba9876543210.png"

    internal fun stroke(
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
    internal fun firstFrameLayers(): List<Layer> {
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
                rasterFile = PIXEL_PATH,
                opacity = 0.123456789f,
                isAlphaLocked = true,
                isClippingMask = true,
            )
        val mask =
            Layer(
                id = 3L,
                name = "Masked",
                index = 2,
                rasterFile = PIXEL_PATH,
                maskFile = MASK_PATH,
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
        val background = Layer(id = 1L, name = "Background", index = 0, rasterFile = PIXEL_PATH)
        return listOf(background, pixel, mask, text, adjustment, filter, group, child, nestedGroup, nestedChild, reference) + blendLayers
    }

    internal fun richDocument(): CanvasDocument {
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
}
