package com.artflow.studio.core.export

import com.artflow.studio.core.pixels.Channels
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.domain.model.layer.BlendMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.zip.ZipInputStream

class ProcreateInterchangeFidelityTest {

    private fun filesOf(bytes: ByteArray): ProcreateReader.Files {
        val entries = LinkedHashMap<String, ByteArray>()
        ZipInputStream(bytes.inputStream()).use { zip ->
            generateSequence { zip.nextEntry }.forEach { entries[it.name] = zip.readBytes() }
        }
        return object : ProcreateReader.Files {
            override val names = entries.keys
            override fun read(name: String) = entries[name]
        }
    }

    private fun reopen(bytes: ByteArray): ProcreateReader.Document = ProcreateReader.open(filesOf(bytes)) { null }

    @Test
    fun groupBlendModeRoundTrip() {
        val width = 64
        val height = 64
        val composite = PixelBuffer(width, height)
        val layerPixels = PixelBuffer(width, height)
        for (i in layerPixels.pixels.indices) {
            layerPixels.pixels[i] = Channels.argb(255, 200, 100, 50)
            composite.pixels[i] = Channels.argb(255, 200, 100, 50)
        }

        val childLayer = ProcreateWriter.Layer(
            name = "Child",
            pixels = layerPixels,
            blendMode = BlendMode.NORMAL,
        )

        val group = ProcreateWriter.Group(
            name = "Multiply Group",
            opacity = 0.9f,
            visible = true,
            children = listOf(childLayer),
            blendMode = BlendMode.MULTIPLY,
        )

        val bytes = ProcreateWriter.write(
            width = width,
            height = height,
            nodes = listOf(group),
            composite = composite,
        )

        val document = reopen(bytes)
        assertEquals(1, document.nodes.size)
        val readGroup = document.nodes[0] as ProcreateReader.Group
        assertEquals("Multiply Group", readGroup.name)
        assertEquals(BlendMode.MULTIPLY, readGroup.blendMode)
        assertEquals(1, readGroup.children.size)
    }

    @Test
    fun animationTimelineMetadataRoundTrip() {
        val width = 64
        val height = 64
        val composite = PixelBuffer(width, height)
        val frame1 = PixelBuffer(width, height)
        val frame2 = PixelBuffer(width, height)

        val layer1 = ProcreateWriter.Layer(
            name = "Frame 1 (Background)",
            pixels = frame1,
            animationFrameHold = 2,
            animationIsBackground = true,
            animationIsForeground = false,
        )

        val layer2 = ProcreateWriter.Layer(
            name = "Frame 2 (Foreground)",
            pixels = frame2,
            animationFrameHold = 1,
            animationIsBackground = false,
            animationIsForeground = true,
        )

        val bytes = ProcreateWriter.write(
            width = width,
            height = height,
            nodes = listOf(layer2, layer1), // Top first
            composite = composite,
            animationPlaybackMode = 1, // Ping-Pong
            animationFramerate = 24,
        )

        val document = reopen(bytes)
        assertEquals(1, document.animationPlaybackMode)
        assertEquals(24, document.animationFramerate)

        val layers = document.layers()
        val bgLayer = layers.firstOrNull { it.name == "Frame 1 (Background)" }
        val fgLayer = layers.firstOrNull { it.name == "Frame 2 (Foreground)" }

        assertNotNull(bgLayer)
        assertEquals(2, bgLayer!!.animationFrameHold)
        assertTrue(bgLayer.animationIsBackground)

        assertNotNull(fgLayer)
        assertEquals(1, fgLayer!!.animationFrameHold)
        assertTrue(fgLayer.animationIsForeground)
    }

    @Test
    fun drawingGuideMetadataRoundTrip() {
        val width = 64
        val height = 64
        val composite = PixelBuffer(width, height)
        val layer = ProcreateWriter.Layer(name = "Layer 1", pixels = composite)

        val bytes = ProcreateWriter.write(
            width = width,
            height = height,
            nodes = listOf(layer),
            composite = composite,
            drawingGuideType = 0, // 2D Grid
            drawingGuideGridSize = 32f,
            drawingGuideOpacity = 0.5f,
            drawingGuideThickness = 1.5f,
        )

        val document = reopen(bytes)
        assertEquals(0, document.drawingGuideType)
        assertEquals(32f, document.drawingGuideGridSize ?: 0f, 0.01f)
        assertEquals(0.5f, document.drawingGuideOpacity ?: 0f, 0.01f)
        assertEquals(1.5f, document.drawingGuideThickness ?: 0f, 0.01f)
    }

    @Test
    fun psbFormatDetection() {
        val psdHeader = "8BPS".toByteArray(Charsets.US_ASCII) + ByteArray(22)
        val psbHeader = "8BPB".toByteArray(Charsets.US_ASCII) + ByteArray(22)
        val randomHeader = "TEST".toByteArray(Charsets.US_ASCII) + ByteArray(22)

        assertTrue(PsdCodec.isPsdOrPsb(psdHeader))
        assertTrue(PsdCodec.isPsdOrPsb(psbHeader))
        org.junit.Assert.assertFalse(PsdCodec.isPsdOrPsb(randomHeader))
    }
}
