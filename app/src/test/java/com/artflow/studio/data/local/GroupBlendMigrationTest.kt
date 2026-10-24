package com.artflow.studio.data.local

import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.core.render.Compositor
import com.artflow.studio.domain.model.animation.AnimationFrame
import com.artflow.studio.domain.model.layer.BlendMode
import com.artflow.studio.domain.model.layer.Layer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupBlendMigrationTest {
    private fun document(layers: List<Layer>): CanvasDocument =
        CanvasDocument(
            width = 2,
            height = 1,
            dpi = 72,
            backgroundColor = -1,
            activeLayerId = layers.first().id,
            nextLayerId = 1_000,
            layers = layers,
            savedAt = 123L,
        )

    @Test fun legacyMigrationPreservesEveryFrameAndOnlyChangesPreviouslyPassingGroups() {
        val layers =
            listOf(
                Layer(1, "Paint", 0),
                Layer(10, "Legacy passing", 1, isGroup = true),
                Layer(11, "Faded", 2, isGroup = true, opacity = 0.5f),
                Layer(12, "Masked", 3, isGroup = true, maskFile = "masks/12.png"),
                Layer(13, "Disabled mask", 4, isGroup = true, maskFile = "masks/13.png", maskEnabled = false),
                Layer(14, "Multiply", 5, isGroup = true, blendMode = BlendMode.MULTIPLY),
                Layer(15, "Pass Through", 6, isGroup = true, blendMode = BlendMode.PASS_THROUGH),
            )
        val frames =
            listOf(
                AnimationFrame(1, "First", layers = layers),
                AnimationFrame(2, "Second", layers = layers.map { it.copy(id = it.id + 100) }),
            )
        for (version in 1..2) {
            val legacy = document(layers).copy(version = version, frames = frames)
            val signed = CanvasDocumentCodec.encode(legacy).decodeToString()
            val unsigned = signed.replace(Regex("\"checksum\":\"[0-9a-f]{64}\""), "\"checksum\":null")
            for (encoded in listOf(signed, unsigned)) {
                val migrated = CanvasDocumentCodec.decode(encoded)
                CanvasDocumentCodec.requireValidHeader(migrated)
                CanvasDocumentCodec.requireValidStructure(migrated)
                assertEquals(CanvasDocument.CURRENT_VERSION, migrated.version)
                for (stack in listOf(migrated.layers) + migrated.frames.map { it.layers }) {
                    assertEquals(BlendMode.PASS_THROUGH, stack.single { it.name == "Legacy passing" }.blendMode)
                    assertEquals(BlendMode.PASS_THROUGH, stack.single { it.name == "Disabled mask" }.blendMode)
                    assertEquals(BlendMode.NORMAL, stack.single { it.name == "Faded" }.blendMode)
                    assertEquals(0.5f, stack.single { it.name == "Faded" }.opacity)
                    assertEquals(BlendMode.NORMAL, stack.single { it.name == "Masked" }.blendMode)
                    assertEquals("masks/12.png", stack.single { it.name == "Masked" }.maskFile)
                    assertEquals(BlendMode.MULTIPLY, stack.single { it.name == "Multiply" }.blendMode)
                    assertEquals(BlendMode.NORMAL, stack.single { it.name == "Paint" }.blendMode)
                }
                assertEquals(migrated, CanvasDocumentCodec.decode(CanvasDocumentCodec.encode(migrated).decodeToString()))
            }
        }
    }

    @Test fun migrationKeepsTheLegacyPictureAndNewNormalGroupsKeepTheirIsolation() {
        val paper = Layer(1, "Paper", 0)
        val ink = Layer(2, "Ink", 1, blendMode = BlendMode.MULTIPLY, parentGroupId = 10)
        val group = Layer(10, "Group", 2, isGroup = true)
        val colours = mapOf(1L to 0xFF808080.toInt(), 2L to 0xFF800000.toInt())

        fun render(layers: List<Layer>): Int =
            Compositor()
                .composite(
                    layers.map { layer ->
                        Compositor.LayerInput(layer, colours[layer.id]?.let { PixelBuffer.filled(2, 1, it) })
                    },
                    2,
                    1,
                ).pixels[0]

        val legacy = document(listOf(paper, ink, group)).copy(version = 2)
        val migrated = CanvasDocumentCodec.decode(CanvasDocumentCodec.encode(legacy).decodeToString())
        assertEquals(render(listOf(paper, ink.copy(parentGroupId = null))), render(migrated.layers))

        val current = document(listOf(paper, ink, group))
        val reopened = CanvasDocumentCodec.decode(CanvasDocumentCodec.encode(current).decodeToString())
        assertEquals(BlendMode.NORMAL, reopened.layers.last().blendMode)
        assertEquals(0xFF800000.toInt(), render(reopened.layers))
    }

    @Test fun migrationNeverAcceptsATamperedLegacyChecksum() {
        val legacy = document(listOf(Layer(10, "Old group", 0, isGroup = true))).copy(version = 2)
        val signed = CanvasDocumentCodec.encode(legacy).decodeToString()
        val tampered = signed.replace("Old group", "Changed group")
        val failure = runCatching { CanvasDocumentCodec.decode(tampered) }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure?.message.orEmpty().contains("checksum"))
    }
}
