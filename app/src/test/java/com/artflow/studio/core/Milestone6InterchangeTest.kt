package com.artflow.studio.core

import com.artflow.studio.core.color.ColorProfile
import com.artflow.studio.core.export.ProcreateReader
import com.artflow.studio.core.export.ProcreateWriter
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.domain.model.layer.BlendMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

class Milestone6InterchangeTest {
    @Test
    fun `procreate writer and reader round-trip group blend modes, animation metadata, drawing guides, and editable text payload`() {
        val pixels = PixelBuffer(32, 32).apply { fill(0xFF3366CC.toInt()) }
        val textJson = """{"text":"ArtFlow","curveAmount":0.35,"wordSpacing":6.0}"""
        val group =
            ProcreateWriter.Group(
                name = "Foreground Group",
                opacity = 0.85f,
                visible = true,
                blendMode = BlendMode.MULTIPLY,
                children =
                    listOf(
                        ProcreateWriter.Layer(
                            name = "Title Text",
                            pixels = pixels,
                            animationFrameHold = 4,
                            animationIsForeground = true,
                            textPayload = textJson,
                        ),
                    ),
            )
        val zipBytes =
            ProcreateWriter.write(
                width = 32,
                height = 32,
                nodes = listOf(group),
                composite = pixels,
                background = 0xFFFFFFFF.toInt(),
                dpi = 300,
                name = "FullParityArtwork",
                colorProfile = ColorProfile.DISPLAY_P3,
                animationPlaybackMode = 2,
                animationFramerate = 24,
                drawingGuideType = 1,
                drawingGuideGridSize = 64f,
                drawingGuideOpacity = 0.6f,
                drawingGuideThickness = 2f,
            )

        val entries = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zin ->
            var entry = zin.nextEntry
            while (entry != null) {
                entries[entry.name] = zin.readBytes()
                entry = zin.nextEntry
            }
        }
        val files =
            object : ProcreateReader.Files {
                override val names: Collection<String> get() = entries.keys

                override fun read(name: String): ByteArray? = entries[name]
            }

        val doc = ProcreateReader.open(files) { null }
        assertEquals(ColorProfile.DISPLAY_P3, doc.colorProfile)
        assertEquals(2, doc.animationPlaybackMode)
        assertEquals(24, doc.animationFramerate)
        assertEquals(1, doc.drawingGuideType)
        assertEquals(64f, doc.drawingGuideGridSize!!, 0.01f)

        val readGroup = doc.nodes.single() as ProcreateReader.Group
        assertEquals(BlendMode.MULTIPLY, readGroup.blendMode)
        val readLayer = readGroup.children.single() as ProcreateReader.Layer
        assertEquals(4, readLayer.animationFrameHold)
        assertTrue(readLayer.animationIsForeground)
        assertEquals(textJson, readLayer.textPayload)
    }
}
