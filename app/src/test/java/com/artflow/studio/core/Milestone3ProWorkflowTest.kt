package com.artflow.studio.core

import com.artflow.studio.core.canvas.KeyChord
import com.artflow.studio.core.canvas.KeyboardShortcuts
import com.artflow.studio.core.canvas.SeamlessTileMode
import com.artflow.studio.core.canvas.SeamlessTiling
import com.artflow.studio.core.canvas.ShortcutAction
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.core.render.StrokeRasterizer
import com.artflow.studio.core.text.TextLayerContent
import com.artflow.studio.core.text.TextLayout
import com.artflow.studio.data.local.CanvasDocument
import com.artflow.studio.data.local.CanvasDocumentCodec
import com.artflow.studio.domain.model.brush.BrushParams
import com.artflow.studio.domain.model.brush.Stroke
import com.artflow.studio.domain.model.brush.StrokePoint
import com.artflow.studio.domain.model.layer.Layer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Milestone3ProWorkflowTest {
    private val red = 0xFFE03030.toInt()

    @Test
    fun `seamless tiling wraps edge-crossing strokes onto opposite borders and builds 3x3 preview`() {
        val stroke =
            Stroke(
                id = 55L,
                points =
                    listOf(
                        StrokePoint(2f, 2f, pressure = 1f, timestamp = 0L),
                        StrokePoint(6f, 4f, pressure = 1f, timestamp = 16L),
                    ),
                brushParams = BrushParams(size = 16f, spacing = 0.2f),
                layerId = 1L,
                color = red,
            )
        // Corner stroke in BOTH mode should expand to 4 copies (origin, +W, +H, +W+H).
        val wrapped = SeamlessTiling.wrappedStrokes(stroke, 64, 64, SeamlessTileMode.BOTH)
        assertEquals(4, wrapped.size)

        val rasterizer = StrokeRasterizer()
        val canvas = rasterizer.rasterize(wrapped, 64, 64)
        // Painted at top-left (2, 2) must seamlessly appear at bottom-right (63, 63).
        assertNotEquals(0, canvas.get(2, 2) ushr 24)
        assertNotEquals(0, canvas.get(63, 63) ushr 24)
        assertNotEquals(0, canvas.get(63, 2) ushr 24)
        assertNotEquals(0, canvas.get(2, 63) ushr 24)

        val preview = SeamlessTiling.renderTiledPreview(canvas, SeamlessTileMode.BOTH, tilesX = 3, tilesY = 3)
        assertEquals(192, preview.width)
        assertEquals(192, preview.height)
        assertEquals(canvas.get(2, 2), preview.get(66, 66))

        // Coordinate wrap helper
        val (wx, wy) = SeamlessTiling.wrapPoint(-4f, 70f, 64, 64, SeamlessTileMode.BOTH)
        assertEquals(60f, wx, 0.001f)
        assertEquals(6f, wy, 0.001f)
    }

    @Test
    fun `non-destructive text preserves curveAmount, wordSpacing, and affine transforms across v8 save`() {
        val text =
            TextLayerContent(
                text = "ArtFlow Studio",
                style = TextLayout.TextStyle(fontSize = 32f),
                color = 0xFFFFFFFF.toInt(),
                x = 100f,
                y = 80f,
                curveAmount = 0.45f,
                wordSpacing = 8f,
            ).transform(deltaX = 20f, deltaY = -10f, deltaRotation = 15f, scaleMultiplierX = 1.25f, scaleMultiplierY = 1.25f)

        assertTrue(text.hasTransform())
        assertEquals(0.45f, text.curveAmount, 0.001f)
        assertEquals(8f, text.wordSpacing, 0.001f)
        assertEquals(15f, text.rotationDegrees, 0.001f)

        val doc =
            CanvasDocument(
                width = 256,
                height = 256,
                dpi = 300,
                backgroundColor = 0,
                activeLayerId = 1L,
                nextLayerId = 2L,
                layers = listOf(Layer(id = 1L, name = "Title", index = 0, textContent = text)),
                tileMode = SeamlessTileMode.BOTH,
            )
        val decoded = CanvasDocumentCodec.decode(String(CanvasDocumentCodec.encode(doc)))
        assertEquals(SeamlessTileMode.BOTH, decoded.tileMode)
        val restored = decoded.layers.first().textContent!!
        assertEquals(0.45f, restored.curveAmount, 0.001f)
        assertEquals(8f, restored.wordSpacing, 0.001f)
        assertEquals(120f, restored.x, 0.001f)
    }

    @Test
    fun `keyboard shortcut overrides resolve cleanly, evict conflicts, and round-trip canonical encoding`() {
        val customChord = KeyChord(key = "q", primary = true, shift = true)
        val overrides = KeyboardShortcuts.withOverride(emptyMap(), ShortcutAction.TOOL_BRUSH, customChord)

        assertEquals(ShortcutAction.TOOL_BRUSH, KeyboardShortcuts.actionFor(customChord, overrides))
        assertNull("Old default 'b' should no longer trigger TOOL_BRUSH when overridden", KeyboardShortcuts.actionFor(KeyChord("b"), overrides))
        assertEquals(listOf(customChord), KeyboardShortcuts.chordsFor(ShortcutAction.TOOL_BRUSH, overrides))

        // Reassigning the same chord to TOOL_ERASER evicts TOOL_BRUSH cleanly.
        val reassigned = KeyboardShortcuts.withOverride(overrides, ShortcutAction.TOOL_ERASER, customChord)
        assertEquals(ShortcutAction.TOOL_ERASER, KeyboardShortcuts.actionFor(customChord, reassigned))

        val encoded = KeyboardShortcuts.encodeChord(customChord)
        assertEquals("primary+shift+q", encoded)
        assertEquals(customChord, KeyboardShortcuts.decodeChord(encoded))
        assertEquals("Ctrl+Shift+Q", KeyboardShortcuts.formatChord(customChord))
    }
}
